// SPDX-License-Identifier: MIT

#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <sys/syscall.h>
#include <unistd.h>

JNIEXPORT jint JNICALL
Java_io_github_jonoffcpu_agent_SignalMaskHelper_setBlocked(JNIEnv *env, jclass type, jint signal_number,
                                               jboolean blocked) {
    (void)env;
    (void)type;
    sigset_t set;
    if (sigemptyset(&set) != 0 || sigaddset(&set, signal_number) != 0) {
        return -1;
    }
    return pthread_sigmask(blocked ? SIG_BLOCK : SIG_UNBLOCK, &set, NULL);
}

JNIEXPORT jlong JNICALL
Java_io_github_jonoffcpu_agent_SignalMaskHelper_currentTid(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return (jlong)syscall(SYS_gettid);
}
