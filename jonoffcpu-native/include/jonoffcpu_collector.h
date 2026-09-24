// SPDX-License-Identifier: MIT
#ifndef JONOFFCPU_COLLECTOR_H
#define JONOFFCPU_COLLECTOR_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Version 2 exchanges encoded protobuf messages (jonoffcpu-collector.proto): prepare and enable
// take an encoded PrepareRequest or EnableRequest, and every call returns an encoded
// CollectorReply in `bytes`. The status is zero exactly when the reply is not an error.
#define JONOFFCPU_COLLECTOR_ABI_VERSION 2U

// The bound on an encoded request and on an encoded reply.
#define JONOFFCPU_COLLECTOR_MAX_MESSAGE_BYTES (64u * 1024u)

struct jonoffcpu_result {
    uint32_t struct_size;
    uint32_t abi_version;
    int32_t code;
    uint32_t reserved;
    uint8_t *bytes;
    size_t len;
};

int32_t jonoffcpu_collector_prepare(const uint8_t *request, size_t len,
                                    struct jonoffcpu_result *out);
int32_t jonoffcpu_collector_enable(uint64_t handle, const uint8_t *request, size_t len,
                                   struct jonoffcpu_result *out);
int32_t jonoffcpu_collector_stop(uint64_t handle, uint64_t timeout_ms,
                                 struct jonoffcpu_result *out);
int32_t jonoffcpu_collector_close(uint64_t handle, struct jonoffcpu_result *out);
void jonoffcpu_result_free(struct jonoffcpu_result *result);

#ifdef __cplusplus
}
#endif

#endif
