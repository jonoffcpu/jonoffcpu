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
use serde_json::json;
use std::fs;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Barrier, mpsc};
use std::thread;
use std::time::Duration;

static COOKIE: AtomicU64 = AtomicU64::new(0);

extern "C" fn signal_handler(
    _signal: i32,
    info: *mut libc::siginfo_t,
    _context: *mut libc::c_void,
) {
    if !info.is_null() {
        unsafe {
            COOKIE.store((*info).si_value().sival_ptr as u64, Ordering::Relaxed);
        }
    }
}

fn main() -> Result<()> {
    let signal = std::env::var("JONOFFCPU_SMOKE_SIGNAL")
        .ok()
        .filter(|value| !value.is_empty())
        .map(|value| value.parse::<i32>())
        .transpose()
        .context("invalid JONOFFCPU_SMOKE_SIGNAL")?
        .unwrap_or(libc::SIGPROF);
    install_signal_handler(signal)?;
    let keep_blocked_thread = Arc::new(AtomicBool::new(true));
    let blocked_thread_flag = Arc::clone(&keep_blocked_thread);
    let (blocked_tid_tx, blocked_tid_rx) = mpsc::sync_channel(1);
    let blocked_thread = thread::spawn(move || -> Result<()> {
        block_signal(signal)?;
        blocked_tid_tx
            .send(unsafe { libc::syscall(libc::SYS_gettid) as u32 })
            .map_err(|_| anyhow::anyhow!("blocked-thread TID receiver disconnected"))?;
        while blocked_thread_flag.load(Ordering::Relaxed) {
            thread::sleep(Duration::from_millis(5));
        }
        Ok(())
    });
    let blocked_tid = blocked_tid_rx.recv()?;
    let output = std::env::var("JONOFFCPU_SMOKE_OUTPUT").unwrap_or_else(|_| {
        format!("/tmp/jonoffcpu-native-smoke-{}.pb", unsafe {
            libc::getpid()
        })
    });
    let _ = fs::remove_file(&output);
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
        target_pid: unsafe { libc::getpid() } as u32,
        output_path: output.clone(),
        sampling: Some(sampling.clone()),
        time_split: Some(time_split),
        exclude_calling_thread: false,
    }
    .encode_to_vec();
    let prepared = match call(|out| unsafe {
        jonoffcpu_collector_prepare(prepare_request.as_ptr(), prepare_request.len(), out)
    })?
    .result
    {
        Some(Reply::Prepared(prepared)) => prepared,
        other => bail!("prepare did not return Prepared: {other:?}"),
    };
    let handle = prepared.handle;
    let delivery = if signal >= libc::SIGRTMIN() && signal <= libc::SIGRTMAX() {
        capture::SignalDelivery::Queued
    } else {
        capture::SignalDelivery::Coalescing
    };
    let enable_request = capture::EnableRequest {
        session_id: "12345678-1234-4abc-8def-123456789abc".to_string(),
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
    for _ in 0..20 {
        thread::sleep(Duration::from_millis(2));
        if COOKIE.load(Ordering::Relaxed) != 0 {
            break;
        }
    }
    let stop = call(|out| unsafe { jonoffcpu_collector_stop(handle, 5_000, out) })?;
    let stop_retry = call(|out| unsafe { jonoffcpu_collector_stop(handle, 5_000, out) })?;
    if stop_retry != stop {
        bail!("idempotent stop did not return the cached terminal witness");
    }
    keep_blocked_thread.store(false, Ordering::Relaxed);
    blocked_thread
        .join()
        .map_err(|_| anyhow::anyhow!("blocked signal thread panicked"))??;
    let barrier = Arc::new(Barrier::new(2));
    let mut close_threads = Vec::new();
    for _ in 0..2 {
        let barrier = Arc::clone(&barrier);
        close_threads.push(thread::spawn(move || {
            barrier.wait();
            call(|out| unsafe { jonoffcpu_collector_close(handle, out) })
        }));
    }
    for close_thread in close_threads {
        close_thread
            .join()
            .map_err(|_| anyhow::anyhow!("concurrent close thread panicked"))??;
    }
    call(|out| unsafe { jonoffcpu_collector_close(handle, out) })?;
    drop(
        std::fs::OpenOptions::new()
            .append(true)
            .open(&output)
            .context("source artifact was not appendable after close")?,
    );
    let records = capture::decode(&fs::read(&output)?)?;
    let (Some(Record::CaptureStart(start)), Some(Record::CaptureEnd(end))) = (
        records.first().and_then(|record| record.record.as_ref()),
        records.last().and_then(|record| record.record.as_ref()),
    ) else {
        bail!("source artifact does not have start/end control records");
    };
    for (calibration, environment) in [
        (&start.wall_clock_calibration, &start.signal_environment),
        (&end.wall_clock_calibration, &end.signal_environment),
    ] {
        verify_wall_clock_calibration(
            calibration
                .as_ref()
                .context("control record has no wall-clock calibration")?,
        )?;
        let environment = environment
            .as_ref()
            .context("control record has no signal environment")?;
        let ok = capture::DiagnosticStatus::Ok;
        let audit = environment.thread_mask_audit.clone().unwrap_or_default();
        if environment.selected_signal != signal
            || environment.signal_delivery() != delivery
            || environment
                .rlimit_sigpending
                .as_ref()
                .is_none_or(|limit| limit.status() != ok)
            || environment
                .signal_queue
                .as_ref()
                .is_none_or(|queue| queue.status() != ok)
            || audit.status() == capture::DiagnosticStatus::Unavailable
            || audit.status() == capture::DiagnosticStatus::Unspecified
            || audit.blocked_threads == 0
            || !audit.blocked_tid_examples.contains(&blocked_tid)
        {
            bail!("source artifact did not retain the blocked-thread signal environment audit");
        }
    }
    let observations = records
        .iter()
        .filter_map(|record| match &record.record {
            Some(Record::Observation(observation)) => Some(observation),
            _ => None,
        })
        .collect::<Vec<_>>();
    if observations.is_empty() || COOKIE.load(Ordering::Relaxed) & (1u64 << 63) == 0 {
        bail!("collector did not deliver a bit-63 cookie observation");
    }
    let target_pid = unsafe { libc::getpid() } as u32;
    if observations
        .iter()
        .any(|row| row.target_tgid != target_pid || row.target_tid == 0)
    {
        bail!("collector did not persist exact target-namespace process/thread IDs");
    }
    println!(
        "{}",
        serde_json::to_string_pretty(&json!({
            "prepare": prepared,
            "stop": stop,
            "idempotentStopAndConcurrentClose": true,
            "sourcePath": output,
            "records": records.len(),
            "observations": observations.len(),
            "targetNamespaceTgid": target_pid,
            "targetNamespaceMapped": observations.len(),
            "blockedAuditTid": blocked_tid,
            "cookie": format!("{:016x}", COOKIE.load(Ordering::Relaxed)),
            "appendableAfterClose": true,
        }))?
    );
    Ok(())
}

fn verify_wall_clock_calibration(calibration: &capture::WallClockCalibration) -> Result<()> {
    if calibration.sample_count != 9 || calibration.selected_sample_index >= 9 {
        bail!("wall-clock calibration sample mismatch");
    }
    let before = calibration.monotonic_before_nanos;
    let midpoint = calibration.monotonic_midpoint_nanos;
    let width = calibration.bracket_width_nanos;
    if calibration.monotonic_after_nanos.checked_sub(before) != Some(width)
        || midpoint != before + width / 2
        || calibration.midpoint_uncertainty_nanos != width / 2 + width % 2
        || i128::from(calibration.realtime_minus_monotonic_nanos)
            != i128::from(calibration.realtime_nanos) - i128::from(midpoint)
    {
        bail!("wall-clock calibration derived values disagree");
    }
    Ok(())
}
fn block_signal(signal: i32) -> Result<()> {
    unsafe {
        let mut set: libc::sigset_t = std::mem::zeroed();
        if libc::sigemptyset(&mut set) != 0 || libc::sigaddset(&mut set, signal) != 0 {
            return Err(std::io::Error::last_os_error()).context("block signal on fixture thread");
        }
        let result = libc::pthread_sigmask(libc::SIG_BLOCK, &set, std::ptr::null_mut());
        if result != 0 {
            return Err(std::io::Error::from_raw_os_error(result))
                .context("block signal on fixture thread");
        }
    }
    Ok(())
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

fn install_signal_handler(signal: i32) -> Result<()> {
    unsafe {
        let mut action: libc::sigaction = std::mem::zeroed();
        action.sa_flags = libc::SA_SIGINFO;
        action.sa_sigaction = signal_handler as usize;
        libc::sigemptyset(&mut action.sa_mask);
        if libc::sigaction(signal, &action, std::ptr::null_mut()) != 0 {
            return Err(std::io::Error::last_os_error()).context("install signal handler");
        }
    }
    Ok(())
}
