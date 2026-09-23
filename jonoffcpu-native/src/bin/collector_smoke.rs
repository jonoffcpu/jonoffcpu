// SPDX-License-Identifier: MIT
use anyhow::{Context, Result, bail};
use jonoffcpu_native::{
    JonoffcpuResult, jonoffcpu_collector_close, jonoffcpu_collector_enable,
    jonoffcpu_collector_prepare, jonoffcpu_collector_stop, jonoffcpu_result_free,
};
use serde_json::{Value, json};
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
        format!("/tmp/jonoffcpu-native-smoke-{}.ndjson", unsafe {
            libc::getpid()
        })
    });
    let _ = fs::remove_file(&output);
    let prepare = call(|out| unsafe {
        let json = json!({
            "targetPid": libc::getpid(),
            "outputPath": output,
            "sampling": {
                "reasons": ["blocked", "runnable", "preempted"],
                "minOffCpuMicros": null,
                "maxOffCpuMicros": null,
                "admission": {
                    "policy": "uniform",
                    "probability": "1",
                    "probabilityThreshold": 4_294_967_296_u64,
                },
            },
            "timeSplit": {"source": "schedInfo"},
        })
        .to_string();
        jonoffcpu_collector_prepare(json.as_ptr().cast(), json.len(), out)
    })?;
    let handle = u64::from_str_radix(
        prepare["handle"]
            .as_str()
            .context("prepare response missing handle")?,
        16,
    )?;
    call(|out| unsafe {
        let json = json!({
            "sessionId":"12345678-1234-4abc-8def-123456789abc",
            "captureEpoch":2_147_483_649_u32,
            "signal":signal,
        })
        .to_string();
        jonoffcpu_collector_enable(handle, json.as_ptr().cast(), json.len(), out)
    })?;
    for _ in 0..20 {
        thread::sleep(Duration::from_millis(2));
        if COOKIE.load(Ordering::Relaxed) != 0 {
            break;
        }
    }
    let stop = call(|out| unsafe { jonoffcpu_collector_stop(handle, 5_000, out) })?;
    let stop_retry = call(|out| unsafe { jonoffcpu_collector_stop(handle, 5_000, out) })?;
    if stop_retry["captureEnd"] != stop["captureEnd"] {
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
    let rows = jonoffcpu_native::capture::decode(&fs::read(&output)?)?
        .iter()
        .map(jonoffcpu_native::capture::to_json)
        .collect::<Vec<_>>();
    if rows.first().and_then(|row| row["recordType"].as_str()) != Some("captureStart")
        || rows.last().and_then(|row| row["recordType"].as_str()) != Some("captureEnd")
    {
        bail!("source artifact does not have start/end control rows");
    }
    for control in [rows.first().unwrap(), rows.last().unwrap()] {
        verify_wall_clock_calibration(&control["wallClockCalibration"])?;
        let environment = &control["signalEnvironment"];
        let audit = &environment["threadMaskAudit"];
        let expected_class = if signal >= libc::SIGRTMIN() && signal <= libc::SIGRTMAX() {
            "realtime"
        } else {
            "standard"
        };
        if environment["selectedSignal"].as_i64() != Some(signal as i64)
            || environment["signalClass"] != expected_class
            || environment["rlimitSigpending"]["status"] != "ok"
            || environment["sigQ"]["status"] != "ok"
            || audit["status"] == "unavailable"
            || audit["blockedThreads"]
                .as_str()
                .and_then(|value| value.parse::<u64>().ok())
                .is_none_or(|value| value == 0)
            || !audit["blockedTidExamples"]
                .as_array()
                .is_some_and(|examples| {
                    examples
                        .iter()
                        .any(|tid| tid.as_u64() == Some(blocked_tid as u64))
                })
        {
            bail!("source artifact did not retain the blocked-thread signal environment audit");
        }
    }
    let observation_rows = rows
        .iter()
        .filter(|row| row["recordType"] == "observation")
        .collect::<Vec<_>>();
    let observations = observation_rows.len();
    if observations == 0 || COOKIE.load(Ordering::Relaxed) & (1u64 << 63) == 0 {
        bail!("collector did not deliver a bit-63 cookie observation");
    }
    let target_pid = unsafe { libc::getpid() } as u64;
    if observation_rows.iter().any(|row| {
        row["targetTgid"].as_u64() != Some(target_pid)
            || row["targetTid"].as_u64().is_none_or(|tid| tid == 0)
    }) {
        bail!("collector did not persist exact target-namespace process/thread IDs");
    }
    println!(
        "{}",
        serde_json::to_string_pretty(&json!({
            "prepare":prepare,
            "stop":stop,
            "idempotentStopAndConcurrentClose":true,
            "sourcePath":output,
            "rows":rows.len(),
            "observations":observations,
            "targetNamespaceTgid":target_pid,
            "targetNamespaceMapped":observation_rows.len(),
            "blockedAuditTid":blocked_tid,
            "cookie":format!("{:016x}", COOKIE.load(Ordering::Relaxed)),
            "appendableAfterClose":true,
        }))?
    );
    Ok(())
}

fn verify_wall_clock_calibration(calibration: &Value) -> Result<()> {
    let decimal_u64 = |field: &str| -> Result<u64> {
        calibration[field]
            .as_str()
            .with_context(|| format!("wall-clock calibration missing {field}"))?
            .parse::<u64>()
            .with_context(|| format!("wall-clock calibration invalid {field}"))
    };
    if calibration["schemaVersion"] != 1
        || calibration["method"] != "clock_gettime-bracket-v1"
        || calibration["sampleCount"] != 9
        || calibration["selectedSampleIndex"]
            .as_u64()
            .is_none_or(|index| index >= 9)
        || calibration["monotonicClock"] != "CLOCK_MONOTONIC"
        || calibration["wallClock"] != "CLOCK_REALTIME"
    {
        bail!("wall-clock calibration schema mismatch");
    }
    let before = decimal_u64("monotonicBeforeNanos")?;
    let realtime = decimal_u64("realtimeNanos")?;
    let after = decimal_u64("monotonicAfterNanos")?;
    let midpoint = decimal_u64("monotonicMidpointNanos")?;
    let width = decimal_u64("bracketWidthNanos")?;
    let uncertainty = decimal_u64("midpointUncertaintyNanos")?;
    let offset = calibration["realtimeMinusMonotonicNanos"]
        .as_str()
        .context("wall-clock calibration missing signed offset")?
        .parse::<i128>()
        .context("wall-clock calibration invalid signed offset")?;
    if after.checked_sub(before) != Some(width)
        || midpoint != before + width / 2
        || uncertainty != width / 2 + width % 2
        || offset != i128::from(realtime) - i128::from(midpoint)
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

fn call(operation: impl FnOnce(*mut JonoffcpuResult) -> i32) -> Result<Value> {
    let mut result = JonoffcpuResult::default();
    let status = operation(&mut result);
    let bytes = unsafe { std::slice::from_raw_parts(result.json.cast::<u8>(), result.json_len) };
    let value: Value = serde_json::from_slice(bytes)?;
    unsafe { jonoffcpu_result_free(&mut result) };
    if status != 0 {
        bail!("native call failed ({status}): {value}");
    }
    Ok(value)
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
