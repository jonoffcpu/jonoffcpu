// SPDX-License-Identifier: MIT

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdatomic.h>
#include <unistd.h>

static _Atomic int injected;

int fsync(int fd) {
    static int (*real_fsync)(int);
    if (real_fsync == NULL) {
        real_fsync = dlsym(RTLD_NEXT, "fsync");
        if (real_fsync == NULL) {
            errno = ENOSYS;
            return -1;
        }
    }
    if (atomic_exchange_explicit(&injected, 1, memory_order_relaxed) == 0) {
        errno = EIO;
        return -1;
    }
    return real_fsync(fd);
}
