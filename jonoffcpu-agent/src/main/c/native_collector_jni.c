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

JNIEXPORT jstring JNICALL Java_io_github_lhotari_jonoffcpu_agent_NativeCollector_prepare(
        JNIEnv* env, jclass ignored, jstring config);
JNIEXPORT jstring JNICALL Java_io_github_lhotari_jonoffcpu_agent_NativeCollector_enable(
        JNIEnv* env, jclass ignored, jlong handle, jstring capture);
JNIEXPORT jstring JNICALL Java_io_github_lhotari_jonoffcpu_agent_NativeCollector_stop(
        JNIEnv* env, jclass ignored, jlong handle, jlong timeout_millis);
JNIEXPORT jstring JNICALL Java_io_github_lhotari_jonoffcpu_agent_NativeCollector_close(
        JNIEnv* env, jclass ignored, jlong handle);
JNIEXPORT void JNICALL Java_io_github_lhotari_jonoffcpu_agent_NativeProfiler_initialize0(
        JNIEnv* env, jclass ignored, jstring library);
JNIEXPORT jbyteArray JNICALL Java_io_github_lhotari_jonoffcpu_agent_NativeProfiler_execute0(
        JNIEnv* env, jclass ignored, jbyteArray command);

typedef int32_t (*json_call)(const char*, size_t, struct jonoffcpu_result*);
typedef int32_t (*handle_json_call)(uint64_t, const char*, size_t, struct jonoffcpu_result*);

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

static jbyteArray utf8_bytes(JNIEnv* env, jstring value) {
    if (value == NULL) {
        throw_by_name(env, "java/lang/IllegalArgumentException", "JSON input must not be null");
        return NULL;
    }
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL) return NULL;
    jmethodID get_bytes = (*env)->GetMethodID(env, string_class, "getBytes", "(Ljava/lang/String;)[B");
    if (get_bytes == NULL) return NULL;
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    if (charset == NULL) return NULL;
    return (jbyteArray)(*env)->CallObjectMethod(env, value, get_bytes, charset);
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

static int contains(const char* data, size_t length, const char* needle) {
    size_t needle_length = strlen(needle);
    if (needle_length > length) return 0;
    for (size_t i = 0; i <= length - needle_length; i++) {
        if (memcmp(data + i, needle, needle_length) == 0) return 1;
    }
    return 0;
}

static jstring finish_result(JNIEnv* env, int32_t return_code, struct jonoffcpu_result* result) {
    jstring response = NULL;
    if (result->struct_size != sizeof(*result) || result->abi_version != JONOFFCPU_COLLECTOR_ABI_VERSION) {
        throw_by_name(env, "java/lang/IllegalStateException", "Native collector ABI result mismatch");
    } else if (result->code != return_code) {
        throw_by_name(env, "java/lang/IllegalStateException", "Native collector return code mismatch");
    } else if (result->json == NULL || result->json_len == 0 || result->json_len > JONOFFCPU_MAX_RESULT_BYTES) {
        throw_by_name(env, "java/lang/IllegalStateException", "Native collector returned invalid JSON length");
    } else if ((return_code == 0 && !contains(result->json, result->json_len, "\"ok\":true"))
            || (return_code != 0 && !contains(result->json, result->json_len, "\"ok\":false"))) {
        throw_by_name(env, "java/lang/IllegalStateException", "Native collector code/JSON status mismatch");
    } else {
        response = new_utf8_string(env, result->json, result->json_len);
    }
    jonoffcpu_result_free(result);
    return response;
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
    jclass native_collector = (*env)->FindClass(env, "io/github/lhotari/jonoffcpu/agent/NativeCollector");
    if (native_collector == NULL) {
        fail_vm_init(env, "JONOFFCPU agent cannot load NativeCollector from companion JAR");
        return;
    }
    JNINativeMethod methods[] = {
        {(char*)"prepare", (char*)"(Ljava/lang/String;)Ljava/lang/String;",
         (void*)Java_io_github_lhotari_jonoffcpu_agent_NativeCollector_prepare},
        {(char*)"enable", (char*)"(JLjava/lang/String;)Ljava/lang/String;",
         (void*)Java_io_github_lhotari_jonoffcpu_agent_NativeCollector_enable},
        {(char*)"stop", (char*)"(JJ)Ljava/lang/String;",
         (void*)Java_io_github_lhotari_jonoffcpu_agent_NativeCollector_stop},
        {(char*)"close", (char*)"(J)Ljava/lang/String;",
         (void*)Java_io_github_lhotari_jonoffcpu_agent_NativeCollector_close},
    };
    if ((*env)->RegisterNatives(env, native_collector, methods,
            (jint)(sizeof(methods) / sizeof(methods[0]))) != 0) {
        fail_vm_init(env, "JONOFFCPU agent cannot register NativeCollector methods");
        return;
    }
    jclass agent = (*env)->FindClass(env, "io/github/lhotari/jonoffcpu/agent/SignalCaptureAgent");
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

JNIEXPORT jstring JNICALL Java_io_github_lhotari_jonoffcpu_agent_NativeCollector_prepare(
        JNIEnv* env, jclass ignored, jstring config) {
    (void)ignored;
    jbyteArray bytes = utf8_bytes(env, config);
    if (bytes == NULL) return NULL;
    jsize length = (*env)->GetArrayLength(env, bytes);
    if (length > (jsize)JONOFFCPU_MAX_RESULT_BYTES) {
        throw_by_name(env, "java/lang/IllegalArgumentException", "Native config exceeds 64 KiB");
        return NULL;
    }
    jbyte* data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (data == NULL) return NULL;
    struct jonoffcpu_result result;
    initialize_result(&result);
    int32_t code = jonoffcpu_collector_prepare((const char*)data, (size_t)length, &result);
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    return finish_result(env, code, &result);
}

JNIEXPORT jstring JNICALL Java_io_github_lhotari_jonoffcpu_agent_NativeCollector_enable(
        JNIEnv* env, jclass ignored, jlong handle, jstring capture) {
    (void)ignored;
    jbyteArray bytes = utf8_bytes(env, capture);
    if (bytes == NULL) return NULL;
    jsize length = (*env)->GetArrayLength(env, bytes);
    if (length > (jsize)JONOFFCPU_MAX_RESULT_BYTES) {
        throw_by_name(env, "java/lang/IllegalArgumentException", "Native capture config exceeds 64 KiB");
        return NULL;
    }
    jbyte* data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (data == NULL) return NULL;
    struct jonoffcpu_result result;
    initialize_result(&result);
    int32_t code = jonoffcpu_collector_enable((uint64_t)handle, (const char*)data, (size_t)length, &result);
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    return finish_result(env, code, &result);
}

JNIEXPORT jstring JNICALL Java_io_github_lhotari_jonoffcpu_agent_NativeCollector_stop(
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

JNIEXPORT jstring JNICALL Java_io_github_lhotari_jonoffcpu_agent_NativeCollector_close(
        JNIEnv* env, jclass ignored, jlong handle) {
    (void)ignored;
    struct jonoffcpu_result result;
    initialize_result(&result);
    int32_t code = jonoffcpu_collector_close((uint64_t)handle, &result);
    return finish_result(env, code, &result);
}

JNIEXPORT void JNICALL Java_io_github_lhotari_jonoffcpu_agent_NativeProfiler_initialize0(
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

JNIEXPORT jbyteArray JNICALL Java_io_github_lhotari_jonoffcpu_agent_NativeProfiler_execute0(
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
