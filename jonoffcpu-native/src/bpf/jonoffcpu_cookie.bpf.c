// SPDX-License-Identifier: MIT
#include "vmlinux.h"
#include <bpf/bpf_core_read.h>
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>
#include "jonoffcpu_cookie.h"

extern int bpf_send_signal_task(struct task_struct *task, int sig,
                                enum pid_type type, __u64 value) __ksym;

struct {
    __uint(type, BPF_MAP_TYPE_TASK_STORAGE);
    __uint(map_flags, BPF_F_NO_PREALLOC);
    __type(key, int);
    __type(value, struct jonoffcpu_target_binding);
} target_tasks SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_TASK_STORAGE);
    __uint(map_flags, BPF_F_NO_PREALLOC);
    __type(key, int);
    __type(value, struct jonoffcpu_thread_state);
} thread_states SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_STACK_TRACE);
    __uint(key_size, sizeof(__u32));
    __uint(value_size, JONOFFCPU_STACK_DEPTH * sizeof(__u64));
    __uint(max_entries, 10240);
} stack_traces SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 1 << 20);
} observations SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
    __uint(max_entries, 1);
    __type(key, __u32);
    __type(value, struct jonoffcpu_stats);
} stats SEC(".maps");

volatile __u32 target_tgid;
volatile __u32 signal_number;
volatile __u32 capture_epoch;
volatile __u64 min_off_cpu_ns;
volatile __u64 max_off_cpu_ns;
volatile __u64 sample_threshold;
volatile __u32 admission_policy;
volatile __u64 record_all_above_ns;
volatile __u64 record_all_above_scaled;
volatile __u32 record_all_above_shift;
volatile __u32 has_min_off_cpu;
volatile __u32 has_max_off_cpu;
/* Bit (1 << reason) is set for each selected switch-out reason. */
volatile __u32 reason_mask;
volatile __u32 enabled;
volatile __u64 next_sequence;
volatile __u64 target_pid_namespace_device;
volatile __u64 target_pid_namespace_inode;
volatile __u32 target_namespace_tgid;
/* The collector's own drain thread lives in the target process; its poll sleeps
 * would otherwise be captured, signalled, and written back by itself. */
volatile __u32 collector_tid;
volatile __u32 agent_tid;
/* JONOFFCPU_TIME_SPLIT_*: whether each interval also records its run-queue
 * part, read from the scheduler's own per-task accounting. */
volatile __u32 time_split_source;

/* Local CO-RE flavours, so the object neither depends on the build host's
 * vmlinux.h carrying sched_info nor fails to load on a kernel without
 * CONFIG_SCHED_INFO; the collector refuses schedInfo there before loading. */
struct sched_info___jonoffcpu {
    unsigned long long run_delay;
} __attribute__((preserve_access_index));

struct task_struct___jonoffcpu {
    struct sched_info___jonoffcpu sched_info;
} __attribute__((preserve_access_index));

static __always_inline struct jonoffcpu_stats *current_stats(void)
{
    __u32 zero = 0;
    return bpf_map_lookup_elem(&stats, &zero);
}

static __always_inline struct jonoffcpu_target_binding *target_binding(
    struct task_struct *task, struct task_struct **leader_out)
{
    /* Direct CO-RE access preserves the verifier's task pointer type. */
    struct task_struct *leader = task->group_leader;
    if (!leader)
        return 0;
    *leader_out = leader;
    struct jonoffcpu_target_binding *binding =
        bpf_task_storage_get(&target_tasks, leader, 0, 0);
    if (binding && !binding->process_generation_ns) {
        __u64 generation = BPF_CORE_READ(leader, start_boottime);
        __sync_val_compare_and_swap(&binding->process_generation_ns, 0,
                                    generation);
    }
    if (binding && !binding->host_tgid) {
        __u64 host_tgid = BPF_CORE_READ(leader, tgid);
        __sync_val_compare_and_swap(&binding->host_tgid, 0, host_tgid);
    }
    return binding;
}

/* The scheduler adds each wait on a run queue to sched_info.run_delay when
 * the task gets a CPU, before the switch-in hook runs; the difference across
 * an interval is that interval's run-queue time, in rq_clock nanoseconds. */
static __always_inline __u64 run_delay(struct task_struct *task)
{
    struct task_struct___jonoffcpu *flavour = (void *)task;

    if (!bpf_core_field_exists(flavour->sched_info.run_delay))
        return 0;
    return BPF_CORE_READ(flavour, sched_info.run_delay);
}

static __always_inline __u32 allocate_sequence(struct jonoffcpu_stats *s)
{
    __u64 old = next_sequence;
    __u64 next;
    __u64 observed;

    if (!old) {
        if (s)
            __sync_fetch_and_add(&s->sequence_exhaustions, 1);
        return 0;
    }
    next = old == 0xffffffffULL ? 0 : old + 1;
    observed = __sync_val_compare_and_swap(&next_sequence, old, next);
    if (observed != old) {
        if (s)
            __sync_fetch_and_add(&s->sequence_contentions, 1);
        return 0;
    }
    return (__u32)old;
}

/* The raw tracepoint, not the trace event: `preempt` is the scheduler's own
 * verdict, and `prev_state` is the state captured before the switch rather than
 * a re-read of prev->__state, which races with a concurrent wakeup. The
 * four-argument prototype exists since Linux 5.18; on an older kernel the
 * verifier rejects the access to the fourth argument and the load fails closed.
 * The hook runs in the outgoing task's context, so the current task is prev. */
SEC("tp_btf/sched_switch")
int BPF_PROG(record_switch_out, bool preempt, struct task_struct *prev,
             struct task_struct *next, unsigned int prev_state)
{
    struct task_struct *task = bpf_get_current_task_btf();
    struct task_struct *leader = 0;
    struct jonoffcpu_target_binding *binding;
    struct jonoffcpu_thread_state initial = {};
    struct jonoffcpu_thread_state *state;
    struct jonoffcpu_stats *s;
    struct bpf_pidns_info self_ids = {};
    __u64 pid_tgid;
    __u8 reason;

    pid_tgid = bpf_get_current_pid_tgid();
    /* The numeric host TGID is only an optional, verified fast filter. */
    if (target_tgid && (__u32)(pid_tgid >> 32) != target_tgid)
        return 0;
    binding = target_binding(task, &leader);
    if (!binding) {
        s = current_stats();
        if (enabled && s)
            __sync_fetch_and_add(&s->lifetime_rejections, 1);
        return 0;
    }
    if (!enabled)
        return 0;
    /* The profiler's own threads report their TIDs from inside the target's
     * PID namespace. Their waits are never captured. */
    if ((collector_tid || agent_tid) &&
        !bpf_get_ns_current_pid_tgid(target_pid_namespace_device,
                                     target_pid_namespace_inode,
                                     &self_ids, sizeof(self_ids)) &&
        (self_ids.pid == collector_tid || self_ids.pid == agent_tid))
        return 0;
    if (preempt)
        reason = JONOFFCPU_REASON_PREEMPTED;
    else if (prev_state == 0)
        reason = JONOFFCPU_REASON_RUNNABLE;
    else
        reason = JONOFFCPU_REASON_BLOCKED;
    s = current_stats();
    if (s) {
        if (reason == JONOFFCPU_REASON_PREEMPTED)
            __sync_fetch_and_add(&s->switch_outs_preempted, 1);
        else if (reason == JONOFFCPU_REASON_RUNNABLE)
            __sync_fetch_and_add(&s->switch_outs_runnable, 1);
        else
            __sync_fetch_and_add(&s->switch_outs_blocked, 1);
    }
    initial.start_monotonic_ns = bpf_ktime_get_ns();
    initial.thread_generation_ns = BPF_CORE_READ(task, start_boottime);
    initial.prev_task_state = prev_state;
    initial.reason = reason;
    initial.preempted = preempt ? 1 : 0;
    initial.run_delay_at_switch_out =
        time_split_source == JONOFFCPU_TIME_SPLIT_SCHED_INFO ? run_delay(task) : 0;
    /* The state is stored for every reason and filtered at switch-in: skipping
     * the store would leave an older start time behind for the next interval. */
    state = bpf_task_storage_get(&thread_states, task, &initial,
                                 BPF_LOCAL_STORAGE_GET_F_CREATE);
    if (state) {
        state->start_monotonic_ns = initial.start_monotonic_ns;
        state->thread_generation_ns = initial.thread_generation_ns;
        state->prev_task_state = initial.prev_task_state;
        state->reason = initial.reason;
        state->preempted = initial.preempted;
        state->run_delay_at_switch_out = initial.run_delay_at_switch_out;
        if (s)
            __sync_fetch_and_add(&s->switch_outs, 1);
    } else {
        if (s)
            __sync_fetch_and_add(&s->thread_state_failures, 1);
    }
    return 0;
}

#if defined(JONOFFCPU_SCHED_EXIT_TP_BTF)
SEC("tp_btf/sched_exit_tp")
int BPF_PROG(capture_switch_in, bool is_switch)
#elif defined(JONOFFCPU_FINISH_FENTRY)
SEC("fentry/finish_task_switch")
int BPF_PROG(capture_switch_in)
#elif defined(JONOFFCPU_CONTROL_FENTRY)
SEC("fentry/__x64_sys_clock_nanosleep")
int BPF_PROG(capture_switch_in)
#else
SEC("kprobe/finish_task_switch")
int BPF_KPROBE(capture_switch_in)
#endif
{
    struct task_struct *task = bpf_get_current_task_btf();
    struct task_struct *leader = 0;
    struct jonoffcpu_target_binding *binding;
    struct jonoffcpu_target_binding *final_binding;
    struct jonoffcpu_thread_state *state;
    struct jonoffcpu_observation *event;
    struct jonoffcpu_stats *s = current_stats();
    __u64 pid_tgid;
    __u64 end_ns;
    __u64 duration_ns;
    __u64 duration_us;
    __u64 admission_threshold;
    __u64 cookie;
    __u64 registration_token;
    __u64 runqueue_ns = 0;
    __u8 has_runqueue = 0;
    __u32 sequence;
    struct bpf_pidns_info target_ids = {};
    long namespace_result;
    long stack_id;

    pid_tgid = bpf_get_current_pid_tgid();
    if (target_tgid && (__u32)(pid_tgid >> 32) != target_tgid)
        return 0;
    binding = target_binding(task, &leader);
    if (!binding) {
        if (enabled && s)
            __sync_fetch_and_add(&s->lifetime_rejections, 1);
        return 0;
    }
    registration_token = binding->registration_token;
#if defined(JONOFFCPU_SCHED_EXIT_TP_BTF)
    if (!is_switch) {
        if (s)
            __sync_fetch_and_add(&s->scheduler_exit_no_switches, 1);
        return 0;
    }
    if (s)
        __sync_fetch_and_add(&s->scheduler_exit_switches, 1);
#endif
    state = bpf_task_storage_get(&thread_states, task, 0, 0);
    if (!state)
        return 0;
    end_ns = bpf_ktime_get_ns();
    if (end_ns < state->start_monotonic_ns)
        goto cleanup;
    duration_ns = end_ns - state->start_monotonic_ns;
    duration_us = duration_ns / 1000;
    if (!enabled)
        goto cleanup;
    /* The run-queue part, taken before any filter so that every interval is
     * read alike. It is the raw growth of run_delay, in rq_clock nanoseconds:
     * rq_clock can be a little stale when a runnable task departs, so the value
     * may exceed a runnable or preempted interval's bpf_ktime_get_ns duration by
     * microseconds. The consumers apply the split rule; only a counter that went
     * backwards is dropped here, and counted. */
    if (time_split_source == JONOFFCPU_TIME_SPLIT_SCHED_INFO) {
        __u64 delay = run_delay(task);

        if (delay >= state->run_delay_at_switch_out) {
            runqueue_ns = delay - state->run_delay_at_switch_out;
            has_runqueue = 1;
        } else if (s) {
            __sync_fetch_and_add(&s->runqueue_inversions, 1);
        }
    }
    /* Reason filter, then duration bounds, then admission. */
    if (state->reason > JONOFFCPU_REASON_PREEMPTED ||
        !(reason_mask & (1U << state->reason))) {
        if (s) {
            __sync_fetch_and_add(&s->reason_rejections, 1);
            __sync_fetch_and_add(&s->reason_rejected_duration_us, duration_us);
        }
        goto cleanup;
    }
    if ((has_min_off_cpu && duration_ns <= min_off_cpu_ns) ||
        (has_max_off_cpu && duration_ns >= max_off_cpu_ns))
        goto cleanup;
    if (s)
        __sync_fetch_and_add(&s->eligible_intervals, 1);
    if (s)
        __sync_fetch_and_add(&s->eligible_duration_us, duration_us);
    /* The proportional policy admits with probability duration / D, so the
     * 32-bit threshold is duration * 2^32 / D. Userspace pre-shifts D below
     * 2^32; duration < D keeps the shifted numerator below 2^64. */
    if (admission_policy == JONOFFCPU_ADMISSION_PROPORTIONAL) {
        if (duration_ns >= record_all_above_ns)
            admission_threshold = JONOFFCPU_ADMISSION_CERTAIN;
        else if (record_all_above_scaled)
            admission_threshold = ((duration_ns >> record_all_above_shift) << 32) /
                                  record_all_above_scaled;
        else
            admission_threshold = 0;
    } else {
        admission_threshold = sample_threshold;
    }
    if ((__u64)bpf_get_prandom_u32() >= admission_threshold) {
        if (s)
            __sync_fetch_and_add(&s->admission_rejections, 1);
        goto cleanup;
    }
    if (s)
        __sync_fetch_and_add(&s->selected_intervals, 1);
    namespace_result = bpf_get_ns_current_pid_tgid(
        target_pid_namespace_device, target_pid_namespace_inode,
        &target_ids, sizeof(target_ids));
    if (namespace_result || target_ids.tgid != target_namespace_tgid ||
        !target_ids.pid) {
        if (s)
            __sync_fetch_and_add(&s->target_namespace_failures, 1);
        goto cleanup;
    }

    event = bpf_ringbuf_reserve(&observations, sizeof(*event), 0);
    if (!event) {
        if (s)
            __sync_fetch_and_add(&s->ring_reserve_failures, 1);
        goto cleanup;
    }
    __builtin_memset(event, 0, sizeof(*event));
    event->start_monotonic_ns = state->start_monotonic_ns;
    event->end_monotonic_ns = end_ns;
    event->process_generation_ns = BPF_CORE_READ(leader, start_boottime);
    event->thread_generation_ns = state->thread_generation_ns;
    event->registration_token = registration_token;
    event->admission_threshold = admission_threshold;
    event->host_tgid = (__u32)(pid_tgid >> 32);
    event->host_tid = (__u32)pid_tgid;
    event->target_tgid = target_ids.tgid;
    event->target_tid = target_ids.pid;
    event->capture_epoch = capture_epoch;
    event->prev_task_state = state->prev_task_state;
    event->reason = state->reason;
    event->preempted = state->preempted;
    event->has_runqueue = has_runqueue;
    event->runqueue_ns = runqueue_ns;
    bpf_get_current_comm(event->comm, sizeof(event->comm));
    stack_id = bpf_get_stackid(ctx, &stack_traces, 0);
    event->kernel_stack_id = stack_id;
    if (stack_id < 0 && s)
        __sync_fetch_and_add(&s->kernel_stack_failures, 1);
    stack_id = bpf_get_stackid(ctx, &stack_traces, BPF_F_USER_STACK);
    event->user_stack_id = stack_id;
    if (stack_id < 0 && s)
        __sync_fetch_and_add(&s->user_stack_failures, 1);

    /* Revalidate the exact leader binding immediately before allocating the
     * externally visible ID and requesting the signal. */
    final_binding = target_binding(task, &leader);
    if (!final_binding || final_binding->registration_token != registration_token) {
        if (s)
            __sync_fetch_and_add(&s->lifetime_rejections, 1);
        bpf_ringbuf_discard(event, 0);
        goto cleanup;
    }
    sequence = allocate_sequence(s);
    if (!sequence) {
        bpf_ringbuf_discard(event, 0);
        goto cleanup;
    }
    cookie = ((__u64)capture_epoch << 32) | sequence;
    event->correlation_id = cookie;
    event->sequence = sequence;
    event->signal_result = bpf_send_signal_task(task, signal_number,
                                                PIDTYPE_PID, cookie);
    if (event->signal_result && s)
        __sync_fetch_and_add(&s->signal_failures, 1);
    bpf_ringbuf_submit(event, 0);

cleanup:
    bpf_task_storage_delete(&thread_states, task);
    return 0;
}

char LICENSE[] SEC("license") = "Dual BSD/GPL";
