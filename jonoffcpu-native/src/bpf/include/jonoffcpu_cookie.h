// SPDX-License-Identifier: MIT
#ifndef JONOFFCPU_COOKIE_H
#define JONOFFCPU_COOKIE_H

#define JONOFFCPU_TASK_COMM_LEN 16
#define JONOFFCPU_STACK_DEPTH 127

/* Admission policies applied after the duration bounds. The uniform policy is
 * zero so that a zeroed configuration behaves like a fixed threshold. */
#define JONOFFCPU_ADMISSION_UNIFORM 0
#define JONOFFCPU_ADMISSION_PROPORTIONAL 1
#define JONOFFCPU_ADMISSION_CERTAIN (1ULL << 32)

/* Why the scheduler took the thread off the CPU, derived at switch-out from the
 * raw sched_switch arguments. Zero is never written by the kernel: a capture
 * without classification reads back as unspecified. */
#define JONOFFCPU_REASON_UNSPECIFIED 0
#define JONOFFCPU_REASON_BLOCKED 1
#define JONOFFCPU_REASON_RUNNABLE 2
#define JONOFFCPU_REASON_PREEMPTED 3

/* Where the run-queue part of an interval comes from. Off reads nothing. */
#define JONOFFCPU_TIME_SPLIT_OFF 0
#define JONOFFCPU_TIME_SPLIT_SCHED_INFO 1

struct jonoffcpu_target_binding {
    __u64 registration_token;
    __u64 process_generation_ns;
    __u64 host_tgid;
};

struct jonoffcpu_thread_state {
    __u64 start_monotonic_ns;
    __u64 thread_generation_ns;
    /* The raw task state sched_switch reported; 0 is TASK_RUNNING. */
    /* The scheduler's cumulative sched_info.run_delay at switch-out. */
    __u64 run_delay_at_switch_out;
    __u32 prev_task_state;
    __u8 reason;
    __u8 preempted;
};

struct jonoffcpu_observation {
    __u64 correlation_id;
    __u64 start_monotonic_ns;
    __u64 end_monotonic_ns;
    __u64 process_generation_ns;
    __u64 thread_generation_ns;
    __u64 registration_token;
    __u64 admission_threshold;
    __s64 signal_result;
    __s64 kernel_stack_id;
    __s64 user_stack_id;
    __u32 host_tgid;
    __u32 host_tid;
    __u32 target_tgid;
    __u32 target_tid;
    __u32 capture_epoch;
    __u32 sequence;
    char comm[JONOFFCPU_TASK_COMM_LEN];
    __u32 prev_task_state;
    __u8 reason;
    __u8 preempted;
    /* Whether runqueue_ns carries the interval's run-queue time. */
    __u8 has_runqueue;
    __u8 reserved;
    /* The growth of sched_info.run_delay across the interval. */
    __u64 runqueue_ns;
};

struct jonoffcpu_stats {
    __u64 switch_outs;
    __u64 scheduler_exit_switches;
    __u64 scheduler_exit_no_switches;
    __u64 lifetime_rejections;
    __u64 thread_state_failures;
    __u64 eligible_intervals;
    __u64 eligible_duration_us;
    __u64 admission_rejections;
    __u64 selected_intervals;
    __u64 sequence_exhaustions;
    __u64 sequence_contentions;
    __u64 kernel_stack_failures;
    __u64 user_stack_failures;
    __u64 signal_failures;
    __u64 ring_reserve_failures;
    __u64 target_namespace_failures;
    /* Every switch-out of the target's threads by reason, counted before the
     * reason filter, so a blocked-only capture still reports how often its
     * threads were preempted. */
    __u64 switch_outs_blocked;
    __u64 switch_outs_runnable;
    __u64 switch_outs_preempted;
    __u64 reason_rejections;
    __u64 reason_rejected_duration_us;
    /* Run-queue readings dropped because sched_info.run_delay went backwards
     * across the interval; the interval is then recorded unsplit. */
    __u64 runqueue_inversions;
};

#endif
