// SPDX-License-Identifier: MIT
use anyhow::{Context, Result, bail};
use jonoffcpu_native::bpf_endpoint_boundary::EndpointBoundarySkelBuilder;
use libbpf_rs::RingBufferBuilder;
use libbpf_rs::skel::{OpenSkel, SkelBuilder};
use serde::Serialize;
use std::collections::HashSet;
use std::fs;
use std::mem::{MaybeUninit, size_of};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Barrier, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const WORKERS: usize = 8;
const ITERATIONS: usize = 500;

#[repr(C)]
#[derive(Clone, Copy, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct BoundarySample {
    entry_ns: u64,
    endpoint_ns: u64,
    delta_ns: u64,
    tgid: u32,
    tid: u32,
    cpu: u32,
    reserved: u32,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Counters {
    entry_calls: u64,
    switch_endpoints: u64,
    no_switch_endpoints: u64,
    paired_samples: u64,
    missing_entries: u64,
    negative_deltas: u64,
    map_update_failures: u64,
    ring_reserve_failures: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Distribution {
    count: usize,
    min: u64,
    p50: u64,
    p90: u64,
    p99: u64,
    p999: u64,
    max: u64,
    mean: f64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ResultDocument {
    schema_version: u32,
    passed: bool,
    kernel: String,
    loader: &'static str,
    clock: &'static str,
    entry_backend: &'static str,
    entry_symbol: String,
    endpoint_backend: &'static str,
    endpoint_symbol: &'static str,
    production_hook_unchanged: bool,
    target_tgid: u32,
    worker_threads: usize,
    worker_iterations: usize,
    worker_tids: Vec<u32>,
    all_target_pairs: usize,
    worker_pairs: usize,
    distribution_nanos: Distribution,
    counters: Counters,
    samples: Vec<BoundarySample>,
}

fn main() -> Result<()> {
    let symbol = finish_task_switch_symbol()?;
    let tgid = unsafe { libc::getpid() } as u32;
    let mut object = MaybeUninit::uninit();
    let mut open = EndpointBoundarySkelBuilder::default()
        .open(&mut object)
        .context("open endpoint-boundary measurement skeleton")?;
    {
        let bss = open
            .maps
            .bss_data
            .as_deref_mut()
            .context("missing BPF bss")?;
        bss.target_tgid = tgid;
        bss.enabled = 0;
    }
    let mut skel = open
        .load()
        .context("load endpoint-boundary measurement object")?;
    let _entry = skel
        .progs
        .measure_finish_task_switch_entry
        .attach_kprobe(false, &symbol)
        .with_context(|| format!("attach kprobe/{symbol}"))?;
    let _endpoint = skel
        .progs
        .measure_sched_exit_endpoint
        .attach()
        .context("attach tp_btf/sched_exit_tp endpoint")?;

    let samples = Arc::new(Mutex::new(Vec::<BoundarySample>::new()));
    let callback_samples = Arc::clone(&samples);
    let mut builder = RingBufferBuilder::new();
    builder
        .add(&skel.maps.samples, move |data| {
            if data.len() == size_of::<BoundarySample>() {
                let sample = unsafe { std::ptr::read_unaligned(data.as_ptr().cast()) };
                callback_samples.lock().unwrap().push(sample);
            }
            0
        })
        .context("register boundary sample callback")?;
    let ring = builder.build().context("build boundary sample ring")?;

    let barrier = Arc::new(Barrier::new(WORKERS + 1));
    let completed = Arc::new(AtomicUsize::new(0));
    let worker_tids = Arc::new(Mutex::new(Vec::new()));
    let mut workers = Vec::new();
    for _ in 0..WORKERS {
        let barrier = Arc::clone(&barrier);
        let completed = Arc::clone(&completed);
        let worker_tids = Arc::clone(&worker_tids);
        workers.push(thread::spawn(move || {
            worker_tids
                .lock()
                .unwrap()
                .push(unsafe { libc::syscall(libc::SYS_gettid) as u32 });
            barrier.wait();
            for _ in 0..ITERATIONS {
                thread::sleep(Duration::from_micros(100));
            }
            completed.fetch_add(1, Ordering::Release);
        }));
    }
    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 1;
    barrier.wait();
    let deadline = Instant::now() + Duration::from_secs(20);
    while completed.load(Ordering::Acquire) != WORKERS && Instant::now() < deadline {
        ring.consume().context("consume boundary samples")?;
        thread::sleep(Duration::from_millis(1));
    }
    if completed.load(Ordering::Acquire) != WORKERS {
        bail!("endpoint-boundary workers did not finish before deadline");
    }
    for worker in workers {
        worker
            .join()
            .map_err(|_| anyhow::anyhow!("measurement worker panicked"))?;
    }
    skel.maps.bss_data.as_deref_mut().unwrap().enabled = 0;
    ring.consume().context("drain boundary samples")?;

    let mut tids = worker_tids.lock().unwrap().clone();
    tids.sort_unstable();
    let tid_set = tids.iter().copied().collect::<HashSet<_>>();
    let all_samples = samples.lock().unwrap();
    let worker_samples = all_samples
        .iter()
        .filter(|sample| tid_set.contains(&sample.tid))
        .copied()
        .collect::<Vec<_>>();
    if worker_samples.len() < WORKERS * ITERATIONS * 9 / 10 {
        bail!(
            "insufficient paired worker samples: expected about {}, got {}",
            WORKERS * ITERATIONS,
            worker_samples.len()
        );
    }
    if worker_samples
        .iter()
        .any(|sample| sample.endpoint_ns < sample.entry_ns || sample.delta_ns == 0)
    {
        bail!("invalid endpoint-boundary timestamp ordering");
    }
    let bss = skel.maps.bss_data.as_deref().unwrap();
    let counters = Counters {
        entry_calls: bss.entry_calls,
        switch_endpoints: bss.switch_endpoints,
        no_switch_endpoints: bss.no_switch_endpoints,
        paired_samples: bss.paired_samples,
        missing_entries: bss.missing_entries,
        negative_deltas: bss.negative_deltas,
        map_update_failures: bss.map_update_failures,
        ring_reserve_failures: bss.ring_reserve_failures,
    };
    if counters.negative_deltas != 0
        || counters.map_update_failures != 0
        || counters.ring_reserve_failures != 0
    {
        bail!("measurement integrity counters are nonzero");
    }
    let deltas = worker_samples
        .iter()
        .map(|sample| sample.delta_ns)
        .collect::<Vec<_>>();
    let result = ResultDocument {
        schema_version: 1,
        passed: true,
        kernel: fs::read_to_string("/proc/sys/kernel/osrelease")?
            .trim()
            .to_string(),
        loader: "libbpf-rs/libbpf-cargo 0.27.1 (libbpf 1.7.0)",
        clock: "bpf_ktime_get_ns at both endpoints",
        entry_backend: "dynamic kprobe resolved from /proc/kallsyms",
        entry_symbol: symbol,
        endpoint_backend: "BTF tracepoint",
        endpoint_symbol: "tp_btf/sched_exit_tp (is_switch=true)",
        production_hook_unchanged: true,
        target_tgid: tgid,
        worker_threads: WORKERS,
        worker_iterations: ITERATIONS,
        worker_tids: tids,
        all_target_pairs: all_samples.len(),
        worker_pairs: worker_samples.len(),
        distribution_nanos: distribution(deltas),
        counters,
        samples: worker_samples,
    };
    println!("{}", serde_json::to_string_pretty(&result)?);
    Ok(())
}

fn finish_task_switch_symbol() -> Result<String> {
    fs::read_to_string("/proc/kallsyms")?
        .lines()
        .filter_map(|line| line.split_whitespace().nth(2))
        .find(|name| name.starts_with("finish_task_switch") && !name.ends_with(".cold"))
        .map(ToOwned::to_owned)
        .context("find live finish_task_switch symbol")
}

fn distribution(mut values: Vec<u64>) -> Distribution {
    values.sort_unstable();
    let sum = values.iter().map(|value| *value as u128).sum::<u128>();
    Distribution {
        count: values.len(),
        min: values[0],
        p50: percentile(&values, 500),
        p90: percentile(&values, 900),
        p99: percentile(&values, 990),
        p999: percentile(&values, 999),
        max: *values.last().unwrap(),
        mean: sum as f64 / values.len() as f64,
    }
}

fn percentile(values: &[u64], permille: usize) -> u64 {
    let rank = (values.len() * permille + 999) / 1000;
    values[rank.saturating_sub(1)]
}
