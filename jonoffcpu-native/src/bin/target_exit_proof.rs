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
        .unwrap_or_else(|_| format!("/tmp/jonoffcpu-target-exit-{child_pid}.pb"));
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
        target_pid: child_pid,
        output_path: output.clone(),
        sampling: Some(sampling.clone()),
        time_split: Some(time_split),
        exclude_calling_thread: false,
    }
    .encode_to_vec();
    let prepared = call(|out| unsafe {
        jonoffcpu_collector_prepare(prepare_request.as_ptr(), prepare_request.len(), out)
    })
    .inspect_err(|_| terminate_child(&mut child))?;
    let Some(Reply::Prepared(prepared)) = prepared.result else {
        terminate_child(&mut child);
        bail!("prepare did not return Prepared");
    };
    let handle = prepared.handle;
    let enable_request = |session_id: &str, capture_epoch: u32| {
        capture::EnableRequest {
            session_id: session_id.to_string(),
            capture_epoch,
            signal: TARGET_SIGNAL,
            signal_delivery: capture::SignalDelivery::Coalescing as i32,
            sampling: Some(sampling.clone()),
            time_split: Some(time_split),
        }
        .encode_to_vec()
    };
    let input = enable_request("12345678-1234-4abc-8def-123456789abc", 17);
    call(|out| unsafe { jonoffcpu_collector_enable(handle, input.as_ptr(), input.len(), out) })
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
    if end.state() != capture::CaptureState::Incomplete
        || !end.target_exited
        || end.incomplete_reason() != capture::IncompleteReason::TargetExited
    {
        bail!("automatic target-exit captureEnd has the wrong terminal state");
    }

    if expect_terminal_sync_retry {
        // Leave the worker idle after the automatic fsync failure. It must
        // wait for a command instead of re-entering five-second finalization.
        thread::sleep(Duration::from_millis(100));
        if record_count(&output, is_capture_end)? != 1 {
            bail!("automatic finalization retried captureEnd before Stop");
        }

        // Terminal freeze retires the one-shot handle even though durability
        // is still pending. A rejected Enable must not append another start or
        // otherwise modify the frozen source prefix.
        let source_before_enable = fs::read(&output)?;
        let input = enable_request("22345678-1234-4abc-8def-123456789abc", 18);
        let rejected_enable = call_error(|out| unsafe {
            jonoffcpu_collector_enable(handle, input.as_ptr(), input.len(), out)
        })?;
        if rejected_enable.code() != capture::CollectorErrorCode::InvalidState
            || fs::read(&output)? != source_before_enable
            || record_count(&output, |record| matches!(record, Record::CaptureStart(_)))? != 1
            || record_count(&output, is_capture_end)? != 1
        {
            bail!("Enable reopened a terminal-frozen collector: {rejected_enable:?}");
        }
    }

    let stop = call(|out| unsafe { jonoffcpu_collector_stop(handle, 5_000, out) })?;
    let stop_retry = call(|out| unsafe { jonoffcpu_collector_stop(handle, 5_000, out) })?;
    let expected_sync_attempts = if expect_terminal_sync_retry { 2 } else { 1 };
    let Some(Reply::Stopped(stopped)) = stop.result.clone() else {
        bail!("Stop did not return Stopped");
    };
    let publication = stopped.terminal_publication.clone().unwrap_or_default();
    if stop.state() != capture::CollectorState::Incomplete
        || stopped.capture_end.as_ref() != Some(&end)
        || stop_retry != stop
        || !publication.automatic
        || publication.append_attempts != 1
        || publication.flush_attempts != 1
        || publication.sync_attempts != expected_sync_attempts
        || (expect_terminal_sync_retry
            && !publication
                .first_error
                .contains("terminal source sync failed"))
        || (!expect_terminal_sync_retry && !publication.first_error.is_empty())
    {
        bail!(
            "Stop did not return the cached automatic terminal witness: {}",
            serde_json::to_string(&stop)?
        );
    }
    call(|out| unsafe { jonoffcpu_collector_close(handle, out) })?;

    let source = fs::read(&output).context("read retained partial source")?;
    let records = capture::decode(&source)?
        .into_iter()
        .filter_map(|record| record.record)
        .collect::<Vec<_>>();
    let capture_end_records = records
        .iter()
        .filter(|record| is_capture_end(record))
        .count();
    let Some(Record::CaptureStart(start)) = records.first() else {
        bail!("partial target-exit source was not retained");
    };
    if records.last() != Some(&Record::CaptureEnd(end.clone()))
        || capture_end_records != 1
        || source.is_empty()
    {
        bail!("partial target-exit source was not retained");
    }
    if start.target_pid != child_pid || prepared.target_pid != child_pid {
        bail!("prepared identity did not retain the exact child target");
    }

    println!(
        "{}",
        serde_json::to_string_pretty(&json!({
            "captureEndAppearedBeforeExplicitStop": true,
            "injectedTerminalSyncEio": expect_terminal_sync_retry,
            "enableRejectedAfterTerminalFreeze": expect_terminal_sync_retry,
            "childTargetPid": child_pid,
            "preparedHostTgid": prepared.host_tgid,
            "targetExitToCaptureEndMillis": observed_after.as_millis(),
            "captureEnd": end,
            "terminalPublication": publication,
            "captureEndRecords": capture_end_records,
            "idempotentStop": true,
            "sourcePath": output,
            "sourceBytes": source.len(),
            "sourceRecords": records.len(),
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

fn is_capture_end(record: &Record) -> bool {
    matches!(record, Record::CaptureEnd(_))
}

fn wait_for_capture_end(path: &str, timeout: Duration) -> Result<capture::CaptureEnd> {
    let deadline = Instant::now() + timeout;
    loop {
        // A partially written trailing record is expected while the collector is still finalizing.
        if let Ok(bytes) = fs::read(path) {
            if let Ok(records) = capture::decode(&bytes) {
                for record in records.into_iter().rev() {
                    if let Some(Record::CaptureEnd(end)) = record.record {
                        return Ok(end);
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

fn record_count(path: &str, matches: impl Fn(&Record) -> bool) -> Result<usize> {
    Ok(capture::decode(&fs::read(path)?)?
        .iter()
        .filter_map(|record| record.record.as_ref())
        .filter(|record| matches(record))
        .count())
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

fn call_error(
    operation: impl FnOnce(*mut JonoffcpuResult) -> i32,
) -> Result<capture::CollectorError> {
    let (status, reply) = call_collector(operation)?;
    match reply.result {
        Some(Reply::Error(error)) if status != 0 => Ok(error),
        other => bail!("native call unexpectedly succeeded: {other:?}"),
    }
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
