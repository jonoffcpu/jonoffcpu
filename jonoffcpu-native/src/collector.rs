// SPDX-License-Identifier: MIT
use crate::bpf_sched_exit::JonoffcpuCookieSkelBuilder;
use crate::capture;
use crate::capture::{CollectorErrorCode as ErrorCode, CollectorReply, CollectorState};
use anyhow::{Context, Result, anyhow, bail};
#[cfg(test)]
use libbpf_rs::TracepointCategory;
use libbpf_rs::skel::{OpenSkel, SkelBuilder};
use libbpf_rs::{Link, MapCore, MapFlags, RingBufferBuilder};
use prost::Message;
use std::collections::{HashMap, HashSet};
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{BufWriter, Read, Write};
use std::mem::{MaybeUninit, size_of};
#[cfg(test)]
use std::os::fd::AsFd;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const SOURCE_ID: &str = "jonoffcpu.offcpu.v1";
const LOADER: &str = "libbpf-rs/libbpf-cargo 0.27.1 (libbpf 1.7.0)";
const HOOK: &str = "tp_btf/sched_exit_tp";
const SWITCH_OUT_HOOK: &str = "tp_btf/sched_switch";
const DRAIN_QUIET_POLLS: usize = 2;
const MAX_RING_BATCH: usize = 1024;
const OUTPUT_BUFFER_BYTES: usize = 256 * 1024;
const MAX_AUDITED_THREADS: usize = 4096;
const MAX_TID_EXAMPLES: usize = 32;
const MAX_DIAGNOSTIC_REASON: usize = 240;
const MAX_PROC_STATUS_BYTES: usize = 64 * 1024;
const SIGNAL_DIAGNOSTIC_BUDGET: Duration = Duration::from_millis(100);
const WALL_CLOCK_CALIBRATION_SAMPLES: usize = 9;
const TARGET_EXIT_DRAIN_TIMEOUT: Duration = Duration::from_secs(5);

/// A failure with the `CollectorErrorCode` the reply reports. It is attached as anyhow context at
/// the point that knows what went wrong, so the code travels with the error instead of being
/// guessed from its message; the outermost coded context in a chain decides the code.
#[derive(Clone, Debug)]
pub(crate) struct Failure {
    pub(crate) code: ErrorCode,
    pub(crate) message: String,
}

impl fmt::Display for Failure {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for Failure {}

impl Failure {
    /// Classifies an error by its outermost coded context; an uncoded error is an internal one.
    pub(crate) fn of(error: &anyhow::Error) -> Self {
        // anyhow's downcast sees through plain context layers to the outermost `Failure`, whether
        // it was attached as context or is the error itself.
        let code = error
            .downcast_ref::<Failure>()
            .map_or(ErrorCode::InternalError, |failure| failure.code);
        Self {
            code,
            message: format!("{error:#}"),
        }
    }

    fn into_error(self) -> anyhow::Error {
        anyhow::Error::new(self)
    }
}

/// A new error that carries its code.
pub(crate) fn fail(code: ErrorCode, message: impl Into<String>) -> anyhow::Error {
    Failure {
        code,
        message: message.into(),
    }
    .into_error()
}

/// Adds coded context to a fallible result, as `anyhow::Context` adds plain context.
pub(crate) trait Coded<T, E> {
    fn coded(self, code: ErrorCode, message: impl Into<String>) -> Result<T>;
}

impl<T, E, R> Coded<T, E> for R
where
    R: Context<T, E>,
{
    fn coded(self, code: ErrorCode, message: impl Into<String>) -> Result<T> {
        self.context(Failure {
            code,
            message: message.into(),
        })
    }
}

fn invalid_config(message: impl Into<String>) -> anyhow::Error {
    fail(ErrorCode::InvalidConfig, message)
}

fn invalid_state(message: impl Into<String>) -> anyhow::Error {
    fail(ErrorCode::InvalidState, message)
}

/// A validated `PrepareRequest`.
#[derive(Debug)]
pub(crate) struct PrepareConfig {
    target_pid: u32,
    output_path: PathBuf,
    sampling: SamplingPolicy,
    time_split: TimeSplitPolicy,
    /// The agent's controller thread calls prepare and later only polls the profiler; when set, that
    /// thread's own waits are left out of the capture like the collector's, so the profiler does not
    /// observe itself.
    exclude_calling_thread: bool,
    calling_tid: u32,
}

/// A validated `EnableRequest`.
#[derive(Debug, Clone)]
pub(crate) struct EnableConfig {
    session_id: String,
    capture_epoch: u32,
    signal: i32,
    signal_delivery: capture::SignalDelivery,
    sampling: capture::Sampling,
    time_split: capture::TimeSplit,
}

/// Where each interval's run-queue part comes from. Echoed like `sampling`: it changes what is
/// measured, not which intervals are kept, so it is a block of its own.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct TimeSplitPolicy {
    /// The message as the agent sent it, echoed unchanged.
    message: capture::TimeSplit,
    source: TimeSplitSource,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TimeSplitSource {
    /// Nothing is read; observations carry no run-queue part.
    Off,
    /// The growth of the scheduler's `task_struct.sched_info.run_delay` across the interval.
    SchedInfo,
}

impl TimeSplitPolicy {
    fn parse(message: capture::TimeSplit) -> Result<Self> {
        let source = match capture::TimeSplitSource::try_from(message.source) {
            Ok(capture::TimeSplitSource::Off) => TimeSplitSource::Off,
            Ok(capture::TimeSplitSource::SchedInfo) => TimeSplitSource::SchedInfo,
            _ => return Err(invalid_config("timeSplit.source must be OFF or SCHED_INFO")),
        };
        Ok(Self { message, source })
    }

    /// The kernel's `JONOFFCPU_TIME_SPLIT_*` value.
    fn kernel_value(&self) -> u32 {
        match self.source {
            TimeSplitSource::Off => 0,
            TimeSplitSource::SchedInfo => 1,
        }
    }
}

/// Whether the running kernel keeps `task_struct.sched_info.run_delay` (`CONFIG_SCHED_INFO`).
fn run_delay_available() -> Result<bool> {
    use libbpf_rs::btf::types::Struct;
    let btf =
        libbpf_rs::btf::Btf::from_vmlinux().coded(ErrorCode::BpfUnsupported, "load vmlinux BTF")?;
    let Some(task) = btf.type_by_name::<Struct<'_>>("task_struct") else {
        return Ok(false);
    };
    let Some(member) = task
        .iter()
        .find(|member| member.name.is_some_and(|name| name == "sched_info"))
    else {
        return Ok(false);
    };
    let Some(sched_info) = btf
        .type_by_id::<libbpf_rs::btf::BtfType<'_>>(member.ty)
        .map(|ty| ty.skip_mods_and_typedefs())
        .and_then(|ty| Struct::try_from(ty).ok())
    else {
        return Ok(false);
    };
    Ok(sched_info
        .iter()
        .any(|member| member.name.is_some_and(|name| name == "run_delay")))
}

/// The resolved sampling policy. The message is echoed unchanged in every reply and in the
/// `captureStart` record, so the agent and the correlator can compare copies structurally.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct SamplingPolicy {
    message: capture::Sampling,
    /// The switch-out reasons whose intervals are eligible, in canonical order.
    reasons: Vec<OffCpuReason>,
    min_off_cpu_micros: Option<u64>,
    max_off_cpu_micros: Option<u64>,
    admission: Admission,
}

/// Why the scheduler took a thread off the CPU. The kernel derives it at switch-out from the raw
/// `sched_switch` arguments: `preempt` gives `Preempted`; otherwise a `prev_state` of zero
/// (`TASK_RUNNING`) gives `Runnable` and any other state `Blocked`. A user-space thread preempted by
/// the tick is switched out at an ordinary `schedule()` on its return to user mode, so it is
/// `Runnable`; `Preempted` is preemption at a point inside the kernel.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum OffCpuReason {
    Blocked,
    Runnable,
    Preempted,
}

impl OffCpuReason {
    /// The kernel's `JONOFFCPU_REASON_*` value, which is also the capture schema's enum number.
    pub(crate) fn kernel_value(self) -> u8 {
        match self {
            Self::Blocked => 1,
            Self::Runnable => 2,
            Self::Preempted => 3,
        }
    }

    pub(crate) fn from_kernel(value: u8) -> Option<Self> {
        match value {
            1 => Some(Self::Blocked),
            2 => Some(Self::Runnable),
            3 => Some(Self::Preempted),
            _ => None,
        }
    }

    fn from_message(value: i32) -> Option<Self> {
        u8::try_from(value).ok().and_then(Self::from_kernel)
    }

    /// The classification the BPF program applies, which the tests model.
    #[cfg(test)]
    pub(crate) fn classify(preempted: bool, prev_task_state: u32) -> Self {
        if preempted {
            Self::Preempted
        } else if prev_task_state == 0 {
            Self::Runnable
        } else {
            Self::Blocked
        }
    }
}

/// Admission decides which duration-eligible intervals are recorded. The `none` policy never
/// reaches the collector: the agent runs async-profiler alone in that case.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Admission {
    /// Every eligible interval is admitted with the same probability `probability_threshold / 2^32`.
    Uniform { probability_threshold: u64 },
    /// An interval of at least `record_all_above_micros` is always admitted; a shorter one with
    /// probability `duration / record_all_above_micros`.
    Proportional { record_all_above_micros: u64 },
}

/// Shifts the proportional policy's reference duration below 2^32 for the kernel. The BPF program
/// has no 128-bit arithmetic; with `duration < reference` the shifted numerator
/// `(duration >> shift) << 32` then stays below 2^64. The threshold the kernel applies is
/// `((duration >> shift) << 32) / scaled`, or 2^32 at and above the reference; the agent's
/// verifier and the correlator recompute that exact value from each row.
pub(crate) fn proportional_scale(record_all_above_ns: u64) -> (u64, u32) {
    let shift = (64 - record_all_above_ns.leading_zeros()).saturating_sub(32);
    (record_all_above_ns >> shift, shift)
}

impl SamplingPolicy {
    fn parse(message: capture::Sampling) -> Result<Self> {
        let mut reasons = Vec::with_capacity(message.reasons.len());
        for value in &message.reasons {
            reasons.push(OffCpuReason::from_message(*value).ok_or_else(|| {
                invalid_config(format!("sampling.reasons has an invalid reason {value}"))
            })?);
        }
        if reasons.is_empty() {
            return Err(invalid_config(
                "sampling.reasons must name at least one switch-out reason",
            ));
        }
        // Canonical order keeps the echoed message equal to the one the agent sent.
        if reasons.windows(2).any(|pair| pair[0] >= pair[1]) {
            return Err(invalid_config(
                "sampling.reasons must be distinct and in the order blocked, runnable, preempted",
            ));
        }
        let min_ns = message
            .min_off_cpu_micros
            .map(|value| {
                value
                    .checked_mul(1_000)
                    .ok_or_else(|| invalid_config("minOffCpuMicros overflows nanos"))
            })
            .transpose()?;
        let max_ns = message
            .max_off_cpu_micros
            .map(|value| {
                value
                    .checked_mul(1_000)
                    .ok_or_else(|| invalid_config("maxOffCpuMicros overflows nanos"))
            })
            .transpose()?;
        if min_ns.zip(max_ns).is_some_and(|(min, max)| min >= max) {
            return Err(invalid_config(
                "minOffCpuMicros must be strictly below maxOffCpuMicros",
            ));
        }
        let admission = match &message.admission {
            Some(capture::sampling::Admission::Uniform(uniform)) => {
                let threshold = uniform.probability_threshold;
                if threshold == 0 || threshold > (1_u64 << 32) {
                    return Err(invalid_config(
                        "probabilityThreshold must be in 1..=4294967296",
                    ));
                }
                Admission::Uniform {
                    probability_threshold: threshold,
                }
            }
            Some(capture::sampling::Admission::Proportional(proportional)) => {
                let micros = proportional.record_all_above_micros;
                if micros == 0 {
                    return Err(invalid_config("recordAllAboveMicros must be at least 1"));
                }
                micros
                    .checked_mul(1_000)
                    .ok_or_else(|| invalid_config("recordAllAboveMicros overflows nanos"))?;
                Admission::Proportional {
                    record_all_above_micros: micros,
                }
            }
            Some(capture::sampling::Admission::None(_)) => {
                return Err(invalid_config(
                    "sampling.admission none runs no off-CPU source; the collector must not be prepared",
                ));
            }
            None => return Err(invalid_config("sampling.admission must be set")),
        };
        Ok(Self {
            reasons,
            min_off_cpu_micros: message.min_off_cpu_micros,
            max_off_cpu_micros: message.max_off_cpu_micros,
            admission,
            message,
        })
    }

    /// Bit `1 << reason` for each selected reason, as the BPF program tests it.
    fn reason_mask(&self) -> u32 {
        self.reasons
            .iter()
            .fold(0, |mask, reason| mask | (1_u32 << reason.kernel_value()))
    }

    fn min_off_cpu_ns(&self) -> u64 {
        self.min_off_cpu_micros
            .and_then(|value| value.checked_mul(1_000))
            .unwrap_or(0)
    }

    fn max_off_cpu_ns(&self) -> u64 {
        self.max_off_cpu_micros
            .and_then(|value| value.checked_mul(1_000))
            .unwrap_or(0)
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
struct Observation {
    correlation_id: u64,
    start_monotonic_ns: u64,
    end_monotonic_ns: u64,
    process_generation_ns: u64,
    thread_generation_ns: u64,
    registration_token: u64,
    admission_threshold: u64,
    signal_result: i64,
    kernel_stack_id: i64,
    user_stack_id: i64,
    host_tgid: u32,
    host_tid: u32,
    target_tgid: u32,
    target_tid: u32,
    capture_epoch: u32,
    sequence: u32,
    comm: [u8; 16],
    prev_task_state: u32,
    reason: u8,
    preempted: u8,
    has_runqueue: u8,
    reserved: u8,
    runqueue_ns: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
struct KernelStats {
    switch_outs: u64,
    scheduler_exit_switches: u64,
    scheduler_exit_no_switches: u64,
    lifetime_rejections: u64,
    thread_state_failures: u64,
    eligible_intervals: u64,
    eligible_duration_us: u64,
    admission_rejections: u64,
    selected_intervals: u64,
    sequence_exhaustions: u64,
    sequence_contentions: u64,
    kernel_stack_failures: u64,
    user_stack_failures: u64,
    signal_failures: u64,
    ring_reserve_failures: u64,
    target_namespace_failures: u64,
    switch_outs_blocked: u64,
    switch_outs_runnable: u64,
    switch_outs_preempted: u64,
    reason_rejections: u64,
    reason_rejected_duration_us: u64,
    runqueue_inversions: u64,
}

impl KernelStats {
    fn add(&mut self, other: &Self) {
        let left = unsafe {
            std::slice::from_raw_parts_mut(
                (self as *mut Self).cast::<u64>(),
                size_of::<Self>() / size_of::<u64>(),
            )
        };
        let right = unsafe {
            std::slice::from_raw_parts(
                (other as *const Self).cast::<u64>(),
                size_of::<Self>() / size_of::<u64>(),
            )
        };
        for (left, right) in left.iter_mut().zip(right) {
            *left = left.saturating_add(*right);
        }
    }

    fn message(&self) -> capture::KernelCounters {
        capture::KernelCounters {
            switch_outs: self.switch_outs,
            scheduler_exit_switches: self.scheduler_exit_switches,
            scheduler_exit_no_switches: self.scheduler_exit_no_switches,
            lifetime_rejections: self.lifetime_rejections,
            thread_state_failures: self.thread_state_failures,
            eligible_intervals: self.eligible_intervals,
            eligible_duration_micros: self.eligible_duration_us,
            admission_rejections: self.admission_rejections,
            selected_intervals: self.selected_intervals,
            sequence_exhaustions: self.sequence_exhaustions,
            sequence_contentions: self.sequence_contentions,
            kernel_stack_failures: self.kernel_stack_failures,
            user_stack_failures: self.user_stack_failures,
            signal_failures: self.signal_failures,
            ring_reserve_failures: self.ring_reserve_failures,
            target_namespace_failures: self.target_namespace_failures,
            switch_outs_blocked: self.switch_outs_blocked,
            switch_outs_runnable: self.switch_outs_runnable,
            switch_outs_preempted: self.switch_outs_preempted,
            reason_rejections: self.reason_rejections,
            reason_rejected_duration_micros: self.reason_rejected_duration_us,
            runqueue_inversions: self.runqueue_inversions,
        }
    }
}

#[derive(Default)]
struct UserStats {
    received_observations: u64,
    written_observations: u64,
    symbolization_failures: u64,
    write_failures: u64,
    poll_failures: u64,
    drain_timed_out: u64,
}

impl UserStats {
    fn message(&self) -> capture::UserspaceCounters {
        capture::UserspaceCounters {
            received_observations: self.received_observations,
            written_observations: self.written_observations,
            symbolization_failures: self.symbolization_failures,
            write_failures: self.write_failures,
            poll_failures: self.poll_failures,
            drain_timed_out: self.drain_timed_out,
        }
    }
}

struct PreparedIdentity {
    target_pid: u32,
    host_tgid: u32,
    pid_namespace_device: u64,
    pid_namespace_inode: u64,
    time_namespace_inode: u64,
    registration_token: u64,
    process_generation_ns: u64,
    pidfd: OwnedFd,
}

pub(crate) struct PreparedCollector {
    /// Also in the reply; the tests address the collector by it directly.
    #[cfg_attr(not(test), allow(dead_code))]
    pub(crate) handle: u64,
    pub(crate) reply: CollectorReply,
}

/// The frozen result of a stop: the terminal `captureEnd` as it was appended and how it got there.
#[derive(Clone, Debug, PartialEq)]
struct StopOutcome {
    complete: bool,
    stopped: capture::Stopped,
}

impl StopOutcome {
    fn state(&self) -> CollectorState {
        if self.complete {
            CollectorState::Complete
        } else {
            CollectorState::Incomplete
        }
    }
}

type Reply<T> = std::result::Result<T, Failure>;

enum Command {
    Enable(EnableConfig, Sender<Reply<capture::Enabled>>),
    Stop(Duration, Sender<Reply<StopOutcome>>),
    Close,
}

struct RegistryEntry {
    sender: Sender<Command>,
    join: Option<JoinHandle<()>>,
    close_requested: bool,
    /// Set by the worker before it exits: the stop a close performed, if any.
    close_result: Arc<Mutex<Option<Reply<Option<StopOutcome>>>>>,
}

static REGISTRY: OnceLock<Mutex<HashMap<u64, RegistryEntry>>> = OnceLock::new();
static CLOSED_HANDLES: OnceLock<Mutex<HashSet<u64>>> = OnceLock::new();
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

fn registry() -> &'static Mutex<HashMap<u64, RegistryEntry>> {
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn closed_handles() -> &'static Mutex<HashSet<u64>> {
    CLOSED_HANDLES.get_or_init(|| Mutex::new(HashSet::new()))
}

/// A reply of the current C ABI version.
pub(crate) fn reply(
    state: CollectorState,
    result: capture::collector_reply::Result,
) -> CollectorReply {
    CollectorReply {
        abi_version: crate::ABI_VERSION,
        state: state as i32,
        result: Some(result),
    }
}

/// The reply to a failed call. Only a timed-out stop or close may be retried, and says so in its
/// state; every other failure leaves the call's effect undefined, which is `ERROR`.
pub(crate) fn error_reply(code: ErrorCode, message: String) -> CollectorReply {
    let state = match code {
        ErrorCode::StopTimeout => CollectorState::Stopping,
        ErrorCode::CloseTimeout => CollectorState::Closing,
        _ => CollectorState::Error,
    };
    reply(
        state,
        capture::collector_reply::Result::Error(capture::CollectorError {
            code: code as i32,
            message,
        }),
    )
}

pub(crate) fn parse_prepare(bytes: &[u8]) -> Result<PrepareConfig> {
    let request = capture::PrepareRequest::decode(bytes)
        .coded(ErrorCode::InvalidConfig, "invalid PrepareRequest")?;
    if request.target_pid == 0 {
        return Err(invalid_config("targetPid must be nonzero"));
    }
    if request.output_path.is_empty() {
        return Err(invalid_config("outputPath must be nonempty"));
    }
    let sampling = SamplingPolicy::parse(
        request
            .sampling
            .ok_or_else(|| invalid_config("sampling must be set"))?,
    )?;
    let time_split = TimeSplitPolicy::parse(
        request
            .time_split
            .ok_or_else(|| invalid_config("timeSplit must be set"))?,
    )?;
    Ok(PrepareConfig {
        target_pid: request.target_pid,
        output_path: PathBuf::from(request.output_path),
        sampling,
        time_split,
        exclude_calling_thread: request.exclude_calling_thread,
        calling_tid: 0,
    })
}

pub(crate) fn parse_enable(bytes: &[u8]) -> Result<EnableConfig> {
    let request = capture::EnableRequest::decode(bytes)
        .coded(ErrorCode::InvalidConfig, "invalid EnableRequest")?;
    let session = request.session_id.as_bytes();
    if session.len() != 36
        || [8, 13, 18, 23].iter().any(|index| session[*index] != b'-')
        || !session
            .iter()
            .all(|byte| *byte == b'-' || byte.is_ascii_digit() || (b'a'..=b'f').contains(byte))
    {
        return Err(invalid_config(
            "sessionId must be a canonical lowercase UUID",
        ));
    }
    let signal = request.signal;
    if request.capture_epoch == 0
        || signal <= 0
        || signal > libc::SIGRTMAX()
        || signal == libc::SIGKILL
        || signal == libc::SIGSTOP
        // Linux kernel RT signals below libc's runtime minimum are reserved by libc.
        || (32..libc::SIGRTMIN()).contains(&signal)
    {
        return Err(invalid_config(
            "captureEpoch and signal must be valid nonzero values",
        ));
    }
    // Delivery is never inferred: an unspecified or mismatched policy is rejected, not replaced.
    if request.signal_delivery != signal_delivery(signal) as i32 {
        return Err(invalid_config(
            "signalDelivery does not match the selected signal category",
        ));
    }
    Ok(EnableConfig {
        session_id: request.session_id,
        capture_epoch: request.capture_epoch,
        signal,
        signal_delivery: signal_delivery(signal),
        sampling: request
            .sampling
            .ok_or_else(|| invalid_config("sampling must be set"))?,
        time_split: request
            .time_split
            .ok_or_else(|| invalid_config("timeSplit must be set"))?,
    })
}

fn signal_delivery(signal: i32) -> capture::SignalDelivery {
    if signal >= libc::SIGRTMIN() && signal <= libc::SIGRTMAX() {
        capture::SignalDelivery::Queued
    } else {
        capture::SignalDelivery::Coalescing
    }
}

fn signal_environment_snapshot(
    identity: &PreparedIdentity,
    signal: i32,
    phase: capture::SnapshotPhase,
    outer_deadline: Option<Instant>,
) -> capture::SignalEnvironment {
    let snapshot_started = Instant::now();
    let budget = outer_deadline.map_or(SIGNAL_DIAGNOSTIC_BUDGET, |outer| {
        (outer.saturating_duration_since(snapshot_started) / 4).min(SIGNAL_DIAGNOSTIC_BUDGET)
    });
    let deadline = snapshot_started + budget;
    let (observed_ns, clock_unavailable_reason) = match monotonic_ns() {
        Ok(value) => (Some(value), String::new()),
        Err(error) => (None, diagnostic_reason(&error)),
    };
    let alive_before = !pidfd_exited(&identity.pidfd);
    let unavailable = if alive_before {
        None
    } else {
        Some("target pidfd reported exit before signal-environment snapshot")
    };
    let (mut rlimit, mut sigq, mut audit) = if let Some(reason) = unavailable {
        unavailable_signal_diagnostics(identity, signal, reason, false)
    } else if Instant::now() >= deadline {
        unavailable_signal_diagnostics(
            identity,
            signal,
            "signal-environment time budget exhausted before target reads",
            true,
        )
    } else {
        (
            sigpending_limit_snapshot(identity.target_pid),
            sigq_snapshot(identity.target_pid, deadline),
            thread_mask_audit(identity, signal, deadline),
        )
    };
    let alive_after = !pidfd_exited(&identity.pidfd);
    if !alive_before || !alive_after {
        let reason = if alive_before {
            "target pidfd reported exit during signal-environment snapshot; numeric PID observations discarded"
        } else {
            "target pidfd reported exit before signal-environment snapshot"
        };
        (rlimit, sigq, audit) = unavailable_signal_diagnostics(identity, signal, reason, false);
    }
    capture::SignalEnvironment {
        phase: phase as i32,
        observed_monotonic_nanos: observed_ns,
        clock_unavailable_reason,
        selected_signal: signal,
        signal_delivery: signal_delivery(signal) as i32,
        runtime_realtime_min: libc::SIGRTMIN(),
        runtime_realtime_max: libc::SIGRTMAX(),
        time_budget_millis: millis(budget),
        maximum_time_budget_millis: millis(SIGNAL_DIAGNOSTIC_BUDGET),
        time_budget_exceeded: audit.time_budget_exceeded,
        target_pidfd_alive_before: alive_before,
        target_pidfd_alive_after: alive_after,
        rlimit_sigpending: Some(rlimit),
        signal_queue: Some(sigq),
        thread_mask_audit: Some(audit),
    }
}

fn millis(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

fn unavailable_signal_diagnostics(
    identity: &PreparedIdentity,
    signal: i32,
    reason: &str,
    timed_out: bool,
) -> (
    capture::SigpendingLimit,
    capture::SignalQueue,
    capture::ThreadMaskAudit,
) {
    (
        unavailable_sigpending_limit(reason.to_string()),
        unavailable_sigq(reason, None),
        unavailable_thread_mask_audit(identity, signal, reason, timed_out),
    )
}

fn unavailable_sigpending_limit(reason: String) -> capture::SigpendingLimit {
    capture::SigpendingLimit {
        status: capture::DiagnosticStatus::Unavailable as i32,
        soft: None,
        hard: None,
        unavailable_reason: reason,
    }
}

fn sigpending_limit_snapshot(target_pid: u32) -> capture::SigpendingLimit {
    let mut limit = std::mem::MaybeUninit::<libc::rlimit>::uninit();
    let result = unsafe {
        libc::prlimit(
            target_pid as libc::pid_t,
            libc::RLIMIT_SIGPENDING,
            std::ptr::null(),
            limit.as_mut_ptr(),
        )
    };
    if result != 0 {
        return unavailable_sigpending_limit(diagnostic_reason(&std::io::Error::last_os_error()));
    }
    let limit = unsafe { limit.assume_init() };
    capture::SigpendingLimit {
        status: capture::DiagnosticStatus::Ok as i32,
        soft: Some(rlimit_value(limit.rlim_cur)),
        hard: Some(rlimit_value(limit.rlim_max)),
        unavailable_reason: String::new(),
    }
}

fn rlimit_value(value: libc::rlim_t) -> capture::RlimitValue {
    use capture::rlimit_value::Value;
    capture::RlimitValue {
        value: Some(if value == libc::RLIM_INFINITY {
            Value::Unlimited(true)
        } else {
            #[allow(
                clippy::useless_conversion,
                reason = "rlim_t is not u64 on every target"
            )]
            Value::Limit(u64::from(value))
        }),
    }
}

fn sigq_snapshot(target_pid: u32, deadline: Instant) -> capture::SignalQueue {
    let status_path = format!("/proc/{target_pid}/status");
    let status = match read_proc_status(&status_path, deadline) {
        Ok(status) => status,
        Err(error) => {
            return unavailable_sigq(&diagnostic_reason(&error), None);
        }
    };
    let Some(value) = proc_status_value(&status.text, "SigQ") else {
        let reason = if status.truncated {
            "target status exceeded byte limit before SigQ"
        } else {
            "SigQ field missing from target status"
        };
        return unavailable_sigq(reason, Some(status.truncated));
    };
    let Some((used, limit)) = value.split_once('/') else {
        return unavailable_sigq(
            "invalid SigQ field in target status",
            Some(status.truncated),
        );
    };
    let parsed = used.parse::<u64>().ok().zip(limit.parse::<u64>().ok());
    let Some((used, limit)) = parsed else {
        return unavailable_sigq(
            "non-decimal SigQ field in target status",
            Some(status.truncated),
        );
    };
    capture::SignalQueue {
        status: capture::DiagnosticStatus::Ok as i32,
        used: Some(used),
        limit: Some(limit),
        observed_headroom: Some(limit.saturating_sub(used)),
        over_limit: Some(used > limit),
        unavailable_reason: String::new(),
        status_file_truncated: Some(status.truncated),
    }
}

fn unavailable_sigq(reason: &str, status_file_truncated: Option<bool>) -> capture::SignalQueue {
    capture::SignalQueue {
        status: capture::DiagnosticStatus::Unavailable as i32,
        used: None,
        limit: None,
        observed_headroom: None,
        over_limit: None,
        unavailable_reason: reason.to_string(),
        status_file_truncated,
    }
}

fn thread_mask_audit(
    identity: &PreparedIdentity,
    signal: i32,
    deadline: Instant,
) -> capture::ThreadMaskAudit {
    if Instant::now() >= deadline {
        return unavailable_thread_mask_audit(
            identity,
            signal,
            "signal-environment time budget exhausted before thread audit",
            true,
        );
    }
    let task_path = format!("/proc/{}/task", identity.target_pid);
    let entries = match fs::read_dir(&task_path) {
        Ok(entries) => entries,
        Err(error) => {
            return unavailable_thread_mask_audit(
                identity,
                signal,
                &diagnostic_reason(&error),
                false,
            );
        }
    };
    let mut tids = Vec::with_capacity(MAX_AUDITED_THREADS.min(128));
    let mut unknown_examples = Vec::new();
    let mut unknown = 0_u64;
    let mut enumeration_failures = 0_u64;
    let mut truncated = false;
    let mut timed_out = false;
    for (entries_seen, entry) in entries.enumerate() {
        if Instant::now() >= deadline {
            truncated = true;
            timed_out = true;
            break;
        }
        if entries_seen == MAX_AUDITED_THREADS {
            truncated = true;
            break;
        }
        let entry = match entry {
            Ok(entry) => entry,
            Err(error) => {
                enumeration_failures += 1;
                push_unknown_example(&mut unknown_examples, None, &error);
                continue;
            }
        };
        let Some(tid) = entry
            .file_name()
            .to_str()
            .and_then(|name| name.parse::<u32>().ok())
        else {
            enumeration_failures += 1;
            continue;
        };
        tids.push(tid);
    }
    tids.sort_unstable();
    let mut blocked = 0_u64;
    let mut unblocked = 0_u64;
    let mut blocked_examples = Vec::new();
    let mut status_files_truncated = 0_u64;
    for tid in &tids {
        if Instant::now() >= deadline {
            truncated = true;
            timed_out = true;
            break;
        }
        let path = format!("/proc/{}/task/{tid}/status", identity.target_pid);
        let status = match read_proc_status(&path, deadline) {
            Ok(status) => status,
            Err(error) => {
                unknown += 1;
                push_unknown_example(&mut unknown_examples, Some(*tid), &error);
                continue;
            }
        };
        if status.truncated {
            status_files_truncated += 1;
        }
        let Some(mask) = proc_status_value(&status.text, "SigBlk") else {
            unknown += 1;
            let reason = if status.truncated {
                "status exceeded byte limit before SigBlk"
            } else {
                "SigBlk field missing"
            };
            push_unknown_message(&mut unknown_examples, Some(*tid), reason);
            continue;
        };
        match signal_is_blocked(mask, signal) {
            Ok(true) => {
                blocked += 1;
                if blocked_examples.len() < MAX_TID_EXAMPLES {
                    blocked_examples.push(*tid);
                }
            }
            Ok(false) => unblocked += 1,
            Err(reason) => {
                unknown += 1;
                push_unknown_message(&mut unknown_examples, Some(*tid), &reason);
            }
        }
    }
    let audited = blocked + unblocked + unknown;
    let status = if truncated || enumeration_failures != 0 || unknown != 0 {
        capture::DiagnosticStatus::Partial
    } else {
        capture::DiagnosticStatus::Ok
    };
    capture::ThreadMaskAudit {
        status: status as i32,
        selected_signal: signal,
        target_pid_argument: identity.target_pid,
        target_pid_namespace_inode: identity.pid_namespace_inode,
        max_audited_threads: MAX_AUDITED_THREADS as u32,
        audited_threads: audited,
        blocked_threads: blocked,
        unblocked_threads: unblocked,
        unknown_threads: unknown,
        enumeration_failures,
        enumeration_truncated: truncated,
        time_budget_exceeded: timed_out,
        status_files_truncated,
        unaudited_threads_known_minimum: (!truncated && enumeration_failures == 0).then_some(0),
        blocked_tid_examples: blocked_examples,
        blocked_examples_truncated: blocked as usize > MAX_TID_EXAMPLES,
        unknown_examples,
        unknown_examples_truncated: (unknown + enumeration_failures) as usize > MAX_TID_EXAMPLES,
        unavailable_reason: String::new(),
    }
}

fn unavailable_thread_mask_audit(
    identity: &PreparedIdentity,
    signal: i32,
    reason: &str,
    timed_out: bool,
) -> capture::ThreadMaskAudit {
    capture::ThreadMaskAudit {
        status: capture::DiagnosticStatus::Unavailable as i32,
        selected_signal: signal,
        target_pid_argument: identity.target_pid,
        target_pid_namespace_inode: identity.pid_namespace_inode,
        max_audited_threads: MAX_AUDITED_THREADS as u32,
        time_budget_exceeded: timed_out,
        unaudited_threads_known_minimum: None,
        unavailable_reason: reason.to_string(),
        ..capture::ThreadMaskAudit::default()
    }
}

struct ProcStatus {
    text: String,
    truncated: bool,
}

fn read_proc_status(path: &str, deadline: Instant) -> Result<ProcStatus> {
    if Instant::now() >= deadline {
        anyhow::bail!("signal-environment time budget exhausted before reading {path}");
    }
    let file = File::open(path).with_context(|| format!("open {path}"))?;
    let mut bytes = Vec::with_capacity(MAX_PROC_STATUS_BYTES.min(4096));
    file.take((MAX_PROC_STATUS_BYTES + 1) as u64)
        .read_to_end(&mut bytes)
        .with_context(|| format!("read {path}"))?;
    let truncated = bytes.len() > MAX_PROC_STATUS_BYTES;
    bytes.truncate(MAX_PROC_STATUS_BYTES);
    if truncated {
        let complete_len = bytes
            .iter()
            .rposition(|byte| *byte == b'\n')
            .map_or(0, |position| position + 1);
        bytes.truncate(complete_len);
    }
    let text = String::from_utf8(bytes).with_context(|| format!("decode {path}"))?;
    Ok(ProcStatus { text, truncated })
}

fn proc_status_value<'a>(status: &'a str, field: &str) -> Option<&'a str> {
    status.lines().find_map(|line| {
        line.split_once(':')
            .filter(|(name, _)| *name == field)
            .map(|(_, value)| value.trim())
    })
}

fn signal_is_blocked(mask: &str, signal: i32) -> std::result::Result<bool, String> {
    if signal <= 0 {
        return Err("selected signal is not positive".to_string());
    }
    let mask = mask.trim();
    if mask.is_empty() || !mask.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err("SigBlk is not hexadecimal".to_string());
    }
    let bit = (signal as usize) - 1;
    let nibble_from_right = bit / 4;
    if nibble_from_right >= mask.len() {
        return Ok(false);
    }
    let nibble = mask.as_bytes()[mask.len() - 1 - nibble_from_right];
    let value = (nibble as char)
        .to_digit(16)
        .ok_or_else(|| "SigBlk is not hexadecimal".to_string())?;
    Ok(value & (1 << (bit % 4)) != 0)
}

fn push_unknown_example(
    examples: &mut Vec<capture::UnknownThread>,
    tid: Option<u32>,
    error: &dyn std::fmt::Display,
) {
    push_unknown_message(examples, tid, &diagnostic_reason(error));
}

fn push_unknown_message(
    examples: &mut Vec<capture::UnknownThread>,
    tid: Option<u32>,
    reason: &str,
) {
    if examples.len() < MAX_TID_EXAMPLES {
        examples.push(capture::UnknownThread {
            tid,
            reason: diagnostic_reason(&reason),
        });
    }
}

fn diagnostic_reason(error: &dyn std::fmt::Display) -> String {
    error
        .to_string()
        .chars()
        .take(MAX_DIAGNOSTIC_REASON)
        .collect()
}

#[cfg(test)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PrepareFault {
    Binding,
    FirstAttach,
    SecondAttach,
    AfterBothAttaches,
}

#[cfg(test)]
struct PrepareTestControl {
    fault: PrepareFault,
    events: mpsc::SyncSender<PrepareTestEvent>,
    resume: Receiver<()>,
}

#[cfg(test)]
#[derive(Debug)]
enum PrepareTestEvent {
    Attached {
        stage: PrepareObservedStage,
        program_ids: Vec<u32>,
        link_ids: Vec<u32>,
    },
    DisabledSnapshot {
        enabled: u32,
        next_sequence: u64,
        thread_states: usize,
        stats: KernelStats,
    },
    WorkerExited,
}

#[cfg(test)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PrepareObservedStage {
    StartAttached,
    BothAttached,
}

pub(crate) fn prepare(config: PrepareConfig) -> Result<PreparedCollector> {
    prepare_internal(
        config,
        #[cfg(test)]
        None,
    )
}

fn prepare_internal(
    config: PrepareConfig,
    #[cfg(test)] test_control: Option<PrepareTestControl>,
) -> Result<PreparedCollector> {
    let mut config = config;
    if config.exclude_calling_thread {
        config.calling_tid = current_tid();
    }
    let identity = verify_identity(config.target_pid)?;
    let output_path = config.output_path.clone();
    let handle = allocate_handle()?;
    let (command_tx, command_rx) = mpsc::channel();
    let (ready_tx, ready_rx) = mpsc::sync_channel(1);
    let close_result = Arc::new(Mutex::new(None));
    let worker_close_result = Arc::clone(&close_result);
    #[cfg(test)]
    let test_exit_events = test_control.as_ref().map(|control| control.events.clone());
    let thread_name = format!("jonoffcpu-collector-{handle:016x}");
    let join = thread::Builder::new()
        .name(thread_name)
        .spawn(move || {
            let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                worker(
                    config,
                    identity,
                    command_rx,
                    ready_tx.clone(),
                    Arc::clone(&worker_close_result),
                    #[cfg(test)]
                    test_control,
                )
            }));
            let failure = match outcome {
                Ok(Ok(())) => None,
                Ok(Err(error)) => Some(Failure::of(&error)),
                Err(_) => Some(Failure {
                    code: ErrorCode::InternalError,
                    message: "collector worker panicked".to_string(),
                }),
            };
            if let Some(failure) = failure {
                let _ = ready_tx.send(Err(failure.clone()));
                let mut result = worker_close_result.lock().unwrap();
                if result.is_none() {
                    *result = Some(Err(failure));
                }
            }
            #[cfg(test)]
            if let Some(events) = test_exit_events {
                let _ = events.send(PrepareTestEvent::WorkerExited);
            }
        })
        .context("spawn collector worker")?;

    let prepared_result = ready_rx
        .recv_timeout(Duration::from_secs(10))
        .context("collector prepare timed out")?;
    let prepared = match prepared_result {
        Ok(prepared) => prepared,
        Err(failure) => {
            join.join()
                .map_err(|_| anyhow!("collector worker panicked after prepare failure"))?;
            return Err(failure.into_error());
        }
    };
    registry().lock().unwrap().insert(
        handle,
        RegistryEntry {
            sender: command_tx,
            join: Some(join),
            close_requested: false,
            close_result,
        },
    );
    let reply = reply(
        CollectorState::Prepared,
        capture::collector_reply::Result::Prepared(capture::Prepared {
            handle,
            source_path: path_text(&output_path),
            target_pid: prepared.target_pid,
            host_tgid: prepared.host_tgid,
            sampling: Some(prepared.sampling.message.clone()),
            time_split: Some(prepared.time_split.message),
            verified_identity: Some(prepared.verified_identity()),
        }),
    );
    Ok(PreparedCollector { handle, reply })
}

fn path_text(path: &Path) -> String {
    path.to_string_lossy().into_owned()
}

#[derive(Clone)]
struct PreparedReply {
    target_pid: u32,
    host_tgid: u32,
    sampling: SamplingPolicy,
    time_split: TimeSplitPolicy,
    registration_token: u64,
    pid_namespace_device: u64,
    pid_namespace_inode: u64,
    time_namespace_inode: u64,
    process_generation_ns: u64,
}

impl PreparedReply {
    /// Prepare rejects a different time namespace and any monotonic offset, so the clock is
    /// verified and the offset is zero.
    fn verified_identity(&self) -> capture::VerifiedIdentity {
        capture::VerifiedIdentity {
            registration_token: self.registration_token,
            process_generation_nanos: self.process_generation_ns,
            pid_namespace_device: self.pid_namespace_device,
            pid_namespace_inode: self.pid_namespace_inode,
            time_namespace_inode: self.time_namespace_inode,
            clock_verified: true,
            monotonic_offset_nanos: 0,
        }
    }
}

pub(crate) fn enable(handle: u64, config: EnableConfig) -> Result<CollectorReply> {
    let enabled = request(handle, |sender| {
        let (tx, rx) = mpsc::channel();
        sender
            .send(Command::Enable(config, tx))
            .map_err(|_| anyhow!("collector worker stopped"))?;
        rx.recv_timeout(Duration::from_secs(10))
            .context("collector enable timed out")?
            .map_err(Failure::into_error)
    })?;
    Ok(reply(
        CollectorState::Enabled,
        capture::collector_reply::Result::Enabled(enabled),
    ))
}

pub(crate) fn stop(handle: u64, timeout: Duration) -> Result<CollectorReply> {
    let outcome = request(handle, |sender| {
        let (tx, rx) = mpsc::channel();
        sender
            .send(Command::Stop(timeout, tx))
            .map_err(|_| anyhow!("collector worker stopped"))?;
        rx.recv_timeout(timeout.saturating_add(Duration::from_secs(1)))
            .coded(ErrorCode::StopTimeout, "collector stop timed out")?
            .map_err(Failure::into_error)
    })?;
    Ok(reply(
        outcome.state(),
        capture::collector_reply::Result::Stopped(outcome.stopped),
    ))
}

fn closed_reply(
    handle: u64,
    idempotent: bool,
    stopped: Option<capture::Stopped>,
) -> CollectorReply {
    reply(
        CollectorState::Closed,
        capture::collector_reply::Result::Closed(capture::Closed {
            handle,
            idempotent,
            stopped,
        }),
    )
}

pub(crate) fn close(handle: u64) -> Result<CollectorReply> {
    if closed_handles().lock().unwrap().contains(&handle) {
        return Ok(closed_reply(handle, true, None));
    }
    let (result_slot, deadline) = {
        let mut entries = registry().lock().unwrap();
        let entry = entries
            .get_mut(&handle)
            .ok_or_else(|| fail(ErrorCode::InvalidHandle, "invalid collector handle"))?;
        if !entry.close_requested {
            // A worker failure publishes close_result before exiting. A failed
            // send is therefore still joinable and yields that bounded error.
            let _ = entry.sender.send(Command::Close);
            entry.close_requested = true;
        }
        (
            Arc::clone(&entry.close_result),
            Instant::now() + Duration::from_secs(10),
        )
    };
    let join = loop {
        if result_slot.lock().unwrap().is_some() {
            let mut entries = registry().lock().unwrap();
            if let Some(entry) = entries.get_mut(&handle) {
                if entry.join.as_ref().is_some_and(JoinHandle::is_finished) {
                    if let Some(join) = entry.join.take() {
                        break join;
                    }
                }
            } else if closed_handles().lock().unwrap().contains(&handle) {
                return Ok(closed_reply(handle, true, None));
            }
        }
        if Instant::now() >= deadline {
            return Err(fail(
                ErrorCode::CloseTimeout,
                "close timed out; collector ownership retained",
            ));
        }
        thread::sleep(Duration::from_millis(5));
    };
    let join_result = join
        .join()
        .map_err(|_| anyhow!("collector worker panicked"));
    let outcome = result_slot
        .lock()
        .unwrap()
        .clone()
        .unwrap()
        .map_err(Failure::into_error);
    closed_handles().lock().unwrap().insert(handle);
    registry().lock().unwrap().remove(&handle);
    join_result?;
    Ok(closed_reply(
        handle,
        false,
        outcome?.map(|outcome| outcome.stopped),
    ))
}

fn request<T>(handle: u64, operation: impl FnOnce(Sender<Command>) -> Result<T>) -> Result<T> {
    let sender = registry()
        .lock()
        .unwrap()
        .get(&handle)
        .map(|entry| entry.sender.clone())
        .ok_or_else(|| fail(ErrorCode::InvalidHandle, "invalid collector handle"))?;
    operation(sender)
}

fn allocate_handle() -> Result<u64> {
    loop {
        let handle = NEXT_HANDLE.load(Ordering::Relaxed);
        if handle == 0 || handle == u64::MAX {
            bail!("collector handle space exhausted");
        }
        if NEXT_HANDLE
            .compare_exchange_weak(handle, handle + 1, Ordering::Relaxed, Ordering::Relaxed)
            .is_ok()
        {
            return Ok(handle);
        }
    }
}

fn verify_identity(target_pid: u32) -> Result<PreparedIdentity> {
    fs::metadata(format!("/proc/{target_pid}")).coded(
        ErrorCode::IoError,
        format!("target {target_pid} is not visible"),
    )?;
    let self_time = fs::metadata("/proc/self/ns/time")
        .coded(ErrorCode::IoError, "read collector time namespace")?
        .ino();
    let target_time = fs::metadata(format!("/proc/{target_pid}/ns/time"))
        .coded(ErrorCode::IoError, "read target time namespace")?
        .ino();
    let target_pid_namespace_before = fs::metadata(format!("/proc/{target_pid}/ns/pid"))
        .coded(ErrorCode::IoError, "read target PID namespace")?;
    if self_time != target_time {
        return Err(invalid_config(
            "collector and target use different time namespaces",
        ));
    }
    let monotonic_offset = monotonic_time_namespace_offset().coded(
        ErrorCode::InvalidConfig,
        "verify the collector's monotonic time namespace offset",
    )?;
    if monotonic_offset != 0 {
        return Err(invalid_config(format!(
            "unsupported nonzero monotonic time namespace offset: {monotonic_offset}ns"
        )));
    }
    let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, target_pid, 0) } as i32;
    if fd < 0 {
        return Err(std::io::Error::last_os_error()).context("pidfd_open target group leader");
    }
    let pidfd = unsafe { OwnedFd::from_raw_fd(fd) };
    if pidfd_exited(&pidfd) {
        return Err(fail(
            ErrorCode::TargetExited,
            "target exited while verifying PID namespace",
        ));
    }
    let target_pid_namespace_after = fs::metadata(format!("/proc/{target_pid}/ns/pid"))
        .context("recheck target PID namespace")?;
    if target_pid_namespace_before.dev() != target_pid_namespace_after.dev()
        || target_pid_namespace_before.ino() != target_pid_namespace_after.ino()
    {
        bail!("target PID namespace changed during identity verification");
    }
    Ok(PreparedIdentity {
        target_pid,
        host_tgid: 0,
        pid_namespace_device: target_pid_namespace_after.dev(),
        pid_namespace_inode: target_pid_namespace_after.ino(),
        time_namespace_inode: target_time,
        registration_token: random_nonzero_u64()?,
        process_generation_ns: 0,
        pidfd,
    })
}

fn monotonic_time_namespace_offset() -> Result<i128> {
    let offsets =
        fs::read_to_string("/proc/self/timens_offsets").context("read time namespace offsets")?;
    let line = offsets
        .lines()
        .find(|line| line.split_whitespace().next() == Some("monotonic"))
        .context("time namespace offsets have no monotonic row")?;
    let mut fields = line.split_whitespace();
    fields.next();
    let seconds = fields
        .next()
        .context("monotonic time namespace seconds missing")?
        .parse::<i128>()
        .context("invalid monotonic time namespace seconds")?;
    let nanos = fields
        .next()
        .context("monotonic time namespace nanos missing")?
        .parse::<i128>()
        .context("invalid monotonic time namespace nanos")?;
    if !(-999_999_999..=999_999_999).contains(&nanos) {
        bail!("invalid monotonic time namespace nanosecond offset");
    }
    seconds
        .checked_mul(1_000_000_000)
        .and_then(|value| value.checked_add(nanos))
        .context("monotonic time namespace offset overflow")
}

fn random_nonzero_u64() -> Result<u64> {
    loop {
        let mut value = 0u64;
        let result = unsafe {
            libc::getrandom(
                (&mut value as *mut u64).cast::<libc::c_void>(),
                size_of::<u64>(),
                0,
            )
        };
        if result < 0 {
            return Err(std::io::Error::last_os_error()).context("getrandom registration token");
        }
        if result as usize == size_of::<u64>() && value != 0 {
            return Ok(value);
        }
    }
}

fn target_binding_bytes(token: u64) -> [u8; 24] {
    let mut value = [0u8; 24];
    value[..8].copy_from_slice(&token.to_ne_bytes());
    value
}

fn worker(
    config: PrepareConfig,
    mut identity: PreparedIdentity,
    commands: Receiver<Command>,
    ready: mpsc::SyncSender<Reply<PreparedReply>>,
    close_result: Arc<Mutex<Option<Reply<Option<StopOutcome>>>>>,
    #[cfg(test)] test_control: Option<PrepareTestControl>,
) -> Result<()> {
    let file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&config.output_path)
        .coded(
            ErrorCode::IoError,
            format!("create source artifact {}", config.output_path.display()),
        )?;
    // Rows with symbolized stacks run to a few kilobytes; a large buffer keeps a drain batch to a
    // handful of write syscalls instead of one per row.
    let mut writer = BufWriter::with_capacity(OUTPUT_BUFFER_BYTES, file);
    writer
        .write_all(&capture::header())
        .coded(ErrorCode::IoError, "write source artifact header")?;

    // No silent fallback: a kernel without the accounting needs an explicit `off`.
    if config.time_split.source == TimeSplitSource::SchedInfo && !run_delay_available()? {
        return Err(fail(
            ErrorCode::BpfUnsupported,
            "timeSplit.source SCHED_INFO needs task_struct.sched_info.run_delay (CONFIG_SCHED_INFO), \
             which this kernel lacks; set timeSplit.source to OFF",
        ));
    }
    let mut object = MaybeUninit::uninit();
    let open = JonoffcpuCookieSkelBuilder::default()
        .open(&mut object)
        .coded(
            ErrorCode::BpfUnsupported,
            "open scheduler-exit BPF skeleton",
        )?;
    let mut skel = open.load().coded(
        ErrorCode::BpfUnsupported,
        "load scheduler-exit BPF object (requires tp_btf/sched_exit_tp and the four-argument \
         tp_btf/sched_switch of Linux 5.18 or newer)",
    )?;
    {
        let bss = skel
            .maps
            .bss_data
            .as_deref_mut()
            .context("missing BPF bss")?;
        // The exact host TGID is learned through the pidfd-bound task storage
        // value. Until then, no numeric prefilter is used.
        bss.target_tgid = 0;
        bss.target_pid_namespace_device = identity.pid_namespace_device;
        bss.target_pid_namespace_inode = identity.pid_namespace_inode;
        bss.target_namespace_tgid = identity.target_pid;
        bss.enabled = 0;
        bss.next_sequence = 1;
    }
    #[cfg(test)]
    let binding_key = if test_control
        .as_ref()
        .is_some_and(|control| control.fault == PrepareFault::Binding)
    {
        (-1_i32).to_ne_bytes()
    } else {
        identity.pidfd.as_raw_fd().to_ne_bytes()
    };
    #[cfg(not(test))]
    let binding_key = identity.pidfd.as_raw_fd().to_ne_bytes();
    skel.maps
        .target_tasks
        .update(
            &binding_key,
            &target_binding_bytes(identity.registration_token),
            MapFlags::ANY,
        )
        .coded(ErrorCode::BpfUnsupported, "seed exact target task storage")?;

    // The injected failure attaches the BTF program as a classic tracepoint of a name that does not
    // exist, so the kernel itself refuses it and the unwind path is the real one.
    #[cfg(test)]
    let switch_out = if test_control
        .as_ref()
        .is_some_and(|control| control.fault == PrepareFault::FirstAttach)
    {
        skel.progs.record_switch_out.attach_tracepoint(
            TracepointCategory::Sched,
            "jonoffcpu_intentionally_missing_sched_switch",
        )
    } else {
        skel.progs.record_switch_out.attach()
    }
    .coded(ErrorCode::BpfUnsupported, "attach sched_switch recorder")?;
    #[cfg(not(test))]
    let switch_out = skel
        .progs
        .record_switch_out
        .attach()
        .coded(ErrorCode::BpfUnsupported, "attach sched_switch recorder")?;

    #[cfg(test)]
    if test_control
        .as_ref()
        .is_some_and(|control| control.fault == PrepareFault::SecondAttach)
    {
        observe_prepare_pause(
            test_control.as_ref().unwrap(),
            PrepareObservedStage::StartAttached,
            &skel,
            Some(&switch_out),
            None,
        )?;
        return Err(std::io::Error::from_raw_os_error(libc::EPERM))
            .context("injected END attach failure");
    }
    let switch_in = skel.progs.capture_switch_in.attach().coded(
        ErrorCode::BpfUnsupported,
        "attach sched_exit_tp completion hook",
    )?;

    #[cfg(test)]
    if test_control
        .as_ref()
        .is_some_and(|control| control.fault == PrepareFault::AfterBothAttaches)
    {
        observe_prepare_pause(
            test_control.as_ref().unwrap(),
            PrepareObservedStage::BothAttached,
            &skel,
            Some(&switch_out),
            Some(&switch_in),
        )?;
        bail!("injected setup failure after both attaches");
    }
    (identity.host_tgid, identity.process_generation_ns) =
        wait_for_kernel_identity(&skel.maps.target_tasks, &identity.pidfd)?;
    skel.maps.bss_data.as_deref_mut().unwrap().target_tgid = identity.host_tgid;

    let pending = Arc::new(Mutex::new(Vec::<Observation>::new()));
    let callback_pending = Arc::clone(&pending);
    let mut ring_builder = RingBufferBuilder::new();
    ring_builder
        .add(&skel.maps.observations, move |data| {
            if data.len() == size_of::<Observation>() {
                let event =
                    unsafe { std::ptr::read_unaligned(data.as_ptr().cast::<Observation>()) };
                callback_pending.lock().unwrap().push(event);
            }
            0
        })
        .coded(
            ErrorCode::BpfUnsupported,
            "register observation ring callback",
        )?;
    let ring = ring_builder
        .build()
        .coded(ErrorCode::BpfUnsupported, "build observation ring")?;

    let reply = PreparedReply {
        target_pid: identity.target_pid,
        host_tgid: identity.host_tgid,
        sampling: config.sampling.clone(),
        time_split: config.time_split.clone(),
        registration_token: identity.registration_token,
        pid_namespace_device: identity.pid_namespace_device,
        pid_namespace_inode: identity.pid_namespace_inode,
        time_namespace_inode: identity.time_namespace_inode,
        process_generation_ns: identity.process_generation_ns,
    };

    ready
        .send(Ok(reply.clone()))
        .map_err(|_| anyhow!("prepare caller disconnected"))?;
    worker_loop(
        config,
        identity,
        commands,
        &mut skel,
        ring,
        pending,
        &mut writer,
        Some(switch_out),
        Some(switch_in),
        reply.clone(),
        close_result,
    )?;
    Ok(())
}

#[cfg(test)]
fn observe_prepare_pause(
    control: &PrepareTestControl,
    stage: PrepareObservedStage,
    skel: &crate::bpf_sched_exit::JonoffcpuCookieSkel<'_>,
    switch_out: Option<&Link>,
    switch_in: Option<&Link>,
) -> Result<()> {
    let mut program_ids = Vec::new();
    let mut link_ids = Vec::new();
    if let Some(link) = switch_out {
        program_ids.push(program_id(
            skel.progs.record_switch_out.as_fd().as_raw_fd(),
        )?);
        link_ids.push(link.info()?.id);
    }
    if let Some(link) = switch_in {
        program_ids.push(program_id(
            skel.progs.capture_switch_in.as_fd().as_raw_fd(),
        )?);
        link_ids.push(link.info()?.id);
    }
    control
        .events
        .send(PrepareTestEvent::Attached {
            stage,
            program_ids,
            link_ids,
        })
        .map_err(|_| anyhow!("prepare test observer disconnected"))?;
    control
        .resume
        .recv_timeout(Duration::from_secs(5))
        .context("prepare test pause timed out")?;
    let thread_states = skel.maps.thread_states.keys().count();
    control
        .events
        .send(PrepareTestEvent::DisabledSnapshot {
            enabled: skel.maps.bss_data.as_deref().unwrap().enabled,
            next_sequence: skel.maps.bss_data.as_deref().unwrap().next_sequence,
            thread_states,
            stats: read_stats(&skel.maps.stats)?,
        })
        .map_err(|_| anyhow!("prepare test observer disconnected"))?;
    Ok(())
}

#[cfg(test)]
fn program_id(fd: i32) -> Result<u32> {
    let mut info: libbpf_rs::libbpf_sys::bpf_prog_info = unsafe { std::mem::zeroed() };
    let mut size = size_of::<libbpf_rs::libbpf_sys::bpf_prog_info>() as u32;
    let result = unsafe {
        libbpf_rs::libbpf_sys::bpf_obj_get_info_by_fd(
            fd,
            (&mut info as *mut libbpf_rs::libbpf_sys::bpf_prog_info).cast(),
            &mut size,
        )
    };
    if result != 0 {
        return Err(std::io::Error::last_os_error()).context("read test-owned BPF program ID");
    }
    Ok(info.id)
}

fn wait_for_kernel_identity(map: &impl MapCore, pidfd: &OwnedFd) -> Result<(u32, u64)> {
    let deadline = Instant::now() + Duration::from_secs(2);
    while Instant::now() < deadline {
        if pidfd_exited(pidfd) {
            return Err(fail(
                ErrorCode::TargetExited,
                "target exited before kernel identity discovery",
            ));
        }
        if let Some(value) = map.lookup(&pidfd.as_raw_fd().to_ne_bytes(), MapFlags::ANY)? {
            if value.len() == 24 {
                let host_tgid = u64::from_ne_bytes(value[16..24].try_into().unwrap());
                let process_generation = u64::from_ne_bytes(value[8..16].try_into().unwrap());
                if host_tgid != 0 && process_generation != 0 {
                    return Ok((
                        u32::try_from(host_tgid).context("kernel host TGID exceeds u32")?,
                        process_generation,
                    ));
                }
            }
        }
        thread::sleep(Duration::from_millis(1));
    }
    bail!("target did not reach a scheduler hook for exact kernel identity discovery")
}

#[allow(clippy::too_many_arguments)]
fn worker_loop(
    config: PrepareConfig,
    identity: PreparedIdentity,
    commands: Receiver<Command>,
    skel: &mut crate::bpf_sched_exit::JonoffcpuCookieSkel<'_>,
    ring: libbpf_rs::RingBuffer<'_>,
    pending: Arc<Mutex<Vec<Observation>>>,
    writer: &mut BufWriter<File>,
    mut switch_out: Option<Link>,
    mut switch_in: Option<Link>,
    reply: PreparedReply,
    close_result: Arc<Mutex<Option<Reply<Option<StopOutcome>>>>>,
) -> Result<()> {
    let mut capture: Option<CaptureState> = None;
    let mut stopped = false;
    let mut terminal_stop: Option<StopOutcome> = None;
    let mut terminal_publication = TerminalPublication::default();
    let mut automatic_stop_attempted = false;
    loop {
        if capture.is_some() {
            if ring.consume_raw_n(MAX_RING_BATCH) < 0 {
                capture.as_mut().unwrap().userspace.poll_failures += 1;
            }
            drain_events(skel, &pending, writer, capture.as_mut().unwrap());
            if !automatic_stop_attempted && pidfd_exited(&identity.pidfd) {
                // A failed automatic finalization waits for an explicit
                // command. In particular, a bounded drain failure must not
                // start another five-second attempt on every poll cycle.
                automatic_stop_attempted = true;
                let result = stop_capture(
                    &config.output_path,
                    &identity,
                    skel,
                    &ring,
                    &pending,
                    writer,
                    &mut capture,
                    &mut terminal_publication,
                    TARGET_EXIT_DRAIN_TIMEOUT,
                    &mut switch_out,
                    &mut switch_in,
                    true,
                    true,
                );
                if terminal_publication.started() {
                    // Terminal freeze retires this one-shot handle even when
                    // append/flush/fsync has not yet become durable.
                    stopped = true;
                }
                if let Ok(value) = result {
                    terminal_stop = Some(value);
                }
            }
        }
        let command = if capture.is_some() {
            match commands.recv_timeout(Duration::from_millis(5)) {
                Ok(command) => command,
                Err(mpsc::RecvTimeoutError::Timeout) => continue,
                Err(mpsc::RecvTimeoutError::Disconnected) => break,
            }
        } else {
            match commands.recv() {
                Ok(command) => command,
                Err(_) => break,
            }
        };
        match command {
            Command::Enable(enable, response) => {
                let result = if stopped || terminal_publication.started() {
                    Err(invalid_state("collector capture already stopped"))
                } else {
                    enable_capture(
                        &config,
                        &identity,
                        &reply,
                        enable,
                        skel,
                        writer,
                        &mut capture,
                    )
                };
                let _ = response.send(result.map_err(|error| Failure::of(&error)));
            }
            Command::Stop(timeout, response) => {
                let result = if let Some(value) = &terminal_stop {
                    Ok(value.clone())
                } else {
                    stop_capture(
                        &config.output_path,
                        &identity,
                        skel,
                        &ring,
                        &pending,
                        writer,
                        &mut capture,
                        &mut terminal_publication,
                        timeout,
                        &mut switch_out,
                        &mut switch_in,
                        pidfd_exited(&identity.pidfd),
                        false,
                    )
                };
                if terminal_publication.started() {
                    stopped = true;
                }
                if result.is_ok() {
                    terminal_stop = result.as_ref().ok().cloned();
                }
                let _ = response.send(result.map_err(|error| Failure::of(&error)));
            }
            Command::Close => {
                let result = if let Some(value) = &terminal_stop {
                    Ok(Some(value.clone()))
                } else if capture.is_some() || terminal_publication.started() {
                    stop_capture(
                        &config.output_path,
                        &identity,
                        skel,
                        &ring,
                        &pending,
                        writer,
                        &mut capture,
                        &mut terminal_publication,
                        Duration::from_secs(5),
                        &mut switch_out,
                        &mut switch_in,
                        pidfd_exited(&identity.pidfd),
                        false,
                    )
                    .map(Some)
                } else {
                    Ok(None)
                };
                // Explicit drop order: disable/detach happens in stop; the
                // links still remain valid during a prepared-only close.
                skel.maps.bss_data.as_deref_mut().unwrap().enabled = 0;
                switch_out.take();
                switch_in.take();
                *close_result.lock().unwrap() = Some(result.map_err(|error| Failure::of(&error)));
                break;
            }
        }
    }
    Ok(())
}

struct CaptureState {
    config: EnableConfig,
    started_ns: u64,
    userspace: UserStats,
    kernel_symbols: KernelSymbols,
    user_maps: Vec<UserMap>,
    /// Stack ids already written as their own record. The BPF stack map returns one id per distinct
    /// stack and is never cleared during a capture, so an id identifies the same frames throughout.
    emitted_stacks: HashSet<i64>,
    post_detach_signal_environment: Option<capture::SignalEnvironment>,
    end_wall_clock_calibration: Option<capture::WallClockCalibration>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
enum TerminalPublicationStage {
    #[default]
    Open,
    AppendStarted,
    RowWritten,
    Flushed,
    Durable,
    Failed,
}

#[derive(Default)]
struct TerminalPublication {
    stage: TerminalPublicationStage,
    /// The frozen stop result; its `captureEnd` is the record appended, once.
    outcome: Option<StopOutcome>,
    automatic: bool,
    append_attempts: u32,
    flush_attempts: u32,
    sync_attempts: u32,
    first_error: Option<String>,
}

impl TerminalPublication {
    fn started(&self) -> bool {
        self.stage != TerminalPublicationStage::Open
    }

    fn begin(&mut self, outcome: StopOutcome, automatic: bool) -> Result<()> {
        if self.started() {
            bail!("terminal source publication already started");
        }
        self.outcome = Some(outcome);
        self.automatic = automatic;
        self.stage = TerminalPublicationStage::AppendStarted;
        Ok(())
    }

    fn capture_end(&self) -> &capture::CaptureEnd {
        self.outcome
            .as_ref()
            .and_then(|outcome| outcome.stopped.capture_end.as_ref())
            .expect("a started terminal publication holds its captureEnd")
    }

    fn advance(&mut self, sink: &mut impl TerminalSink) -> Result<StopOutcome> {
        loop {
            match self.stage {
                TerminalPublicationStage::Open => bail!("terminal source publication not started"),
                TerminalPublicationStage::AppendStarted => {
                    self.append_attempts += 1;
                    let end = self.capture_end().clone();
                    if let Err(error) = sink.append_terminal(&end) {
                        self.remember_error("append", &error);
                        self.stage = TerminalPublicationStage::Failed;
                        return Err(self.stable_error());
                    }
                    self.stage = TerminalPublicationStage::RowWritten;
                }
                TerminalPublicationStage::RowWritten => {
                    self.flush_attempts += 1;
                    if let Err(error) = sink.flush_terminal() {
                        self.remember_error("flush", &error);
                        return Err(self.stable_error());
                    }
                    self.stage = TerminalPublicationStage::Flushed;
                }
                TerminalPublicationStage::Flushed => {
                    self.sync_attempts += 1;
                    if let Err(error) = sink.sync_terminal() {
                        self.remember_error("sync", &error);
                        return Err(self.stable_error());
                    }
                    self.stage = TerminalPublicationStage::Durable;
                }
                TerminalPublicationStage::Durable => return Ok(self.durable_outcome()),
                TerminalPublicationStage::Failed => return Err(self.stable_error()),
            }
        }
    }

    fn remember_error(&mut self, operation: &str, error: &anyhow::Error) {
        if self.first_error.is_none() {
            let end = self.capture_end();
            let state = end.state().as_str_name();
            let reason = end.incomplete_reason().as_str_name();
            self.first_error = Some(format!(
                "terminal source {operation} failed after frozen captureEnd \
                 (capture state={state}, incompleteReason={reason}); \
                 append will not be retried: {error:#}"
            ));
        }
    }

    /// The first failure, repeated unchanged by every retry that cannot make progress.
    fn stable_error(&self) -> anyhow::Error {
        fail(
            ErrorCode::IoError,
            self.first_error
                .as_deref()
                .unwrap_or("terminal source publication failed"),
        )
    }

    fn durable_outcome(&self) -> StopOutcome {
        let mut outcome = self.outcome.clone().unwrap();
        outcome.stopped.terminal_publication = Some(capture::TerminalPublication {
            automatic: self.automatic,
            append_attempts: self.append_attempts,
            flush_attempts: self.flush_attempts,
            sync_attempts: self.sync_attempts,
            first_error: self.first_error.clone().unwrap_or_default(),
        });
        outcome
    }
}

trait TerminalSink {
    fn append_terminal(&mut self, end: &capture::CaptureEnd) -> Result<()>;
    fn flush_terminal(&mut self) -> Result<()>;
    fn sync_terminal(&mut self) -> Result<()>;
}

impl TerminalSink for BufWriter<File> {
    fn append_terminal(&mut self, end: &capture::CaptureEnd) -> Result<()> {
        write_record(
            self,
            &capture::Record {
                record: Some(capture::record::Record::CaptureEnd(end.clone())),
            },
        )
        .context("append captureEnd")
    }

    fn flush_terminal(&mut self) -> Result<()> {
        Write::flush(self).context("flush captureEnd")
    }

    fn sync_terminal(&mut self) -> Result<()> {
        self.get_ref().sync_all().context("fsync source artifact")
    }
}

#[derive(Clone, Copy, Debug)]
struct ClockBracket {
    monotonic_before_ns: u64,
    realtime_ns: u64,
    monotonic_after_ns: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct WallClockCalibration {
    sample_count: usize,
    selected_sample_index: usize,
    monotonic_before_ns: u64,
    realtime_ns: u64,
    monotonic_after_ns: u64,
    monotonic_midpoint_ns: u64,
    realtime_minus_monotonic_ns: i128,
    bracket_width_ns: u64,
    midpoint_uncertainty_ns: u64,
}

impl WallClockCalibration {
    /// The record's form. The realtime-minus-monotonic difference of two u64 clocks can in principle
    /// exceed the sint64 field, so it is converted with a check instead of truncated.
    fn message(&self) -> Result<capture::WallClockCalibration> {
        Ok(capture::WallClockCalibration {
            sample_count: u32::try_from(self.sample_count)
                .context("wall-clock calibration sample count exceeds u32")?,
            selected_sample_index: u32::try_from(self.selected_sample_index)
                .context("wall-clock calibration sample index exceeds u32")?,
            monotonic_before_nanos: self.monotonic_before_ns,
            realtime_nanos: self.realtime_ns,
            monotonic_after_nanos: self.monotonic_after_ns,
            monotonic_midpoint_nanos: self.monotonic_midpoint_ns,
            realtime_minus_monotonic_nanos: i64::try_from(self.realtime_minus_monotonic_ns)
                .context("wall-clock realtime minus monotonic offset exceeds sint64")?,
            bracket_width_nanos: self.bracket_width_ns,
            midpoint_uncertainty_nanos: self.midpoint_uncertainty_ns,
        })
    }
}

fn select_wall_clock_calibration(samples: &[ClockBracket]) -> Result<WallClockCalibration> {
    let first = samples
        .first()
        .context("wall-clock calibration has no samples")?;
    let mut selected_index = 0;
    let mut selected_width = first
        .monotonic_after_ns
        .checked_sub(first.monotonic_before_ns)
        .context("wall-clock calibration monotonic bracket reversed")?;
    for (index, sample) in samples.iter().enumerate().skip(1) {
        let width = sample
            .monotonic_after_ns
            .checked_sub(sample.monotonic_before_ns)
            .context("wall-clock calibration monotonic bracket reversed")?;
        if width < selected_width {
            selected_index = index;
            selected_width = width;
        }
    }
    let selected = samples[selected_index];
    let midpoint = selected.monotonic_before_ns + selected_width / 2;
    Ok(WallClockCalibration {
        sample_count: samples.len(),
        selected_sample_index: selected_index,
        monotonic_before_ns: selected.monotonic_before_ns,
        realtime_ns: selected.realtime_ns,
        monotonic_after_ns: selected.monotonic_after_ns,
        monotonic_midpoint_ns: midpoint,
        realtime_minus_monotonic_ns: i128::from(selected.realtime_ns) - i128::from(midpoint),
        bracket_width_ns: selected_width,
        midpoint_uncertainty_ns: selected_width / 2 + selected_width % 2,
    })
}

fn capture_wall_clock_calibration() -> Result<WallClockCalibration> {
    let mut samples = Vec::with_capacity(WALL_CLOCK_CALIBRATION_SAMPLES);
    for _ in 0..WALL_CLOCK_CALIBRATION_SAMPLES {
        samples.push(ClockBracket {
            monotonic_before_ns: clock_ns(libc::CLOCK_MONOTONIC, "CLOCK_MONOTONIC")?,
            realtime_ns: clock_ns(libc::CLOCK_REALTIME, "CLOCK_REALTIME")?,
            monotonic_after_ns: clock_ns(libc::CLOCK_MONOTONIC, "CLOCK_MONOTONIC")?,
        });
    }
    select_wall_clock_calibration(&samples)
}

fn enable_capture(
    prepare: &PrepareConfig,
    identity: &PreparedIdentity,
    reply: &PreparedReply,
    enable: EnableConfig,
    skel: &mut crate::bpf_sched_exit::JonoffcpuCookieSkel<'_>,
    writer: &mut BufWriter<File>,
    capture: &mut Option<CaptureState>,
) -> Result<capture::Enabled> {
    if capture.is_some() {
        return Err(invalid_state("collector is already enabled"));
    }
    if enable.sampling != prepare.sampling.message {
        return Err(invalid_config("sampling differs from prepared policy"));
    }
    if enable.time_split != prepare.time_split.message {
        return Err(invalid_config(
            "timeSplit differs from prepared configuration",
        ));
    }
    reset_stats(&skel.maps.stats)?;
    let wall_clock_calibration = capture_wall_clock_calibration()?.message()?;
    let started_ns = monotonic_ns()?;
    let signal_environment = signal_environment_snapshot(
        identity,
        enable.signal,
        capture::SnapshotPhase::BeforeEnable,
        None,
    );
    {
        let bss = skel
            .maps
            .bss_data
            .as_deref_mut()
            .context("missing BPF bss")?;
        bss.signal_number = enable.signal as u32;
        bss.capture_epoch = enable.capture_epoch;
        // enable_capture runs on the worker thread that drains the ring buffer and writes rows.
        bss.collector_tid = current_tid();
        bss.agent_tid = prepare.calling_tid;
        bss.min_off_cpu_ns = prepare.sampling.min_off_cpu_ns();
        bss.max_off_cpu_ns = prepare.sampling.max_off_cpu_ns();
        bss.has_min_off_cpu = u32::from(prepare.sampling.min_off_cpu_micros.is_some());
        bss.has_max_off_cpu = u32::from(prepare.sampling.max_off_cpu_micros.is_some());
        bss.reason_mask = prepare.sampling.reason_mask();
        bss.time_split_source = prepare.time_split.kernel_value();
        match prepare.sampling.admission {
            Admission::Uniform {
                probability_threshold,
            } => {
                bss.admission_policy = 0;
                bss.sample_threshold = probability_threshold;
                bss.record_all_above_ns = 0;
                bss.record_all_above_scaled = 0;
                bss.record_all_above_shift = 0;
            }
            Admission::Proportional {
                record_all_above_micros,
            } => {
                let record_all_above_ns = record_all_above_micros
                    .checked_mul(1_000)
                    .ok_or_else(|| invalid_config("recordAllAboveMicros overflows nanos"))?;
                let (scaled, shift) = proportional_scale(record_all_above_ns);
                bss.admission_policy = 1;
                bss.sample_threshold = 0;
                bss.record_all_above_ns = record_all_above_ns;
                bss.record_all_above_scaled = scaled;
                bss.record_all_above_shift = shift;
            }
        }
        bss.next_sequence = 1;
    }
    let verified_identity = reply.verified_identity();
    let start = capture::CaptureStart {
        source_id: SOURCE_ID.to_string(),
        session_id: enable.session_id.clone(),
        capture_epoch: enable.capture_epoch,
        signal: enable.signal,
        signal_delivery: enable.signal_delivery as i32,
        host_tgid: identity.host_tgid,
        target_pid: identity.target_pid,
        verified_identity: Some(verified_identity),
        started_monotonic_nanos: started_ns,
        sampling: Some(prepare.sampling.message.clone()),
        time_split: Some(prepare.time_split.message),
        loader: LOADER.to_string(),
        hook: HOOK.to_string(),
        switch_out_hook: SWITCH_OUT_HOOK.to_string(),
        wall_clock_calibration: Some(wall_clock_calibration),
        signal_environment: Some(signal_environment.clone()),
    };
    write_record(
        writer,
        &capture::Record {
            record: Some(capture::record::Record::CaptureStart(start)),
        },
    )
    .coded(ErrorCode::IoError, "append captureStart")?;
    writer
        .flush()
        .coded(ErrorCode::IoError, "flush captureStart")?;
    let enabled = capture::Enabled {
        session_id: enable.session_id.clone(),
        capture_epoch: enable.capture_epoch,
        signal: enable.signal,
        signal_delivery: enable.signal_delivery as i32,
        source_path: path_text(&prepare.output_path),
        target_pid: reply.target_pid,
        host_tgid: reply.host_tgid,
        sampling: Some(reply.sampling.message.clone()),
        time_split: Some(reply.time_split.message),
        verified_identity: Some(reply.verified_identity()),
        signal_environment: Some(signal_environment),
    };
    *capture = Some(CaptureState {
        config: enable,
        started_ns,
        userspace: UserStats::default(),
        kernel_symbols: KernelSymbols::load(),
        emitted_stacks: HashSet::new(),
        user_maps: UserMap::load(reply.target_pid),
        post_detach_signal_environment: None,
        end_wall_clock_calibration: None,
    });
    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 1;
    Ok(enabled)
}

#[allow(clippy::too_many_arguments)]
fn stop_capture(
    output_path: &Path,
    identity: &PreparedIdentity,
    skel: &mut crate::bpf_sched_exit::JonoffcpuCookieSkel<'_>,
    ring: &libbpf_rs::RingBuffer<'_>,
    pending: &Arc<Mutex<Vec<Observation>>>,
    writer: &mut BufWriter<File>,
    capture: &mut Option<CaptureState>,
    terminal_publication: &mut TerminalPublication,
    timeout: Duration,
    switch_out: &mut Option<Link>,
    switch_in: &mut Option<Link>,
    target_exited: bool,
    automatic: bool,
) -> Result<StopOutcome> {
    // Once terminal publication starts, the captureEnd record is frozen and
    // the capture is no longer active. A retry may advance flush/fsync only;
    // it must never build or append another terminal record.
    if terminal_publication.started() {
        return terminal_publication.advance(writer);
    }
    let state = capture
        .as_mut()
        .ok_or_else(|| invalid_state("collector is not enabled"))?;
    let deadline = Instant::now() + timeout;
    let wall_clock_calibration = match &state.end_wall_clock_calibration {
        Some(calibration) => *calibration,
        None => {
            let calibration = capture_wall_clock_calibration()?.message()?;
            state.end_wall_clock_calibration = Some(calibration);
            calibration
        }
    };
    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 0;
    let stopped_ns = monotonic_ns()?;
    switch_in.take();
    switch_out.take();
    let detached_ns = monotonic_ns()?;
    let signal_environment = state
        .post_detach_signal_environment
        .get_or_insert_with(|| {
            signal_environment_snapshot(
                identity,
                state.config.signal,
                capture::SnapshotPhase::AfterDetach,
                Some(deadline),
            )
        })
        .clone();
    // The gate is closed and both links are detached. Consume until the ring is
    // quiet, bounded by the caller timeout.
    let mut quiet = 0;
    while quiet < DRAIN_QUIET_POLLS {
        if Instant::now() >= deadline {
            state.userspace.drain_timed_out += 1;
            return Err(fail(
                ErrorCode::StopTimeout,
                "stop timed out draining the observation ring",
            ));
        }
        let consumed = ring.consume_raw_n(MAX_RING_BATCH);
        if consumed < 0 {
            state.userspace.poll_failures += 1;
            bail!("ring drain failed with {consumed}");
        }
        drain_events(skel, pending, writer, state);
        if consumed == 0 {
            quiet += 1;
            thread::yield_now();
        } else {
            quiet = 0;
        }
    }
    let kernel = read_stats(&skel.maps.stats)?;
    let drain_completed_ns = monotonic_ns()?;
    let complete = state.userspace.write_failures == 0
        && state.userspace.poll_failures == 0
        && kernel.ring_reserve_failures == 0
        && kernel.target_namespace_failures == 0
        && !target_exited;
    let end = capture_end(
        state,
        &kernel,
        stopped_ns,
        detached_ns,
        drain_completed_ns,
        target_exited,
        complete,
        signal_environment,
        wall_clock_calibration,
    );
    let outcome = StopOutcome {
        complete,
        stopped: capture::Stopped {
            source_path: path_text(output_path),
            capture_end: Some(end),
            terminal_publication: None,
        },
    };
    terminal_publication.begin(outcome, automatic)?;
    // Retire the active capture before the first append attempt. Even a
    // partial/ambiguous write failure can therefore never re-enter capture
    // finalization or append a second captureEnd.
    *capture = None;
    terminal_publication.advance(writer)
}

fn pidfd_exited(pidfd: &OwnedFd) -> bool {
    let mut descriptor = libc::pollfd {
        fd: pidfd.as_raw_fd(),
        events: libc::POLLIN,
        revents: 0,
    };
    (unsafe { libc::poll(&mut descriptor, 1, 0) }) == 1 && descriptor.revents & libc::POLLIN != 0
}

#[allow(
    clippy::too_many_arguments,
    reason = "capture-end fields map directly to the persisted terminal record"
)]
fn capture_end(
    capture: &CaptureState,
    kernel: &KernelStats,
    stopped_ns: u64,
    detached_ns: u64,
    drain_completed_ns: u64,
    target_exited: bool,
    complete: bool,
    signal_environment: capture::SignalEnvironment,
    wall_clock_calibration: capture::WallClockCalibration,
) -> capture::CaptureEnd {
    use capture::IncompleteReason;
    let incomplete_reason = if complete {
        IncompleteReason::Unspecified
    } else if target_exited {
        IncompleteReason::TargetExited
    } else if capture.userspace.write_failures != 0 {
        IncompleteReason::SourceWriteFailure
    } else if kernel.ring_reserve_failures != 0 {
        IncompleteReason::KernelRingOverflow
    } else if kernel.target_namespace_failures != 0 {
        IncompleteReason::TargetNamespaceMappingFailure
    } else {
        IncompleteReason::RingPollFailure
    };
    let state = if complete {
        capture::CaptureState::Complete
    } else {
        capture::CaptureState::Incomplete
    };
    capture::CaptureEnd {
        session_id: capture.config.session_id.clone(),
        capture_epoch: capture.config.capture_epoch,
        state: state as i32,
        incomplete_reason: incomplete_reason as i32,
        started_monotonic_nanos: capture.started_ns,
        stopped_monotonic_nanos: stopped_ns,
        detached_monotonic_nanos: detached_ns,
        drain_completed_monotonic_nanos: drain_completed_ns,
        drain_timed_out: false,
        target_exited,
        wall_clock_calibration: Some(wall_clock_calibration),
        signal_environment: Some(signal_environment),
        kernel_counters: Some(kernel.message()),
        userspace_counters: Some(capture.userspace.message()),
    }
}

fn drain_events(
    skel: &crate::bpf_sched_exit::JonoffcpuCookieSkel<'_>,
    pending: &Arc<Mutex<Vec<Observation>>>,
    writer: &mut BufWriter<File>,
    capture: &mut CaptureState,
) {
    let events = std::mem::take(&mut *pending.lock().unwrap());
    for event in events {
        capture.userspace.received_observations += 1;
        // Each distinct stack is symbolized and written once; observations then reference it by id.
        let kernel_error = emit_stack(skel, capture, writer, event.kernel_stack_id, true);
        let user_error = emit_stack(skel, capture, writer, event.user_stack_id, false);
        if kernel_error.is_some() || user_error.is_some() {
            capture.userspace.symbolization_failures += 1;
        }
        let row = observation_record(&event, kernel_error, user_error);
        match write_record(writer, &row) {
            Ok(()) => capture.userspace.written_observations += 1,
            Err(_) => capture.userspace.write_failures += 1,
        }
    }
    if writer.flush().is_err() {
        capture.userspace.write_failures += 1;
    }
}

/// What an observation's stack id needs before the observation can reference it.
#[derive(Debug, PartialEq, Eq)]
enum StackAction {
    /// The id was announced by an earlier `stack` record.
    AlreadyEmitted,
    /// The first sighting: materialize and write the record before the observation.
    NeedsRecord,
    /// The kernel could not take the stack, so there is no record and the observation carries this.
    Error(String),
}

fn stack_action(stack_id: i64, emitted: &HashSet<i64>) -> StackAction {
    if stack_id < 0 {
        StackAction::Error(format!("bpf_stack_error_{stack_id}"))
    } else if emitted.contains(&stack_id) {
        StackAction::AlreadyEmitted
    } else {
        StackAction::NeedsRecord
    }
}

/// Writes the `stack` record for an id the capture has not seen yet. Returns the error code when the
/// stack could not be materialized, in which case no record exists and the observation carries it.
fn emit_stack(
    skel: &crate::bpf_sched_exit::JonoffcpuCookieSkel<'_>,
    capture: &mut CaptureState,
    writer: &mut BufWriter<File>,
    stack_id: i64,
    kernel: bool,
) -> Option<String> {
    match stack_action(stack_id, &capture.emitted_stacks) {
        StackAction::AlreadyEmitted => return None,
        StackAction::Error(error) => return Some(error),
        StackAction::NeedsRecord => {}
    }
    let frames = if kernel {
        materialize_frames(&skel.maps.stack_traces, stack_id, |address| {
            capture.kernel_symbols.resolve(address)
        })
    } else {
        materialize_frames(&skel.maps.stack_traces, stack_id, |address| {
            resolve_user(address, &capture.user_maps)
        })
    };
    let frames = match frames {
        Ok(frames) => frames,
        Err(error) => return Some(error.to_string()),
    };
    let row = capture::Record {
        record: Some(capture::record::Record::Stack(capture::Stack {
            id: stack_id,
            frame: frames,
        })),
    };
    if write_record(writer, &row).is_err() {
        capture.userspace.write_failures += 1;
        return Some("stack_record_write_failure".to_string());
    }
    capture.emitted_stacks.insert(stack_id);
    None
}

fn observation_record(
    event: &Observation,
    kernel_stack_error: Option<String>,
    user_stack_error: Option<String>,
) -> capture::Record {
    let comm_end = event
        .comm
        .iter()
        .position(|byte| *byte == 0)
        .unwrap_or(event.comm.len());
    capture::Record {
        record: Some(capture::record::Record::Observation(capture::Observation {
            correlation_id: event.correlation_id,
            host_tgid: event.host_tgid,
            host_tid: event.host_tid,
            target_tgid: event.target_tgid,
            target_tid: event.target_tid,
            process_generation_nanos: event.process_generation_ns,
            thread_generation_nanos: event.thread_generation_ns,
            registration_token: event.registration_token,
            start_monotonic_nanos: event.start_monotonic_ns,
            end_monotonic_nanos: event.end_monotonic_ns,
            admission_threshold: event.admission_threshold,
            signal_result: event.signal_result,
            comm: String::from_utf8_lossy(&event.comm[..comm_end]).into_owned(),
            kernel_stack_id: event.kernel_stack_id,
            user_stack_id: event.user_stack_id,
            kernel_stack_error: kernel_stack_error.unwrap_or_default(),
            user_stack_error: user_stack_error.unwrap_or_default(),
            // The kernel writes one of the three reasons; anything else reads back as unspecified
            // and the correlator rejects it against the recomputed classification.
            reason: OffCpuReason::from_kernel(event.reason)
                .map_or(0, |reason| i32::from(reason.kernel_value())),
            prev_task_state: event.prev_task_state,
            preempted: event.preempted != 0,
            runqueue_nanos: (event.has_runqueue != 0).then_some(event.runqueue_ns),
        })),
    }
}

fn materialize_frames(
    map: &impl MapCore,
    stack_id: i64,
    mut resolve: impl FnMut(u64) -> (Option<String>, Option<String>),
) -> Result<Vec<capture::Frame>> {
    if stack_id < 0 {
        bail!("bpf_stack_error_{stack_id}");
    }
    let bytes = map
        .lookup(&(stack_id as u32).to_ne_bytes(), MapFlags::ANY)?
        .context("stack ID not present")?;
    let mut frames = Vec::new();
    for raw in bytes.chunks_exact(size_of::<u64>()) {
        let address = u64::from_ne_bytes(raw.try_into().unwrap());
        if address == 0 {
            break;
        }
        let (symbol, module) = resolve(address);
        frames.push(capture::Frame {
            address,
            symbol: symbol.unwrap_or_default(),
            module: module.unwrap_or_default(),
        });
    }
    Ok(frames)
}

struct KernelSymbols(Vec<(u64, String)>);

impl KernelSymbols {
    fn load() -> Self {
        let mut symbols = fs::read_to_string("/proc/kallsyms")
            .unwrap_or_default()
            .lines()
            .filter_map(|line| {
                let mut fields = line.split_whitespace();
                let address = u64::from_str_radix(fields.next()?, 16).ok()?;
                fields.next()?;
                let name = fields.next()?.to_string();
                (address != 0).then_some((address, name))
            })
            .collect::<Vec<_>>();
        symbols.sort_unstable_by_key(|entry| entry.0);
        Self(symbols)
    }

    fn resolve(&self, address: u64) -> (Option<String>, Option<String>) {
        let index = self.0.partition_point(|entry| entry.0 <= address);
        let symbol = index.checked_sub(1).map(|index| {
            let (base, name) = &self.0[index];
            format!("{name}+0x{:x}", address - base)
        });
        (symbol, Some("[kernel]".to_string()))
    }
}

struct UserMap {
    start: u64,
    end: u64,
    file_offset: u64,
    module: String,
}

impl UserMap {
    fn load(pid: u32) -> Vec<Self> {
        fs::read_to_string(format!("/proc/{pid}/maps"))
            .unwrap_or_default()
            .lines()
            .filter_map(|line| {
                let mut fields = line.split_whitespace();
                let range = fields.next()?;
                fields.next()?;
                let file_offset = u64::from_str_radix(fields.next()?, 16).ok()?;
                fields.next()?;
                fields.next()?;
                let module = fields.collect::<Vec<_>>().join(" ");
                let (start, end) = range.split_once('-')?;
                Some(Self {
                    start: u64::from_str_radix(start, 16).ok()?,
                    end: u64::from_str_radix(end, 16).ok()?,
                    file_offset,
                    module,
                })
            })
            .collect()
    }
}

fn resolve_user(address: u64, maps: &[UserMap]) -> (Option<String>, Option<String>) {
    maps.iter()
        .find(|map| map.start <= address && address < map.end)
        .map(|map| {
            let offset = address - map.start + map.file_offset;
            (
                Some(format!("{}+0x{offset:x}", map.module)),
                Some(map.module.clone()),
            )
        })
        .unwrap_or((None, None))
}

fn reset_stats(map: &impl MapCore) -> Result<()> {
    let zero = 0u32.to_ne_bytes();
    let current = map
        .lookup_percpu(&zero, MapFlags::ANY)?
        .context("stats map entry missing")?;
    let zeros = current
        .iter()
        .map(|value| vec![0u8; value.len()])
        .collect::<Vec<_>>();
    map.update_percpu(&zero, &zeros, MapFlags::ANY)?;
    Ok(())
}

fn read_stats(map: &impl MapCore) -> Result<KernelStats> {
    let values = map
        .lookup_percpu(&0u32.to_ne_bytes(), MapFlags::ANY)?
        .context("stats map entry missing")?;
    let mut total = KernelStats::default();
    for value in values {
        if value.len() != size_of::<KernelStats>() {
            bail!("unexpected stats value size {}", value.len());
        }
        let stats = unsafe { std::ptr::read_unaligned(value.as_ptr().cast::<KernelStats>()) };
        total.add(&stats);
    }
    Ok(total)
}

/// Appends one length-delimited record: a varint byte count followed by the encoded message.
fn write_record(writer: &mut BufWriter<File>, record: &capture::Record) -> Result<()> {
    let mut encoded = Vec::with_capacity(record.encoded_len() + 8);
    record.encode_length_delimited(&mut encoded)?;
    writer.write_all(&encoded)?;
    Ok(())
}

/// This thread's ID in the process's own PID namespace, which is also the target's namespace.
fn current_tid() -> u32 {
    unsafe { libc::syscall(libc::SYS_gettid) as u32 }
}

fn monotonic_ns() -> Result<u64> {
    clock_ns(libc::CLOCK_MONOTONIC, "CLOCK_MONOTONIC")
}

fn clock_ns(clock: libc::clockid_t, name: &str) -> Result<u64> {
    let mut value = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    if unsafe { libc::clock_gettime(clock, &mut value) } != 0 {
        return Err(std::io::Error::last_os_error())
            .with_context(|| format!("clock_gettime {name}"));
    }
    if value.tv_sec < 0 || !(0..1_000_000_000).contains(&value.tv_nsec) {
        bail!("clock_gettime {name} returned an invalid timespec");
    }
    (value.tv_sec as u64)
        .checked_mul(1_000_000_000)
        .and_then(|seconds| seconds.checked_add(value.tv_nsec as u64))
        .with_context(|| format!("clock_gettime {name} nanoseconds overflow"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use capture::sampling::Admission as AdmissionMessage;
    use libbpf_rs::Program;
    use serde_json::json;

    static PRIVILEGED_SIGNAL_COUNT: AtomicU64 = AtomicU64::new(0);

    extern "C" fn privileged_signal_handler(
        _signal: i32,
        _info: *mut libc::siginfo_t,
        _context: *mut libc::c_void,
    ) {
        PRIVILEGED_SIGNAL_COUNT.fetch_add(1, Ordering::Relaxed);
    }

    #[derive(Default)]
    struct FaultSink {
        rows: Vec<capture::CaptureEnd>,
        append_failures: usize,
        flush_failures: usize,
        sync_failures: usize,
        persistent_sync_failure: bool,
        append_calls: usize,
        flush_calls: usize,
        sync_calls: usize,
    }

    impl TerminalSink for FaultSink {
        fn append_terminal(&mut self, end: &capture::CaptureEnd) -> Result<()> {
            self.append_calls += 1;
            if self.append_failures != 0 {
                self.append_failures -= 1;
                return Err(std::io::Error::from_raw_os_error(libc::EIO))
                    .context("injected append EIO");
            }
            self.rows.push(end.clone());
            Ok(())
        }

        fn flush_terminal(&mut self) -> Result<()> {
            self.flush_calls += 1;
            if self.flush_failures != 0 {
                self.flush_failures -= 1;
                return Err(std::io::Error::from_raw_os_error(libc::EIO))
                    .context("injected flush EIO");
            }
            Ok(())
        }

        fn sync_terminal(&mut self) -> Result<()> {
            self.sync_calls += 1;
            if self.sync_failures != 0 {
                self.sync_failures -= 1;
                return Err(std::io::Error::from_raw_os_error(libc::EIO))
                    .context("injected fsync EIO");
            }
            if self.persistent_sync_failure {
                return Err(std::io::Error::from_raw_os_error(libc::EIO))
                    .context("injected persistent fsync EIO");
            }
            Ok(())
        }
    }

    fn incomplete_terminal_outcome() -> StopOutcome {
        StopOutcome {
            complete: false,
            stopped: capture::Stopped {
                source_path: "/tmp/source.pb".to_string(),
                capture_end: Some(capture::CaptureEnd {
                    state: capture::CaptureState::Incomplete as i32,
                    target_exited: true,
                    incomplete_reason: capture::IncompleteReason::TargetExited as i32,
                    ..capture::CaptureEnd::default()
                }),
                terminal_publication: None,
            },
        }
    }

    /// Models the kernel predicate: strict bounds first, then the random draw against the
    /// per-interval threshold of the admission policy.
    fn admitted(
        duration_ns: u64,
        min_micros: Option<u64>,
        max_micros: Option<u64>,
        random: u32,
        admission: &Admission,
    ) -> bool {
        let duration_matches = min_micros
            .map(|value| duration_ns > value * 1_000)
            .unwrap_or(true)
            && max_micros
                .map(|value| duration_ns < value * 1_000)
                .unwrap_or(true);
        let threshold = match *admission {
            Admission::Uniform {
                probability_threshold,
            } => probability_threshold,
            Admission::Proportional {
                record_all_above_micros,
            } => admission_threshold(duration_ns, record_all_above_micros * 1_000),
        };
        duration_matches && u64::from(random) < threshold
    }

    /// The kernel's per-interval threshold under the proportional policy.
    fn admission_threshold(duration_ns: u64, record_all_above_ns: u64) -> u64 {
        if duration_ns >= record_all_above_ns {
            return 1_u64 << 32;
        }
        let (scaled, shift) = proportional_scale(record_all_above_ns);
        ((duration_ns >> shift) << 32) / scaled
    }

    fn uniform(probability_threshold: u64) -> Admission {
        Admission::Uniform {
            probability_threshold,
        }
    }

    fn proportional(record_all_above_micros: u64) -> Admission {
        Admission::Proportional {
            record_all_above_micros,
        }
    }

    fn uniform_message(probability: &str, probability_threshold: u64) -> AdmissionMessage {
        AdmissionMessage::Uniform(capture::UniformAdmission {
            probability: probability.to_string(),
            probability_threshold,
        })
    }

    fn proportional_message(record_all_above_micros: u64) -> AdmissionMessage {
        AdmissionMessage::Proportional(capture::ProportionalAdmission {
            record_all_above_micros,
        })
    }

    fn reasons(values: &[capture::OffCpuReason]) -> Vec<i32> {
        values.iter().map(|reason| *reason as i32).collect()
    }

    fn sampling(
        min_off_cpu_micros: Option<u64>,
        max_off_cpu_micros: Option<u64>,
        admission: AdmissionMessage,
    ) -> capture::Sampling {
        capture::Sampling {
            reasons: reasons(&[capture::OffCpuReason::Blocked]),
            min_off_cpu_micros,
            max_off_cpu_micros,
            admission: Some(admission),
        }
    }

    fn sched_info() -> capture::TimeSplit {
        capture::TimeSplit {
            source: capture::TimeSplitSource::SchedInfo as i32,
        }
    }

    fn prepare_request(sampling: capture::Sampling) -> capture::PrepareRequest {
        capture::PrepareRequest {
            target_pid: 1,
            output_path: "/tmp/source.pb".to_string(),
            sampling: Some(sampling),
            time_split: Some(sched_info()),
            exclude_calling_thread: false,
        }
    }

    fn parse(request: &capture::PrepareRequest) -> Result<PrepareConfig> {
        parse_prepare(&request.encode_to_vec())
    }

    fn code_of(result: Result<PrepareConfig>) -> ErrorCode {
        Failure::of(&result.expect_err("request unexpectedly accepted")).code
    }

    /// The block is required, names one source, and is echoed as the agent sent it.
    #[test]
    fn time_split_is_an_explicit_required_block() {
        let mut request = prepare_request(sampling(None, None, uniform_message("1", 1 << 32)));
        let parsed = parse(&request).unwrap();
        assert_eq!(parsed.time_split.source, TimeSplitSource::SchedInfo);
        assert_eq!(parsed.time_split.kernel_value(), 1);
        assert_eq!(parsed.time_split.message, sched_info());
        let off = capture::TimeSplit {
            source: capture::TimeSplitSource::Off as i32,
        };
        request.time_split = Some(off);
        let parsed = parse(&request).unwrap();
        assert_eq!(parsed.time_split.kernel_value(), 0);
        assert_eq!(parsed.time_split.message, off);
        for rejected in [
            Some(capture::TimeSplit { source: 0 }),
            Some(capture::TimeSplit { source: 3 }),
            None,
        ] {
            request.time_split = rejected;
            assert_eq!(code_of(parse(&request)), ErrorCode::InvalidConfig);
        }
    }

    /// Undecodable, empty and incomplete requests are configuration errors.
    #[test]
    fn malformed_prepare_requests_are_invalid_config() {
        assert_eq!(code_of(parse_prepare(&[0xff])), ErrorCode::InvalidConfig);
        assert_eq!(code_of(parse_prepare(&[])), ErrorCode::InvalidConfig);
        let mut request = prepare_request(sampling(None, None, uniform_message("1", 1 << 32)));
        request.target_pid = 0;
        assert_eq!(code_of(parse(&request)), ErrorCode::InvalidConfig);
        request.target_pid = 1;
        request.output_path.clear();
        assert_eq!(code_of(parse(&request)), ErrorCode::InvalidConfig);
        request.output_path = "/tmp/source.pb".to_string();
        request.sampling = None;
        assert_eq!(code_of(parse(&request)), ErrorCode::InvalidConfig);
    }

    /// The ring record keeps the C layout, and the run-queue part reaches field 21 only when the
    /// kernel marked it present.
    #[test]
    fn observation_carries_the_run_queue_part_only_when_present() {
        assert_eq!(size_of::<Observation>(), 136);
        let mut event: Observation = unsafe { std::mem::zeroed() };
        event.reason = OffCpuReason::Blocked.kernel_value();
        event.prev_task_state = 1;
        event.runqueue_ns = 1234;
        let runqueue = |event: &Observation| match observation_record(event, None, None).record {
            Some(capture::record::Record::Observation(row)) => row.runqueue_nanos,
            _ => panic!("not an observation"),
        };
        assert_eq!(runqueue(&event), None);
        event.has_runqueue = 1;
        assert_eq!(runqueue(&event), Some(1234));
        event.runqueue_ns = 0;
        assert_eq!(runqueue(&event), Some(0));
    }

    #[test]
    fn delivery_policy_must_match_signal_before_enable() {
        let mut request = capture::EnableRequest {
            session_id: "01234567-89ab-cdef-0123-456789abcdef".to_string(),
            capture_epoch: 1,
            signal: libc::SIGRTMIN(),
            signal_delivery: capture::SignalDelivery::Queued as i32,
            sampling: Some(sampling(None, None, uniform_message("1", 1 << 32))),
            time_split: Some(sched_info()),
        };
        let parse = |request: &capture::EnableRequest| parse_enable(&request.encode_to_vec());
        assert!(parse(&request).is_ok());
        request.signal_delivery = capture::SignalDelivery::Coalescing as i32;
        assert!(parse(&request).is_err());
        request.signal = libc::SIGPROF;
        assert!(parse(&request).is_ok());
        request.signal_delivery = capture::SignalDelivery::Queued as i32;
        assert!(parse(&request).is_err());
        // Delivery is never inferred from the signal.
        request.signal_delivery = capture::SignalDelivery::Unspecified as i32;
        assert!(parse(&request).is_err());
        request.signal_delivery = 7;
        assert!(parse(&request).is_err());
        request.signal_delivery = capture::SignalDelivery::Coalescing as i32;
        for reserved in 32..libc::SIGRTMIN() {
            request.signal = reserved;
            assert!(parse(&request).is_err());
        }
        request.signal = libc::SIGPROF;
        request.session_id = "01234567-89AB-cdef-0123-456789abcdef".to_string();
        assert!(parse(&request).is_err());
        request.session_id = "01234567-89ab-cdef-0123-456789abcdef".to_string();
        request.sampling = None;
        assert!(parse(&request).is_err());
    }

    #[test]
    fn optional_duration_bounds_are_strict_and_compose_with_admission() {
        assert!(admitted(10_000, None, None, 4, &uniform(5)));
        assert!(!admitted(10_000, Some(10), None, 0, &uniform(1)));
        assert!(admitted(10_001, Some(10), None, 0, &uniform(1)));
        assert!(!admitted(20_000, None, Some(20), 0, &uniform(1)));
        assert!(admitted(19_999, None, Some(20), 0, &uniform(1)));
        assert!(admitted(15_000, Some(10), Some(20), 0, &uniform(1)));
        assert!(!admitted(15_000, Some(10), Some(20), 1, &uniform(1)));
        assert!(!admitted(10_000, Some(10), Some(20), 0, &uniform(u64::MAX)));
        // Proportional: a 5 µs interval against a 10 µs reference admits half the draws.
        assert!(admitted(
            5_000,
            None,
            None,
            (1 << 31) - 1,
            &proportional(10)
        ));
        assert!(!admitted(5_000, None, None, 1 << 31, &proportional(10)));
        assert!(admitted(10_000, None, None, u32::MAX, &proportional(10)));
        assert!(!admitted(
            10_000,
            Some(10),
            None,
            u32::MAX,
            &proportional(10)
        ));
    }

    /// The BPF program's classification: the `preempt` argument wins, then a zero task state
    /// (`TASK_RUNNING`) is a voluntary switch-out while still runnable, and any other state blocks.
    #[test]
    fn switch_out_reason_follows_the_raw_sched_switch_arguments() {
        const TASK_INTERRUPTIBLE: u32 = 0x1;
        const TASK_UNINTERRUPTIBLE: u32 = 0x2;
        const TASK_KILLABLE: u32 = TASK_UNINTERRUPTIBLE | 0x100;
        assert_eq!(OffCpuReason::classify(true, 0), OffCpuReason::Preempted);
        // A preempted task keeps whatever state it was entering; preemption still wins.
        assert_eq!(
            OffCpuReason::classify(true, TASK_INTERRUPTIBLE),
            OffCpuReason::Preempted
        );
        assert_eq!(OffCpuReason::classify(false, 0), OffCpuReason::Runnable);
        for state in [TASK_INTERRUPTIBLE, TASK_UNINTERRUPTIBLE, TASK_KILLABLE] {
            assert_eq!(OffCpuReason::classify(false, state), OffCpuReason::Blocked);
        }
        for reason in [
            OffCpuReason::Blocked,
            OffCpuReason::Runnable,
            OffCpuReason::Preempted,
        ] {
            assert_eq!(
                OffCpuReason::from_kernel(reason.kernel_value()),
                Some(reason)
            );
        }
        assert_eq!(OffCpuReason::from_kernel(0), None);
        assert_eq!(OffCpuReason::from_kernel(4), None);
        // The capture schema's enum numbers are the kernel's values.
        assert_eq!(
            i32::from(OffCpuReason::Blocked.kernel_value()),
            capture::OffCpuReason::Blocked as i32
        );
        assert_eq!(
            i32::from(OffCpuReason::Runnable.kernel_value()),
            capture::OffCpuReason::Runnable as i32
        );
        assert_eq!(
            i32::from(OffCpuReason::Preempted.kernel_value()),
            capture::OffCpuReason::Preempted as i32
        );
    }

    /// The reason filter runs before the bounds: an unselected reason is never eligible, whatever
    /// its duration and whatever the admission draw.
    #[test]
    fn reason_filter_precedes_bounds_and_admission() {
        use capture::OffCpuReason as Reason;
        let parse_reasons = |values: Vec<i32>| {
            let mut message = sampling(Some(10), None, uniform_message("1", 1 << 32));
            message.reasons = values;
            parse(&prepare_request(message))
        };
        let blocked = parse_reasons(reasons(&[Reason::Blocked])).unwrap().sampling;
        let everything = parse_reasons(reasons(&[
            Reason::Blocked,
            Reason::Runnable,
            Reason::Preempted,
        ]))
        .unwrap()
        .sampling;
        let selected = |sampling: &SamplingPolicy, reason: OffCpuReason, duration_ns: u64| {
            sampling.reason_mask() & (1 << reason.kernel_value()) != 0
                && admitted(
                    duration_ns,
                    sampling.min_off_cpu_micros,
                    sampling.max_off_cpu_micros,
                    0,
                    &sampling.admission,
                )
        };
        assert!(selected(&blocked, OffCpuReason::Blocked, 20_000));
        assert!(!selected(&blocked, OffCpuReason::Runnable, 20_000));
        assert!(!selected(&blocked, OffCpuReason::Preempted, u64::MAX / 2));
        assert!(!selected(&blocked, OffCpuReason::Blocked, 10_000));
        assert!(selected(&everything, OffCpuReason::Preempted, 20_000));
        assert_eq!(everything.reason_mask(), 0b1110);
        for rejected in [
            vec![],
            reasons(&[Reason::Preempted, Reason::Blocked]),
            reasons(&[Reason::Blocked, Reason::Blocked]),
            reasons(&[Reason::Unspecified]),
            vec![4],
        ] {
            assert_eq!(
                code_of(parse_reasons(rejected.clone())),
                ErrorCode::InvalidConfig,
                "{rejected:?}"
            );
        }
    }

    #[test]
    fn stacks_are_announced_once_before_they_are_referenced() {
        let mut emitted = HashSet::new();
        assert_eq!(stack_action(7, &emitted), StackAction::NeedsRecord);
        emitted.insert(7);
        assert_eq!(stack_action(7, &emitted), StackAction::AlreadyEmitted);
        // A second id is announced on its own, and an id is never confused with another.
        assert_eq!(stack_action(8, &emitted), StackAction::NeedsRecord);
        assert_eq!(stack_action(0, &emitted), StackAction::NeedsRecord);
        // A failed stack has no record at all; the error travels on the observation.
        assert_eq!(
            stack_action(-7, &emitted),
            StackAction::Error("bpf_stack_error_-7".to_string())
        );
        assert_eq!(
            stack_action(-1, &HashSet::new()),
            StackAction::Error("bpf_stack_error_-1".to_string())
        );
    }

    #[test]
    fn proportional_threshold_is_exact_and_overflow_free() {
        let certain = 1_u64 << 32;
        assert_eq!(admission_threshold(10_000_000, 10_000_000), certain);
        assert_eq!(admission_threshold(u64::MAX, 10_000_000), certain);
        assert_eq!(admission_threshold(1_000_000, 10_000_000), certain / 10);
        assert_eq!(admission_threshold(0, 10_000_000), 0);
        assert_eq!(admission_threshold(1, 10_000_000), 429);
        assert_eq!(proportional_scale(10_000_000), (10_000_000, 0));
        // Above 2^32 ns the reference is shifted so the numerator stays within u64.
        let long = 10_000_000_000_u64;
        let (scaled, shift) = proportional_scale(long);
        assert_eq!(shift, 2);
        assert_eq!(scaled, long >> 2);
        // Shifting drops the low bits of both operands, so the result can be one or two below.
        let just_below = admission_threshold(long - 1, long);
        assert!(
            just_below >= certain - 2 && just_below < certain,
            "{just_below}"
        );
        assert_eq!(admission_threshold(long / 2, long), certain / 2);
        let (scaled, shift) = proportional_scale(u64::MAX);
        assert_eq!(shift, 32);
        assert_eq!(scaled, u32::MAX as u64);
        assert_eq!(admission_threshold(u64::MAX - 1, u64::MAX), certain);
        let mut previous = 0;
        for duration in (0..long).step_by(123_456_789) {
            let threshold = admission_threshold(duration, long);
            assert!(threshold >= previous);
            previous = threshold;
        }
    }

    #[test]
    fn prepare_policy_accepts_each_shape_and_rejects_bad_values() {
        for (min, max) in [
            (None, None),
            (Some(10), None),
            (None, Some(20)),
            (Some(10), Some(20)),
        ] {
            parse(&prepare_request(sampling(
                min,
                max,
                uniform_message("1", 1 << 32),
            )))
            .unwrap();
            parse(&prepare_request(sampling(
                min,
                max,
                proportional_message(5),
            )))
            .unwrap();
        }
        let message = sampling(Some(10), None, proportional_message(5));
        let config = parse(&prepare_request(message.clone())).unwrap();
        assert_eq!(config.sampling.min_off_cpu_micros, Some(10));
        assert_eq!(config.sampling.admission, proportional(5));
        // The echoed message is the one received.
        assert_eq!(config.sampling.message, message);
        assert_eq!(config.sampling.reason_mask(), 0b0010);
        for rejected in [
            sampling(Some(10), Some(10), uniform_message("1", 1 << 32)),
            sampling(None, Some(u64::MAX), uniform_message("1", 1 << 32)),
            sampling(None, None, uniform_message("0", 0)),
            sampling(None, None, uniform_message("2", (1 << 32) + 1)),
            sampling(None, None, proportional_message(0)),
            sampling(None, None, proportional_message(u64::MAX)),
            sampling(
                None,
                None,
                AdmissionMessage::None(capture::NoAdmission::default()),
            ),
            capture::Sampling {
                admission: None,
                ..sampling(None, None, proportional_message(5))
            },
        ] {
            assert_eq!(
                code_of(parse(&prepare_request(rejected.clone()))),
                ErrorCode::InvalidConfig,
                "accepted {rejected:?}"
            );
        }
    }

    /// The outermost coded context decides the reply's code; an uncoded error is internal.
    #[test]
    fn failures_carry_their_code_through_context() {
        let io: Result<()> = Err(std::io::Error::from_raw_os_error(libc::ENOENT).into());
        let coded = io.coded(ErrorCode::IoError, "create source artifact /x");
        let failure = Failure::of(&coded.context("prepare").unwrap_err());
        assert_eq!(failure.code, ErrorCode::IoError);
        assert!(
            failure
                .message
                .starts_with("prepare: create source artifact /x: ")
        );
        let failure = Failure::of(&anyhow!("unexpected"));
        assert_eq!(failure.code, ErrorCode::InternalError);
        // A failure sent across the worker channel keeps its code.
        let resent = Failure::of(&Failure::of(&fail(ErrorCode::TargetExited, "gone")).into_error());
        assert_eq!(resent.code, ErrorCode::TargetExited);
        assert_eq!(resent.message, "gone");
        assert_eq!(
            error_reply(ErrorCode::StopTimeout, String::new()).state(),
            CollectorState::Stopping
        );
        assert_eq!(
            error_reply(ErrorCode::CloseTimeout, String::new()).state(),
            CollectorState::Closing
        );
        assert_eq!(
            error_reply(ErrorCode::InvalidHandle, String::new()).state(),
            CollectorState::Error
        );
    }

    #[test]
    fn wall_clock_calibration_selects_first_narrowest_bracket() {
        let calibration = select_wall_clock_calibration(&[
            ClockBracket {
                monotonic_before_ns: 100,
                realtime_ns: 1_000,
                monotonic_after_ns: 120,
            },
            ClockBracket {
                monotonic_before_ns: 200,
                realtime_ns: 2_000,
                monotonic_after_ns: 205,
            },
            ClockBracket {
                monotonic_before_ns: 300,
                realtime_ns: 3_000,
                monotonic_after_ns: 305,
            },
        ])
        .unwrap();
        assert_eq!(calibration.sample_count, 3);
        assert_eq!(calibration.selected_sample_index, 1);
        assert_eq!(calibration.monotonic_before_ns, 200);
        assert_eq!(calibration.monotonic_after_ns, 205);
        assert_eq!(calibration.monotonic_midpoint_ns, 202);
        assert_eq!(calibration.realtime_minus_monotonic_ns, 1_798);
        assert_eq!(calibration.bracket_width_ns, 5);
        assert_eq!(calibration.midpoint_uncertainty_ns, 3);
    }

    #[test]
    fn wall_clock_calibration_message_is_exact_and_rejects_an_unrepresentable_offset() {
        let calibration = select_wall_clock_calibration(&[ClockBracket {
            monotonic_before_ns: 1_000,
            realtime_ns: 7,
            monotonic_after_ns: 1_005,
        }])
        .unwrap();
        assert_eq!(
            calibration.message().unwrap(),
            capture::WallClockCalibration {
                sample_count: 1,
                selected_sample_index: 0,
                monotonic_before_nanos: 1_000,
                realtime_nanos: 7,
                monotonic_after_nanos: 1_005,
                monotonic_midpoint_nanos: 1_002,
                realtime_minus_monotonic_nanos: 7 - 1_002,
                bracket_width_nanos: 5,
                midpoint_uncertainty_nanos: 3,
            }
        );
        // The proto3 JSON mapping prints the 64-bit values as exact decimal strings.
        assert_eq!(
            serde_json::to_value(calibration.message().unwrap()).unwrap()["realtimeMinusMonotonicNanos"],
            json!("-995")
        );
        let beyond = select_wall_clock_calibration(&[ClockBracket {
            monotonic_before_ns: u64::MAX - 5,
            realtime_ns: 7,
            monotonic_after_ns: u64::MAX,
        }])
        .unwrap();
        assert!(beyond.message().is_err());
    }

    #[test]
    fn wall_clock_calibration_rejects_missing_or_reversed_brackets() {
        assert!(select_wall_clock_calibration(&[]).is_err());
        assert!(
            select_wall_clock_calibration(&[ClockBracket {
                monotonic_before_ns: 2,
                realtime_ns: 10,
                monotonic_after_ns: 1,
            }])
            .is_err()
        );
        assert!(
            select_wall_clock_calibration(&[
                ClockBracket {
                    monotonic_before_ns: 1,
                    realtime_ns: 10,
                    monotonic_after_ns: 2,
                },
                ClockBracket {
                    monotonic_before_ns: 4,
                    realtime_ns: 11,
                    monotonic_after_ns: 3,
                },
            ])
            .is_err()
        );
    }

    #[test]
    fn terminal_publication_retries_only_fsync_after_transient_eio() {
        let outcome = incomplete_terminal_outcome();
        let expected_end = outcome.stopped.capture_end.clone().unwrap();
        let mut publication = TerminalPublication::default();
        publication.begin(outcome, true).unwrap();
        let mut sink = FaultSink {
            sync_failures: 1,
            ..FaultSink::default()
        };

        let first_error = publication.advance(&mut sink).unwrap_err();
        assert_eq!(Failure::of(&first_error).code, ErrorCode::IoError);
        let first_error = first_error.to_string();
        assert!(first_error.contains("capture state=CAPTURE_STATE_INCOMPLETE"));
        assert!(first_error.contains("incompleteReason=INCOMPLETE_REASON_TARGET_EXITED"));
        assert_eq!(publication.stage, TerminalPublicationStage::Flushed);
        assert_eq!(sink.rows, vec![expected_end]);
        assert_eq!(
            (sink.append_calls, sink.flush_calls, sink.sync_calls),
            (1, 1, 1)
        );

        let result = publication.advance(&mut sink).unwrap();
        assert_eq!(result.state(), CollectorState::Incomplete);
        assert_eq!(result.stopped.capture_end.as_ref(), Some(&sink.rows[0]));
        assert_eq!(
            result.stopped.terminal_publication,
            Some(capture::TerminalPublication {
                automatic: true,
                append_attempts: 1,
                flush_attempts: 1,
                sync_attempts: 2,
                first_error: first_error.clone(),
            })
        );
        assert_eq!(
            (sink.append_calls, sink.flush_calls, sink.sync_calls),
            (1, 1, 2)
        );

        let retry = publication.advance(&mut sink).unwrap();
        assert_eq!(retry, result);
        assert_eq!(
            (sink.append_calls, sink.flush_calls, sink.sync_calls),
            (1, 1, 2)
        );
    }

    #[test]
    fn terminal_publication_never_retries_an_ambiguous_append_failure() {
        let mut publication = TerminalPublication::default();
        publication
            .begin(incomplete_terminal_outcome(), true)
            .unwrap();
        let mut sink = FaultSink {
            append_failures: 1,
            ..FaultSink::default()
        };

        let first_error = publication.advance(&mut sink).unwrap_err().to_string();
        let retry_error = publication.advance(&mut sink).unwrap_err().to_string();
        assert_eq!(retry_error, first_error);
        assert_eq!(publication.stage, TerminalPublicationStage::Failed);
        assert!(sink.rows.is_empty());
        assert_eq!(
            (sink.append_calls, sink.flush_calls, sink.sync_calls),
            (1, 0, 0)
        );
    }

    #[test]
    fn terminal_publication_retries_flush_without_appending_again() {
        let mut publication = TerminalPublication::default();
        publication
            .begin(incomplete_terminal_outcome(), true)
            .unwrap();
        let mut sink = FaultSink {
            flush_failures: 1,
            ..FaultSink::default()
        };

        publication.advance(&mut sink).unwrap_err();
        assert_eq!(publication.stage, TerminalPublicationStage::RowWritten);
        let result = publication.advance(&mut sink).unwrap();
        assert_eq!(
            result
                .stopped
                .terminal_publication
                .as_ref()
                .map(|publication| publication.flush_attempts),
            Some(2)
        );
        assert_eq!(
            (sink.append_calls, sink.flush_calls, sink.sync_calls),
            (1, 2, 1)
        );
        assert_eq!(sink.rows.len(), 1);
    }

    #[test]
    fn terminal_publication_persistent_fsync_failure_is_bounded_per_retry() {
        let mut publication = TerminalPublication::default();
        publication
            .begin(incomplete_terminal_outcome(), true)
            .unwrap();
        let mut sink = FaultSink {
            persistent_sync_failure: true,
            ..FaultSink::default()
        };

        let first_error = publication.advance(&mut sink).unwrap_err().to_string();
        let retry_error = publication.advance(&mut sink).unwrap_err().to_string();
        assert_eq!(retry_error, first_error);
        assert_eq!(publication.stage, TerminalPublicationStage::Flushed);
        assert_eq!(
            (sink.append_calls, sink.flush_calls, sink.sync_calls),
            (1, 1, 2)
        );
        assert_eq!(sink.rows.len(), 1);
    }

    #[test]
    fn truncated_proc_status_never_exposes_partial_signal_fields() {
        for (field, partial) in [("SigQ", "123/456"), ("SigBlk", "0000000000000400")] {
            let path = std::env::temp_dir().join(format!(
                "jonoffcpu-proc-status-{}-{}",
                std::process::id(),
                random_nonzero_u64().unwrap()
            ));
            let field_start = MAX_PROC_STATUS_BYTES - 5;
            let filler_len = field_start - 3;
            let mut bytes = Vec::with_capacity(MAX_PROC_STATUS_BYTES + partial.len() + 16);
            bytes.extend_from_slice(b"X:");
            bytes.extend(std::iter::repeat_n(b'x', filler_len));
            bytes.push(b'\n');
            bytes.extend_from_slice(format!("{field}:\t{partial}\n").as_bytes());
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&path)
                .unwrap();
            file.write_all(&bytes).unwrap();
            drop(file);

            let status = read_proc_status(
                path.to_str().unwrap(),
                Instant::now() + Duration::from_secs(1),
            )
            .unwrap();
            fs::remove_file(path).unwrap();
            assert!(status.truncated);
            assert_eq!(proc_status_value(&status.text, field), None);
        }
    }

    #[test]
    #[ignore = "requires root, BTF, tracing, and the production scheduler-exit kfunc"]
    fn privileged_prepare_failure_unwinds_all_owned_bpf_objects() {
        install_privileged_signal_handler().unwrap();
        PRIVILEGED_SIGNAL_COUNT.store(0, Ordering::Relaxed);
        let registry_before = registry().lock().unwrap().len();
        let mut cases = vec![json!({
            "case": "deadTarget",
            "error": "target not visible",
            "successfulHandle": false,
            "artifactCreated": false,
        })];

        let dead_pid = unsafe { libc::fork() };
        if dead_pid == 0 {
            unsafe { libc::_exit(0) };
        }
        assert!(dead_pid > 0);
        assert_eq!(
            unsafe { libc::waitpid(dead_pid, std::ptr::null_mut(), 0) },
            dead_pid
        );
        let dead_path = privileged_output("dead-target");
        let mut dead_config = privileged_prepare(dead_path.clone());
        dead_config.target_pid = dead_pid as u32;
        let dead_error = prepare(dead_config)
            .err()
            .expect("dead target prepare unexpectedly succeeded");
        assert!(dead_error.to_string().contains("not visible"));
        assert!(!dead_path.exists());

        for (fault, expected) in [
            (PrepareFault::Binding, "seed exact target task storage"),
            (PrepareFault::FirstAttach, "attach sched_switch recorder"),
            (PrepareFault::SecondAttach, "injected END attach failure"),
            (
                PrepareFault::AfterBothAttaches,
                "injected setup failure after both attaches",
            ),
        ] {
            let result = run_prepare_fault(fault);
            assert!(result.error.contains(expected), "{}", result.error);
            assert!(result.worker_exited);
            // The header is written when the artifact is created, and nothing after it.
            assert_eq!(
                fs::metadata(&result.path).unwrap().len(),
                capture::HEADER_LEN as u64
            );
            assert_source_has_no_records(&result.path);
            assert_eq!(PRIVILEGED_SIGNAL_COUNT.load(Ordering::Relaxed), 0);
            for id in &result.program_ids {
                assert!(Program::fd_from_id(*id).is_err(), "program {id} survived");
            }
            for id in &result.link_ids {
                let fd = unsafe { libbpf_rs::libbpf_sys::bpf_link_get_fd_by_id(*id) };
                if fd >= 0 {
                    unsafe { libc::close(fd) };
                    panic!("link {id} survived prepare failure");
                }
                assert_eq!(
                    std::io::Error::last_os_error().raw_os_error(),
                    Some(libc::ENOENT)
                );
            }
            cases.push(json!({
                "case": format!("{fault:?}"),
                "error": result.error,
                "successfulHandle": false,
                "workerExited": result.worker_exited,
                "artifactBytes": capture::HEADER_LEN,
                "captureStartRows": 0,
                "observationRows": 0,
                "signals": 0,
                "programIdsReleased": result.program_ids,
                "linkIdsReleased": result.link_ids,
                "disabledSnapshotVerified": matches!(fault, PrepareFault::SecondAttach | PrepareFault::AfterBothAttaches),
            }));
            let _ = fs::remove_file(result.path);
        }
        assert_eq!(registry().lock().unwrap().len(), registry_before);

        let normal_path = privileged_output("normal-after-faults");
        let normal = prepare(privileged_prepare(normal_path.clone())).unwrap();
        enable(normal.handle, privileged_enable(0x8000_0901)).unwrap();
        for _ in 0..16 {
            thread::sleep(Duration::from_millis(1));
        }
        stop(normal.handle, Duration::from_secs(5)).unwrap();
        close(normal.handle).unwrap();
        let records = source_records(&normal_path);
        assert!(matches!(
            records.first(),
            Some(capture::record::Record::CaptureStart(_))
        ));
        assert!(matches!(
            records.last(),
            Some(capture::record::Record::CaptureEnd(_))
        ));
        println!(
            "{}",
            serde_json::to_string_pretty(&json!({
                "gate": "T09",
                "passed": true,
                "cases": cases,
                "normalPrepareEnableStopAfterFailures": true,
                "registryEntriesBefore": registry_before,
                "registryEntriesAfter": registry().lock().unwrap().len(),
            }))
            .unwrap()
        );
        fs::remove_file(normal_path).unwrap();
    }

    #[test]
    #[ignore = "requires root, BTF, tracing, and the production scheduler-exit kfunc"]
    fn privileged_source_handle_and_epoch_are_one_shot() {
        install_privileged_signal_handler().unwrap();
        let first_path = privileged_output("t14-first");
        let first = prepare(privileged_prepare(first_path.clone())).unwrap();
        enable(first.handle, privileged_enable(0x8000_1401)).unwrap();
        thread::sleep(Duration::from_millis(20));
        stop(first.handle, Duration::from_secs(5)).unwrap();
        let old_handle_error = enable(first.handle, privileged_enable(0x8000_1401)).unwrap_err();
        assert_eq!(Failure::of(&old_handle_error).code, ErrorCode::InvalidState);
        let old_handle_error = old_handle_error.to_string();
        assert!(old_handle_error.contains("already stopped"));
        close(first.handle).unwrap();
        let closed_handle_error = enable(first.handle, privileged_enable(0x8000_1401)).unwrap_err();
        assert_eq!(
            Failure::of(&closed_handle_error).code,
            ErrorCode::InvalidHandle
        );
        let closed_handle_error = closed_handle_error.to_string();
        let old_source_error = prepare(privileged_prepare(first_path.clone()))
            .err()
            .expect("old source path unexpectedly reopened");
        assert_eq!(Failure::of(&old_source_error).code, ErrorCode::IoError);
        let old_source_error = old_source_error.to_string();
        assert!(old_source_error.contains("create source artifact"));

        let fresh_path = privileged_output("t14-fresh");
        let fresh = prepare(privileged_prepare(fresh_path.clone())).unwrap();
        enable(fresh.handle, privileged_enable(0x8000_1402)).unwrap();
        thread::sleep(Duration::from_millis(20));
        stop(fresh.handle, Duration::from_secs(5)).unwrap();
        close(fresh.handle).unwrap();
        let epoch = |path: &Path| match source_records(path).first() {
            Some(capture::record::Record::CaptureStart(start)) => start.capture_epoch,
            other => panic!("source does not start with captureStart: {other:?}"),
        };
        assert_eq!(epoch(&first_path), 0x8000_1401);
        assert_eq!(epoch(&fresh_path), 0x8000_1402);
        println!(
            "{}",
            serde_json::to_string_pretty(&json!({
                "gate": "T14",
                "passed": true,
                "oldHandleRestartRejected": old_handle_error,
                "closedHandleReuseRejected": closed_handle_error,
                "oldSourceReuseRejected": old_source_error,
                "originalEpoch": 0x8000_1401_u32,
                "freshEpoch": 0x8000_1402_u32,
                "freshOwnedSourceSucceeded": true,
            }))
            .unwrap()
        );
        fs::remove_file(first_path).unwrap();
        fs::remove_file(fresh_path).unwrap();
    }

    struct PrepareFaultResult {
        error: String,
        path: PathBuf,
        program_ids: Vec<u32>,
        link_ids: Vec<u32>,
        worker_exited: bool,
    }

    fn run_prepare_fault(fault: PrepareFault) -> PrepareFaultResult {
        let path = privileged_output(match fault {
            PrepareFault::Binding => "binding",
            PrepareFault::FirstAttach => "first-attach",
            PrepareFault::SecondAttach => "second-attach",
            PrepareFault::AfterBothAttaches => "after-both",
        });
        let (event_tx, event_rx) = mpsc::sync_channel(8);
        let (resume_tx, resume_rx) = mpsc::sync_channel(0);
        let control = PrepareTestControl {
            fault,
            events: event_tx,
            resume: resume_rx,
        };
        let config = privileged_prepare(path.clone());
        let caller = thread::spawn(move || {
            prepare_internal(config, Some(control))
                .err()
                .expect("faulted prepare unexpectedly succeeded")
                .to_string()
        });
        let mut program_ids = Vec::new();
        let mut link_ids = Vec::new();
        let mut worker_exited = false;
        while !caller.is_finished() || !worker_exited {
            match event_rx.recv_timeout(Duration::from_millis(100)) {
                Ok(PrepareTestEvent::Attached {
                    stage,
                    program_ids: programs,
                    link_ids: links,
                }) => {
                    assert_eq!(
                        stage,
                        if fault == PrepareFault::SecondAttach {
                            PrepareObservedStage::StartAttached
                        } else {
                            PrepareObservedStage::BothAttached
                        }
                    );
                    program_ids = programs;
                    link_ids = links;
                    for _ in 0..20 {
                        thread::sleep(Duration::from_millis(1));
                    }
                    resume_tx.send(()).unwrap();
                }
                Ok(PrepareTestEvent::DisabledSnapshot {
                    enabled,
                    next_sequence,
                    thread_states,
                    stats,
                }) => {
                    assert_eq!(enabled, 0);
                    assert_eq!(next_sequence, 1);
                    assert_eq!(thread_states, 0);
                    assert_eq!(stats.switch_outs, 0);
                    assert_eq!(stats.eligible_intervals, 0);
                    assert_eq!(stats.selected_intervals, 0);
                    assert_eq!(stats.ring_reserve_failures, 0);
                }
                Ok(PrepareTestEvent::WorkerExited) => worker_exited = true,
                Err(mpsc::RecvTimeoutError::Timeout) if caller.is_finished() => break,
                Err(mpsc::RecvTimeoutError::Timeout) => continue,
                Err(mpsc::RecvTimeoutError::Disconnected) => break,
            }
        }
        let error = caller.join().unwrap();
        while let Ok(event) = event_rx.try_recv() {
            if matches!(event, PrepareTestEvent::WorkerExited) {
                worker_exited = true;
            }
        }
        PrepareFaultResult {
            error,
            path,
            program_ids,
            link_ids,
            worker_exited,
        }
    }

    fn privileged_sampling() -> capture::Sampling {
        use capture::OffCpuReason as Reason;
        capture::Sampling {
            reasons: reasons(&[Reason::Blocked, Reason::Runnable, Reason::Preempted]),
            min_off_cpu_micros: None,
            max_off_cpu_micros: None,
            admission: Some(uniform_message("1", 1_u64 << 32)),
        }
    }

    fn privileged_prepare(path: PathBuf) -> PrepareConfig {
        let mut request = prepare_request(privileged_sampling());
        request.target_pid = unsafe { libc::getpid() as u32 };
        request.output_path = path.to_string_lossy().into_owned();
        parse(&request).unwrap()
    }

    fn privileged_enable(epoch: u32) -> EnableConfig {
        let request = capture::EnableRequest {
            session_id: "12345678-1234-4abc-8def-123456789abc".to_string(),
            capture_epoch: epoch,
            signal: libc::SIGRTMIN() + 5,
            signal_delivery: capture::SignalDelivery::Queued as i32,
            sampling: Some(privileged_sampling()),
            time_split: Some(sched_info()),
        };
        parse_enable(&request.encode_to_vec()).unwrap()
    }

    fn privileged_output(label: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "jonoffcpu-{label}-{}-{}.pb",
            std::process::id(),
            random_nonzero_u64().unwrap()
        ))
    }

    fn source_records(path: &Path) -> Vec<capture::record::Record> {
        capture::decode(&fs::read(path).unwrap())
            .unwrap()
            .into_iter()
            .filter_map(|record| record.record)
            .collect()
    }

    fn assert_source_has_no_records(path: &Path) {
        assert!(source_records(path).is_empty());
    }

    fn install_privileged_signal_handler() -> Result<()> {
        unsafe {
            let mut action: libc::sigaction = std::mem::zeroed();
            action.sa_flags = libc::SA_SIGINFO | libc::SA_RESTART;
            action.sa_sigaction = privileged_signal_handler as usize;
            libc::sigemptyset(&mut action.sa_mask);
            if libc::sigaction(libc::SIGRTMIN() + 5, &action, std::ptr::null_mut()) != 0 {
                return Err(std::io::Error::last_os_error())
                    .context("install privileged test signal handler");
            }
        }
        Ok(())
    }
}
