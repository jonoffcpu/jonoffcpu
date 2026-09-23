// SPDX-License-Identifier: MIT

use anyhow::{Context, Result, bail};
use jonoffcpu_native::{
    JonoffcpuResult, jonoffcpu_collector_close, jonoffcpu_collector_enable,
    jonoffcpu_collector_prepare, jonoffcpu_collector_stop, jonoffcpu_result_free,
};
use serde_json::{Value, json};
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::process::{Child, Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

const TARGET_SIGNAL: i32 = libc::SIGPROF;

extern "C" fn signal_handler(
    _signal: i32,
    _info: *mut libc::siginfo_t,
    _context: *mut libc::c_void,
) {
}

fn main() -> Result<()> {
    if std::env::args().nth(1).as_deref() == Some("--child") {
        return child_target();
    }
    parent_proof()
}

fn child_target() -> Result<()> {
    install_signal_handler(TARGET_SIGNAL)?;
    println!("ready");
    std::io::stdout().flush()?;
    let mut input = std::io::stdin();
    let mut byte = [0_u8; 1];
    loop {
        let mut descriptor = libc::pollfd {
            fd: libc::STDIN_FILENO,
            events: libc::POLLIN,
            revents: 0,
        };
        let result = unsafe { libc::poll(&mut descriptor, 1, 10) };
        if result < 0 {
            let error = std::io::Error::last_os_error();
            if error.kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error).context("poll child exit pipe");
        }
        if result == 0 {
            continue;
        }
        if descriptor.revents & (libc::POLLIN | libc::POLLHUP) != 0 {
            let _ = input.read(&mut byte)?;
            return Ok(());
        }
    }
}

fn parent_proof() -> Result<()> {
    let expect_terminal_sync_retry =
        std::env::var_os("JONOFFCPU_EXPECT_TERMINAL_SYNC_RETRY").is_some();
    let mut child = spawn_child()?;
    let child_pid = child.id();
    let mut ready = String::new();
    BufReader::new(child.stdout.take().context("child stdout unavailable")?)
        .read_line(&mut ready)?;
    if ready != "ready\n" {
        terminate_child(&mut child);
        bail!("child target did not become ready");
    }

    let output = std::env::var("JONOFFCPU_TARGET_EXIT_OUTPUT")
        .unwrap_or_else(|_| format!("/tmp/jonoffcpu-target-exit-{child_pid}.ndjson"));
    let _ = fs::remove_file(&output);
    let prepare = call(|out| unsafe {
        let input = json!({
            "targetPid": child_pid,
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
        })
        .to_string();
        jonoffcpu_collector_prepare(input.as_ptr().cast(), input.len(), out)
    })
    .inspect_err(|_| terminate_child(&mut child))?;
    let handle = u64::from_str_radix(
        prepare["handle"]
            .as_str()
            .context("prepare response missing handle")?,
        16,
    )?;
    call(|out| unsafe {
        let input = json!({
            "sessionId":"12345678-1234-4abc-8def-123456789abc",
            "captureEpoch":17,
            "signal":TARGET_SIGNAL,
            "signalDelivery":"coalescing",
        })
        .to_string();
        jonoffcpu_collector_enable(handle, input.as_ptr().cast(), input.len(), out)
    })
    .inspect_err(|_| terminate_child(&mut child))?;
    thread::sleep(Duration::from_millis(100));

    let exit_requested = Instant::now();
    child
        .stdin
        .take()
        .context("child stdin unavailable")?
        .write_all(b"x")?;
    let status = child.wait().context("wait for child target exit")?;
    if !status.success() {
        bail!("child target exited unsuccessfully: {status}");
    }

    // Do not call Stop while waiting: the worker must observe the prepared
    // pidfd, detach, drain and publish captureEnd on its own.
    let end = wait_for_capture_end(&output, Duration::from_secs(10))?;
    let observed_after = exit_requested.elapsed();
    if end["state"] != "incomplete"
        || end["targetExited"] != true
        || end["incompleteReason"] != "target_exited"
    {
        bail!("automatic target-exit captureEnd has the wrong terminal state");
    }

    if expect_terminal_sync_retry {
        // Leave the worker idle after the automatic fsync failure. It must
        // wait for a command instead of re-entering five-second finalization.
        thread::sleep(Duration::from_millis(100));
        if capture_end_count(&output)? != 1 {
            bail!("automatic finalization retried captureEnd before Stop");
        }

        // Terminal freeze retires the one-shot handle even though durability
        // is still pending. A rejected Enable must not append another start or
        // otherwise modify the frozen source prefix.
        let source_before_enable = fs::read(&output)?;
        let rejected_enable = call_error(|out| unsafe {
            let input = json!({
                "sessionId":"22345678-1234-4abc-8def-123456789abc",
                "captureEpoch":18,
                "signal":TARGET_SIGNAL,
                "signalDelivery":"coalescing",
            })
            .to_string();
            jonoffcpu_collector_enable(handle, input.as_ptr().cast(), input.len(), out)
        })?;
        if rejected_enable["error"]["code"] != "invalid_state"
            || fs::read(&output)? != source_before_enable
            || record_type_count(&output, "captureStart")? != 1
            || record_type_count(&output, "captureEnd")? != 1
        {
            bail!("Enable reopened a terminal-frozen collector: {rejected_enable}");
        }
    }

    let stop = call(|out| unsafe { jonoffcpu_collector_stop(handle, 5_000, out) })?;
    let stop_retry = call(|out| unsafe { jonoffcpu_collector_stop(handle, 5_000, out) })?;
    let expected_sync_attempts = if expect_terminal_sync_retry { 2 } else { 1 };
    if stop["state"] != "incomplete"
        || stop["captureEnd"] != end
        || stop_retry != stop
        || stop["terminalPublication"]["state"] != "durable"
        || stop["terminalPublication"]["automatic"] != true
        || stop["terminalPublication"]["appendAttempts"] != 1
        || stop["terminalPublication"]["flushAttempts"] != 1
        || stop["terminalPublication"]["syncAttempts"] != expected_sync_attempts
        || (expect_terminal_sync_retry
            && !stop["terminalPublication"]["firstError"]
                .as_str()
                .is_some_and(|message| message.contains("terminal source sync failed")))
        || (!expect_terminal_sync_retry && !stop["terminalPublication"]["firstError"].is_null())
    {
        bail!("Stop did not return the cached automatic terminal witness: {stop}");
    }
    call(|out| unsafe { jonoffcpu_collector_close(handle, out) })?;

    let source = fs::read(&output).context("read retained partial source")?;
    let rows = jonoffcpu_native::capture::decode(&source)?
        .iter()
        .map(jonoffcpu_native::capture::to_json)
        .collect::<Vec<_>>();
    let capture_end_rows = rows
        .iter()
        .filter(|row| row["recordType"] == "captureEnd")
        .count();
    if rows.first().and_then(|row| row["recordType"].as_str()) != Some("captureStart")
        || rows.last() != Some(&end)
        || capture_end_rows != 1
        || fs::metadata(&output)?.len() == 0
    {
        bail!("partial target-exit source was not retained");
    }
    if rows[0]["targetPid"].as_u64() != Some(u64::from(child_pid))
        || prepare["targetPid"].as_u64() != Some(u64::from(child_pid))
    {
        bail!("prepared identity did not retain the exact child target");
    }

    println!(
        "{}",
        serde_json::to_string_pretty(&json!({
            "captureEndAppearedBeforeExplicitStop": true,
            "injectedTerminalSyncEio": expect_terminal_sync_retry,
            "enableRejectedAfterTerminalFreeze": expect_terminal_sync_retry,
            "childTargetPid": child_pid,
            "preparedHostTgid": prepare["hostTgid"],
            "targetExitToCaptureEndMillis": observed_after.as_millis(),
            "captureEnd": end,
            "terminalPublication": stop["terminalPublication"],
            "captureEndRows": capture_end_rows,
            "idempotentStop": true,
            "sourcePath": output,
            "sourceBytes": source.len(),
            "sourceRows": rows.len(),
        }))?
    );
    Ok(())
}

fn spawn_child() -> Result<Child> {
    Command::new(std::env::current_exe()?)
        .arg("--child")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .context("spawn target child")
}

fn terminate_child(child: &mut Child) {
    let _ = child.kill();
    let _ = child.wait();
}

fn wait_for_capture_end(path: &str, timeout: Duration) -> Result<Value> {
    let deadline = Instant::now() + timeout;
    loop {
        // A partially written trailing record is expected while the collector is still finalizing.
        if let Ok(bytes) = fs::read(path) {
            if let Ok(records) = jonoffcpu_native::capture::decode(&bytes) {
                for record in records.iter().rev() {
                    let row = jonoffcpu_native::capture::to_json(record);
                    if row["recordType"] == "captureEnd" {
                        return Ok(row);
                    }
                }
            }
        }
        if Instant::now() >= deadline {
            bail!("captureEnd did not appear after exact pidfd target exit");
        }
        thread::sleep(Duration::from_millis(5));
    }
}

fn capture_end_count(path: &str) -> Result<usize> {
    record_type_count(path, "captureEnd")
}

fn record_type_count(path: &str, record_type: &str) -> Result<usize> {
    Ok(fs::read_to_string(path)?
        .lines()
        .filter_map(|line| serde_json::from_str::<Value>(line).ok())
        .filter(|row| row["recordType"] == record_type)
        .count())
}

fn call(operation: impl FnOnce(*mut JonoffcpuResult) -> i32) -> Result<Value> {
    let (status, value) = call_result(operation)?;
    if status != 0 {
        bail!("native call failed ({status}): {value}");
    }
    Ok(value)
}

fn call_error(operation: impl FnOnce(*mut JonoffcpuResult) -> i32) -> Result<Value> {
    let (status, value) = call_result(operation)?;
    if status == 0 {
        bail!("native call unexpectedly succeeded: {value}");
    }
    Ok(value)
}

fn call_result(operation: impl FnOnce(*mut JonoffcpuResult) -> i32) -> Result<(i32, Value)> {
    let mut result = JonoffcpuResult::default();
    let status = operation(&mut result);
    let bytes = unsafe { std::slice::from_raw_parts(result.json.cast::<u8>(), result.json_len) };
    let value: Value = serde_json::from_slice(bytes)?;
    unsafe { jonoffcpu_result_free(&mut result) };
    Ok((status, value))
}

fn install_signal_handler(signal: i32) -> Result<()> {
    unsafe {
        let mut action: libc::sigaction = std::mem::zeroed();
        action.sa_flags = libc::SA_SIGINFO | libc::SA_RESTART;
        action.sa_sigaction = signal_handler as usize;
        libc::sigemptyset(&mut action.sa_mask);
        if libc::sigaction(signal, &action, std::ptr::null_mut()) != 0 {
            return Err(std::io::Error::last_os_error()).context("install child signal handler");
        }
    }
    Ok(())
}
