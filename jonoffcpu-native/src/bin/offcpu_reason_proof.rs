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
    }

    fn total(&self) -> u64 {
        self.blocked + self.runnable + self.preempted + self.invalid
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PhaseResult {
    reason_mask: u32,
    sleeper: ReasonCounts,
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
}

extern "C" fn signal_handler(_: i32, _: *mut libc::siginfo_t, _: *mut libc::c_void) {}

/// The workload threads' TIDs, published once each has pinned itself.
#[derive(Default)]
struct Tids {
    sleeper: AtomicU32,
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

    let every_reason = run_phase(&mut skel, &ring, &events, &tids, ALL_REASONS)?;
    let blocked_only = run_phase(&mut skel, &ring, &events, &tids, BLOCKED_ONLY)?;
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
) -> Result<PhaseResult> {
    reset_stats(&skel.maps.stats)?;
    events.lock().unwrap().clear();
    {
        let bss = skel
            .maps
            .bss_data
            .as_deref_mut()
            .context("missing BPF bss")?;
        bss.reason_mask = reason_mask;
        bss.enabled = 1;
    }
    let deadline = Instant::now() + PHASE;
    while Instant::now() < deadline {
        thread::sleep(Duration::from_millis(2));
        ring.consume().context("consume observation ring")?;
    }
    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 0;
    thread::sleep(Duration::from_millis(20));
    ring.consume().context("drain observation ring")?;
    let stats = read_stats(&skel.maps.stats)?;

    let sleeper = tids.sleeper.load(Ordering::Acquire);
    let yielder = tids.yielder.load(Ordering::Acquire);
    let spinners = tids.spinners.lock().unwrap().clone();
    let mut result = PhaseResult {
        reason_mask,
        sleeper: ReasonCounts::default(),
        yielder: ReasonCounts::default(),
        spinners: ReasonCounts::default(),
        stats,
    };
    for event in events.lock().unwrap().iter() {
        if event.target_tid == sleeper {
            result.sleeper.add(event);
        } else if event.target_tid == yielder {
            result.yielder.add(event);
        } else if spinners.contains(&event.target_tid) {
            result.spinners.add(event);
        }
    }
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
