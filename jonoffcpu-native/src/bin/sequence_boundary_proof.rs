// SPDX-License-Identifier: MIT
use anyhow::{Context, Result, bail};
use jonoffcpu_native::bpf_sched_exit::JonoffcpuCookieSkelBuilder;
use libbpf_rs::skel::{OpenSkel, SkelBuilder};
use libbpf_rs::{MapCore, MapFlags, RingBufferBuilder};
use serde::Serialize;
use std::collections::HashSet;
use std::fs;
use std::mem::{MaybeUninit, size_of};
use std::os::fd::{FromRawFd, OwnedFd};
use std::os::unix::fs::MetadataExt;
use std::sync::atomic::{AtomicI32, AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Barrier, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const CAPTURE_EPOCH: u32 = 0x8000_0001;
const REGISTRATION_TOKEN: u64 = 0x7151_0000_0000_0001;
const INITIAL_SEQUENCE: u64 = u32::MAX as u64;
const WORKER_THREADS: usize = 16;
const WORKER_ITERATIONS: usize = 200;

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
    reserved: [u8; 2],
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
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ProofResult {
    passed: bool,
    kernel: String,
    loader: &'static str,
    production_object: &'static str,
    hook: &'static str,
    worker_threads: usize,
    worker_iterations: usize,
    scheduler_exit_no_switch_callbacks: u64,
    no_switch_emitted_observations: usize,
    no_switch_delivered_signals: u64,
    initial_sequence: String,
    final_next_sequence: String,
    emitted_observations: usize,
    delivered_signals: u64,
    unique_cookies: usize,
    emitted_cookie: String,
    emitted_sequence: String,
    signal_code: i32,
    signal_code_name: &'static str,
    signal_tid: u32,
    stats: KernelStats,
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
    install_signal_handler()?;

    let tgid = unsafe { libc::getpid() } as u32;
    let pidfd = open_pidfd(tgid)?;
    let mut object = MaybeUninit::uninit();
    let mut open = JonoffcpuCookieSkelBuilder::default()
        .open(&mut object)
        .context("open production sched_exit libbpf skeleton")?;
    {
        let bss = open
            .maps
            .bss_data
            .as_deref_mut()
            .context("missing BPF bss")?;
        bss.target_tgid = tgid;
        let pid_namespace = fs::metadata("/proc/self/ns/pid")?;
        bss.target_pid_namespace_device = pid_namespace.dev();
        bss.target_pid_namespace_inode = pid_namespace.ino();
        bss.target_namespace_tgid = tgid;
        bss.signal_number = libc::SIGPROF as u32;
        bss.capture_epoch = CAPTURE_EPOCH;
        bss.min_off_cpu_ns = 0;
        bss.has_min_off_cpu = 0;
        bss.max_off_cpu_ns = 0;
        bss.has_max_off_cpu = 0;
        bss.sample_threshold = 1u64 << 32;
        // Every switch-out reason stays eligible, as before the reason filter existed.
        bss.reason_mask = 0b1110;
        bss.next_sequence = INITIAL_SEQUENCE;
    }
    let mut skel = open
        .load()
        .context("load production CO-RE object with sched_exit_tp kfunc path")?;
    reset_stats(&skel.maps.stats)?;

    skel.maps
        .target_tasks
        .update(
            &pidfd_raw(&pidfd).to_ne_bytes(),
            &target_binding_value(),
            MapFlags::ANY,
        )
        .context("bind exact target task through pidfd-backed task storage")?;
    let _switch_out = skel
        .progs
        .record_switch_out
        .attach()
        .context("attach production sched_switch start hook")?;
    let _switch_in = skel
        .progs
        .capture_switch_in
        .attach()
        .context("attach production tp_btf/sched_exit_tp completion hook")?;

    let events = Arc::new(Mutex::new(Vec::<Observation>::new()));
    let callback_events = Arc::clone(&events);
    let mut ring_builder = RingBufferBuilder::new();
    ring_builder
        .add(&skel.maps.observations, move |data| {
            if data.len() == size_of::<Observation>() {
                let event =
                    unsafe { std::ptr::read_unaligned(data.as_ptr().cast::<Observation>()) };
                callback_events.lock().unwrap().push(event);
            }
            0
        })
        .context("register production observation ring callback")?;
    let ring = ring_builder.build().context("build observation ring")?;

    let worker_threads = WORKER_THREADS;
    let barrier = Arc::new(Barrier::new(worker_threads + 1));
    let completed = Arc::new(AtomicUsize::new(0));
    let mut workers = Vec::with_capacity(worker_threads);
    for _ in 0..worker_threads {
        let barrier = Arc::clone(&barrier);
        let completed = Arc::clone(&completed);
        workers.push(thread::spawn(move || {
            barrier.wait();
            for _ in 0..WORKER_ITERATIONS {
                thread::sleep(Duration::from_micros(100));
            }
            completed.fetch_add(1, Ordering::Release);
        }));
    }

    skel.maps
        .bss_data
        .as_deref_mut()
        .context("missing BPF bss")?
        .enabled = 1;
    barrier.wait();

    let deadline = Instant::now() + Duration::from_secs(15);
    while completed.load(Ordering::Acquire) != worker_threads && Instant::now() < deadline {
        ring.consume()
            .context("consume production observation ring")?;
        thread::sleep(Duration::from_millis(1));
    }
    if completed.load(Ordering::Acquire) != worker_threads {
        bail!("concurrent scheduler workers did not finish before the deadline");
    }
    for worker in workers {
        worker
            .join()
            .map_err(|_| anyhow::anyhow!("scheduler worker panicked"))?;
    }
    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 0;
    ring.consume()
        .context("drain production observation ring")?;

    let final_next_sequence = skel.maps.bss_data.as_deref().unwrap().next_sequence;
    let stats = read_stats(&skel.maps.stats)?;
    let events = events.lock().unwrap();
    let cookies = events
        .iter()
        .map(|event| event.correlation_id)
        .collect::<HashSet<_>>();
    let expected_cookie = ((CAPTURE_EPOCH as u64) << 32) | u32::MAX as u64;

    if events.len() != 1 {
        bail!(
            "expected exactly one emitted observation, found {}",
            events.len()
        );
    }
    let event = events[0];
    if event.sequence != u32::MAX || event.correlation_id != expected_cookie {
        bail!(
            "last valid sequence did not round-trip: sequence={} cookie={:016x}",
            event.sequence,
            event.correlation_id
        );
    }
    if event.sequence == 0 || (event.correlation_id as u32) == 0 {
        bail!("zero or wrapped sequence was emitted");
    }
    if cookies.len() != 1 {
        bail!("duplicate or conflicting cookies were emitted: {cookies:?}");
    }
    if event.signal_result != 0 || stats.signal_failures != 0 {
        bail!(
            "production signal request failed: result={} failures={}",
            event.signal_result,
            stats.signal_failures
        );
    }
    if SIGNAL_COUNT.load(Ordering::Relaxed) != 1
        || SIGNAL_COOKIE.load(Ordering::Relaxed) != expected_cookie
    {
        bail!(
            "handler delivery mismatch: count={} cookie={:016x}",
            SIGNAL_COUNT.load(Ordering::Relaxed),
            SIGNAL_COOKIE.load(Ordering::Relaxed)
        );
    }
    if SIGNAL_CODE.load(Ordering::Relaxed) != libc::SI_KERNEL {
        bail!("handler did not receive the production SI_KERNEL kfunc signal");
    }
    if SIGNAL_TID.load(Ordering::Relaxed) != event.host_tid {
        bail!("signal handler TID did not match the emitted resumed task");
    }
    if final_next_sequence != 0 {
        bail!("next_sequence did not stop at the zero exhaustion sentinel");
    }
    if stats.selected_intervals < 2 || stats.sequence_exhaustions == 0 {
        bail!(
            "later selected observations did not reach exhaustion: selected={} exhausted={}",
            stats.selected_intervals,
            stats.sequence_exhaustions
        );
    }
    if stats.admission_rejections != 0
        || stats.ring_reserve_failures != 0
        || stats.target_namespace_failures != 0
    {
        bail!("a pre-sequence rejection made the boundary proof ambiguous: {stats:?}");
    }
    if stats.selected_intervals != 1 + stats.sequence_exhaustions + stats.sequence_contentions {
        bail!(
            "selected observations did not reconcile with emission/exhaustion/contention: {stats:?}"
        );
    }
    if stats.scheduler_exit_no_switches < worker_threads as u64 {
        bail!(
            "expected at least one sched_exit is_switch=false callback per new worker: workers={} no_switches={}",
            worker_threads,
            stats.scheduler_exit_no_switches
        );
    }
    let valid_switch_emissions = stats
        .selected_intervals
        .saturating_sub(stats.sequence_exhaustions)
        .saturating_sub(stats.sequence_contentions) as usize;
    let no_switch_emitted_observations = events.len().saturating_sub(valid_switch_emissions);
    let successful_signal_requests = events
        .iter()
        .filter(|observation| observation.signal_result == 0)
        .count() as u64;
    let no_switch_delivered_signals = SIGNAL_COUNT
        .load(Ordering::Relaxed)
        .saturating_sub(successful_signal_requests);
    if no_switch_emitted_observations != 0 || no_switch_delivered_signals != 0 {
        bail!(
            "sched_exit no-switch callbacks added output: observations={} signals={}",
            no_switch_emitted_observations,
            no_switch_delivered_signals
        );
    }

    let result = ProofResult {
        passed: true,
        kernel: fs::read_to_string("/proc/sys/kernel/osrelease")?
            .trim()
            .to_string(),
        loader: "libbpf-rs/libbpf-cargo 0.27.1 (libbpf 1.7.0)",
        production_object: "src/bpf/jonoffcpu_cookie.bpf.c compiled with JONOFFCPU_SCHED_EXIT_TP_BTF",
        hook: "tp_btf/sched_switch + tp_btf/sched_exit_tp (is_switch gate exercised)",
        worker_threads,
        worker_iterations: WORKER_ITERATIONS,
        scheduler_exit_no_switch_callbacks: stats.scheduler_exit_no_switches,
        // The production false branch returns before ring reserve and signal request. Observing at
        // least every worker's first tail plus reconciling valid-switch output makes both counts zero.
        no_switch_emitted_observations,
        no_switch_delivered_signals,
        initial_sequence: INITIAL_SEQUENCE.to_string(),
        final_next_sequence: final_next_sequence.to_string(),
        emitted_observations: events.len(),
        delivered_signals: SIGNAL_COUNT.load(Ordering::Relaxed),
        unique_cookies: cookies.len(),
        emitted_cookie: format!("{:016x}", event.correlation_id),
        emitted_sequence: event.sequence.to_string(),
        signal_code: SIGNAL_CODE.load(Ordering::Relaxed),
        signal_code_name: "SI_KERNEL",
        signal_tid: SIGNAL_TID.load(Ordering::Relaxed),
        stats,
    };
    println!("{}", serde_json::to_string_pretty(&result)?);
    Ok(())
}

fn reset_stats(map: &impl MapCore) -> Result<()> {
    let key = 0u32.to_ne_bytes();
    let current = map
        .lookup_percpu(&key, MapFlags::ANY)?
        .context("stats map entry missing")?;
    let zeros = current
        .iter()
        .map(|value| vec![0u8; value.len()])
        .collect::<Vec<_>>();
    map.update_percpu(&key, &zeros, MapFlags::ANY)?;
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

fn install_signal_handler() -> Result<()> {
    unsafe {
        let mut action: libc::sigaction = std::mem::zeroed();
        action.sa_flags = libc::SA_SIGINFO;
        action.sa_sigaction = signal_handler as usize;
        libc::sigemptyset(&mut action.sa_mask);
        if libc::sigaction(libc::SIGPROF, &action, std::ptr::null_mut()) != 0 {
            return Err(std::io::Error::last_os_error())
                .context("install SIGPROF SA_SIGINFO handler");
        }
    }
    Ok(())
}

fn open_pidfd(pid: u32) -> Result<OwnedFd> {
    let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid, 0) } as i32;
    if fd < 0 {
        return Err(std::io::Error::last_os_error()).context("pidfd_open target group leader");
    }
    Ok(unsafe { OwnedFd::from_raw_fd(fd) })
}

fn target_binding_value() -> [u8; 24] {
    let mut value = [0u8; 24];
    value[..8].copy_from_slice(&REGISTRATION_TOKEN.to_ne_bytes());
    value
}

fn pidfd_raw(fd: &OwnedFd) -> i32 {
    use std::os::fd::AsRawFd;
    fd.as_raw_fd()
}
