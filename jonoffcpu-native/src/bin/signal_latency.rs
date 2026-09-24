// SPDX-License-Identifier: MIT
use anyhow::{Context, Result, bail};
use jonoffcpu_native::capture::collector_reply::Result as Reply;
use jonoffcpu_native::capture::record::Record;
use jonoffcpu_native::capture::{self, CollectorReply};
use jonoffcpu_native::{
    JonoffcpuResult, call_collector, jonoffcpu_collector_close, jonoffcpu_collector_enable,
    jonoffcpu_collector_prepare, jonoffcpu_collector_stop,
};
use prost::Message;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::mem::size_of;
use std::process::Command;
use std::sync::atomic::{AtomicBool, AtomicPtr, AtomicU64, AtomicUsize, Ordering};
use std::thread;
use std::time::{Duration, Instant};

const CAPACITY: usize = 65_536;
const DEFAULT_SAMPLES: usize = 5_000;
const SLEEP_NS: i64 = 1_000_000;

#[repr(C)]
struct SharedCapture {
    next: AtomicUsize,
    ready: AtomicBool,
    stop: AtomicBool,
    sleeps: AtomicU64,
    unblocked: AtomicBool,
    clock_errors: AtomicU64,
    timestamps: [AtomicU64; CAPACITY],
    cookies: [AtomicU64; CAPACITY],
}

static SHARED: AtomicPtr<SharedCapture> = AtomicPtr::new(std::ptr::null_mut());

extern "C" fn signal_handler(
    _signal: i32,
    info: *mut libc::siginfo_t,
    _context: *mut libc::c_void,
) {
    let shared = SHARED.load(Ordering::Relaxed);
    if shared.is_null() {
        return;
    }
    let mut now = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    // clock_gettime is async-signal-safe. Take the timestamp before touching
    // the capture index or cookie so it represents minimal native entry cost.
    if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut now) } != 0 {
        unsafe { &*shared }
            .clock_errors
            .fetch_add(1, Ordering::Relaxed);
        return;
    }
    let timestamp = (now.tv_sec as u64) * 1_000_000_000 + now.tv_nsec as u64;
    let cookie = if info.is_null() {
        0
    } else {
        unsafe { (*info).si_value().sival_ptr as u64 }
    };
    let capture = unsafe { &*shared };
    let index = capture.next.fetch_add(1, Ordering::Relaxed);
    if index < CAPACITY {
        capture.timestamps[index].store(timestamp, Ordering::Relaxed);
        capture.cookies[index].store(cookie, Ordering::Release);
    }
}

fn main() -> Result<()> {
    let target_samples = std::env::var("JONOFFCPU_LATENCY_SAMPLES")
        .ok()
        .map(|value| value.parse::<usize>())
        .transpose()
        .context("invalid JONOFFCPU_LATENCY_SAMPLES")?
        .unwrap_or(DEFAULT_SAMPLES);
    if target_samples == 0 || target_samples > CAPACITY {
        bail!("sample target must be in 1..={CAPACITY}");
    }
    let signal = env_i32("JONOFFCPU_LATENCY_SIGNAL")?.unwrap_or(libc::SIGPROF);
    if signal <= 0
        || signal > libc::SIGRTMAX()
        || signal == libc::SIGKILL
        || signal == libc::SIGSTOP
    {
        bail!("invalid measurement signal {signal}");
    }
    let blocked_sleeps = env_u64("JONOFFCPU_LATENCY_BLOCKED_SLEEPS")?.unwrap_or(0);
    let pending_limit = env_u64("JONOFFCPU_LATENCY_PENDING_LIMIT")?;
    let max_runtime = Duration::from_secs(env_u64("JONOFFCPU_LATENCY_MAX_SECONDS")?.unwrap_or(30));
    let output = std::env::var("JONOFFCPU_LATENCY_SOURCE")
        .unwrap_or_else(|_| "/tmp/jonoffcpu-native-latency-source.pb".to_string());
    let _ = fs::remove_file(&output);
    let load_before = read_trimmed("/proc/loadavg");
    let shared = allocate_shared()?;
    SHARED.store(shared, Ordering::Relaxed);
    let child = unsafe { libc::fork() };
    if child < 0 {
        return Err(std::io::Error::last_os_error()).context("fork latency target");
    }
    if child == 0 {
        child_main(shared, signal, blocked_sleeps, pending_limit);
    }
    let mut child = ChildGuard { pid: child, shared };
    wait_ready(shared)?;

    let sampling = capture::Sampling {
        reasons: vec![
            capture::OffCpuReason::Blocked as i32,
            capture::OffCpuReason::Runnable as i32,
            capture::OffCpuReason::Preempted as i32,
        ],
        min_off_cpu_micros: None,
        max_off_cpu_micros: None,
        admission: Some(capture::sampling::Admission::Uniform(
            capture::UniformAdmission {
                probability: "1".to_string(),
                probability_threshold: 1 << 32,
            },
        )),
    };
    let time_split = capture::TimeSplit {
        source: capture::TimeSplitSource::SchedInfo as i32,
    };
    let prepare_request = capture::PrepareRequest {
        target_pid: u32::try_from(child.pid).context("child PID is negative")?,
        output_path: output.clone(),
        sampling: Some(sampling.clone()),
        time_split: Some(time_split),
        exclude_calling_thread: false,
    }
    .encode_to_vec();
    let Some(Reply::Prepared(prepared)) = call(|out| unsafe {
        jonoffcpu_collector_prepare(prepare_request.as_ptr(), prepare_request.len(), out)
    })?
    .result
    else {
        bail!("prepare did not return Prepared");
    };
    let handle = prepared.handle;
    let delivery = if signal >= libc::SIGRTMIN() && signal <= libc::SIGRTMAX() {
        capture::SignalDelivery::Queued
    } else {
        capture::SignalDelivery::Coalescing
    };
    let enable_request = capture::EnableRequest {
        session_id: "6c6a9170-0d39-4d89-8a5a-196a3d849ce1".to_string(),
        capture_epoch: 2_147_483_649,
        signal,
        signal_delivery: delivery as i32,
        sampling: Some(sampling),
        time_split: Some(time_split),
    }
    .encode_to_vec();
    call(|out| unsafe {
        jonoffcpu_collector_enable(handle, enable_request.as_ptr(), enable_request.len(), out)
    })?;

    let started = Instant::now();
    while unsafe { &*shared }.next.load(Ordering::Acquire) < target_samples
        && started.elapsed() < max_runtime
    {
        thread::sleep(Duration::from_millis(10));
    }
    let stop = call(|out| unsafe { jonoffcpu_collector_stop(handle, 5_000, out) })?;
    call(|out| unsafe { jonoffcpu_collector_close(handle, out) })?;
    // Allow signal work already accepted before detach to reach the handler.
    thread::sleep(Duration::from_millis(100));
    child.stop_and_wait()?;
    let capture_state = stop.state().as_str_name();
    let Some(Reply::Stopped(stopped)) = stop.result else {
        bail!("stop did not return Stopped");
    };
    let end = stopped
        .capture_end
        .context("stop reply has no captureEnd")?;
    let kernel = end.kernel_counters.unwrap_or_default();
    let userspace = end.userspace_counters.unwrap_or_default();

    let records = capture::decode(&fs::read(&output)?)?;
    let mut source = HashMap::new();
    let mut duplicate_source = 0u64;
    let mut signal_request_failures = 0u64;
    for record in &records {
        let Some(Record::Observation(row)) = &record.record else {
            continue;
        };
        if source
            .insert(row.correlation_id, row.end_monotonic_nanos)
            .is_some()
        {
            duplicate_source += 1;
        }
        if row.signal_result != 0 {
            signal_request_failures += 1;
        }
    }

    let capture = unsafe { &*shared };
    let total_handler_entries = capture.next.load(Ordering::Acquire);
    let stored_handler_entries = total_handler_entries.min(CAPACITY);
    let mut seen_handler = HashSet::new();
    let mut matched_source = HashSet::new();
    let mut latencies = Vec::new();
    let mut duplicate_handler = 0u64;
    let mut orphan_handler = 0u64;
    let mut invalid_order = 0u64;
    let mut zero_cookie = 0u64;
    let mut handler_out_of_order = 0u64;
    let mut previous_sequence = None;
    for index in 0..stored_handler_entries {
        let cookie = capture.cookies[index].load(Ordering::Acquire);
        let timestamp = capture.timestamps[index].load(Ordering::Relaxed);
        zero_cookie += u64::from(cookie == 0);
        let sequence = cookie as u32;
        if previous_sequence.is_some_and(|previous| sequence <= previous) {
            handler_out_of_order += 1;
        }
        previous_sequence = Some(sequence);
        if !seen_handler.insert(cookie) {
            duplicate_handler += 1;
        }
        match source.get(&cookie) {
            Some(end) if timestamp >= *end => {
                latencies.push(timestamp - end);
                matched_source.insert(cookie);
            }
            Some(_) => invalid_order += 1,
            None => orphan_handler += 1,
        }
    }
    latencies.sort_unstable();
    let elapsed_ms = started.elapsed().as_millis();
    let result = json!({
        "benchmark":"native-signal-handler-entry-v1",
        "scope":"scheduler-exit source end through BPF stack capture and signal request to minimal native SA_SIGINFO handler entry; excludes async-profiler and Java stack walking",
        "signal":signal,
        "signalName":signal_name(signal),
        "hook":"tp_btf/sched_exit_tp",
        "switchOutHook":"tp_btf/sched_switch",
        "loader":"libbpf-rs/libbpf-cargo 0.27.1 (libbpf 1.7.0)",
        "targetSleepNanos":SLEEP_NS,
        "blockedSleeps":blocked_sleeps,
        "targetUnblocked":capture.unblocked.load(Ordering::Acquire),
        "targetSleepCount":capture.sleeps.load(Ordering::Acquire),
        "targetRlimitSigpending":pending_limit,
        "requestedHandlerSamples":target_samples,
        "measurementElapsedMillis":elapsed_ms,
        "counts":{
            "selectedIntervals":kernel.selected_intervals,
            "ringReserveFailures":kernel.ring_reserve_failures,
            "signalFailures":kernel.signal_failures,
            "writtenObservations":userspace.written_observations,
            "sourceUnique":source.len(),
            "sourceDuplicate":duplicate_source,
            "signalRequestFailuresInRows":signal_request_failures,
            "handlerEntries":total_handler_entries,
            "handlerStored":stored_handler_entries,
            "handlerBufferOverflow":total_handler_entries.saturating_sub(CAPACITY),
            "handlerClockErrors":capture.clock_errors.load(Ordering::Relaxed),
            "handlerZeroCookie":zero_cookie,
            "handlerDuplicateCookie":duplicate_handler,
            "handlerOutOfOrder":handler_out_of_order,
            "matchedUnique":matched_source.len(),
            "matchedMeasurements":latencies.len(),
            "unmatchedSource":source.len().saturating_sub(matched_source.len()),
            "orphanHandler":orphan_handler,
            "timestampBeforeSourceEnd":invalid_order,
        },
        "latencyNanos":latency_summary(&latencies),
        "context":{
            "kernel":command_output("uname", &["-srvo"]),
            "cpuModel":cpu_model(),
            "logicalCpus":thread::available_parallelism().map(|value| value.get()).unwrap_or(0),
            "loadavgBefore":load_before,
            "loadavgAfter":read_trimmed("/proc/loadavg"),
            "pidNamespace":"private Docker PID namespace; targetPid differs from BPF hostTgid",
            "targetPid":prepared.target_pid,
            "hostTgid":prepared.host_tgid,
        },
        "sourcePath":output,
        "captureState":capture_state,
    });
    println!("{}", serde_json::to_string_pretty(&result)?);
    unsafe { libc::munmap(shared.cast(), size_of::<SharedCapture>()) };
    Ok(())
}

fn allocate_shared() -> Result<*mut SharedCapture> {
    let memory = unsafe {
        libc::mmap(
            std::ptr::null_mut(),
            size_of::<SharedCapture>(),
            libc::PROT_READ | libc::PROT_WRITE,
            libc::MAP_SHARED | libc::MAP_ANONYMOUS,
            -1,
            0,
        )
    };
    if memory == libc::MAP_FAILED {
        return Err(std::io::Error::last_os_error()).context("mmap fixed latency capture");
    }
    Ok(memory.cast())
}

fn child_main(
    shared: *mut SharedCapture,
    signal: i32,
    blocked_sleeps: u64,
    pending_limit: Option<u64>,
) -> ! {
    // Resolve and warm the libc/vDSO clock path before a signal can arrive.
    let mut warm_clock = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    unsafe {
        if libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut warm_clock) != 0 {
            libc::_exit(3);
        }
        if let Some(limit) = pending_limit {
            let mut current: libc::rlimit = std::mem::zeroed();
            if libc::getrlimit(libc::RLIMIT_SIGPENDING, &mut current) != 0 {
                libc::_exit(4);
            }
            current.rlim_cur = limit.min(current.rlim_max);
            if libc::setrlimit(libc::RLIMIT_SIGPENDING, &current) != 0 {
                libc::_exit(5);
            }
        }
        let mut mask: libc::sigset_t = std::mem::zeroed();
        libc::sigemptyset(&mut mask);
        libc::sigaddset(&mut mask, signal);
        if blocked_sleeps != 0
            && libc::pthread_sigmask(libc::SIG_BLOCK, &mask, std::ptr::null_mut()) != 0
        {
            libc::_exit(6);
        }
        let mut action: libc::sigaction = std::mem::zeroed();
        action.sa_flags = libc::SA_SIGINFO;
        action.sa_sigaction = signal_handler as usize;
        libc::sigemptyset(&mut action.sa_mask);
        if libc::sigaction(signal, &action, std::ptr::null_mut()) != 0 {
            libc::_exit(2);
        }
    }
    let capture = unsafe { &*shared };
    capture
        .unblocked
        .store(blocked_sleeps == 0, Ordering::Release);
    capture.ready.store(true, Ordering::Release);
    while !capture.stop.load(Ordering::Acquire) {
        let request = libc::timespec {
            tv_sec: 0,
            tv_nsec: SLEEP_NS,
        };
        unsafe {
            libc::nanosleep(&request, std::ptr::null_mut());
        }
        let sleeps = capture.sleeps.fetch_add(1, Ordering::Relaxed) + 1;
        if blocked_sleeps != 0 && sleeps == blocked_sleeps {
            let mut mask: libc::sigset_t = unsafe { std::mem::zeroed() };
            unsafe {
                libc::sigemptyset(&mut mask);
                libc::sigaddset(&mut mask, signal);
                if libc::pthread_sigmask(libc::SIG_UNBLOCK, &mask, std::ptr::null_mut()) != 0 {
                    libc::_exit(7);
                }
            }
            capture.unblocked.store(true, Ordering::Release);
        }
    }
    unsafe { libc::_exit(0) }
}

struct ChildGuard {
    pid: libc::pid_t,
    shared: *mut SharedCapture,
}

impl ChildGuard {
    fn stop_and_wait(&mut self) -> Result<()> {
        if self.pid <= 0 {
            return Ok(());
        }
        unsafe { &*self.shared }.stop.store(true, Ordering::Release);
        let mut status = 0;
        if unsafe { libc::waitpid(self.pid, &mut status, 0) } < 0 {
            return Err(std::io::Error::last_os_error()).context("wait latency target");
        }
        self.pid = 0;
        if !libc::WIFEXITED(status) || libc::WEXITSTATUS(status) != 0 {
            bail!("latency target exited with status {status}");
        }
        Ok(())
    }
}

impl Drop for ChildGuard {
    fn drop(&mut self) {
        if self.pid > 0 {
            unsafe { &*self.shared }.stop.store(true, Ordering::Release);
            unsafe {
                libc::kill(self.pid, libc::SIGKILL);
                libc::waitpid(self.pid, std::ptr::null_mut(), 0);
            }
        }
    }
}

fn wait_ready(shared: *mut SharedCapture) -> Result<()> {
    let deadline = Instant::now() + Duration::from_secs(2);
    while Instant::now() < deadline {
        if unsafe { &*shared }.ready.load(Ordering::Acquire) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(1));
    }
    bail!("latency target did not install its handler")
}

fn call(operation: impl FnOnce(*mut JonoffcpuResult) -> i32) -> Result<CollectorReply> {
    let (status, reply) = call_collector(operation)?;
    if status != 0 {
        bail!(
            "native call failed ({status}): {}",
            serde_json::to_string(&reply)?
        );
    }
    Ok(reply)
}

fn percentile(sorted: &[u64], percent: usize) -> u64 {
    let index = (sorted.len() * percent).div_ceil(100).saturating_sub(1);
    sorted[index]
}

fn latency_summary(latencies: &[u64]) -> Value {
    if latencies.is_empty() {
        return Value::Null;
    }
    json!({
        "min":latencies[0],
        "p50":percentile(latencies, 50),
        "p90":percentile(latencies, 90),
        "p99":percentile(latencies, 99),
        "max":latencies[latencies.len()-1],
        "mean":(latencies.iter().map(|value| *value as u128).sum::<u128>() / latencies.len() as u128) as u64,
    })
}

fn env_u64(name: &str) -> Result<Option<u64>> {
    std::env::var(name)
        .ok()
        .filter(|value| !value.is_empty())
        .map(|value| {
            value
                .parse::<u64>()
                .with_context(|| format!("invalid {name}"))
        })
        .transpose()
}

fn env_i32(name: &str) -> Result<Option<i32>> {
    std::env::var(name)
        .ok()
        .filter(|value| !value.is_empty())
        .map(|value| {
            value
                .parse::<i32>()
                .with_context(|| format!("invalid {name}"))
        })
        .transpose()
}

fn signal_name(signal: i32) -> String {
    if signal == libc::SIGPROF {
        "SIGPROF".to_string()
    } else if signal >= libc::SIGRTMIN() && signal <= libc::SIGRTMAX() {
        format!("SIGRTMIN+{}", signal - libc::SIGRTMIN())
    } else {
        format!("signal-{signal}")
    }
}

fn read_trimmed(path: &str) -> String {
    fs::read_to_string(path)
        .unwrap_or_else(|error| format!("unavailable: {error}"))
        .trim()
        .to_string()
}

fn command_output(program: &str, arguments: &[&str]) -> String {
    Command::new(program)
        .args(arguments)
        .output()
        .ok()
        .filter(|output| output.status.success())
        .map(|output| String::from_utf8_lossy(&output.stdout).trim().to_string())
        .unwrap_or_else(|| "unavailable".to_string())
}

fn cpu_model() -> String {
    fs::read_to_string("/proc/cpuinfo")
        .ok()
        .and_then(|contents| {
            contents.lines().find_map(|line| {
                let (name, value) = line.split_once(':')?;
                (name.trim() == "model name").then(|| value.trim().to_string())
            })
        })
        .unwrap_or_else(|| "unavailable".to_string())
}
