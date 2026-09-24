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

/// The capture stream and collector protocol schemas, generated from
/// jonoffcpu-capture-codec/src/main/proto/jonoffcpu-capture.proto and jonoffcpu-collector.proto,
/// which share one package. Every message also has the proto3 JSON mapping through serde
/// (lowerCamelCase names, 64-bit integers as decimal strings, enums by value name), which is how
/// the proof tools print records and replies.
#[allow(clippy::all)]
pub mod capture {
    include!(concat!(
        env!("OUT_DIR"),
        "/io.github.jonoffcpu.capture.v1.rs"
    ));
    include!(concat!(
        env!("OUT_DIR"),
        "/io.github.jonoffcpu.capture.v1.serde.rs"
    ));

    /// Header written once at the start of a capture file: the magic, a zero byte, and the format
    /// version. A reader that does not find it is looking at something else entirely.
    pub const MAGIC: &[u8; 10] = b"JONOFFCPU\0";
    pub const FORMAT_VERSION: u16 = 2;
    pub const HEADER_LEN: usize = MAGIC.len() + 2;

    pub fn header() -> [u8; HEADER_LEN] {
        let mut bytes = [0u8; HEADER_LEN];
        bytes[..MAGIC.len()].copy_from_slice(MAGIC);
        bytes[MAGIC.len()..].copy_from_slice(&FORMAT_VERSION.to_le_bytes());
        bytes
    }

    /// Decodes a whole capture file. Proof tools and tests read streams this way; the correlator has
    /// its own reader that also reports a truncated tail instead of rejecting it.
    pub fn decode(bytes: &[u8]) -> anyhow::Result<Vec<Record>> {
        use anyhow::{Context, bail};
        use prost::Message;
        if bytes.len() < HEADER_LEN || &bytes[..MAGIC.len()] != MAGIC {
            bail!("not a jonoffcpu capture stream");
        }
        let version = u16::from_le_bytes([bytes[MAGIC.len()], bytes[MAGIC.len() + 1]]);
        if version != FORMAT_VERSION {
            bail!("unsupported capture format version {version}");
        }
        let mut rest = &bytes[HEADER_LEN..];
        let mut records = Vec::new();
        while !rest.is_empty() {
            records
                .push(Record::decode_length_delimited(&mut rest).context("decode capture record")?);
        }
        Ok(records)
    }
}

mod collector;

/// The C ABI version, carried in every `JonoffcpuResult` and every `CollectorReply`. Version 2
/// exchanges encoded protobuf messages instead of JSON.
pub const ABI_VERSION: u32 = 2;

/// The bound on an encoded request and on an encoded reply.
pub const MAX_MESSAGE_BYTES: usize = 64 * 1024;

/// The longest error message a reply carries, so an error reply always fits the bound.
const MAX_ERROR_MESSAGE_BYTES: usize = 8 * 1024;

use capture::{CollectorErrorCode as ErrorCode, CollectorReply};
use prost::Message;
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

/// The result of every C ABI call: the status the call returned and the encoded `CollectorReply`,
/// owned by the library until `jonoffcpu_result_free`.
#[repr(C)]
pub struct JonoffcpuResult {
    pub struct_size: u32,
    pub abi_version: u32,
    pub code: i32,
    pub reserved: u32,
    pub bytes: *mut u8,
    pub len: usize,
}

impl Default for JonoffcpuResult {
    fn default() -> Self {
        Self {
            struct_size: std::mem::size_of::<Self>() as u32,
            abi_version: ABI_VERSION,
            code: STATUS_INTERNAL_ERROR,
            reserved: 0,
            bytes: ptr::null_mut(),
            len: 0,
        }
    }
}

impl JonoffcpuResult {
    /// The reply bytes, or an empty slice before a call filled the result.
    pub fn reply_bytes(&self) -> &[u8] {
        if self.bytes.is_null() {
            &[]
        } else {
            unsafe { slice::from_raw_parts(self.bytes, self.len) }
        }
    }
}

#[unsafe(no_mangle)]
/// Prepare a disabled native collector from an encoded `PrepareRequest`.
///
/// # Safety
/// `request` must reference `len` readable bytes and `out` must reference writable
/// storage for one `JonoffcpuResult`.
pub unsafe extern "C" fn jonoffcpu_collector_prepare(
    request: *const u8,
    len: usize,
    out: *mut JonoffcpuResult,
) -> i32 {
    unsafe {
        ffi_call(out, || {
            let input = input_bytes(request, len)?;
            let prepared = collector::prepare(collector::parse_prepare(input)?)?;
            Ok(prepared.reply)
        })
    }
}

#[unsafe(no_mangle)]
/// Enable a prepared collector with an encoded `EnableRequest`.
///
/// # Safety
/// `request` must reference `len` readable bytes and `out` must reference writable
/// storage for one `JonoffcpuResult`.
pub unsafe extern "C" fn jonoffcpu_collector_enable(
    handle: u64,
    request: *const u8,
    len: usize,
    out: *mut JonoffcpuResult,
) -> i32 {
    unsafe {
        ffi_call(out, || {
            let input = input_bytes(request, len)?;
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
                return Err(collector::fail(
                    ErrorCode::InvalidConfig,
                    "stop timeout must be nonzero",
                ));
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
/// Free the reply storage returned in a `JonoffcpuResult` and reset the structure.
///
/// # Safety
/// `result` must be null or point to a result last initialized by this library
/// and not freed since that initialization.
pub unsafe extern "C" fn jonoffcpu_result_free(result: *mut JonoffcpuResult) {
    if result.is_null() {
        return;
    }
    let result = unsafe { &mut *result };
    if !result.bytes.is_null() {
        drop(unsafe { Box::from_raw(ptr::slice_from_raw_parts_mut(result.bytes, result.len)) });
    }
    *result = JonoffcpuResult::default();
    result.code = STATUS_OK;
}

/// The request bytes. An empty request is a message with every field at its default, which the
/// request's validation then rejects.
unsafe fn input_bytes<'a>(request: *const u8, len: usize) -> anyhow::Result<&'a [u8]> {
    if len == 0 {
        return Ok(&[]);
    }
    if request.is_null() || len > MAX_MESSAGE_BYTES {
        return Err(collector::fail(
            ErrorCode::InvalidConfig,
            format!("request must be at most {MAX_MESSAGE_BYTES} readable bytes"),
        ));
    }
    Ok(unsafe { slice::from_raw_parts(request, len) })
}

/// Runs one call and stores its encoded reply. Every outcome, including a panic, is a reply; the
/// returned status is zero exactly when the reply is not an error.
unsafe fn ffi_call(
    out: *mut JonoffcpuResult,
    operation: impl FnOnce() -> anyhow::Result<CollectorReply>,
) -> i32 {
    if out.is_null() {
        return STATUS_INVALID_CONFIG;
    }
    unsafe { ptr::write(out, JonoffcpuResult::default()) };
    let reply = match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(Ok(reply)) => reply,
        Ok(Err(error)) => {
            let failure = collector::Failure::of(&error);
            collector::error_reply(failure.code, bounded_message(failure.message))
        }
        Err(_) => collector::error_reply(
            ErrorCode::InternalError,
            "native collector panicked".to_string(),
        ),
    };
    let mut encoded = reply.encode_to_vec();
    let mut status = match &reply.result {
        Some(capture::collector_reply::Result::Error(error)) => status_of(error.code()),
        _ => STATUS_OK,
    };
    if encoded.len() > MAX_MESSAGE_BYTES {
        encoded = collector::error_reply(
            ErrorCode::InternalError,
            format!("collector reply exceeds {MAX_MESSAGE_BYTES} bytes"),
        )
        .encode_to_vec();
        status = STATUS_INTERNAL_ERROR;
    }
    let length = encoded.len();
    let output = unsafe { &mut *out };
    output.code = status;
    output.len = length;
    output.bytes = Box::into_raw(encoded.into_boxed_slice()).cast::<u8>();
    status
}

/// Truncates an error message at a character boundary, so an error reply stays within the bound.
fn bounded_message(mut message: String) -> String {
    if message.len() > MAX_ERROR_MESSAGE_BYTES {
        let mut end = MAX_ERROR_MESSAGE_BYTES;
        while !message.is_char_boundary(end) {
            end -= 1;
        }
        message.truncate(end);
    }
    message
}

/// The C status of an error reply, kept from ABI version 1 so the codes stay stable.
fn status_of(code: ErrorCode) -> i32 {
    match code {
        ErrorCode::InvalidConfig => STATUS_INVALID_CONFIG,
        ErrorCode::InvalidHandle => STATUS_INVALID_HANDLE,
        ErrorCode::InvalidState | ErrorCode::TargetExited => STATUS_INVALID_STATE,
        ErrorCode::BpfUnsupported => STATUS_BPF_UNSUPPORTED,
        ErrorCode::IoError => STATUS_IO_ERROR,
        ErrorCode::StopTimeout | ErrorCode::CloseTimeout => STATUS_STOP_TIMEOUT,
        ErrorCode::InternalError | ErrorCode::Unspecified => STATUS_INTERNAL_ERROR,
    }
}

/// Calls one C ABI entry point in-process and decodes its reply, for the proof tools. The status
/// must agree with the reply: zero exactly when the reply is not an error.
pub fn call_collector(
    operation: impl FnOnce(*mut JonoffcpuResult) -> i32,
) -> anyhow::Result<(i32, CollectorReply)> {
    let mut result = JonoffcpuResult::default();
    let status = operation(&mut result);
    let decoded = CollectorReply::decode(result.reply_bytes());
    unsafe { jonoffcpu_result_free(&mut result) };
    let reply = decoded.map_err(|error| anyhow::anyhow!("undecodable collector reply: {error}"))?;
    let is_error = matches!(
        reply.result,
        Some(capture::collector_reply::Result::Error(_))
    );
    if reply.abi_version != ABI_VERSION || (status == STATUS_OK) == is_error {
        anyhow::bail!("collector status {status} disagrees with its reply: {reply:?}");
    }
    Ok((status, reply))
}
