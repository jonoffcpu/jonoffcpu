// SPDX-License-Identifier: MIT
#[allow(clippy::all, dead_code, non_camel_case_types, non_snake_case)]
pub mod bpf {
    include!(concat!(env!("OUT_DIR"), "/jonoffcpu_cookie.skel.rs"));
}

#[allow(clippy::all, dead_code, non_camel_case_types, non_snake_case)]
pub mod bpf_control {
    include!(concat!(
        env!("OUT_DIR"),
        "/jonoffcpu_cookie_control.skel.rs"
    ));
}

#[allow(clippy::all, dead_code, non_camel_case_types, non_snake_case)]
pub mod bpf_finish_fentry {
    include!(concat!(
        env!("OUT_DIR"),
        "/jonoffcpu_cookie_finish_fentry.skel.rs"
    ));
}

#[allow(clippy::all, dead_code, non_camel_case_types, non_snake_case)]
pub mod bpf_sched_exit {
    include!(concat!(
        env!("OUT_DIR"),
        "/jonoffcpu_cookie_sched_exit.skel.rs"
    ));
}

#[allow(clippy::all, dead_code, non_camel_case_types, non_snake_case)]
pub mod bpf_endpoint_boundary {
    include!(concat!(env!("OUT_DIR"), "/endpoint_boundary.skel.rs"));
}

mod collector;

pub const ABI_VERSION: u32 = 1;

use serde_json::Value;
use std::ffi::CString;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;
use std::time::Duration;

const STATUS_OK: i32 = 0;
const STATUS_INVALID_CONFIG: i32 = 1;
const STATUS_INVALID_HANDLE: i32 = 2;
const STATUS_INVALID_STATE: i32 = 3;
const STATUS_BPF_UNSUPPORTED: i32 = 4;
const STATUS_IO_ERROR: i32 = 5;
const STATUS_INTERNAL_ERROR: i32 = 6;
const STATUS_STOP_TIMEOUT: i32 = 7;

#[repr(C)]
pub struct JonoffcpuResult {
    pub struct_size: u32,
    pub abi_version: u32,
    pub code: i32,
    pub reserved: u32,
    pub json: *mut libc::c_char,
    pub json_len: usize,
}

impl Default for JonoffcpuResult {
    fn default() -> Self {
        Self {
            struct_size: std::mem::size_of::<Self>() as u32,
            abi_version: ABI_VERSION,
            code: STATUS_INTERNAL_ERROR,
            reserved: 0,
            json: ptr::null_mut(),
            json_len: 0,
        }
    }
}

#[unsafe(no_mangle)]
/// Prepare a disabled native collector from UTF-8 JSON.
///
/// # Safety
/// `json` must reference `len` readable bytes and `out` must reference writable
/// storage for one `JonoffcpuResult`.
pub unsafe extern "C" fn jonoffcpu_collector_prepare(
    json: *const libc::c_char,
    len: usize,
    out: *mut JonoffcpuResult,
) -> i32 {
    unsafe {
        ffi_call(out, || {
            let input = input_json(json, len)?;
            let prepared = collector::prepare(collector::parse_prepare(input)?)?;
            let _ = prepared.handle;
            Ok(prepared.response)
        })
    }
}

#[unsafe(no_mangle)]
/// Configure and enable a prepared collector.
///
/// # Safety
/// `json` must reference `len` readable bytes and `out` must reference writable
/// storage for one `JonoffcpuResult`.
pub unsafe extern "C" fn jonoffcpu_collector_enable(
    handle: u64,
    json: *const libc::c_char,
    len: usize,
    out: *mut JonoffcpuResult,
) -> i32 {
    unsafe {
        ffi_call(out, || {
            let input = input_json(json, len)?;
            collector::enable(handle, collector::parse_enable(input)?)
        })
    }
}

#[unsafe(no_mangle)]
/// Quiesce, detach, drain, and finalize a collector capture.
///
/// # Safety
/// `out` must reference writable storage for one `JonoffcpuResult`.
pub unsafe extern "C" fn jonoffcpu_collector_stop(
    handle: u64,
    timeout_ms: u64,
    out: *mut JonoffcpuResult,
) -> i32 {
    unsafe {
        ffi_call(out, || {
            if timeout_ms == 0 {
                anyhow::bail!("stop timeout must be nonzero");
            }
            collector::stop(handle, Duration::from_millis(timeout_ms))
        })
    }
}

#[unsafe(no_mangle)]
/// Release the collector and close its source artifact.
///
/// # Safety
/// `out` must reference writable storage for one `JonoffcpuResult`.
pub unsafe extern "C" fn jonoffcpu_collector_close(handle: u64, out: *mut JonoffcpuResult) -> i32 {
    unsafe { ffi_call(out, || collector::close(handle)) }
}

#[unsafe(no_mangle)]
/// Free JSON storage returned in a `JonoffcpuResult` and reset the structure.
///
/// # Safety
/// `result` must be null or point to a result last initialized by this library
/// and not freed since that initialization.
pub unsafe extern "C" fn jonoffcpu_result_free(result: *mut JonoffcpuResult) {
    if result.is_null() {
        return;
    }
    let result = unsafe { &mut *result };
    if !result.json.is_null() {
        drop(unsafe { CString::from_raw(result.json) });
    }
    *result = JonoffcpuResult::default();
    result.code = STATUS_OK;
}

unsafe fn input_json<'a>(json: *const libc::c_char, len: usize) -> anyhow::Result<&'a str> {
    if json.is_null() || len == 0 || len > 64 * 1024 {
        anyhow::bail!("JSON input pointer/length is invalid");
    }
    std::str::from_utf8(unsafe { slice::from_raw_parts(json.cast::<u8>(), len) })
        .map_err(|_| anyhow::anyhow!("JSON input is not UTF-8"))
}

unsafe fn ffi_call(
    out: *mut JonoffcpuResult,
    operation: impl FnOnce() -> anyhow::Result<Value>,
) -> i32 {
    if out.is_null() {
        return STATUS_INVALID_CONFIG;
    }
    unsafe { ptr::write(out, JonoffcpuResult::default()) };
    let result = catch_unwind(AssertUnwindSafe(operation));
    let (status, value) = match result {
        Ok(Ok(value)) => (STATUS_OK, value),
        Ok(Err(error)) => {
            let message = format!("{error:#}");
            let (status, code) = classify_error(&message);
            (status, collector::control_error(code, message))
        }
        Err(_) => (
            STATUS_INTERNAL_ERROR,
            collector::control_error("internal_error", "native collector panicked"),
        ),
    };
    let encoded = collector::bounded_json(&value).unwrap_or_else(|_| {
        "{\"ok\":false,\"schemaVersion\":1,\"abiVersion\":1,\"state\":\"error\",\"error\":{\"code\":\"internal_error\",\"message\":\"response encoding failed\"}}".to_string()
    });
    let encoded = CString::new(encoded).expect("JSON encoder emitted a NUL byte");
    let length = encoded.as_bytes().len();
    let output = unsafe { &mut *out };
    output.code = status;
    output.json_len = length;
    output.json = encoded.into_raw();
    status
}

fn classify_error(message: &str) -> (i32, &'static str) {
    if message.contains("target_exited") {
        (STATUS_INVALID_STATE, "target_exited")
    } else if message.contains("invalid collector handle") {
        (STATUS_INVALID_HANDLE, "invalid_handle")
    } else if message.contains("already enabled")
        || message.contains("already stopped")
        || message.contains("not enabled")
    {
        (STATUS_INVALID_STATE, "invalid_state")
    } else if message.contains("stop_timeout") || message.contains("stop timed out") {
        (STATUS_STOP_TIMEOUT, "stop_timeout")
    } else if message.contains("close_timeout") || message.contains("close timed out") {
        (STATUS_STOP_TIMEOUT, "close_timeout")
    } else if message.contains("BPF") || message.contains("bpf") || message.contains("attach") {
        (STATUS_BPF_UNSUPPORTED, "bpf_unsupported")
    } else if message.contains("source artifact")
        || message.contains("fsync")
        || message.contains("flush")
        || message.contains("No such file")
        || message.contains("Permission denied")
    {
        (STATUS_IO_ERROR, "io_error")
    } else if message.contains("JSON")
        || message.contains("must")
        || message.contains("differs")
        || message.contains("time namespace")
    {
        (STATUS_INVALID_CONFIG, "invalid_config")
    } else {
        (STATUS_INTERNAL_ERROR, "internal_error")
    }
}
