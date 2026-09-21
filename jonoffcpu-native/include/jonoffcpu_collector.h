// SPDX-License-Identifier: MIT
#ifndef JONOFFCPU_COLLECTOR_H
#define JONOFFCPU_COLLECTOR_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define JONOFFCPU_COLLECTOR_ABI_VERSION 1U

struct jonoffcpu_result {
    uint32_t struct_size;
    uint32_t abi_version;
    int32_t code;
    uint32_t reserved;
    char *json;
    size_t json_len;
};

int32_t jonoffcpu_collector_prepare(const char *json, size_t len,
                              struct jonoffcpu_result *out);
int32_t jonoffcpu_collector_enable(uint64_t handle, const char *json, size_t len,
                             struct jonoffcpu_result *out);
int32_t jonoffcpu_collector_stop(uint64_t handle, uint64_t timeout_ms,
                           struct jonoffcpu_result *out);
int32_t jonoffcpu_collector_close(uint64_t handle, struct jonoffcpu_result *out);
void jonoffcpu_result_free(struct jonoffcpu_result *result);

#ifdef __cplusplus
}
#endif

#endif
