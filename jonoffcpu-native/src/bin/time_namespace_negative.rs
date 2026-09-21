// SPDX-License-Identifier: MIT
use anyhow::{Result, bail};
use jonoffcpu_native::{JonoffcpuResult, jonoffcpu_collector_prepare, jonoffcpu_result_free};
use serde_json::{Value, json};

fn main() -> Result<()> {
    let path = format!("/tmp/jonoffcpu-time-namespace-negative-{}.ndjson", unsafe {
        libc::getpid()
    });
    let input = json!({
        "targetPid":unsafe { libc::getpid() },
        "outputPath":path,
        "sampleThreshold":1,
    })
    .to_string();
    let mut result = JonoffcpuResult::default();
    let status =
        unsafe { jonoffcpu_collector_prepare(input.as_ptr().cast(), input.len(), &mut result) };
    let response: Value = serde_json::from_slice(unsafe {
        std::slice::from_raw_parts(result.json.cast::<u8>(), result.json_len)
    })?;
    unsafe { jonoffcpu_result_free(&mut result) };
    if status == 0
        || response["error"]["code"] != "invalid_config"
        || !response["error"]["message"]
            .as_str()
            .unwrap_or_default()
            .contains("nonzero monotonic time namespace offset")
    {
        bail!("collector did not reject nonzero time namespace offset: {response}");
    }
    println!("{}", serde_json::to_string_pretty(&response)?);
    Ok(())
}
