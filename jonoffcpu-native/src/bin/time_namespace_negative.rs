// SPDX-License-Identifier: MIT
use anyhow::{Result, bail};
use jonoffcpu_native::capture::{self, collector_reply::Result as Reply};
use jonoffcpu_native::{call_collector, jonoffcpu_collector_prepare};
use prost::Message;

fn main() -> Result<()> {
    let path = format!("/tmp/jonoffcpu-time-namespace-negative-{}.pb", unsafe {
        libc::getpid()
    });
    let request = capture::PrepareRequest {
        target_pid: unsafe { libc::getpid() } as u32,
        output_path: path,
        sampling: Some(capture::Sampling {
            reasons: vec![
                capture::OffCpuReason::Blocked as i32,
                capture::OffCpuReason::Runnable as i32,
                capture::OffCpuReason::Preempted as i32,
            ],
            min_off_cpu_micros: None,
            max_off_cpu_micros: None,
            admission: Some(capture::sampling::Admission::Uniform(
                capture::UniformAdmission {
                    probability: "0.0000000003".to_string(),
                    probability_threshold: 1,
                },
            )),
        }),
        time_split: Some(capture::TimeSplit {
            source: capture::TimeSplitSource::SchedInfo as i32,
        }),
        exclude_calling_thread: false,
    }
    .encode_to_vec();
    let (status, reply) = call_collector(|out| unsafe {
        jonoffcpu_collector_prepare(request.as_ptr(), request.len(), out)
    })?;
    let rejected = match &reply.result {
        Some(Reply::Error(error)) => {
            error.code() == capture::CollectorErrorCode::InvalidConfig
                && error
                    .message
                    .contains("nonzero monotonic time namespace offset")
        }
        _ => false,
    };
    if status == 0 || !rejected {
        bail!(
            "collector did not reject nonzero time namespace offset: {}",
            serde_json::to_string(&reply)?
        );
    }
    println!("{}", serde_json::to_string_pretty(&reply)?);
    Ok(())
}
