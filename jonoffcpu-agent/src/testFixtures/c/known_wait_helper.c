// SPDX-License-Identifier: MIT

#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

JNIEXPORT jlong JNICALL
Java_io_github_jonoffcpu_agent_KnownWaitHelper_currentTid(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return (jlong)syscall(SYS_gettid);
}

JNIEXPORT jlong JNICALL
Java_io_github_jonoffcpu_agent_KnownWaitHelper_monotonicNanos(JNIEnv *env, jclass type) {
    (void)type;
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (error != NULL) {
            (*env)->ThrowNew(env, error, "clock_gettime(CLOCK_MONOTONIC) failed");
        }
        return 0;
    }
    return (jlong)((uint64_t)now.tv_sec * UINT64_C(1000000000) + (uint64_t)now.tv_nsec);
}

JNIEXPORT jint JNICALL
Java_io_github_jonoffcpu_agent_KnownWaitHelper_nativeWaitNanos(JNIEnv *env, jclass type, jlong duration_nanos) {
    (void)env;
    (void)type;
    if (duration_nanos <= 0) {
        return EINVAL;
    }
    struct timespec requested = {
        .tv_sec = duration_nanos / 1000000000,
        .tv_nsec = duration_nanos % 1000000000,
    };
    struct timespec remaining;
    int result;
    do {
        result = clock_nanosleep(CLOCK_MONOTONIC, 0, &requested, &remaining);
        if (result == EINTR) {
            requested = remaining;
        }
    } while (result == EINTR);
    return result;
}
