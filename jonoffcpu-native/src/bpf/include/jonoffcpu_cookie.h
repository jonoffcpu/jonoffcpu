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

struct jonoffcpu_target_binding {
    __u64 registration_token;
    __u64 process_generation_ns;
    __u64 host_tgid;
};

struct jonoffcpu_thread_state {
    __u64 start_monotonic_ns;
    __u64 thread_generation_ns;
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
};

#endif
