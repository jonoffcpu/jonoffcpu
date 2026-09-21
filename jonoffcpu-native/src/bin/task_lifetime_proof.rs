// SPDX-License-Identifier: MIT
use anyhow::{Context, Result, anyhow, bail};
use jonoffcpu_native::bpf_sched_exit::JonoffcpuCookieSkelBuilder;
use libbpf_rs::skel::{OpenSkel, SkelBuilder};
use libbpf_rs::{MapCore, MapFlags, RingBufferBuilder, TracepointCategory};
use serde::Serialize;
use serde_json::json;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::mem::{MaybeUninit, size_of};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::fs::MetadataExt;
use std::sync::atomic::{AtomicI32, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use std::thread;
use std::time::{Duration, Instant};

const PROCESS_ID: u32 = 1001;
const THREAD_ID: u32 = 1002;
const PROCESS_EPOCH_A: u32 = 0x8000_0701;
const PROCESS_EPOCH_B: u32 = 0x8000_0702;
const THREAD_EPOCH: u32 = 0x8000_0801;
const TOKEN_A: u64 = 0x7151_0000_0000_0701;
const TOKEN_B: u64 = 0x7151_0000_0000_0702;
const THREAD_TOKEN: u64 = 0x7151_0000_0000_0801;
const ITERATIONS: usize = 32;
const PIDFD_THREAD: u32 = libc::O_EXCL as u32;

static SIGNAL_COUNT: AtomicU64 = AtomicU64::new(0);
static SIGNAL_COOKIE: AtomicU64 = AtomicU64::new(0);
static SIGNAL_CODE: AtomicI32 = AtomicI32::new(i32::MIN);
static SIGNAL_TID: AtomicU32 = AtomicU32::new(0);

#[repr(C)]
#[derive(Clone, Copy, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Observation {
    correlation_id: u64,
    start_monotonic_ns: u64,
    end_monotonic_ns: u64,
    process_generation_ns: u64,
    thread_generation_ns: u64,
    registration_token: u64,
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
#[derive(Clone, Copy, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct KernelStats {
    switch_outs: u64,
    scheduler_exit_switches: u64,
    scheduler_exit_no_switches: u64,
    lifetime_rejections: u64,
    thread_state_failures: u64,
    eligible_intervals: u64,
    eligible_duration_us: u64,
    probability_rejections: u64,
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
            std::slice::from_raw_parts_mut((self as *mut Self).cast::<u64>(), size_of::<Self>() / 8)
        };
        let right = unsafe {
            std::slice::from_raw_parts((other as *const Self).cast::<u64>(), size_of::<Self>() / 8)
        };
        for (left, right) in left.iter_mut().zip(right) {
            *left = left.saturating_add(*right);
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct SignalReport {
    inner_tid: u32,
    signal_code: i32,
    signal_count: u64,
    signal_cookie: u64,
    signal_tid: u32,
}

struct ProcessFixture {
    pid: u32,
    command: OwnedFd,
    reports: OwnedFd,
    pidfd: OwnedFd,
}
struct ThreadFixture {
    pidfd: OwnedFd,
    commands: mpsc::SyncSender<u8>,
    reports: mpsc::Receiver<SignalReport>,
    join: thread::JoinHandle<()>,
}

extern "C" fn signal_handler(
    _signal: i32,
    info: *mut libc::siginfo_t,
    _context: *mut libc::c_void,
) {
    if info.is_null() {
        return;
    }
    unsafe {
        SIGNAL_COUNT.fetch_add(1, Ordering::Relaxed);
        SIGNAL_COOKIE.store((*info).si_value().sival_ptr as u64, Ordering::Relaxed);
        SIGNAL_CODE.store((*info).si_code, Ordering::Relaxed);
        SIGNAL_TID.store(libc::syscall(libc::SYS_gettid) as u32, Ordering::Relaxed);
    }
}

fn main() -> Result<()> {
    preflight()?;
    let process = prove_process_reuse()?;
    let thread = prove_thread_reuse()?;
    println!(
        "{}",
        serde_json::to_string_pretty(&json!({
            "passed": true,
            "kernel": fs::read_to_string("/proc/sys/kernel/osrelease")?.trim(),
            "architecture": std::env::consts::ARCH,
            "signalRuntimeMin": libc::SIGRTMIN(),
            "signalRuntimeMax": libc::SIGRTMAX(),
            "productionObject": "src/bpf/jonoffcpu_cookie.bpf.c compiled with JONOFFCPU_SCHED_EXIT_TP_BTF",
            "loader": "libbpf-rs/libbpf-cargo 0.27.1 (libbpf 1.7.0)",
            "hostPidReuseForced": false,
            "identityBoundary": "exact inner namespace PID/TID reuse; host numeric reuse is not claimed",
            "processReuse": process,
            "threadReuse": thread,
        }))?
    );
    Ok(())
}

fn preflight() -> Result<()> {
    let inner = fs::metadata("/proc/self/ns/pid").context("stat inner PID namespace")?;
    let outer = fs::read_to_string("/outer-pidns-identity")
        .context("runner must bind the outer PID namespace identity")?;
    let inner_identity = format!("{}:{}", inner.dev(), inner.ino());
    if inner_identity == outer.trim() {
        bail!("prerequisite failed: proof is not in a private PID namespace");
    }
    if unsafe { libc::getpid() } != 1 {
        bail!("prerequisite failed: proof must execute directly as namespace PID 1");
    }
    OpenOptions::new()
        .write(true)
        .open("/proc/sys/kernel/ns_last_pid")
        .context("prerequisite failed: private ns_last_pid is not writable")?;
    Ok(())
}

fn prove_process_reuse() -> Result<serde_json::Value> {
    let signal = libc::SIGRTMIN() + 4;
    let namespace = fs::metadata("/proc/self/ns/pid")?;
    let mut object = MaybeUninit::uninit();
    let mut open = JonoffcpuCookieSkelBuilder::default()
        .open(&mut object)
        .context("open production object for T07")?;
    configure(
        open.maps
            .bss_data
            .as_deref_mut()
            .context("missing BPF bss")?,
        0,
        namespace.dev(),
        namespace.ino(),
        PROCESS_ID,
        signal,
        PROCESS_EPOCH_A,
    );
    let mut skel = open.load().context("load production object for T07")?;
    reset_stats(&skel.maps.stats)?;
    let mut original = spawn_process_exact(PROCESS_ID, signal)?;
    skel.maps.target_tasks.update(
        &original.pidfd.as_raw_fd().to_ne_bytes(),
        &binding_value(TOKEN_A),
        MapFlags::ANY,
    )?;
    let switch_out = skel
        .progs
        .record_switch_out
        .attach_tracepoint(TracepointCategory::Sched, "sched_switch")
        .context("attach T07 START")?;
    let switch_in = skel
        .progs
        .capture_switch_in
        .attach()
        .context("attach T07 END")?;
    let (events, ring) = observation_ring(&skel.maps.observations)?;
    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 1;
    let original_report = exercise_process(&original)?;
    drain(&ring)?;
    let original_events = events_for(&events, PROCESS_ID, TOKEN_A, PROCESS_EPOCH_A);
    require_positive("T07 original process", &original_report, &original_events)?;
    let (original_host_tgid, original_generation) =
        binding_identity(&skel.maps.target_tasks, &original.pidfd)?;

    stop_process(&mut original)?;
    require_absent(&skel.maps.target_tasks, &original.pidfd)?;
    drain(&ring)?;
    let negative_sequence = skel.maps.bss_data.as_deref().unwrap().next_sequence;
    let old_event_count = events.lock().unwrap().len();
    let mut replacement = spawn_process_exact(PROCESS_ID, signal)?;
    require_absent(&skel.maps.target_tasks, &replacement.pidfd)?;
    let negative_report = exercise_process(&replacement)?;
    drain(&ring)?;
    if negative_report.signal_count != 0
        || events.lock().unwrap().len() != old_event_count
        || skel.maps.bss_data.as_deref().unwrap().next_sequence != negative_sequence
    {
        bail!("T07 unregistered replacement emitted a signal, row, or sequence");
    }

    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 0;
    skel.maps.target_tasks.update(
        &replacement.pidfd.as_raw_fd().to_ne_bytes(),
        &binding_value(TOKEN_B),
        MapFlags::ANY,
    )?;
    {
        let bss = skel.maps.bss_data.as_deref_mut().unwrap();
        bss.capture_epoch = PROCESS_EPOCH_B;
        bss.enabled = 1;
    }
    let replacement_report = exercise_process(&replacement)?;
    drain(&ring)?;
    let replacement_events = events_for(&events, PROCESS_ID, TOKEN_B, PROCESS_EPOCH_B);
    require_positive(
        "T07 registered replacement",
        &replacement_report,
        &replacement_events,
    )?;
    let (replacement_host_tgid, replacement_generation) =
        binding_identity(&skel.maps.target_tasks, &replacement.pidfd)?;
    if replacement_generation == original_generation {
        bail!("T07 distinct process tasks reported the same generation");
    }
    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 0;
    stop_process(&mut replacement)?;
    drop(switch_out);
    drop(switch_in);
    let stats = read_stats(&skel.maps.stats)?;
    require_path_health("T07", &stats)?;
    Ok(json!({
        "passed": true, "innerPid": PROCESS_ID, "actualIdEquality": original.pid == replacement.pid,
        "hostPidReuseForced": false, "originalHostTgid": original_host_tgid, "replacementHostTgid": replacement_host_tgid,
        "originalProcessGenerationNs": original_generation.to_string(), "replacementProcessGenerationNs": replacement_generation.to_string(),
        "oldStorageAfterExit": "absent", "replacementStorageBeforeRegistration": "absent",
        "negativeSignals": negative_report.signal_count, "negativeRows": 0, "negativeSequenceUnchanged": true,
        "originalToken": format!("{TOKEN_A:016x}"), "replacementToken": format!("{TOKEN_B:016x}"),
        "originalEpoch": PROCESS_EPOCH_A, "replacementEpoch": PROCESS_EPOCH_B,
        "hostFastFilter": "disabled for mechanism proof", "originalRows": original_events,
        "replacementRows": replacement_events, "stats": stats,
    }))
}

fn prove_thread_reuse() -> Result<serde_json::Value> {
    let signal = libc::SIGRTMIN() + 4;
    reset_signal_state();
    install_signal_handler(signal)?;
    unblock_signal(signal)?;
    let namespace = fs::metadata("/proc/self/ns/pid")?;
    let leader_pid = unsafe { libc::getpid() } as u32;
    let leader_pidfd = pidfd_open(leader_pid, 0)?;
    let leader_thread_pidfd = pidfd_open(leader_pid, PIDFD_THREAD)?;
    let mut object = MaybeUninit::uninit();
    let mut open = JonoffcpuCookieSkelBuilder::default()
        .open(&mut object)
        .context("open production object for T08")?;
    configure(
        open.maps
            .bss_data
            .as_deref_mut()
            .context("missing BPF bss")?,
        0,
        namespace.dev(),
        namespace.ino(),
        leader_pid,
        signal,
        THREAD_EPOCH,
    );
    let mut skel = open.load().context("load production object for T08")?;
    reset_stats(&skel.maps.stats)?;
    skel.maps.target_tasks.update(
        &leader_pidfd.as_raw_fd().to_ne_bytes(),
        &binding_value(THREAD_TOKEN),
        MapFlags::ANY,
    )?;
    let start = skel
        .progs
        .record_switch_out
        .attach_tracepoint(TracepointCategory::Sched, "sched_switch")
        .context("attach T08 START")?;
    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 1;
    let (leader_host_tgid, leader_generation) =
        wait_binding_identity(&skel.maps.target_tasks, &leader_pidfd)?;
    skel.maps.bss_data.as_deref_mut().unwrap().target_tgid = leader_host_tgid;

    let original = spawn_thread_exact(THREAD_ID)?;
    let original_state = wait_present(&skel.maps.thread_states, &original.pidfd)?;
    let original_start = decode_u64(&original_state, 0)?;
    let original_generation = decode_u64(&original_state, 8)?;
    if original_start == 0 || original_generation == 0 {
        bail!("T08 START lacked a real timestamp/generation");
    }
    let old_pidfd = stop_thread(original)?;
    require_absent(&skel.maps.thread_states, &old_pidfd)?;
    drop(start);
    delete_if_present(&skel.maps.thread_states, &leader_thread_pidfd)?;
    let negative_sequence = skel.maps.bss_data.as_deref().unwrap().next_sequence;
    let (events, ring) = observation_ring(&skel.maps.observations)?;

    let replacement = spawn_thread_exact(THREAD_ID)?;
    require_absent(&skel.maps.thread_states, &replacement.pidfd)?;
    let end = skel
        .progs
        .capture_switch_in
        .attach()
        .context("attach T08 END-only phase")?;
    reset_signal_state();
    let negative_report = exercise_thread(&replacement)?;
    drain(&ring)?;
    let negative_events = events_for(&events, THREAD_ID, THREAD_TOKEN, THREAD_EPOCH);
    if negative_report.signal_count != 0 || !negative_events.is_empty() {
        bail!("T08 replacement consumed stale START state");
    }
    require_absent(&skel.maps.thread_states, &replacement.pidfd)?;
    if skel.maps.bss_data.as_deref().unwrap().next_sequence != negative_sequence {
        bail!("T08 END-only phase advanced sequence");
    }

    let positive_start = skel
        .progs
        .record_switch_out
        .attach_tracepoint(TracepointCategory::Sched, "sched_switch")
        .context("reattach T08 START")?;
    reset_signal_state();
    let positive_report = exercise_thread(&replacement)?;
    drain(&ring)?;
    let positive_events = events_for(&events, THREAD_ID, THREAD_TOKEN, THREAD_EPOCH);
    require_positive(
        "T08 replacement positive control",
        &positive_report,
        &positive_events,
    )?;
    if positive_events
        .iter()
        .any(|event| event.start_monotonic_ns <= original_start)
    {
        bail!("T08 replacement START predates replacement");
    }
    let replacement_generation = positive_events[0].thread_generation_ns;
    if replacement_generation == original_generation {
        bail!("T08 distinct workers reported the same generation");
    }
    let _replacement_pidfd = stop_thread(replacement)?;
    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 0;
    drop(positive_start);
    drop(end);
    let stats = read_stats(&skel.maps.stats)?;
    require_path_health("T08", &stats)?;
    Ok(json!({
        "passed": true, "leaderInnerTgid": leader_pid, "leaderHostTgid": leader_host_tgid,
        "leaderProcessGenerationNs": leader_generation.to_string(), "innerTid": THREAD_ID,
        "actualIdEquality": true, "hostTidReuseForced": false,
        "originalStartMonotonicNs": original_start.to_string(), "originalThreadGenerationNs": original_generation.to_string(),
        "replacementThreadGenerationNs": replacement_generation.to_string(), "oldStorageAfterExit": "absent",
        "replacementStorageBeforeEnd": "absent", "negativeSignals": negative_report.signal_count,
        "negativeRows": negative_events.len(), "negativeSequenceUnchanged": true,
        "positiveRows": positive_events, "stats": stats,
    }))
}

fn configure(
    bss: &mut jonoffcpu_native::bpf_sched_exit::types::bss,
    target_tgid: u32,
    namespace_device: u64,
    namespace_inode: u64,
    namespace_tgid: u32,
    signal: i32,
    epoch: u32,
) {
    bss.target_tgid = target_tgid;
    bss.target_pid_namespace_device = namespace_device;
    bss.target_pid_namespace_inode = namespace_inode;
    bss.target_namespace_tgid = namespace_tgid;
    bss.signal_number = signal as u32;
    bss.capture_epoch = epoch;
    bss.min_off_cpu_ns = 0;
    bss.max_off_cpu_ns = 0;
    bss.has_min_off_cpu = 0;
    bss.has_max_off_cpu = 0;
    bss.sample_threshold = 1u64 << 32;
    bss.next_sequence = 1;
    bss.enabled = 0;
}

fn observation_ring<'a>(
    map: &'a impl MapCore,
) -> Result<(Arc<Mutex<Vec<Observation>>>, libbpf_rs::RingBuffer<'a>)> {
    let events = Arc::new(Mutex::new(Vec::new()));
    let callback_events = Arc::clone(&events);
    let mut builder = RingBufferBuilder::new();
    builder.add(map, move |data| {
        if data.len() == size_of::<Observation>() {
            let event = unsafe { std::ptr::read_unaligned(data.as_ptr().cast::<Observation>()) };
            let mut events = callback_events.lock().unwrap();
            if events.len() < 256 {
                events.push(event);
            }
        }
        0
    })?;
    Ok((events, builder.build()?))
}

fn events_for(
    events: &Arc<Mutex<Vec<Observation>>>,
    inner_tid: u32,
    token: u64,
    epoch: u32,
) -> Vec<Observation> {
    events
        .lock()
        .unwrap()
        .iter()
        .copied()
        .filter(|event| {
            event.target_tid == inner_tid
                && event.registration_token == token
                && event.capture_epoch == epoch
        })
        .collect()
}

fn require_positive(label: &str, report: &SignalReport, events: &[Observation]) -> Result<()> {
    let event = events
        .last()
        .with_context(|| format!("{label} emitted no observation"))?;
    if report.signal_count == 0
        || report.signal_code != libc::SI_KERNEL
        || report.signal_tid != event.target_tid
        || report.signal_cookie != event.correlation_id
        || event.signal_result != 0
        || event.process_generation_ns == 0
        || event.thread_generation_ns == 0
    {
        bail!("{label} signal/ring mismatch: report={report:?}, event={event:?}");
    }
    Ok(())
}

fn spawn_process_exact(id: u32, signal: i32) -> Result<ProcessFixture> {
    set_next_pid(id)?;
    let (command_read, command_write) = pipe()?;
    let (report_read, report_write) = pipe()?;
    let child = unsafe { libc::fork() };
    if child < 0 {
        return Err(std::io::Error::last_os_error()).context("fork process fixture");
    }
    if child == 0 {
        drop(command_write);
        drop(report_read);
        let status = process_child(command_read, report_write, signal);
        unsafe { libc::_exit(if status.is_ok() { 0 } else { 111 }) };
    }
    drop(command_read);
    drop(report_write);
    if child as u32 != id {
        unsafe {
            libc::kill(child, libc::SIGKILL);
            libc::waitpid(child, std::ptr::null_mut(), 0);
        }
        bail!("PID allocation race/prerequisite failure: requested {id}, received {child}");
    }
    let pidfd = pidfd_open(id, 0)?;
    let ready: SignalReport = read_struct(report_read.as_raw_fd())?;
    if ready.inner_tid != id {
        bail!(
            "process fixture reported unexpected inner TID {}",
            ready.inner_tid
        );
    }
    Ok(ProcessFixture {
        pid: id,
        command: command_write,
        reports: report_read,
        pidfd,
    })
}

fn process_child(command: OwnedFd, reports: OwnedFd, signal: i32) -> Result<()> {
    reset_signal_state();
    install_signal_handler(signal)?;
    unblock_signal(signal)?;
    write_struct(reports.as_raw_fd(), &signal_report())?;
    loop {
        match read_byte(command.as_raw_fd())? {
            b'S' => {
                reset_signal_state();
                for _ in 0..ITERATIONS {
                    thread::sleep(Duration::from_millis(1));
                }
                write_struct(reports.as_raw_fd(), &signal_report())?;
            }
            b'X' => return Ok(()),
            other => bail!("unknown process command {other}"),
        }
    }
}

fn exercise_process(process: &ProcessFixture) -> Result<SignalReport> {
    write_byte(process.command.as_raw_fd(), b'S')?;
    read_struct(process.reports.as_raw_fd())
}
fn stop_process(process: &mut ProcessFixture) -> Result<()> {
    write_byte(process.command.as_raw_fd(), b'X')?;
    let mut status = 0;
    if unsafe { libc::waitpid(process.pid as i32, &mut status, 0) } != process.pid as i32 {
        return Err(std::io::Error::last_os_error()).context("reap process fixture");
    }
    if !libc::WIFEXITED(status) || libc::WEXITSTATUS(status) != 0 {
        bail!("process fixture exited abnormally: {status}");
    }
    require_exited(&process.pidfd)?;
    Ok(())
}

fn spawn_thread_exact(id: u32) -> Result<ThreadFixture> {
    set_next_pid(id)?;
    let (command_tx, command_rx) = mpsc::sync_channel::<u8>(0);
    let (report_tx, report_rx) = mpsc::sync_channel::<SignalReport>(1);
    let join = thread::spawn(move || {
        unblock_signal(libc::SIGRTMIN() + 4).unwrap();
        report_tx.send(signal_report()).unwrap();
        loop {
            match command_rx.recv().unwrap() {
                b'S' => {
                    reset_signal_state();
                    for _ in 0..ITERATIONS {
                        thread::sleep(Duration::from_millis(1));
                    }
                    report_tx.send(signal_report()).unwrap();
                }
                b'X' => break,
                _ => panic!("unknown thread command"),
            }
        }
    });
    let ready = report_rx.recv_timeout(Duration::from_secs(2))?;
    if ready.inner_tid != id {
        command_tx.send(b'X').ok();
        join.join().ok();
        bail!(
            "TID allocation race/prerequisite failure: requested {id}, received {}",
            ready.inner_tid
        );
    }
    Ok(ThreadFixture {
        pidfd: pidfd_open(id, PIDFD_THREAD)?,
        commands: command_tx,
        reports: report_rx,
        join,
    })
}

fn exercise_thread(worker: &ThreadFixture) -> Result<SignalReport> {
    worker.commands.send(b'S')?;
    worker
        .reports
        .recv_timeout(Duration::from_secs(10))
        .map_err(Into::into)
}
fn stop_thread(worker: ThreadFixture) -> Result<OwnedFd> {
    worker.commands.send(b'X')?;
    worker
        .join
        .join()
        .map_err(|_| anyhow!("thread fixture panicked"))?;
    require_exited(&worker.pidfd)?;
    Ok(worker.pidfd)
}

fn set_next_pid(id: u32) -> Result<()> {
    let mut file = OpenOptions::new()
        .write(true)
        .open("/proc/sys/kernel/ns_last_pid")
        .context("open private ns_last_pid")?;
    write!(file, "{}", id - 1).context("write private ns_last_pid")?;
    Ok(())
}
fn pipe() -> Result<(OwnedFd, OwnedFd)> {
    let mut fds = [-1; 2];
    if unsafe { libc::pipe2(fds.as_mut_ptr(), libc::O_CLOEXEC) } != 0 {
        return Err(std::io::Error::last_os_error()).context("pipe2");
    }
    Ok(unsafe { (OwnedFd::from_raw_fd(fds[0]), OwnedFd::from_raw_fd(fds[1])) })
}
fn write_byte(fd: i32, value: u8) -> Result<()> {
    write_all(fd, std::slice::from_ref(&value))
}
fn read_byte(fd: i32) -> Result<u8> {
    let mut value = 0;
    read_all(fd, std::slice::from_mut(&mut value))?;
    Ok(value)
}
fn write_struct<T: Copy>(fd: i32, value: &T) -> Result<()> {
    write_all(fd, unsafe {
        std::slice::from_raw_parts((value as *const T).cast(), size_of::<T>())
    })
}
fn read_struct<T: Copy + Default>(fd: i32) -> Result<T> {
    let mut value = T::default();
    read_all(fd, unsafe {
        std::slice::from_raw_parts_mut((&mut value as *mut T).cast(), size_of::<T>())
    })?;
    Ok(value)
}
fn write_all(fd: i32, mut bytes: &[u8]) -> Result<()> {
    while !bytes.is_empty() {
        let count = unsafe { libc::write(fd, bytes.as_ptr().cast(), bytes.len()) };
        if count < 0 {
            let e = std::io::Error::last_os_error();
            if e.kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            return Err(e).context("write fixture pipe");
        }
        bytes = &bytes[count as usize..];
    }
    Ok(())
}
fn read_all(fd: i32, mut bytes: &mut [u8]) -> Result<()> {
    while !bytes.is_empty() {
        let count = unsafe { libc::read(fd, bytes.as_mut_ptr().cast(), bytes.len()) };
        if count == 0 {
            bail!("fixture pipe closed unexpectedly");
        }
        if count < 0 {
            let e = std::io::Error::last_os_error();
            if e.kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            return Err(e).context("read fixture pipe");
        }
        bytes = &mut bytes[count as usize..];
    }
    Ok(())
}

fn signal_report() -> SignalReport {
    SignalReport {
        inner_tid: unsafe { libc::syscall(libc::SYS_gettid) as u32 },
        signal_code: SIGNAL_CODE.load(Ordering::Relaxed),
        signal_count: SIGNAL_COUNT.load(Ordering::Relaxed),
        signal_cookie: SIGNAL_COOKIE.load(Ordering::Relaxed),
        signal_tid: SIGNAL_TID.load(Ordering::Relaxed),
    }
}
fn reset_signal_state() {
    SIGNAL_COUNT.store(0, Ordering::Relaxed);
    SIGNAL_COOKIE.store(0, Ordering::Relaxed);
    SIGNAL_CODE.store(i32::MIN, Ordering::Relaxed);
    SIGNAL_TID.store(0, Ordering::Relaxed);
}
fn install_signal_handler(signal: i32) -> Result<()> {
    unsafe {
        let mut action: libc::sigaction = std::mem::zeroed();
        action.sa_flags = libc::SA_SIGINFO | libc::SA_RESTART;
        action.sa_sigaction = signal_handler as usize;
        libc::sigemptyset(&mut action.sa_mask);
        if libc::sigaction(signal, &action, std::ptr::null_mut()) != 0 {
            return Err(std::io::Error::last_os_error()).context("install signal handler");
        }
    }
    Ok(())
}
fn unblock_signal(signal: i32) -> Result<()> {
    unsafe {
        let mut set: libc::sigset_t = std::mem::zeroed();
        libc::sigemptyset(&mut set);
        libc::sigaddset(&mut set, signal);
        let result = libc::pthread_sigmask(libc::SIG_UNBLOCK, &set, std::ptr::null_mut());
        if result != 0 {
            return Err(std::io::Error::from_raw_os_error(result)).context("unblock signal");
        }
    }
    Ok(())
}

fn binding_value(token: u64) -> [u8; 24] {
    let mut value = [0; 24];
    value[..8].copy_from_slice(&token.to_ne_bytes());
    value
}
fn binding_identity(map: &impl MapCore, pidfd: &OwnedFd) -> Result<(u32, u64)> {
    let value = map
        .lookup(&pidfd.as_raw_fd().to_ne_bytes(), MapFlags::ANY)?
        .context("target binding absent")?;
    Ok((
        u32::try_from(decode_u64(&value, 16)?)?,
        decode_u64(&value, 8)?,
    ))
}
fn wait_binding_identity(map: &impl MapCore, pidfd: &OwnedFd) -> Result<(u32, u64)> {
    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        let value = binding_identity(map, pidfd)?;
        if value.0 != 0 && value.1 != 0 {
            return Ok(value);
        }
        if Instant::now() >= deadline {
            bail!("scheduler hook did not discover target identity");
        }
        thread::yield_now();
    }
}
fn wait_present(map: &impl MapCore, pidfd: &OwnedFd) -> Result<Vec<u8>> {
    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        if let Some(value) = map.lookup(&pidfd.as_raw_fd().to_ne_bytes(), MapFlags::ANY)? {
            return Ok(value);
        }
        if Instant::now() >= deadline {
            bail!("real scheduler START did not appear");
        }
        thread::yield_now();
    }
}
fn decode_u64(value: &[u8], offset: usize) -> Result<u64> {
    Ok(u64::from_ne_bytes(
        value
            .get(offset..offset + 8)
            .context("short BPF value")?
            .try_into()?,
    ))
}
fn require_absent(map: &impl MapCore, pidfd: &OwnedFd) -> Result<()> {
    match map.lookup(&pidfd.as_raw_fd().to_ne_bytes(), MapFlags::ANY) {
        Ok(None) => Ok(()),
        Ok(Some(_)) => bail!("task storage present on wrong lifetime"),
        Err(error) if error.kind() == libbpf_rs::ErrorKind::NotFound => Ok(()),
        Err(error) => bail!("task storage absence lookup failed: {error}"),
    }
}
fn require_exited(pidfd: &OwnedFd) -> Result<()> {
    let mut fd = libc::pollfd {
        fd: pidfd.as_raw_fd(),
        events: libc::POLLIN,
        revents: 0,
    };
    if unsafe { libc::poll(&mut fd, 1, 0) } != 1 || fd.revents & libc::POLLIN == 0 {
        bail!("old pidfd did not report task exit");
    }
    Ok(())
}
fn delete_if_present(map: &impl MapCore, pidfd: &OwnedFd) -> Result<()> {
    if map
        .lookup(&pidfd.as_raw_fd().to_ne_bytes(), MapFlags::ANY)?
        .is_some()
    {
        map.delete(&pidfd.as_raw_fd().to_ne_bytes())?;
    }
    Ok(())
}
fn pidfd_open(pid: u32, flags: u32) -> Result<OwnedFd> {
    let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid, flags) } as i32;
    if fd < 0 {
        return Err(std::io::Error::last_os_error())
            .with_context(|| format!("pidfd_open {pid} flags={flags}"));
    }
    Ok(unsafe { OwnedFd::from_raw_fd(fd) })
}
fn drain(ring: &libbpf_rs::RingBuffer<'_>) -> Result<()> {
    for _ in 0..4 {
        ring.consume().context("drain ring")?;
    }
    Ok(())
}
fn reset_stats(map: &impl MapCore) -> Result<()> {
    let key = 0u32.to_ne_bytes();
    let current = map
        .lookup_percpu(&key, MapFlags::ANY)?
        .context("stats missing")?;
    let zeros = current
        .iter()
        .map(|value| vec![0; value.len()])
        .collect::<Vec<_>>();
    map.update_percpu(&key, &zeros, MapFlags::ANY)?;
    Ok(())
}
fn read_stats(map: &impl MapCore) -> Result<KernelStats> {
    let values = map
        .lookup_percpu(&0u32.to_ne_bytes(), MapFlags::ANY)?
        .context("stats missing")?;
    let mut total = KernelStats::default();
    for value in values {
        if value.len() != size_of::<KernelStats>() {
            bail!("unexpected stats size {}", value.len());
        }
        total.add(&unsafe { std::ptr::read_unaligned(value.as_ptr().cast()) });
    }
    Ok(total)
}
fn require_path_health(label: &str, stats: &KernelStats) -> Result<()> {
    if stats.ring_reserve_failures != 0
        || stats.target_namespace_failures != 0
        || stats.thread_state_failures != 0
    {
        bail!("{label} path failure made negative ambiguous: {stats:?}");
    }
    Ok(())
}
