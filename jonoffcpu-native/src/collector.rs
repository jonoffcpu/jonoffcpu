// SPDX-License-Identifier: MIT
use crate::bpf_sched_exit::JonoffcpuCookieSkelBuilder;
use anyhow::{Context, Result, anyhow, bail};
use libbpf_rs::skel::{OpenSkel, SkelBuilder};
use libbpf_rs::{Link, MapCore, MapFlags, RingBufferBuilder, TracepointCategory};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
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
const MAX_CONTROL_JSON: usize = 64 * 1024;
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

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct PrepareConfig {
    target_pid: u32,
    output_path: PathBuf,
    sampling: SamplingConfig,
    /// The agent's controller thread calls prepare and later only polls the profiler; when set, that
    /// thread's own waits are left out of the capture like the collector's, so the profiler does not
    /// observe itself.
    #[serde(default)]
    exclude_calling_thread: bool,
    #[serde(skip)]
    calling_tid: u32,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub(crate) struct EnableConfig {
    session_id: String,
    capture_epoch: u32,
    signal: i32,
    #[serde(default)]
    signal_delivery: Option<String>,
    #[serde(default)]
    sampling: Option<SamplingConfig>,
}

/// The resolved sampling policy. The same object is echoed verbatim in every control reply and
/// in the `captureStart` row, so the agent and the correlator can compare copies structurally.
#[derive(Debug, Deserialize, Serialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct SamplingConfig {
    #[serde(deserialize_with = "deserialize_optional_u64")]
    min_off_cpu_micros: Option<u64>,
    #[serde(deserialize_with = "deserialize_optional_u64")]
    max_off_cpu_micros: Option<u64>,
    admission: Admission,
}

/// Admission decides which duration-eligible intervals are recorded. The `none` policy never
/// reaches the collector: the agent runs async-profiler alone in that case.
#[derive(Debug, Deserialize, Serialize, Clone, PartialEq, Eq)]
#[serde(
    tag = "policy",
    rename_all = "lowercase",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
pub(crate) enum Admission {
    /// Every eligible interval is admitted with the same probability `probabilityThreshold / 2^32`.
    Uniform {
        probability: String,
        probability_threshold: u64,
    },
    /// An interval of at least `recordAllAboveMicros` is always admitted; a shorter one with
    /// probability `duration / recordAllAboveMicros`.
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

impl SamplingConfig {
    fn validate(&self) -> Result<()> {
        let min_ns = self
            .min_off_cpu_micros
            .map(|value| {
                value
                    .checked_mul(1_000)
                    .context("minOffCpuMicros overflows nanos")
            })
            .transpose()?;
        let max_ns = self
            .max_off_cpu_micros
            .map(|value| {
                value
                    .checked_mul(1_000)
                    .context("maxOffCpuMicros overflows nanos")
            })
            .transpose()?;
        if min_ns.zip(max_ns).is_some_and(|(min, max)| min >= max) {
            bail!("minOffCpuMicros must be strictly below maxOffCpuMicros");
        }
        match self.admission {
            Admission::Uniform {
                probability_threshold,
                ..
            } => {
                if probability_threshold == 0 || probability_threshold > (1_u64 << 32) {
                    bail!("probabilityThreshold must be in 1..=4294967296");
                }
            }
            Admission::Proportional {
                record_all_above_micros,
            } => {
                if record_all_above_micros == 0 {
                    bail!("recordAllAboveMicros must be at least 1");
                }
                record_all_above_micros
                    .checked_mul(1_000)
                    .context("recordAllAboveMicros overflows nanos")?;
            }
        }
        Ok(())
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

    fn json(&self) -> Value {
        serde_json::to_value(self).expect("sampling config serializes")
    }
}

#[derive(Deserialize)]
#[serde(untagged)]
enum U64Value {
    Number(u64),
    String(String),
}

fn deserialize_optional_u64<'de, D>(deserializer: D) -> std::result::Result<Option<u64>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    Option::<U64Value>::deserialize(deserializer)?.map_or(Ok(None), |value| match value {
        U64Value::Number(value) => Ok(Some(value)),
        U64Value::String(value) => value.parse().map(Some).map_err(serde::de::Error::custom),
    })
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

    fn json(&self) -> Value {
        json!({
            "switchOuts": self.switch_outs.to_string(),
            "schedulerExitSwitches": self.scheduler_exit_switches.to_string(),
            "schedulerExitNoSwitches": self.scheduler_exit_no_switches.to_string(),
            "lifetimeRejections": self.lifetime_rejections.to_string(),
            "threadStateFailures": self.thread_state_failures.to_string(),
            "eligibleIntervals": self.eligible_intervals.to_string(),
            "eligibleDurationMicros": self.eligible_duration_us.to_string(),
            "admissionRejections": self.admission_rejections.to_string(),
            "selectedIntervals": self.selected_intervals.to_string(),
            "sequenceExhaustions": self.sequence_exhaustions.to_string(),
            "sequenceContentions": self.sequence_contentions.to_string(),
            "kernelStackFailures": self.kernel_stack_failures.to_string(),
            "userStackFailures": self.user_stack_failures.to_string(),
            "signalFailures": self.signal_failures.to_string(),
            "ringReserveFailures": self.ring_reserve_failures.to_string(),
            "targetNamespaceFailures": self.target_namespace_failures.to_string(),
        })
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
    fn json(&self) -> Value {
        json!({
            "receivedObservations": self.received_observations.to_string(),
            "writtenObservations": self.written_observations.to_string(),
            "symbolizationFailures": self.symbolization_failures.to_string(),
            "writeFailures": self.write_failures.to_string(),
            "pollFailures": self.poll_failures.to_string(),
            "drainTimedOut": self.drain_timed_out.to_string(),
        })
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
    pub(crate) handle: u64,
    pub(crate) response: Value,
}

enum Command {
    Enable(EnableConfig, Sender<Result<Value, String>>),
    Stop(Duration, Sender<Result<Value, String>>),
    Close,
}

struct RegistryEntry {
    sender: Sender<Command>,
    join: Option<JoinHandle<()>>,
    close_requested: bool,
    close_result: Arc<Mutex<Option<Result<Value, String>>>>,
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

pub(crate) fn parse_prepare(json: &str) -> Result<PrepareConfig> {
    let config: PrepareConfig = serde_json::from_str(json).context("invalid prepare JSON")?;
    if config.target_pid == 0 {
        bail!("targetPid must be nonzero");
    }
    if config.output_path.as_os_str().is_empty() {
        bail!("outputPath must be nonempty");
    }
    config.sampling.validate()?;
    Ok(config)
}

pub(crate) fn parse_enable(json: &str) -> Result<EnableConfig> {
    let config: EnableConfig = serde_json::from_str(json).context("invalid enable JSON")?;
    if config.session_id.len() != 36
        || config.session_id.as_bytes().get(8) != Some(&b'-')
        || config.session_id.as_bytes().get(13) != Some(&b'-')
        || config.session_id.as_bytes().get(18) != Some(&b'-')
        || config.session_id.as_bytes().get(23) != Some(&b'-')
        || !config
            .session_id
            .bytes()
            .all(|byte| byte == b'-' || byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        bail!("sessionId must be a canonical lowercase UUID");
    }
    if config.capture_epoch == 0
        || config.signal <= 0
        || config.signal > libc::SIGRTMAX()
        || config.signal == libc::SIGKILL
        || config.signal == libc::SIGSTOP
        // Linux kernel RT signals below libc's runtime minimum are reserved by libc.
        || (32..libc::SIGRTMIN()).contains(&config.signal)
    {
        bail!("captureEpoch and signal must be valid nonzero values");
    }
    if config
        .signal_delivery
        .as_deref()
        .is_some_and(|delivery| delivery != signal_delivery(config.signal))
    {
        bail!("signalDelivery does not match the selected signal category");
    }
    Ok(config)
}

fn signal_delivery(signal: i32) -> &'static str {
    if signal >= libc::SIGRTMIN() && signal <= libc::SIGRTMAX() {
        "queued"
    } else {
        "coalescing"
    }
}

fn signal_environment_snapshot(
    identity: &PreparedIdentity,
    signal: i32,
    phase: &'static str,
    outer_deadline: Option<Instant>,
) -> Value {
    let snapshot_started = Instant::now();
    let budget = outer_deadline.map_or(SIGNAL_DIAGNOSTIC_BUDGET, |outer| {
        (outer.saturating_duration_since(snapshot_started) / 4).min(SIGNAL_DIAGNOSTIC_BUDGET)
    });
    let deadline = snapshot_started + budget;
    let (observed_ns, clock_unavailable_reason) = match monotonic_ns() {
        Ok(value) => (Some(value.to_string()), None),
        Err(error) => (None, Some(diagnostic_reason(&error))),
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
    let time_budget_exceeded = audit["timeBudgetExceeded"].as_bool().unwrap_or(false);
    json!({
        "snapshotPhase": phase,
        "observedMonotonicNanos": observed_ns,
        "clockUnavailableReason": clock_unavailable_reason,
        "selectedSignal": signal,
        "signalClass": if signal_delivery(signal) == "queued" { "realtime" } else { "standard" },
        "signalDelivery": signal_delivery(signal),
        "runtimeRealtimeMin": libc::SIGRTMIN(),
        "runtimeRealtimeMax": libc::SIGRTMAX(),
        "timeBudgetMillis": budget.as_millis(),
        "maximumTimeBudgetMillis": SIGNAL_DIAGNOSTIC_BUDGET.as_millis(),
        "timeBudgetExceeded": time_budget_exceeded,
        "targetPidfdAliveBefore": alive_before,
        "targetPidfdAliveAfter": alive_after,
        "rlimitSigpending": rlimit,
        "sigQ": sigq,
        "threadMaskAudit": audit,
    })
}

fn unavailable_signal_diagnostics(
    identity: &PreparedIdentity,
    signal: i32,
    reason: &str,
    timed_out: bool,
) -> (Value, Value, Value) {
    (
        json!({
            "status": "unavailable",
            "soft": Value::Null,
            "hard": Value::Null,
            "unavailableReason": reason,
            "scope": "target process; queued signals accounted per real user",
        }),
        unavailable_sigq(reason, None),
        unavailable_thread_mask_audit(identity, signal, reason, timed_out),
    )
}

fn sigpending_limit_snapshot(target_pid: u32) -> Value {
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
        return json!({
            "status": "unavailable",
            "soft": Value::Null,
            "hard": Value::Null,
            "unavailableReason": diagnostic_reason(&std::io::Error::last_os_error()),
            "scope": "target process; queued signals accounted per real user",
        });
    }
    let limit = unsafe { limit.assume_init() };
    json!({
        "status": "ok",
        "soft": rlimit_json(limit.rlim_cur),
        "hard": rlimit_json(limit.rlim_max),
        "unavailableReason": Value::Null,
        "scope": "target process; queued signals accounted per real user",
    })
}

fn rlimit_json(value: libc::rlim_t) -> String {
    if value == libc::RLIM_INFINITY {
        "unlimited".to_string()
    } else {
        value.to_string()
    }
}

fn sigq_snapshot(target_pid: u32, deadline: Instant) -> Value {
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
        return json!({
            "status": "unavailable",
            "used": Value::Null,
            "limit": Value::Null,
            "observedHeadroom": Value::Null,
            "overLimit": Value::Null,
            "unavailableReason": "invalid SigQ field in target status",
            "scope": "shared real-user queued-signal accounting",
            "capacityGuarantee": false,
            "statusFileTruncated": status.truncated,
        });
    };
    let parsed = used.parse::<u64>().ok().zip(limit.parse::<u64>().ok());
    let Some((used, limit)) = parsed else {
        return json!({
            "status": "unavailable",
            "used": Value::Null,
            "limit": Value::Null,
            "observedHeadroom": Value::Null,
            "overLimit": Value::Null,
            "unavailableReason": "non-decimal SigQ field in target status",
            "scope": "shared real-user queued-signal accounting",
            "capacityGuarantee": false,
            "statusFileTruncated": status.truncated,
        });
    };
    json!({
        "status": "ok",
        "used": used.to_string(),
        "limit": limit.to_string(),
        "observedHeadroom": limit.saturating_sub(used).to_string(),
        "overLimit": used > limit,
        "unavailableReason": Value::Null,
        "scope": "shared real-user queued-signal accounting",
        "capacityGuarantee": false,
        "statusFileTruncated": status.truncated,
    })
}

fn unavailable_sigq(reason: &str, status_file_truncated: Option<bool>) -> Value {
    json!({
        "status": "unavailable",
        "used": Value::Null,
        "limit": Value::Null,
        "observedHeadroom": Value::Null,
        "overLimit": Value::Null,
        "unavailableReason": reason,
        "scope": "shared real-user queued-signal accounting",
        "capacityGuarantee": false,
        "statusFileTruncated": status_file_truncated,
    })
}

fn thread_mask_audit(identity: &PreparedIdentity, signal: i32, deadline: Instant) -> Value {
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
        "partial"
    } else {
        "ok"
    };
    json!({
        "status": status,
        "selectedSignal": signal,
        "maskField": "SigBlk",
        "tidNamespace": "collectorProcfs",
        "targetPidArgument": identity.target_pid,
        "targetPidNamespaceInode": identity.pid_namespace_inode.to_string(),
        "maxAuditedThreads": MAX_AUDITED_THREADS,
        "auditedThreads": audited.to_string(),
        "blockedThreads": blocked.to_string(),
        "unblockedThreads": unblocked.to_string(),
        "unknownThreads": unknown.to_string(),
        "enumerationFailures": enumeration_failures.to_string(),
        "enumerationTruncated": truncated,
        "timeBudgetExceeded": timed_out,
        "statusFilesTruncated": status_files_truncated.to_string(),
        "unauditedThreadsKnownMinimum": if truncated || enumeration_failures != 0 {
            Value::Null
        } else {
            json!("0")
        },
        "blockedTidExamples": blocked_examples,
        "blockedExamplesTruncated": blocked as usize > MAX_TID_EXAMPLES,
        "unknownExamples": unknown_examples,
        "unknownExamplesTruncated": (unknown + enumeration_failures) as usize > MAX_TID_EXAMPLES,
        "unavailableReason": Value::Null,
        "remoteMasksModified": false,
        "consistency": "bestEffortNonAtomic",
    })
}

fn unavailable_thread_mask_audit(
    identity: &PreparedIdentity,
    signal: i32,
    reason: &str,
    timed_out: bool,
) -> Value {
    json!({
        "status": "unavailable",
        "selectedSignal": signal,
        "maskField": "SigBlk",
        "tidNamespace": "collectorProcfs",
        "targetPidArgument": identity.target_pid,
        "targetPidNamespaceInode": identity.pid_namespace_inode.to_string(),
        "maxAuditedThreads": MAX_AUDITED_THREADS,
        "auditedThreads": "0",
        "blockedThreads": "0",
        "unblockedThreads": "0",
        "unknownThreads": "0",
        "enumerationFailures": "0",
        "enumerationTruncated": false,
        "timeBudgetExceeded": timed_out,
        "statusFilesTruncated": "0",
        "unauditedThreadsKnownMinimum": Value::Null,
        "blockedTidExamples": [],
        "blockedExamplesTruncated": false,
        "unknownExamples": [],
        "unknownExamplesTruncated": false,
        "unavailableReason": reason,
        "remoteMasksModified": false,
        "consistency": "bestEffortNonAtomic",
    })
}

struct ProcStatus {
    text: String,
    truncated: bool,
}

fn read_proc_status(path: &str, deadline: Instant) -> Result<ProcStatus> {
    if Instant::now() >= deadline {
        bail!("signal-environment time budget exhausted before reading {path}");
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
    examples: &mut Vec<Value>,
    tid: Option<u32>,
    error: &dyn std::fmt::Display,
) {
    push_unknown_message(examples, tid, &diagnostic_reason(error));
}

fn push_unknown_message(examples: &mut Vec<Value>, tid: Option<u32>, reason: &str) {
    if examples.len() < MAX_TID_EXAMPLES {
        examples.push(json!({"tid": tid, "reason": diagnostic_reason(&reason)}));
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
                Ok(Err(error)) => Some(format!("{error:#}")),
                Err(_) => Some("collector worker panicked".to_string()),
            };
            if let Some(message) = failure {
                let _ = ready_tx.send(Err(message.clone()));
                let mut result = worker_close_result.lock().unwrap();
                if result.is_none() {
                    *result = Some(Err(message));
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
        Err(message) => {
            join.join()
                .map_err(|_| anyhow!("collector worker panicked after prepare failure"))?;
            return Err(anyhow!(message));
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
    let response = control_success(json!({
        "handle": format!("{handle:016x}"),
        "state": "prepared",
        "sourcePath": output_path,
        "targetPid": prepared.target_pid,
        "hostTgid": prepared.host_tgid,
        "sampling": prepared.sampling.json(),
        "verifiedIdentity": {
            "registrationToken": format!("{:016x}", prepared.registration_token),
            "processGenerationNs": prepared.process_generation_ns.to_string(),
            "pidNamespaceDevice": prepared.pid_namespace_device.to_string(),
            "pidNamespaceInode": prepared.pid_namespace_inode.to_string(),
            "timeNamespaceInode": prepared.time_namespace_inode.to_string(),
            "clockVerified": true,
            "monotonicOffsetNanos": "0"
        }
    }));
    Ok(PreparedCollector { handle, response })
}

#[derive(Clone)]
struct PreparedReply {
    target_pid: u32,
    host_tgid: u32,
    sampling: SamplingConfig,
    registration_token: u64,
    pid_namespace_device: u64,
    pid_namespace_inode: u64,
    time_namespace_inode: u64,
    process_generation_ns: u64,
}

pub(crate) fn enable(handle: u64, config: EnableConfig) -> Result<Value> {
    request(handle, |sender| {
        let (tx, rx) = mpsc::channel();
        sender
            .send(Command::Enable(config, tx))
            .map_err(|_| anyhow!("collector worker stopped"))?;
        rx.recv_timeout(Duration::from_secs(10))
            .context("collector enable timed out")?
            .map_err(|message| anyhow!(message))
    })
}

pub(crate) fn stop(handle: u64, timeout: Duration) -> Result<Value> {
    request(handle, |sender| {
        let (tx, rx) = mpsc::channel();
        sender
            .send(Command::Stop(timeout, tx))
            .map_err(|_| anyhow!("collector worker stopped"))?;
        rx.recv_timeout(timeout.saturating_add(Duration::from_secs(1)))
            .context("collector stop timed out")?
            .map_err(|message| anyhow!(message))
    })
}

pub(crate) fn close(handle: u64) -> Result<Value> {
    if closed_handles().lock().unwrap().contains(&handle) {
        return Ok(control_success(
            json!({"state":"closed","handle":format!("{handle:016x}"),"idempotent":true}),
        ));
    }
    let (result_slot, deadline) = {
        let mut entries = registry().lock().unwrap();
        let entry = entries
            .get_mut(&handle)
            .ok_or_else(|| anyhow!("invalid collector handle"))?;
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
                return Ok(control_success(
                    json!({"state":"closed","handle":format!("{handle:016x}"),"idempotent":true}),
                ));
            }
        }
        if Instant::now() >= deadline {
            bail!("close_timeout; collector ownership retained");
        }
        thread::sleep(Duration::from_millis(5));
    };
    let join_result = join
        .join()
        .map_err(|_| anyhow!("collector worker panicked"));
    let response = result_slot
        .lock()
        .unwrap()
        .clone()
        .unwrap()
        .map_err(|message| anyhow!(message));
    closed_handles().lock().unwrap().insert(handle);
    registry().lock().unwrap().remove(&handle);
    join_result?;
    response
}

fn request<T>(handle: u64, operation: impl FnOnce(Sender<Command>) -> Result<T>) -> Result<T> {
    let sender = registry()
        .lock()
        .unwrap()
        .get(&handle)
        .map(|entry| entry.sender.clone())
        .ok_or_else(|| anyhow!("invalid collector handle"))?;
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
    fs::metadata(format!("/proc/{target_pid}"))
        .with_context(|| format!("target {target_pid} is not visible"))?;
    let self_time = fs::metadata("/proc/self/ns/time")?.ino();
    let target_time = fs::metadata(format!("/proc/{target_pid}/ns/time"))?.ino();
    let target_pid_namespace_before =
        fs::metadata(format!("/proc/{target_pid}/ns/pid")).context("read target PID namespace")?;
    if self_time != target_time {
        bail!("collector and target use different time namespaces");
    }
    let monotonic_offset = monotonic_time_namespace_offset()?;
    if monotonic_offset != 0 {
        bail!("unsupported nonzero monotonic time namespace offset: {monotonic_offset}ns");
    }
    let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, target_pid, 0) } as i32;
    if fd < 0 {
        return Err(std::io::Error::last_os_error()).context("pidfd_open target group leader");
    }
    let pidfd = unsafe { OwnedFd::from_raw_fd(fd) };
    if pidfd_exited(&pidfd) {
        bail!("target_exited while verifying PID namespace");
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
    ready: mpsc::SyncSender<std::result::Result<PreparedReply, String>>,
    close_result: Arc<Mutex<Option<Result<Value, String>>>>,
    #[cfg(test)] test_control: Option<PrepareTestControl>,
) -> Result<()> {
    let file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&config.output_path)
        .with_context(|| format!("create source artifact {}", config.output_path.display()))?;
    // Rows with symbolized stacks run to a few kilobytes; a large buffer keeps a drain batch to a
    // handful of write syscalls instead of one per row.
    let mut writer = BufWriter::with_capacity(OUTPUT_BUFFER_BYTES, file);

    let mut object = MaybeUninit::uninit();
    let open = JonoffcpuCookieSkelBuilder::default()
        .open(&mut object)
        .context("open scheduler-exit BPF skeleton")?;
    let mut skel = open.load().context("load scheduler-exit BPF object")?;
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
        .context("seed exact target task storage")?;

    #[cfg(test)]
    let first_attach_name = if test_control
        .as_ref()
        .is_some_and(|control| control.fault == PrepareFault::FirstAttach)
    {
        "jonoffcpu_intentionally_missing_sched_switch"
    } else {
        "sched_switch"
    };
    #[cfg(not(test))]
    let first_attach_name = "sched_switch";
    let switch_out = skel
        .progs
        .record_switch_out
        .attach_tracepoint(TracepointCategory::Sched, first_attach_name)
        .context("attach sched_switch recorder")?;

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
    let switch_in = skel
        .progs
        .capture_switch_in
        .attach()
        .context("attach sched_exit_tp completion hook")?;

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
        .context("register observation ring callback")?;
    let ring = ring_builder.build().context("build observation ring")?;

    let reply = PreparedReply {
        target_pid: identity.target_pid,
        host_tgid: identity.host_tgid,
        sampling: config.sampling.clone(),
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
            bail!("target_exited before kernel identity discovery");
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
    close_result: Arc<Mutex<Option<Result<Value, String>>>>,
) -> Result<()> {
    let mut capture: Option<CaptureState> = None;
    let mut stopped = false;
    let mut terminal_stop: Option<Value> = None;
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
                    Err(anyhow!("collector capture already stopped"))
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
                let _ = response.send(result.map_err(|error| format!("{error:#}")));
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
                let _ = response.send(result.map_err(|error| format!("{error:#}")));
            }
            Command::Close => {
                let result = if let Some(value) = &terminal_stop {
                    Ok(value.clone())
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
                } else {
                    Ok(control_success(json!({"state":"closed"})))
                };
                // Explicit drop order: disable/detach happens in stop; the
                // links still remain valid during a prepared-only close.
                skel.maps.bss_data.as_deref_mut().unwrap().enabled = 0;
                switch_out.take();
                switch_in.take();
                *close_result.lock().unwrap() = Some(
                    result
                        .map(|mut value| {
                            value["state"] = json!("closed");
                            value
                        })
                        .map_err(|error| format!("{error:#}")),
                );
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
    post_detach_signal_environment: Option<Value>,
    end_wall_clock_calibration: Option<WallClockCalibration>,
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
    response: Option<Value>,
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

    fn begin(&mut self, response: Value, automatic: bool) -> Result<()> {
        if self.started() {
            bail!("terminal source publication already started");
        }
        self.response = Some(response);
        self.automatic = automatic;
        self.stage = TerminalPublicationStage::AppendStarted;
        Ok(())
    }

    fn advance(&mut self, sink: &mut impl TerminalSink) -> Result<Value> {
        loop {
            match self.stage {
                TerminalPublicationStage::Open => bail!("terminal source publication not started"),
                TerminalPublicationStage::AppendStarted => {
                    self.append_attempts += 1;
                    let end = self.response.as_ref().unwrap()["captureEnd"].clone();
                    if let Err(error) = sink.append_terminal(&end) {
                        self.remember_error("append", &error);
                        self.stage = TerminalPublicationStage::Failed;
                        return Err(anyhow!(self.stable_error().to_owned()));
                    }
                    self.stage = TerminalPublicationStage::RowWritten;
                }
                TerminalPublicationStage::RowWritten => {
                    self.flush_attempts += 1;
                    if let Err(error) = sink.flush_terminal() {
                        self.remember_error("flush", &error);
                        return Err(anyhow!(self.stable_error().to_owned()));
                    }
                    self.stage = TerminalPublicationStage::Flushed;
                }
                TerminalPublicationStage::Flushed => {
                    self.sync_attempts += 1;
                    if let Err(error) = sink.sync_terminal() {
                        self.remember_error("sync", &error);
                        return Err(anyhow!(self.stable_error().to_owned()));
                    }
                    self.stage = TerminalPublicationStage::Durable;
                }
                TerminalPublicationStage::Durable => return Ok(self.durable_response()),
                TerminalPublicationStage::Failed => {
                    return Err(anyhow!(self.stable_error().to_owned()));
                }
            }
        }
    }

    fn remember_error(&mut self, operation: &str, error: &anyhow::Error) {
        if self.first_error.is_none() {
            let response = self.response.as_ref().unwrap();
            let state = response["state"].as_str().unwrap_or("unknown");
            let reason = response["captureEnd"]["incompleteReason"]
                .as_str()
                .unwrap_or("none");
            self.first_error = Some(format!(
                "terminal source {operation} failed after frozen captureEnd \
                 (capture state={state}, incompleteReason={reason}); \
                 append will not be retried: {error:#}"
            ));
        }
    }

    fn stable_error(&self) -> &str {
        self.first_error
            .as_deref()
            .unwrap_or("terminal source publication failed")
    }

    fn durable_response(&self) -> Value {
        let mut response = self.response.as_ref().unwrap().clone();
        response["terminalPublication"] = json!({
            "state": "durable",
            "automatic": self.automatic,
            "appendAttempts": self.append_attempts,
            "flushAttempts": self.flush_attempts,
            "syncAttempts": self.sync_attempts,
            "firstError": self.first_error,
        });
        response
    }
}

trait TerminalSink {
    fn append_terminal(&mut self, value: &Value) -> Result<()>;
    fn flush_terminal(&mut self) -> Result<()>;
    fn sync_terminal(&mut self) -> Result<()>;
}

impl TerminalSink for BufWriter<File> {
    fn append_terminal(&mut self, value: &Value) -> Result<()> {
        write_row(self, value)
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
    fn json(&self) -> Value {
        json!({
            "schemaVersion": 1,
            "method": "clock_gettime-bracket-v1",
            "sampleCount": self.sample_count,
            "selectedSampleIndex": self.selected_sample_index,
            "monotonicClock": "CLOCK_MONOTONIC",
            "wallClock": "CLOCK_REALTIME",
            "monotonicBeforeNanos": self.monotonic_before_ns.to_string(),
            "realtimeNanos": self.realtime_ns.to_string(),
            "monotonicAfterNanos": self.monotonic_after_ns.to_string(),
            "monotonicMidpointNanos": self.monotonic_midpoint_ns.to_string(),
            "realtimeMinusMonotonicNanos": self.realtime_minus_monotonic_ns.to_string(),
            "bracketWidthNanos": self.bracket_width_ns.to_string(),
            "midpointUncertaintyNanos": self.midpoint_uncertainty_ns.to_string(),
            "uncertaintySemantics": "maximum absolute midpoint offset error from unknown realtime-read position within selected monotonic bracket; excludes clock adjustments",
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
    mut enable: EnableConfig,
    skel: &mut crate::bpf_sched_exit::JonoffcpuCookieSkel<'_>,
    writer: &mut BufWriter<File>,
    capture: &mut Option<CaptureState>,
) -> Result<Value> {
    if capture.is_some() {
        bail!("collector is already enabled");
    }
    if enable
        .sampling
        .as_ref()
        .is_some_and(|value| *value != prepare.sampling)
    {
        bail!("sampling differs from prepared policy");
    }
    enable.sampling = Some(prepare.sampling.clone());
    reset_stats(&skel.maps.stats)?;
    let wall_clock_calibration = capture_wall_clock_calibration()?;
    let started_ns = monotonic_ns()?;
    let signal_environment =
        signal_environment_snapshot(identity, enable.signal, "beforeEnable", None);
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
        match prepare.sampling.admission {
            Admission::Uniform {
                probability_threshold,
                ..
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
                    .context("recordAllAboveMicros overflows nanos")?;
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
    let start = json!({
        "schemaVersion": 1,
        "recordType": "captureStart",
        "sourceId": SOURCE_ID,
        "sessionId": enable.session_id,
        "captureEpoch": enable.capture_epoch,
        "signal": enable.signal,
        "signalDelivery": signal_delivery(enable.signal),
        "hostTgid": identity.host_tgid,
        "targetPid": identity.target_pid,
        "registrationToken": format!("{:016x}", identity.registration_token),
        "processGenerationNs": identity.process_generation_ns.to_string(),
        "pidNamespaceDevice": identity.pid_namespace_device.to_string(),
        "pidNamespaceInode": identity.pid_namespace_inode.to_string(),
        "startedMonotonicNanos": started_ns.to_string(),
        "sampling": prepare.sampling.json(),
        "loader": "libbpf-rs/libbpf-cargo 0.27.1 (libbpf 1.7.0)",
        "hook": "tp_btf/sched_exit_tp",
        "switchOutHook": "sched/sched_switch",
        "timeNamespaceInode": identity.time_namespace_inode.to_string(),
        "wallClockCalibration": wall_clock_calibration.json(),
        "signalEnvironment": signal_environment.clone(),
    });
    write_row(writer, &start)?;
    writer.flush().context("flush captureStart")?;
    *capture = Some(CaptureState {
        config: enable.clone(),
        started_ns,
        userspace: UserStats::default(),
        kernel_symbols: KernelSymbols::load(),
        user_maps: UserMap::load(reply.target_pid),
        post_detach_signal_environment: None,
        end_wall_clock_calibration: None,
    });
    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 1;
    Ok(control_success(json!({
        "state":"enabled",
        "sessionId": enable.session_id,
        "captureEpoch": enable.capture_epoch,
        "signal": enable.signal,
        "signalDelivery": signal_delivery(enable.signal),
        "sourcePath": prepare.output_path,
        "targetPid": reply.target_pid,
        "hostTgid": reply.host_tgid,
        "sampling": reply.sampling.json(),
        "signalEnvironment": signal_environment,
        "verifiedIdentity": {
            "registrationToken": format!("{:016x}", reply.registration_token),
            "processGenerationNs": reply.process_generation_ns.to_string(),
            "pidNamespaceDevice": reply.pid_namespace_device.to_string(),
            "pidNamespaceInode": reply.pid_namespace_inode.to_string(),
            "timeNamespaceInode": reply.time_namespace_inode.to_string(),
            "clockVerified": true,
            "monotonicOffsetNanos": "0"
        }
    })))
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
) -> Result<Value> {
    // Once terminal publication starts, the captureEnd object is frozen and
    // the capture is no longer active. A retry may advance flush/fsync only;
    // it must never build or append another terminal row.
    if terminal_publication.started() {
        return terminal_publication.advance(writer);
    }
    let state = capture.as_mut().context("collector is not enabled")?;
    let deadline = Instant::now() + timeout;
    let wall_clock_calibration = match &state.end_wall_clock_calibration {
        Some(calibration) => calibration.clone(),
        None => {
            let calibration = capture_wall_clock_calibration()?;
            state.end_wall_clock_calibration = Some(calibration.clone());
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
                "afterDetach",
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
            bail!("stop_timeout");
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
    let response = control_success(json!({
        "state":if complete { "complete" } else { "incomplete" },
        "sourcePath": output_path,
        "captureEnd": end,
    }));
    terminal_publication.begin(response, automatic)?;
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
    signal_environment: Value,
    wall_clock_calibration: WallClockCalibration,
) -> Value {
    let incomplete_reason = if complete {
        None
    } else if target_exited {
        Some("target_exited")
    } else if capture.userspace.write_failures != 0 {
        Some("source_write_failure")
    } else if kernel.ring_reserve_failures != 0 {
        Some("kernel_ring_overflow")
    } else if kernel.target_namespace_failures != 0 {
        Some("target_namespace_mapping_failure")
    } else {
        Some("ring_poll_failure")
    };
    json!({
        "schemaVersion": 1,
        "recordType": "captureEnd",
        "sourceId": SOURCE_ID,
        "sessionId": capture.config.session_id,
        "captureEpoch": capture.config.capture_epoch,
        "state": if complete { "complete" } else { "incomplete" },
        "startedMonotonicNanos": capture.started_ns.to_string(),
        "stoppedMonotonicNanos": stopped_ns.to_string(),
        "detachedMonotonicNanos": detached_ns.to_string(),
        "drainCompletedMonotonicNanos": drain_completed_ns.to_string(),
        "drainTimedOut": false,
        "targetExited": target_exited,
        "incompleteReason": incomplete_reason,
        "wallClockCalibration": wall_clock_calibration.json(),
        "signalEnvironment": signal_environment,
        "counters": {
            "kernel": kernel.json(),
            "userspace": capture.userspace.json(),
        }
    })
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
        let row = observation_row(skel, capture, &event);
        match row.and_then(|row| write_row(writer, &row)) {
            Ok(()) => capture.userspace.written_observations += 1,
            Err(_) => capture.userspace.write_failures += 1,
        }
    }
    if writer.flush().is_err() {
        capture.userspace.write_failures += 1;
    }
}

fn observation_row(
    skel: &crate::bpf_sched_exit::JonoffcpuCookieSkel<'_>,
    capture: &mut CaptureState,
    event: &Observation,
) -> Result<Value> {
    let kernel_stack =
        materialize_stack(&skel.maps.stack_traces, event.kernel_stack_id, |address| {
            capture.kernel_symbols.resolve(address)
        });
    let user_stack = materialize_stack(&skel.maps.stack_traces, event.user_stack_id, |address| {
        resolve_user(address, &capture.user_maps)
    });
    if kernel_stack.is_err() || user_stack.is_err() {
        capture.userspace.symbolization_failures += 1;
    }
    let comm_end = event
        .comm
        .iter()
        .position(|byte| *byte == 0)
        .unwrap_or(event.comm.len());
    Ok(json!({
        "schemaVersion": 1,
        "recordType": "observation",
        "sourceId": SOURCE_ID,
        "sessionId": capture.config.session_id,
        "captureEpoch": event.capture_epoch,
        "correlationId": format!("{:016x}", event.correlation_id),
        "hostTgid": event.host_tgid,
        "hostTid": event.host_tid,
        "targetTgid": event.target_tgid,
        "targetTid": event.target_tid,
        "processGenerationNs": event.process_generation_ns.to_string(),
        "threadGenerationNs": event.thread_generation_ns.to_string(),
        "registrationToken": format!("{:016x}", event.registration_token),
        "startMonotonicNanos": event.start_monotonic_ns.to_string(),
        "endMonotonicNanos": event.end_monotonic_ns.to_string(),
        "admissionThreshold": event.admission_threshold,
        "signalResult": event.signal_result,
        "comm": String::from_utf8_lossy(&event.comm[..comm_end]),
        "kernelStack": kernel_stack.unwrap_or_else(|error| stack_error(event.kernel_stack_id, &error)),
        "userStack": user_stack.unwrap_or_else(|error| stack_error(event.user_stack_id, &error)),
    }))
}

fn stack_error(stack_id: i64, error: &anyhow::Error) -> Value {
    json!({
        "status":"error",
        "stackId":stack_id,
        "errorCode":error.to_string(),
        "frames":[],
    })
}

fn materialize_stack(
    map: &impl MapCore,
    stack_id: i64,
    mut resolve: impl FnMut(u64) -> (Option<String>, Option<String>),
) -> Result<Value> {
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
        frames.push(json!({
            "address": format!("{address:016x}"),
            "symbol": symbol,
            "module": module,
        }));
    }
    Ok(json!({
        "status":"ok",
        "stackId":stack_id,
        "errorCode":Value::Null,
        "frames":frames,
    }))
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

fn write_row(writer: &mut BufWriter<File>, value: &Value) -> Result<()> {
    serde_json::to_writer(&mut *writer, value)?;
    writer.write_all(b"\n")?;
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

pub(crate) fn control_success(fields: Value) -> Value {
    let mut base = json!({"ok":true,"schemaVersion":1,"abiVersion":1});
    if let (Some(base), Some(fields)) = (base.as_object_mut(), fields.as_object()) {
        base.extend(fields.clone());
    }
    base
}

pub(crate) fn control_error(code: &str, message: impl Into<String>) -> Value {
    let state = match code {
        "stop_timeout" => "stopping",
        "close_timeout" => "closing",
        _ => "error",
    };
    json!({
        "ok":false,
        "schemaVersion":1,
        "abiVersion":1,
        "state":state,
        "error":{"code":code,"message":message.into()}
    })
}

pub(crate) fn bounded_json(value: &Value) -> Result<String> {
    let encoded = serde_json::to_string(value)?;
    if encoded.len() > MAX_CONTROL_JSON {
        bail!("control response exceeds {MAX_CONTROL_JSON} bytes");
    }
    Ok(encoded)
}

#[cfg(test)]
mod tests {
    use super::*;
    use libbpf_rs::Program;

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
        rows: Vec<Value>,
        append_failures: usize,
        flush_failures: usize,
        sync_failures: usize,
        persistent_sync_failure: bool,
        append_calls: usize,
        flush_calls: usize,
        sync_calls: usize,
    }

    impl TerminalSink for FaultSink {
        fn append_terminal(&mut self, value: &Value) -> Result<()> {
            self.append_calls += 1;
            if self.append_failures != 0 {
                self.append_failures -= 1;
                return Err(std::io::Error::from_raw_os_error(libc::EIO))
                    .context("injected append EIO");
            }
            self.rows.push(value.clone());
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

    fn incomplete_terminal_response() -> Value {
        control_success(json!({
            "state": "incomplete",
            "sourcePath": "/tmp/source.ndjson",
            "captureEnd": {
                "schemaVersion": 1,
                "recordType": "captureEnd",
                "state": "incomplete",
                "targetExited": true,
                "incompleteReason": "target_exited",
            },
        }))
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
                ..
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
            probability: "test".to_string(),
            probability_threshold,
        }
    }

    fn proportional(record_all_above_micros: u64) -> Admission {
        Admission::Proportional {
            record_all_above_micros,
        }
    }

    fn sampling_json(bounds: Value, admission: Value) -> Value {
        let mut sampling = json!({"minOffCpuMicros": null, "maxOffCpuMicros": null});
        sampling
            .as_object_mut()
            .unwrap()
            .extend(bounds.as_object().unwrap().clone());
        sampling["admission"] = admission;
        json!({"targetPid":1,"outputPath":"/tmp/source.ndjson","sampling":sampling})
    }

    #[test]
    fn delivery_policy_must_match_signal_before_enable() {
        let mut value = json!({
            "sessionId":"01234567-89ab-cdef-0123-456789abcdef",
            "captureEpoch":1,
            "signal":libc::SIGRTMIN(),
            "signalDelivery":"queued"
        });
        assert!(parse_enable(&value.to_string()).is_ok());
        value["signalDelivery"] = json!("coalescing");
        assert!(parse_enable(&value.to_string()).is_err());
        value["signal"] = json!(libc::SIGPROF);
        assert!(parse_enable(&value.to_string()).is_ok());
        value["signalDelivery"] = json!("queued");
        assert!(parse_enable(&value.to_string()).is_err());
        value["signalDelivery"] = json!("unknown");
        assert!(parse_enable(&value.to_string()).is_err());
        value.as_object_mut().unwrap().remove("signalDelivery");
        assert!(parse_enable(&value.to_string()).is_ok());
        for reserved in 32..libc::SIGRTMIN() {
            value["signal"] = json!(reserved);
            assert!(parse_enable(&value.to_string()).is_err());
        }
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
        let uniform_one =
            json!({"policy":"uniform","probability":"1","probabilityThreshold":4294967296_u64});
        for bounds in [
            json!({}),
            json!({"minOffCpuMicros":"10"}),
            json!({"maxOffCpuMicros":"20"}),
            json!({"minOffCpuMicros":"10","maxOffCpuMicros":"20"}),
        ] {
            parse_prepare(&sampling_json(bounds.clone(), uniform_one.clone()).to_string()).unwrap();
            parse_prepare(
                &sampling_json(
                    bounds,
                    json!({"policy":"proportional","recordAllAboveMicros":5}),
                )
                .to_string(),
            )
            .unwrap();
        }
        let config = parse_prepare(
            &sampling_json(
                json!({"minOffCpuMicros":10}),
                json!({"policy":"proportional","recordAllAboveMicros":5}),
            )
            .to_string(),
        )
        .unwrap();
        assert_eq!(config.sampling.min_off_cpu_micros, Some(10));
        assert_eq!(config.sampling.admission, proportional(5));
        // The echoed object round-trips unchanged.
        assert_eq!(
            config.sampling.json(),
            json!({"minOffCpuMicros":10,"maxOffCpuMicros":null,
                   "admission":{"policy":"proportional","recordAllAboveMicros":5}})
        );
        for rejected in [
            sampling_json(
                json!({"minOffCpuMicros":"10","maxOffCpuMicros":"10"}),
                uniform_one.clone(),
            ),
            sampling_json(
                json!({"maxOffCpuMicros":u64::MAX.to_string()}),
                uniform_one.clone(),
            ),
            sampling_json(
                json!({}),
                json!({"policy":"uniform","probability":"0","probabilityThreshold":0}),
            ),
            sampling_json(
                json!({}),
                json!({"policy":"uniform","probability":"2","probabilityThreshold":4294967297_u64}),
            ),
            sampling_json(
                json!({}),
                json!({"policy":"uniform","probabilityThreshold":1}),
            ),
            sampling_json(
                json!({}),
                json!({"policy":"proportional","recordAllAboveMicros":0}),
            ),
            sampling_json(
                json!({}),
                json!({"policy":"proportional","recordAllAboveMicros":u64::MAX}),
            ),
            sampling_json(
                json!({}),
                json!({"policy":"proportional","recordAllAboveMicros":5,"probability":"1"}),
            ),
            sampling_json(json!({}), json!({"policy":"none"})),
            sampling_json(json!({"sampleThreshold":1}), uniform_one.clone()),
            json!({"targetPid":1,"outputPath":"/tmp/source.ndjson"}),
        ] {
            assert!(
                parse_prepare(&rejected.to_string()).is_err(),
                "accepted {rejected}"
            );
        }
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
    fn wall_clock_calibration_serializes_exact_decimal_nanos() {
        let calibration = select_wall_clock_calibration(&[ClockBracket {
            monotonic_before_ns: u64::MAX - 5,
            realtime_ns: 7,
            monotonic_after_ns: u64::MAX,
        }])
        .unwrap();
        assert_eq!(
            calibration.json(),
            json!({
                "schemaVersion": 1,
                "method": "clock_gettime-bracket-v1",
                "sampleCount": 1,
                "selectedSampleIndex": 0,
                "monotonicClock": "CLOCK_MONOTONIC",
                "wallClock": "CLOCK_REALTIME",
                "monotonicBeforeNanos": (u64::MAX - 5).to_string(),
                "realtimeNanos": "7",
                "monotonicAfterNanos": u64::MAX.to_string(),
                "monotonicMidpointNanos": (u64::MAX - 3).to_string(),
                "realtimeMinusMonotonicNanos": (i128::from(7_u64) - i128::from(u64::MAX - 3)).to_string(),
                "bracketWidthNanos": "5",
                "midpointUncertaintyNanos": "3",
                "uncertaintySemantics": "maximum absolute midpoint offset error from unknown realtime-read position within selected monotonic bracket; excludes clock adjustments",
            })
        );
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
        let response = incomplete_terminal_response();
        let expected_end = response["captureEnd"].clone();
        let mut publication = TerminalPublication::default();
        publication.begin(response, true).unwrap();
        let mut sink = FaultSink {
            sync_failures: 1,
            ..FaultSink::default()
        };

        let first_error = publication.advance(&mut sink).unwrap_err().to_string();
        assert!(first_error.contains("capture state=incomplete"));
        assert!(first_error.contains("incompleteReason=target_exited"));
        assert_eq!(publication.stage, TerminalPublicationStage::Flushed);
        assert_eq!(sink.rows, vec![expected_end]);
        assert_eq!(
            (sink.append_calls, sink.flush_calls, sink.sync_calls),
            (1, 1, 1)
        );

        let result = publication.advance(&mut sink).unwrap();
        assert_eq!(result["state"], "incomplete");
        assert_eq!(result["captureEnd"], sink.rows[0]);
        assert_eq!(result["terminalPublication"]["state"], "durable");
        assert_eq!(result["terminalPublication"]["automatic"], true);
        assert_eq!(result["terminalPublication"]["appendAttempts"], 1);
        assert_eq!(result["terminalPublication"]["flushAttempts"], 1);
        assert_eq!(result["terminalPublication"]["syncAttempts"], 2);
        assert_eq!(result["terminalPublication"]["firstError"], first_error);
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
            .begin(incomplete_terminal_response(), true)
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
            .begin(incomplete_terminal_response(), true)
            .unwrap();
        let mut sink = FaultSink {
            flush_failures: 1,
            ..FaultSink::default()
        };

        publication.advance(&mut sink).unwrap_err();
        assert_eq!(publication.stage, TerminalPublicationStage::RowWritten);
        let result = publication.advance(&mut sink).unwrap();
        assert_eq!(result["terminalPublication"]["flushAttempts"], 2);
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
            .begin(incomplete_terminal_response(), true)
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
        let dead_error = prepare(PrepareConfig {
            target_pid: dead_pid as u32,
            output_path: dead_path.clone(),
            sampling: privileged_sampling(),
        })
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
            assert_eq!(fs::metadata(&result.path).unwrap().len(), 0);
            assert_source_has_no_control_rows(&result.path);
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
                "artifactBytes": 0,
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
        let source = fs::read_to_string(&normal_path).unwrap();
        assert!(source.lines().any(|line| line.contains("\"captureStart\"")));
        assert!(source.lines().any(|line| line.contains("\"captureEnd\"")));
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
        let old_handle_error = enable(first.handle, privileged_enable(0x8000_1401))
            .unwrap_err()
            .to_string();
        assert!(old_handle_error.contains("already stopped"));
        close(first.handle).unwrap();
        let closed_handle_error = enable(first.handle, privileged_enable(0x8000_1401))
            .unwrap_err()
            .to_string();
        assert!(closed_handle_error.contains("invalid collector handle"));
        let old_source_error = prepare(privileged_prepare(first_path.clone()))
            .err()
            .expect("old source path unexpectedly reopened")
            .to_string();
        assert!(old_source_error.contains("create source artifact"));

        let fresh_path = privileged_output("t14-fresh");
        let fresh = prepare(privileged_prepare(fresh_path.clone())).unwrap();
        enable(fresh.handle, privileged_enable(0x8000_1402)).unwrap();
        thread::sleep(Duration::from_millis(20));
        stop(fresh.handle, Duration::from_secs(5)).unwrap();
        close(fresh.handle).unwrap();
        let first_source = fs::read_to_string(&first_path).unwrap();
        let fresh_source = fs::read_to_string(&fresh_path).unwrap();
        assert!(first_source.contains("\"captureEpoch\":2147488769"));
        assert!(fresh_source.contains("\"captureEpoch\":2147488770"));
        assert!(
            fresh_source.contains("\"sequence\":\"1\"") || fresh_source.contains("\"captureEnd\"")
        );
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

    fn privileged_prepare(path: PathBuf) -> PrepareConfig {
        PrepareConfig {
            target_pid: unsafe { libc::getpid() as u32 },
            output_path: path,
            sampling: privileged_sampling(),
        }
    }

    fn privileged_sampling() -> SamplingConfig {
        SamplingConfig {
            min_off_cpu_micros: None,
            max_off_cpu_micros: None,
            admission: uniform(1_u64 << 32),
        }
    }

    fn privileged_enable(epoch: u32) -> EnableConfig {
        EnableConfig {
            session_id: "12345678-1234-4abc-8def-123456789abc".to_string(),
            capture_epoch: epoch,
            signal: libc::SIGRTMIN() + 5,
            signal_delivery: Some("queued".to_string()),
            sampling: None,
        }
    }

    fn privileged_output(label: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "jonoffcpu-{label}-{}-{}.ndjson",
            std::process::id(),
            random_nonzero_u64().unwrap()
        ))
    }

    fn assert_source_has_no_control_rows(path: &Path) {
        let source = fs::read_to_string(path).unwrap();
        assert!(!source.contains("captureStart"));
        assert!(!source.contains("captureEnd"));
        assert!(!source.contains("observation"));
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
