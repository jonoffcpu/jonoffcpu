// SPDX-License-Identifier: MIT
//! Kernel-level evidence for the switch-out reason classification on the production object.
//!
//! Three workloads of known threads run under the scheduler-exit transport with every reason selected:
//! a sleeper (`clock_nanosleep`), a yielder sharing one CPU with a spinner (`sched_yield` while runnable),
//! and more spinners than CPUs they may use (involuntary preemption). The proof checks how each thread's
//! intervals were classified and that the kernel's per-reason switch-out counters account for every
//! switch-out. A second phase selects only blocked intervals and checks that the kernel filter keeps no
//! other reason and counts what it rejected.
//!
//! A user-space thread preempted by the scheduler tick is switched out when it returns to user mode, at an
//! ordinary `schedule()` where `preempt` is false and its state is still `TASK_RUNNING`; only a preemption
//! taken inside the kernel reports `preempt`. So the spinners are expected to be non-blocked, and the proof
//! reports how their intervals split between runnable and preempted rather than assuming one.
//!
//! The same run proves the sleeping/run-queue split. Every interval carries the growth of the scheduler's
//! `sched_info.run_delay` across it; the proof adds a contended sleeper (nice 19, pinned beside three nice-0
//! spinners), checks that each sleeper's recorded run-queue parts add up to the growth of its thread's
//! `/proc/.../schedstat` run delay over the phase, that the contended sleeper waits far longer for a CPU
//! than the uncontended one, that runnable and preempted intervals are run-queue time nearly throughout,
//! and that with the split off no interval carries a run-queue part. It reports how often and by how much a
//! reading exceeds its interval's duration: `rq_clock` can be a few microseconds stale when a runnable task
//! departs (a yield updates it before calling `schedule()`), so runnable and preempted intervals may
//! overshoot slightly, while a blocked interval, which queues only at its wakeup, must not.
use anyhow::{Context, Result, bail};
use jonoffcpu_native::bpf_sched_exit::JonoffcpuCookieSkelBuilder;
use libbpf_rs::skel::{OpenSkel, SkelBuilder};
use libbpf_rs::{MapCore, MapFlags, RingBufferBuilder};
use serde::Serialize;
use std::collections::BTreeMap;
use std::fs;
use std::mem::{MaybeUninit, size_of};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::fs::MetadataExt;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const CAPTURE_EPOCH: u32 = 0x8000_0002;
const REGISTRATION_TOKEN: u64 = 0x7151_0000_0000_0002;
const BLOCKED: u8 = 1;
const RUNNABLE: u8 = 2;
const PREEMPTED: u8 = 3;
const ALL_REASONS: u32 = (1 << BLOCKED) | (1 << RUNNABLE) | (1 << PREEMPTED);
const BLOCKED_ONLY: u32 = 1 << BLOCKED;
const PHASE: Duration = Duration::from_secs(2);
const SPLIT_OFF: u32 = 0;
const SPLIT_SCHED_INFO: u32 = 1;

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
    runqueue_inversions: u64,
}

#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct ReasonCounts {
    blocked: u64,
    runnable: u64,
    preempted: u64,
    invalid: u64,
    /// Blocked intervals whose recorded task state was zero, which the classification forbids.
    blocked_without_state: u64,
    /// Non-preempted, non-blocked intervals with a nonzero task state, which it also forbids.
    inconsistent: u64,
    task_states: BTreeMap<String, u64>,
    /// Intervals without a run-queue part.
    unsplit: u64,
    blocked_duration_ns: u64,
    blocked_runqueue_ns: u64,
    /// Runnable and preempted intervals, which are run-queue time by definition.
    other_duration_ns: u64,
    other_runqueue_ns: u64,
    max_runqueue_ns: u64,
    /// The growth of the thread's `/proc` run delay over the phase, for a single-thread workload.
    proc_run_delay_ns: Option<u64>,
    overshoots: BTreeMap<String, u64>,
    max_overshoot_ns: u64,
}

impl ReasonCounts {
    fn add(&mut self, event: &Observation) {
        let derived = if event.preempted != 0 {
            PREEMPTED
        } else if event.prev_task_state == 0 {
            RUNNABLE
        } else {
            BLOCKED
        };
        if derived != event.reason {
            self.inconsistent += 1;
        }
        match event.reason {
            BLOCKED => {
                self.blocked += 1;
                if event.prev_task_state == 0 {
                    self.blocked_without_state += 1;
                }
            }
            RUNNABLE => self.runnable += 1,
            PREEMPTED => self.preempted += 1,
            _ => self.invalid += 1,
        }
        *self
            .task_states
            .entry(format!("0x{:x}", event.prev_task_state))
            .or_default() += 1;
        let duration = event.end_monotonic_ns - event.start_monotonic_ns;
        if event.has_runqueue == 0 {
            self.unsplit += 1;
            return;
        }
        if event.reason == BLOCKED {
            self.blocked_duration_ns += duration;
            self.blocked_runqueue_ns += event.runqueue_ns;
        } else {
            self.other_duration_ns += duration;
            self.other_runqueue_ns += event.runqueue_ns;
        }
        self.max_runqueue_ns = self.max_runqueue_ns.max(event.runqueue_ns);
        if event.runqueue_ns > duration {
            let over = event.runqueue_ns - duration;
            let key = if event.reason == BLOCKED {
                "blocked"
            } else {
                "other"
            };
            let bucket = match over {
                0..=999 => "<1us",
                1_000..=9_999 => "<10us",
                10_000..=99_999 => "<100us",
                _ => ">=100us",
            };
            *self
                .overshoots
                .entry(format!("{key} {bucket}"))
                .or_default() += 1;
            self.max_overshoot_ns = self.max_overshoot_ns.max(over);
        }
    }

    fn runqueue_ns(&self) -> u64 {
        self.blocked_runqueue_ns + self.other_runqueue_ns
    }

    fn mean_blocked_runqueue_ns(&self) -> u64 {
        self.blocked_runqueue_ns / self.blocked.max(1)
    }

    fn total(&self) -> u64 {
        self.blocked + self.runnable + self.preempted + self.invalid
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PhaseResult {
    reason_mask: u32,
    time_split_source: u32,
    sleeper: ReasonCounts,
    contended_sleeper: ReasonCounts,
    yielder: ReasonCounts,
    spinners: ReasonCounts,
    stats: KernelStats,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ProofResult {
    kernel: String,
    hooks: &'static str,
    cpus: Vec<usize>,
    every_reason: PhaseResult,
    blocked_only: PhaseResult,
    split_off: PhaseResult,
}

extern "C" fn signal_handler(_: i32, _: *mut libc::siginfo_t, _: *mut libc::c_void) {}

/// The workload threads' TIDs, published once each has pinned itself.
#[derive(Default)]
struct Tids {
    sleeper: AtomicU32,
    contended_sleeper: AtomicU32,
    yielder: AtomicU32,
    spinners: Mutex<Vec<u32>>,
}

fn main() -> Result<()> {
    install_signal_handler()?;
    let cpus = allowed_cpus()?;
    let tgid = unsafe { libc::getpid() } as u32;
    let pidfd = open_pidfd(tgid)?;

    let mut object = MaybeUninit::uninit();
    let mut open = JonoffcpuCookieSkelBuilder::default()
        .open(&mut object)
        .context("open libbpf skeleton")?;
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
        bss.sample_threshold = 1u64 << 32;
        bss.next_sequence = 1;
        // The proof's own polling thread is excluded, as the collector's drain thread is.
        bss.collector_tid = current_tid();
    }
    let mut skel = open
        .load()
        .context("load CO-RE object with the four-argument tp_btf/sched_switch")?;
    skel.maps
        .target_tasks
        .update(
            &pidfd.as_raw_fd().to_ne_bytes(),
            &target_binding_value(),
            MapFlags::ANY,
        )
        .context("bind exact target task through pidfd-backed task storage")?;
    let _switch_out = skel
        .progs
        .record_switch_out
        .attach()
        .context("attach tp_btf/sched_switch")?;
    let _switch_in = skel
        .progs
        .capture_switch_in
        .attach()
        .context("attach tp_btf/sched_exit_tp")?;

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
        .context("register observation ring callback")?;
    let ring = ring_builder.build().context("build observation ring")?;

    // The yielder shares the first CPU with one spinner, so sched_yield has someone to yield to. The other
    // spinners outnumber the CPUs left to them, so the scheduler must take the CPU away from them.
    let stop = Arc::new(AtomicBool::new(false));
    let tids = Arc::new(Tids::default());
    let yield_cpu = cpus[0];
    let spin_cpus: Vec<usize> = if cpus.len() > 1 {
        cpus[1..].iter().copied().take(2).collect()
    } else {
        vec![cpus[0]]
    };
    let mut workers = Vec::new();
    {
        let (stop, tids) = (Arc::clone(&stop), Arc::clone(&tids));
        workers.push(thread::spawn(move || {
            tids.sleeper.store(current_tid(), Ordering::Release);
            while !stop.load(Ordering::Relaxed) {
                thread::sleep(Duration::from_millis(1));
            }
        }));
    }
    {
        let (stop, tids) = (Arc::clone(&stop), Arc::clone(&tids));
        workers.push(thread::spawn(move || {
            pin(yield_cpu);
            tids.yielder.store(current_tid(), Ordering::Release);
            while !stop.load(Ordering::Relaxed) {
                unsafe { libc::sched_yield() };
            }
        }));
    }
    // A nice-19 sleeper beside three nice-0 spinners gets the CPU back only after a long wait.
    {
        let (stop, tids) = (Arc::clone(&stop), Arc::clone(&tids));
        let cpu = spin_cpus[0];
        workers.push(thread::spawn(move || {
            pin(cpu);
            let tid = current_tid();
            unsafe { libc::setpriority(libc::PRIO_PROCESS, tid, 19) };
            tids.contended_sleeper.store(tid, Ordering::Release);
            while !stop.load(Ordering::Relaxed) {
                thread::sleep(Duration::from_millis(1));
            }
        }));
    }
    let mut spinner_cpus = vec![yield_cpu];
    for cpu in &spin_cpus {
        spinner_cpus.extend([*cpu, *cpu, *cpu]);
    }
    for cpu in spinner_cpus {
        let (stop, tids) = (Arc::clone(&stop), Arc::clone(&tids));
        workers.push(thread::spawn(move || {
            pin(cpu);
            tids.spinners.lock().unwrap().push(current_tid());
            while !stop.load(Ordering::Relaxed) {
                std::hint::spin_loop();
            }
        }));
    }
    thread::sleep(Duration::from_millis(100));

    let every_reason = run_phase(
        &mut skel,
        &ring,
        &events,
        &tids,
        ALL_REASONS,
        SPLIT_SCHED_INFO,
    )?;
    let blocked_only = run_phase(
        &mut skel,
        &ring,
        &events,
        &tids,
        BLOCKED_ONLY,
        SPLIT_SCHED_INFO,
    )?;
    let split_off = run_phase(&mut skel, &ring, &events, &tids, ALL_REASONS, SPLIT_OFF)?;
    stop.store(true, Ordering::Relaxed);
    for worker in workers {
        worker
            .join()
            .map_err(|_| anyhow::anyhow!("workload thread panicked"))?;
    }

    let result = ProofResult {
        kernel: fs::read_to_string("/proc/sys/kernel/osrelease")?
            .trim()
            .to_string(),
        hooks: "tp_btf/sched_switch + tp_btf/sched_exit_tp",
        cpus,
        every_reason,
        blocked_only,
        split_off,
    };
    println!("{}", serde_json::to_string_pretty(&result)?);
    verify(&result)
}

fn run_phase(
    skel: &mut jonoffcpu_native::bpf_sched_exit::JonoffcpuCookieSkel<'_>,
    ring: &libbpf_rs::RingBuffer<'_>,
    events: &Arc<Mutex<Vec<Observation>>>,
    tids: &Tids,
    reason_mask: u32,
    time_split_source: u32,
) -> Result<PhaseResult> {
    reset_stats(&skel.maps.stats)?;
    events.lock().unwrap().clear();
    let sleeper = tids.sleeper.load(Ordering::Acquire);
    let contended = tids.contended_sleeper.load(Ordering::Acquire);
    let run_delay_before = (proc_run_delay(sleeper)?, proc_run_delay(contended)?);
    {
        let bss = skel
            .maps
            .bss_data
            .as_deref_mut()
            .context("missing BPF bss")?;
        bss.reason_mask = reason_mask;
        bss.time_split_source = time_split_source;
        bss.enabled = 1;
    }
    let deadline = Instant::now() + PHASE;
    while Instant::now() < deadline {
        thread::sleep(Duration::from_millis(2));
        ring.consume().context("consume observation ring")?;
    }
    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 0;
    let run_delay_after = (proc_run_delay(sleeper)?, proc_run_delay(contended)?);
    thread::sleep(Duration::from_millis(20));
    ring.consume().context("drain observation ring")?;
    let stats = read_stats(&skel.maps.stats)?;

    let yielder = tids.yielder.load(Ordering::Acquire);
    let spinners = tids.spinners.lock().unwrap().clone();
    let mut result = PhaseResult {
        reason_mask,
        time_split_source,
        sleeper: ReasonCounts::default(),
        contended_sleeper: ReasonCounts::default(),
        yielder: ReasonCounts::default(),
        spinners: ReasonCounts::default(),
        stats,
    };
    for event in events.lock().unwrap().iter() {
        if event.target_tid == sleeper {
            result.sleeper.add(event);
        } else if event.target_tid == contended {
            result.contended_sleeper.add(event);
        } else if event.target_tid == yielder {
            result.yielder.add(event);
        } else if spinners.contains(&event.target_tid) {
            result.spinners.add(event);
        }
    }
    result.sleeper.proc_run_delay_ns = Some(run_delay_after.0 - run_delay_before.0);
    result.contended_sleeper.proc_run_delay_ns = Some(run_delay_after.1 - run_delay_before.1);
    Ok(result)
}

fn verify(result: &ProofResult) -> Result<()> {
    let every = &result.every_reason;
    for (name, counts) in [
        ("sleeper", &every.sleeper),
        ("yielder", &every.yielder),
        ("spinners", &every.spinners),
    ] {
        if counts.invalid != 0 || counts.inconsistent != 0 || counts.blocked_without_state != 0 {
            bail!(
                "{name}: an interval's reason disagrees with its recorded sched_switch arguments"
            );
        }
    }
    let sleeper = &every.sleeper;
    if sleeper.total() == 0 || sleeper.blocked * 10 < sleeper.total() * 9 {
        bail!("the sleeping thread must be predominantly blocked");
    }
    let yielder = &every.yielder;
    if yielder.runnable == 0 || yielder.runnable * 2 < yielder.total() {
        bail!("the yielding thread must be predominantly runnable");
    }
    let spinners = &every.spinners;
    if spinners.total() == 0 || (spinners.runnable + spinners.preempted) * 10 < spinners.total() * 9
    {
        bail!("oversubscribed spinners must be predominantly runnable or preempted");
    }
    let stats = &every.stats;
    let by_reason =
        stats.switch_outs_blocked + stats.switch_outs_runnable + stats.switch_outs_preempted;
    if by_reason != stats.switch_outs + stats.thread_state_failures {
        bail!("per-reason switch-outs {by_reason} do not account for every switch-out");
    }
    if stats.reason_rejections != 0 {
        bail!("no interval may be rejected when every reason is selected");
    }
    verify_split(result)?;
    let blocked = &result.blocked_only;
    for counts in [&blocked.sleeper, &blocked.yielder, &blocked.spinners] {
        if counts.runnable != 0 || counts.preempted != 0 {
            bail!("the kernel filter kept a reason that was not selected");
        }
    }
    if blocked.sleeper.blocked == 0 {
        bail!("the kernel filter dropped selected blocked intervals");
    }
    if blocked.stats.reason_rejections == 0 || blocked.stats.switch_outs_runnable == 0 {
        bail!("rejected switch-outs must still be counted by reason");
    }
    Ok(())
}

fn verify_split(result: &ProofResult) -> Result<()> {
    let every = &result.every_reason;
    let workloads = [
        ("sleeper", &every.sleeper),
        ("contended sleeper", &every.contended_sleeper),
        ("yielder", &every.yielder),
        ("spinners", &every.spinners),
    ];
    let inversions = every.stats.runqueue_inversions;
    let recorded: u64 = workloads.iter().map(|(_, counts)| counts.total()).sum();
    if inversions * 100 > recorded {
        bail!("{inversions} run-queue readings dropped out of {recorded} intervals");
    }
    for (name, counts) in workloads {
        if counts.unsplit > inversions {
            bail!(
                "{name}: {} intervals have no run-queue part",
                counts.unsplit
            );
        }
        // rq_clock may be stale when a runnable task departs, so a runnable or preempted interval's reading
        // can exceed its duration slightly; a blocked interval queues only at its wakeup and must not.
        let blocked_overshoots: u64 = counts
            .overshoots
            .iter()
            .filter(|(bucket, _)| bucket.starts_with("blocked"))
            .map(|(_, count)| *count)
            .sum();
        if blocked_overshoots * 100 > counts.blocked.max(1) {
            bail!(
                "{name}: {blocked_overshoots} blocked intervals queued for longer than they lasted"
            );
        }
    }
    // Each sleeper's rows cover all of its intervals in the phase, so their run-queue parts must add up to
    // the growth of its /proc run delay, up to the intervals cut by the phase boundaries.
    for (name, counts) in [
        ("sleeper", &every.sleeper),
        ("contended sleeper", &every.contended_sleeper),
    ] {
        let proc = counts.proc_run_delay_ns.unwrap_or(0);
        let recorded = counts.runqueue_ns();
        let tolerance = proc / 10 + 2 * counts.max_runqueue_ns + 1_000_000;
        if recorded.abs_diff(proc) > tolerance {
            bail!(
                "{name}: recorded run-queue time {recorded} ns does not match the /proc run delay \
                 growth {proc} ns"
            );
        }
    }
    let contended = every.contended_sleeper.mean_blocked_runqueue_ns();
    let uncontended = every.sleeper.mean_blocked_runqueue_ns();
    if every.contended_sleeper.blocked == 0 || contended < 100_000 || contended < 10 * uncontended {
        bail!(
            "the contended sleeper must wait far longer for a CPU ({contended} ns per wakeup) than \
             the uncontended one ({uncontended} ns)"
        );
    }
    let spinners = &every.spinners;
    if spinners.other_runqueue_ns * 10 < spinners.other_duration_ns * 9 {
        bail!(
            "runnable and preempted spinner intervals must be run-queue time nearly throughout \
             ({} of {} ns)",
            spinners.other_runqueue_ns,
            spinners.other_duration_ns
        );
    }
    let off = &result.split_off;
    for counts in [
        &off.sleeper,
        &off.contended_sleeper,
        &off.yielder,
        &off.spinners,
    ] {
        if counts.unsplit != counts.total() {
            bail!("with the split off, no interval may carry a run-queue part");
        }
    }
    if off.stats.runqueue_inversions != 0 {
        bail!("with the split off, nothing may be read or dropped");
    }
    Ok(())
}

/// The second field of the thread's schedstat: its cumulative run-queue wait, `sched_info.run_delay`.
fn proc_run_delay(tid: u32) -> Result<u64> {
    let path = format!("/proc/self/task/{tid}/schedstat");
    let text = fs::read_to_string(&path).with_context(|| format!("read {path}"))?;
    text.split_whitespace()
        .nth(1)
        .context("schedstat has no run delay")?
        .parse()
        .context("parse schedstat run delay")
}

fn allowed_cpus() -> Result<Vec<usize>> {
    let mut set: libc::cpu_set_t = unsafe { std::mem::zeroed() };
    if unsafe { libc::sched_getaffinity(0, size_of::<libc::cpu_set_t>(), &mut set) } != 0 {
        return Err(std::io::Error::last_os_error()).context("sched_getaffinity");
    }
    let cpus: Vec<usize> = (0..libc::CPU_SETSIZE as usize)
        .filter(|cpu| unsafe { libc::CPU_ISSET(*cpu, &set) })
        .collect();
    if cpus.is_empty() {
        bail!("no CPU available");
    }
    Ok(cpus)
}

fn pin(cpu: usize) {
    unsafe {
        let mut set: libc::cpu_set_t = std::mem::zeroed();
        libc::CPU_SET(cpu, &mut set);
        libc::sched_setaffinity(0, size_of::<libc::cpu_set_t>(), &set);
    }
}

fn current_tid() -> u32 {
    unsafe { libc::syscall(libc::SYS_gettid) as u32 }
}

fn install_signal_handler() -> Result<()> {
    unsafe {
        let mut action: libc::sigaction = std::mem::zeroed();
        action.sa_flags = libc::SA_SIGINFO | libc::SA_RESTART;
        action.sa_sigaction = signal_handler as usize;
        libc::sigemptyset(&mut action.sa_mask);
        if libc::sigaction(libc::SIGPROF, &action, std::ptr::null_mut()) != 0 {
            return Err(std::io::Error::last_os_error()).context("install SIGPROF handler");
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
    let mut total = [0u64; size_of::<KernelStats>() / size_of::<u64>()];
    for value in values {
        if value.len() != size_of::<KernelStats>() {
            bail!("unexpected stats value size {}", value.len());
        }
        for (slot, raw) in total.iter_mut().zip(value.chunks_exact(size_of::<u64>())) {
            *slot += u64::from_ne_bytes(raw.try_into().unwrap());
        }
    }
    Ok(unsafe {
        std::mem::transmute::<[u64; size_of::<KernelStats>() / size_of::<u64>()], KernelStats>(
            total,
        )
    })
}
