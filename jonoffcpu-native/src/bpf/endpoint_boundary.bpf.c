// SPDX-License-Identifier: MIT

#include "vmlinux.h"
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>

struct boundary_sample {
    __u64 entry_ns;
    __u64 endpoint_ns;
    __u64 delta_ns;
    __u32 tgid;
    __u32 tid;
    __u32 cpu;
    __u32 reserved;
};

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 16384);
    __type(key, __u32);
    __type(value, __u64);
} entry_times SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 1 << 20);
} samples SEC(".maps");

volatile __u32 target_tgid;
volatile __u32 enabled;
volatile __u64 entry_calls;
volatile __u64 switch_endpoints;
volatile __u64 no_switch_endpoints;
volatile __u64 paired_samples;
volatile __u64 missing_entries;
volatile __u64 negative_deltas;
volatile __u64 map_update_failures;
volatile __u64 ring_reserve_failures;

SEC("kprobe")
int measure_finish_task_switch_entry(void *ctx)
{
    __u64 now = bpf_ktime_get_ns();
    __u64 pid_tgid = bpf_get_current_pid_tgid();
    __u32 tgid = pid_tgid >> 32;
    __u32 tid = pid_tgid;

    (void)ctx;
    if (!enabled || tgid != target_tgid)
        return 0;
    __sync_fetch_and_add(&entry_calls, 1);
    if (bpf_map_update_elem(&entry_times, &tid, &now, BPF_ANY))
        __sync_fetch_and_add(&map_update_failures, 1);
    return 0;
}

SEC("tp_btf/sched_exit_tp")
int BPF_PROG(measure_sched_exit_endpoint, bool is_switch)
{
    __u64 end = bpf_ktime_get_ns();
    __u64 pid_tgid = bpf_get_current_pid_tgid();
    __u32 tgid = pid_tgid >> 32;
    __u32 tid = pid_tgid;
    __u64 *entry;
    struct boundary_sample *sample;

    if (!enabled || tgid != target_tgid)
        return 0;
    if (!is_switch) {
        __sync_fetch_and_add(&no_switch_endpoints, 1);
        return 0;
    }
    __sync_fetch_and_add(&switch_endpoints, 1);
    entry = bpf_map_lookup_elem(&entry_times, &tid);
    if (!entry) {
        __sync_fetch_and_add(&missing_entries, 1);
        return 0;
    }
    if (end < *entry) {
        __sync_fetch_and_add(&negative_deltas, 1);
        bpf_map_delete_elem(&entry_times, &tid);
        return 0;
    }
    sample = bpf_ringbuf_reserve(&samples, sizeof(*sample), 0);
    if (!sample) {
        __sync_fetch_and_add(&ring_reserve_failures, 1);
        bpf_map_delete_elem(&entry_times, &tid);
        return 0;
    }
    sample->entry_ns = *entry;
    sample->endpoint_ns = end;
    sample->delta_ns = end - *entry;
    sample->tgid = tgid;
    sample->tid = tid;
    sample->cpu = bpf_get_smp_processor_id();
    sample->reserved = 0;
    bpf_map_delete_elem(&entry_times, &tid);
    __sync_fetch_and_add(&paired_samples, 1);
    bpf_ringbuf_submit(sample, 0);
    return 0;
}

char LICENSE[] SEC("license") = "Dual MIT/GPL";
