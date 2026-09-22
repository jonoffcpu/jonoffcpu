// SPDX-License-Identifier: MIT
use anyhow::{Context, Result, bail};
use jonoffcpu_native::bpf_control::JonoffcpuCookieSkelBuilder;
use libbpf_rs::skel::{OpenSkel, SkelBuilder};
use libbpf_rs::{MapCore, MapFlags, RingBufferBuilder, TracepointCategory};
use serde::Serialize;
use std::fs;
use std::mem::{MaybeUninit, size_of};
use std::os::fd::{FromRawFd, OwnedFd};
use std::os::unix::fs::MetadataExt;
use std::sync::atomic::{AtomicI32, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const CAPTURE_EPOCH: u32 = 0x8000_0001;
const REGISTRATION_TOKEN: u64 = 0x7151_0000_0000_0001;

static SIGNAL_COOKIE: AtomicU64 = AtomicU64::new(0);
static SIGNAL_CODE: AtomicI32 = AtomicI32::new(i32::MIN);
static SIGNAL_TID: AtomicU32 = AtomicU32::new(0);

#[repr(C)]
#[derive(Clone, Copy, Debug, Serialize)]
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

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ProofResult {
    kernel: String,
    loader: &'static str,
    hook: String,
    correlation_id: String,
    bit_63_set: bool,
    signal_number: i32,
    signal_code: i32,
    signal_code_name: &'static str,
    signal_tid: u32,
    event: Observation,
    process_lifetime_binding: &'static str,
    thread_lifetime_binding: &'static str,
}

extern "C" fn signal_handler(
    _signal: i32,
    info: *mut libc::siginfo_t,
    _context: *mut libc::c_void,
) {
    if info.is_null() {
        return;
    }
    // Only atomic stores and the gettid syscall are used in signal context.
    unsafe {
        SIGNAL_COOKIE.store((*info).si_value().sival_ptr as u64, Ordering::Relaxed);
        SIGNAL_CODE.store((*info).si_code, Ordering::Relaxed);
        SIGNAL_TID.store(libc::syscall(libc::SYS_gettid) as u32, Ordering::Relaxed);
    }
}

fn main() -> Result<()> {
    install_signal_handler()?;

    let tgid = unsafe { libc::getpid() } as u32;
    let pidfd = open_pidfd(tgid)?;
    let kprobe_symbol = finish_task_switch_symbol()?;

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
        bss.min_off_cpu_ns = 0;
        bss.has_min_off_cpu = 0;
        bss.max_off_cpu_ns = 0;
        bss.has_max_off_cpu = 0;
        bss.sample_threshold = 1u64 << 32;
        bss.next_sequence = 1;
    }
    let mut skel = open
        .load()
        .context("load CO-RE object (this is the kfunc relocation and verifier gate)")?;

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
        .attach_tracepoint(TracepointCategory::Sched, "sched_switch")
        .context("attach sched_switch tracepoint")?;
    let _switch_in = skel
        .progs
        .capture_switch_in
        .attach()
        .context("attach labelled fentry transport control at __x64_sys_clock_nanosleep")?;

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

    skel.maps
        .bss_data
        .as_deref_mut()
        .context("missing BPF bss")?
        .enabled = 1;

    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline {
        thread::sleep(Duration::from_millis(2));
        ring.consume().context("consume observation ring")?;
        if SIGNAL_COOKIE.load(Ordering::Relaxed) != 0 && !events.lock().unwrap().is_empty() {
            break;
        }
    }
    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 0;

    let event = events
        .lock()
        .unwrap()
        .iter()
        .copied()
        .find(|event| event.correlation_id == SIGNAL_COOKIE.load(Ordering::Relaxed))
        .context("no ring observation matched the delivered signal cookie")?;
    let delivered_cookie = SIGNAL_COOKIE.load(Ordering::Relaxed);
    let signal_code = SIGNAL_CODE.load(Ordering::Relaxed);
    let signal_tid = SIGNAL_TID.load(Ordering::Relaxed);

    if delivered_cookie & (1u64 << 63) == 0 {
        bail!("delivered cookie did not preserve bit 63: {delivered_cookie:016x}");
    }
    if signal_code != libc::SI_KERNEL {
        bail!(
            "expected SI_KERNEL ({}) but handler received {signal_code}",
            libc::SI_KERNEL
        );
    }
    if event.signal_result != 0 {
        bail!("bpf_send_signal_task returned {}", event.signal_result);
    }
    if event.host_tid != signal_tid {
        bail!(
            "signal reached TID {signal_tid}, expected resumed event TID {}",
            event.host_tid
        );
    }
    if event.registration_token != REGISTRATION_TOKEN {
        bail!("event was not admitted through the pidfd-bound target registration");
    }

    let result = ProofResult {
        kernel: fs::read_to_string("/proc/sys/kernel/osrelease")?
            .trim()
            .to_string(),
        loader: "libbpf-rs/libbpf-cargo 0.27.1 (libbpf 1.7.0)",
        hook: format!(
            "CONTROL_ONLY fentry/__x64_sys_clock_nanosleep; required kprobe/{kprobe_symbol} gate failed"
        ),
        correlation_id: format!("{delivered_cookie:016x}"),
        bit_63_set: true,
        signal_number: libc::SIGPROF,
        signal_code,
        signal_code_name: "SI_KERNEL",
        signal_tid,
        event,
        process_lifetime_binding: "BPF task storage seeded by pidfd for exact group leader",
        thread_lifetime_binding: "BPF task storage owned by exact task_struct",
    };
    println!("{}", serde_json::to_string_pretty(&result)?);
    Ok(())
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

fn finish_task_switch_symbol() -> Result<String> {
    let symbols = fs::read_to_string("/proc/kallsyms").context("read /proc/kallsyms")?;
    symbols
        .lines()
        .filter_map(|line| line.split_whitespace().nth(2))
        .find(|name| name.starts_with("finish_task_switch") && !name.ends_with(".cold"))
        .map(ToOwned::to_owned)
        .context("find live finish_task_switch kernel symbol")
}
