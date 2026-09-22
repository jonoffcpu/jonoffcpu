// SPDX-License-Identifier: MIT
use anyhow::{Context, Result};
use jonoffcpu_native::bpf::JonoffcpuCookieSkelBuilder;
use libbpf_rs::skel::{OpenSkel, SkelBuilder};
use std::mem::MaybeUninit;
use std::os::unix::fs::MetadataExt;

fn main() -> Result<()> {
    let mut object = MaybeUninit::uninit();
    let mut open = JonoffcpuCookieSkelBuilder::default()
        .open(&mut object)
        .context("open required KPROBE object")?;
    let bss = open
        .maps
        .bss_data
        .as_deref_mut()
        .context("missing BPF bss")?;
    bss.target_tgid = unsafe { libc::getpid() } as u32;
    let pid_namespace = std::fs::metadata("/proc/self/ns/pid")?;
    bss.target_pid_namespace_device = pid_namespace.dev();
    bss.target_pid_namespace_inode = pid_namespace.ino();
    bss.target_namespace_tgid = unsafe { libc::getpid() } as u32;
    bss.signal_number = libc::SIGPROF as u32;
    bss.capture_epoch = 0x8000_0001;
    bss.min_off_cpu_ns = 0;
    bss.has_min_off_cpu = 0;
    bss.max_off_cpu_ns = 0;
    bss.has_max_off_cpu = 0;
    bss.sample_threshold = 1u64 << 32;
    // Every switch-out reason stays eligible, as before the reason filter existed.
    bss.reason_mask = 0b1110;
    bss.next_sequence = 1;

    let _skel = open
        .load()
        .context("required finish_task_switch KPROBE verifier/load gate")?;
    println!("required finish_task_switch KPROBE object loaded successfully");
    Ok(())
}
