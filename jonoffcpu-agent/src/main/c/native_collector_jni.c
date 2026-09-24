// SPDX-License-Identifier: MIT

#define _GNU_SOURCE

#include <dlfcn.h>
#include <jni.h>
#include <jvmti.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "jonoffcpu_collector.h"
#include "asprof.h"

#define JONOFFCPU_MAX_RESULT_BYTES (64u * 1024u)
#define JONOFFCPU_MAX_PROFILER_OUTPUT_BYTES (16u * 1024u * 1024u)

static char* agent_options;
static char* agent_library_path;
static pthread_mutex_t profiler_lock = PTHREAD_MUTEX_INITIALIZER;
static void* profiler_handle;
static char* profiler_library_path;
static asprof_execute_t profiler_execute;
static asprof_error_str_t profiler_error_str;

struct profiler_output {
    char* data;
    size_t length;
    size_t capacity;
    int failed;
};

static struct profiler_output current_profiler_output;

JNIEXPORT jbyteArray JNICALL Java_io_github_jonoffcpu_jonoffcpu_agent_NativeCollector_prepare(
        JNIEnv* env, jclass ignored, jbyteArray request);
JNIEXPORT jbyteArray JNICALL Java_io_github_jonoffcpu_jonoffcpu_agent_NativeCollector_enable(
        JNIEnv* env, jclass ignored, jlong handle, jbyteArray request);
JNIEXPORT jbyteArray JNICALL Java_io_github_jonoffcpu_jonoffcpu_agent_NativeCollector_stop(
        JNIEnv* env, jclass ignored, jlong handle, jlong timeout_millis);
JNIEXPORT jbyteArray JNICALL Java_io_github_jonoffcpu_jonoffcpu_agent_NativeCollector_close(
        JNIEnv* env, jclass ignored, jlong handle);
JNIEXPORT void JNICALL Java_io_github_jonoffcpu_jonoffcpu_agent_NativeProfiler_initialize0(
        JNIEnv* env, jclass ignored, jstring library);
JNIEXPORT jbyteArray JNICALL Java_io_github_jonoffcpu_jonoffcpu_agent_NativeProfiler_execute0(
        JNIEnv* env, jclass ignored, jbyteArray command);

static void throw_by_name(JNIEnv* env, const char* class_name, const char* message) {
    jclass type = (*env)->FindClass(env, class_name);
    if (type != NULL) {
        (*env)->ThrowNew(env, type, message);
    }
}

static void profiler_output_callback(const char* data, size_t length) {
    struct profiler_output* output = &current_profiler_output;
    if (output->failed || length == 0) return;
    if (length > JONOFFCPU_MAX_PROFILER_OUTPUT_BYTES - output->length) {
        output->failed = 1;
        return;
    }
    size_t required = output->length + length;
    if (required > output->capacity) {
        size_t capacity = output->capacity == 0 ? 1024 : output->capacity;
        while (capacity < required && capacity <= JONOFFCPU_MAX_PROFILER_OUTPUT_BYTES / 2) {
            capacity *= 2;
        }
        if (capacity < required) capacity = required;
        char* replacement = realloc(output->data, capacity);
        if (replacement == NULL) {
            output->failed = 1;
            return;
        }
        output->data = replacement;
        output->capacity = capacity;
    }
    memcpy(output->data + output->length, data, length);
    output->length += length;
}

static jbyteArray new_byte_array(JNIEnv* env, const char* data, size_t length) {
    if (length > (size_t)INT32_MAX) {
        throw_by_name(env, "java/lang/IllegalStateException", "Profiler output exceeds Java array size");
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)length);
    if (result != NULL && length != 0) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte*)data);
    }
    return result;
}

static jstring new_utf8_string(JNIEnv* env, const char* data, size_t length) {
    if (length > (size_t)INT32_MAX) {
        throw_by_name(env, "java/lang/IllegalStateException", "Native result is too large");
        return NULL;
    }
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
    if (bytes == NULL) return NULL;
    if (length != 0) {
        (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length, (const jbyte*)data);
        if ((*env)->ExceptionCheck(env)) return NULL;
    }
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL) return NULL;
    jmethodID constructor = (*env)->GetMethodID(env, string_class, "<init>", "([BLjava/lang/String;)V");
    if (constructor == NULL) return NULL;
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    if (charset == NULL) return NULL;
    return (jstring)(*env)->NewObject(env, string_class, constructor, bytes, charset);
}

// Returns the encoded CollectorReply, error replies included; the Java side decodes it. The bridge
// only checks the ABI envelope and the length bound, and interprets none of the bytes.
static jbyteArray finish_result(JNIEnv* env, int32_t return_code, struct jonoffcpu_result* result) {
    jbyteArray response = NULL;
    if (result->struct_size != sizeof(*result) || result->abi_version != JONOFFCPU_COLLECTOR_ABI_VERSION) {
        throw_by_name(env, "java/lang/IllegalStateException", "Native collector ABI result mismatch");
    } else if (result->code != return_code) {
        throw_by_name(env, "java/lang/IllegalStateException", "Native collector return code mismatch");
    } else if (result->bytes == NULL || result->len == 0
            || result->len > JONOFFCPU_COLLECTOR_MAX_MESSAGE_BYTES) {
        throw_by_name(env, "java/lang/IllegalStateException", "Native collector returned an invalid reply length");
    } else {
        response = (*env)->NewByteArray(env, (jsize)result->len);
        if (response != NULL) {
            (*env)->SetByteArrayRegion(env, response, 0, (jsize)result->len, (const jbyte*)result->bytes);
            if ((*env)->ExceptionCheck(env)) response = NULL;
        }
    }
    jonoffcpu_result_free(result);
    return response;
}

// Copies a request array into native memory the collector can read without holding a JNI
// critical region. Returns NULL with a pending exception on failure.
static uint8_t* copy_request(JNIEnv* env, jbyteArray request, jsize* length, const char* name) {
    if (request == NULL) {
        char message[128];
        snprintf(message, sizeof(message), "%s must not be null", name);
        throw_by_name(env, "java/lang/IllegalArgumentException", message);
        return NULL;
    }
    // An encoded message with every field at its default is legitimately empty; the collector
    // rejects it with an INVALID_CONFIG reply.
    *length = (*env)->GetArrayLength(env, request);
    if (*length < 0 || (size_t)*length > JONOFFCPU_COLLECTOR_MAX_MESSAGE_BYTES) {
        char message[128];
        snprintf(message, sizeof(message), "%s exceeds %u bytes", name,
                JONOFFCPU_COLLECTOR_MAX_MESSAGE_BYTES);
        throw_by_name(env, "java/lang/IllegalArgumentException", message);
        return NULL;
    }
    uint8_t* bytes = malloc(*length == 0 ? 1 : (size_t)*length);
    if (bytes == NULL) {
        throw_by_name(env, "java/lang/OutOfMemoryError", "Cannot copy native collector request");
        return NULL;
    }
    if (*length != 0) (*env)->GetByteArrayRegion(env, request, 0, *length, (jbyte*)bytes);
    if ((*env)->ExceptionCheck(env)) {
        free(bytes);
        return NULL;
    }
    return bytes;
}

static void initialize_result(struct jonoffcpu_result* result) {
    memset(result, 0, sizeof(*result));
    result->struct_size = sizeof(*result);
    result->abi_version = JONOFFCPU_COLLECTOR_ABI_VERSION;
}

static void fail_vm_init(JNIEnv* env, const char* message) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    (*env)->FatalError(env, message);
}

static void JNICALL on_vm_init(jvmtiEnv* jvmti, JNIEnv* env, jthread thread) {
    (void)jvmti;
    (void)thread;
    jclass native_collector = (*env)->FindClass(env, "io/github/jonoffcpu/jonoffcpu/agent/NativeCollector");
    if (native_collector == NULL) {
        fail_vm_init(env, "JONOFFCPU agent cannot load NativeCollector from companion JAR");
        return;
    }
    JNINativeMethod methods[] = {
        {(char*)"prepare", (char*)"([B)[B",
         (void*)Java_io_github_jonoffcpu_jonoffcpu_agent_NativeCollector_prepare},
        {(char*)"enable", (char*)"(J[B)[B",
         (void*)Java_io_github_jonoffcpu_jonoffcpu_agent_NativeCollector_enable},
        {(char*)"stop", (char*)"(JJ)[B",
         (void*)Java_io_github_jonoffcpu_jonoffcpu_agent_NativeCollector_stop},
        {(char*)"close", (char*)"(J)[B",
         (void*)Java_io_github_jonoffcpu_jonoffcpu_agent_NativeCollector_close},
    };
    if ((*env)->RegisterNatives(env, native_collector, methods,
            (jint)(sizeof(methods) / sizeof(methods[0]))) != 0) {
        fail_vm_init(env, "JONOFFCPU agent cannot register NativeCollector methods");
        return;
    }
    jclass agent = (*env)->FindClass(env, "io/github/jonoffcpu/jonoffcpu/agent/SignalCaptureAgent");
    if (agent == NULL) {
        fail_vm_init(env, "JONOFFCPU agent cannot load Java controller from companion JAR");
        return;
    }
    jmethodID start = (*env)->GetStaticMethodID(env, agent, "nativeAgentStart",
            "(Ljava/lang/String;Ljava/lang/String;)V");
    if (start == NULL) {
        fail_vm_init(env, "JONOFFCPU agent Java controller entry point is missing");
        return;
    }
    jstring options = new_utf8_string(env, agent_options, strlen(agent_options));
    jstring library = new_utf8_string(env, agent_library_path, strlen(agent_library_path));
    if (options == NULL || library == NULL) {
        fail_vm_init(env, "JONOFFCPU agent cannot allocate bootstrap arguments");
        return;
    }
    (*env)->CallStaticVoidMethod(env, agent, start, options, library);
    if ((*env)->ExceptionCheck(env)) {
        fail_vm_init(env, "JONOFFCPU agent Java controller startup failed");
    }
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void* reserved) {
    (void)reserved;
    if (options == NULL || options[0] == 0 || strlen(options) > JONOFFCPU_MAX_RESULT_BYTES) {
        fprintf(stderr, "JONOFFCPU agent requires bounded nonempty options\n");
        return JNI_ERR;
    }
    Dl_info info;
    if (dladdr((void*)Agent_OnLoad, &info) == 0 || info.dli_fname == NULL) {
        fprintf(stderr, "JONOFFCPU agent cannot locate its native library\n");
        return JNI_ERR;
    }
    char resolved[PATH_MAX];
    if (realpath(info.dli_fname, resolved) == NULL) {
        fprintf(stderr, "JONOFFCPU agent cannot resolve its native library path\n");
        return JNI_ERR;
    }
    agent_options = strdup(options);
    agent_library_path = strdup(resolved);
    if (agent_options == NULL || agent_library_path == NULL) return JNI_ERR;

    char jar_path[PATH_MAX];
    char* slash = strrchr(resolved, '/');
    if (slash == NULL) return JNI_ERR;
    size_t directory_length = (size_t)(slash - resolved);
    if (directory_length + sizeof("/jonoffcpu-agent.jar") > sizeof(jar_path)) return JNI_ERR;
    memcpy(jar_path, resolved, directory_length);
    memcpy(jar_path + directory_length, "/jonoffcpu-agent.jar", sizeof("/jonoffcpu-agent.jar"));
    if (access(jar_path, R_OK) != 0) {
        fprintf(stderr, "JONOFFCPU agent companion JAR is not readable: %s\n", jar_path);
        return JNI_ERR;
    }

    jvmtiEnv* jvmti = NULL;
    if ((*vm)->GetEnv(vm, (void**)&jvmti, JVMTI_VERSION_1_2) != JNI_OK || jvmti == NULL) return JNI_ERR;
    if ((*jvmti)->AddToSystemClassLoaderSearch(jvmti, jar_path) != JVMTI_ERROR_NONE) return JNI_ERR;
    jvmtiEventCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.VMInit = on_vm_init;
    if ((*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE
            || (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, NULL)
                    != JVMTI_ERROR_NONE) {
        return JNI_ERR;
    }
    return JNI_OK;
}

JNIEXPORT void JNICALL Agent_OnUnload(JavaVM* vm) {
    (void)vm;
    free(agent_options);
    free(agent_library_path);
    agent_options = NULL;
    agent_library_path = NULL;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_jonoffcpu_jonoffcpu_agent_NativeCollector_prepare(
        JNIEnv* env, jclass ignored, jbyteArray request) {
    (void)ignored;
    jsize length = 0;
    uint8_t* data = copy_request(env, request, &length, "PrepareRequest");
    if (data == NULL) return NULL;
    struct jonoffcpu_result result;
    initialize_result(&result);
    int32_t code = jonoffcpu_collector_prepare(data, (size_t)length, &result);
    free(data);
    return finish_result(env, code, &result);
}

JNIEXPORT jbyteArray JNICALL Java_io_github_jonoffcpu_jonoffcpu_agent_NativeCollector_enable(
        JNIEnv* env, jclass ignored, jlong handle, jbyteArray request) {
    (void)ignored;
    jsize length = 0;
    uint8_t* data = copy_request(env, request, &length, "EnableRequest");
    if (data == NULL) return NULL;
    struct jonoffcpu_result result;
    initialize_result(&result);
    int32_t code = jonoffcpu_collector_enable((uint64_t)handle, data, (size_t)length, &result);
    free(data);
    return finish_result(env, code, &result);
}

JNIEXPORT jbyteArray JNICALL Java_io_github_jonoffcpu_jonoffcpu_agent_NativeCollector_stop(
        JNIEnv* env, jclass ignored, jlong handle, jlong timeout_millis) {
    (void)ignored;
    if (timeout_millis < 0) {
        throw_by_name(env, "java/lang/IllegalArgumentException", "Stop timeout must not be negative");
        return NULL;
    }
    struct jonoffcpu_result result;
    initialize_result(&result);
    int32_t code = jonoffcpu_collector_stop((uint64_t)handle, (uint64_t)timeout_millis, &result);
    return finish_result(env, code, &result);
}

JNIEXPORT jbyteArray JNICALL Java_io_github_jonoffcpu_jonoffcpu_agent_NativeCollector_close(
        JNIEnv* env, jclass ignored, jlong handle) {
    (void)ignored;
    struct jonoffcpu_result result;
    initialize_result(&result);
    int32_t code = jonoffcpu_collector_close((uint64_t)handle, &result);
    return finish_result(env, code, &result);
}

JNIEXPORT void JNICALL Java_io_github_jonoffcpu_jonoffcpu_agent_NativeProfiler_initialize0(
        JNIEnv* env, jclass ignored, jstring library) {
    (void)ignored;
    if (library == NULL) {
        throw_by_name(env, "java/lang/IllegalArgumentException", "async-profiler library path must not be null");
        return;
    }
    const char* path = (*env)->GetStringUTFChars(env, library, NULL);
    if (path == NULL) return;
    pthread_mutex_lock(&profiler_lock);
    if (profiler_handle != NULL) {
        int same = profiler_library_path != NULL && strcmp(profiler_library_path, path) == 0;
        pthread_mutex_unlock(&profiler_lock);
        (*env)->ReleaseStringUTFChars(env, library, path);
        if (!same) {
            throw_by_name(env, "java/lang/IllegalStateException",
                    "async-profiler is already initialized from a different library");
        }
        return;
    }

    dlerror();
    void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    const char* load_error = dlerror();
    if (handle == NULL || load_error != NULL) {
        char message[1024];
        snprintf(message, sizeof(message), "Cannot load async-profiler native library: %s",
                load_error == NULL ? "unknown dlopen failure" : load_error);
        pthread_mutex_unlock(&profiler_lock);
        (*env)->ReleaseStringUTFChars(env, library, path);
        throw_by_name(env, "java/lang/UnsatisfiedLinkError", message);
        return;
    }

    dlerror();
    asprof_init_t init = (asprof_init_t)dlsym(handle, "asprof_init");
    asprof_execute_t execute = (asprof_execute_t)dlsym(handle, "asprof_execute");
    asprof_error_str_t error_str = (asprof_error_str_t)dlsym(handle, "asprof_error_str");
    const char* symbol_error = dlerror();
    if (init == NULL || execute == NULL || error_str == NULL || symbol_error != NULL) {
        char message[1024];
        snprintf(message, sizeof(message), "Incompatible async-profiler native library: %s",
                symbol_error == NULL ? "required C API symbol is missing" : symbol_error);
        dlclose(handle);
        pthread_mutex_unlock(&profiler_lock);
        (*env)->ReleaseStringUTFChars(env, library, path);
        throw_by_name(env, "java/lang/UnsatisfiedLinkError", message);
        return;
    }
    char* retained_path = strdup(path);
    if (retained_path == NULL) {
        dlclose(handle);
        pthread_mutex_unlock(&profiler_lock);
        (*env)->ReleaseStringUTFChars(env, library, path);
        throw_by_name(env, "java/lang/OutOfMemoryError", "Cannot retain async-profiler library identity");
        return;
    }
    init();
    profiler_handle = handle;
    profiler_library_path = retained_path;
    profiler_execute = execute;
    profiler_error_str = error_str;
    pthread_mutex_unlock(&profiler_lock);
    (*env)->ReleaseStringUTFChars(env, library, path);
}

JNIEXPORT jbyteArray JNICALL Java_io_github_jonoffcpu_jonoffcpu_agent_NativeProfiler_execute0(
        JNIEnv* env, jclass ignored, jbyteArray command) {
    (void)ignored;
    if (command == NULL) {
        throw_by_name(env, "java/lang/IllegalArgumentException", "Profiler command must not be null");
        return NULL;
    }
    jsize length = (*env)->GetArrayLength(env, command);
    if (length <= 0 || (size_t)length > JONOFFCPU_MAX_RESULT_BYTES) {
        throw_by_name(env, "java/lang/IllegalArgumentException", "Profiler command has an invalid size");
        return NULL;
    }
    jbyte* bytes = (*env)->GetByteArrayElements(env, command, NULL);
    if (bytes == NULL) return NULL;
    if (memchr(bytes, 0, (size_t)length) != NULL) {
        (*env)->ReleaseByteArrayElements(env, command, bytes, JNI_ABORT);
        throw_by_name(env, "java/lang/IllegalArgumentException", "Profiler command contains NUL");
        return NULL;
    }
    char* command_string = malloc((size_t)length + 1);
    if (command_string == NULL) {
        (*env)->ReleaseByteArrayElements(env, command, bytes, JNI_ABORT);
        throw_by_name(env, "java/lang/OutOfMemoryError", "Cannot copy profiler command");
        return NULL;
    }
    memcpy(command_string, bytes, (size_t)length);
    command_string[length] = 0;
    (*env)->ReleaseByteArrayElements(env, command, bytes, JNI_ABORT);

    pthread_mutex_lock(&profiler_lock);
    if (profiler_handle == NULL || profiler_execute == NULL) {
        pthread_mutex_unlock(&profiler_lock);
        free(command_string);
        throw_by_name(env, "java/lang/IllegalStateException", "async-profiler is not initialized");
        return NULL;
    }
    current_profiler_output.length = 0;
    current_profiler_output.failed = 0;
    asprof_error_t error = profiler_execute(command_string, profiler_output_callback);
    free(command_string);
    char error_message[1024];
    error_message[0] = 0;
    if (error != NULL) {
        const char* value = profiler_error_str(error);
        snprintf(error_message, sizeof(error_message), "%s",
                value == NULL ? "Unknown async-profiler error" : value);
    }
    int output_failed = current_profiler_output.failed;
    jbyteArray result = NULL;
    if (error == NULL && !output_failed) {
        result = new_byte_array(env, current_profiler_output.data, current_profiler_output.length);
    }
    pthread_mutex_unlock(&profiler_lock);
    if (error != NULL) {
        throw_by_name(env, "java/lang/IllegalStateException", error_message);
        return NULL;
    }
    if (output_failed) {
        throw_by_name(env, "java/lang/IllegalStateException", "Profiler output exceeds the 16 MiB limit");
        return NULL;
    }
    return result;
}
