// SPDX-License-Identifier: MIT
use anyhow::{Context, Result};
use libbpf_cargo::SkeletonBuilder;
use std::env;
use std::fs::{self, File};
use std::path::PathBuf;
use std::process::{Command, Stdio};

fn main() -> Result<()> {
    let out = PathBuf::from(env::var_os("OUT_DIR").context("OUT_DIR is not set")?);
    link_static_gcc_unwind(&out)?;
    let vmlinux = out.join("vmlinux.h");
    let file = File::create(&vmlinux).context("create generated vmlinux.h")?;
    let status = Command::new(env::var_os("BPFTOOL").unwrap_or_else(|| "bpftool".into()))
        .args([
            "btf",
            "dump",
            "file",
            "/sys/kernel/btf/vmlinux",
            "format",
            "c",
        ])
        .stdout(Stdio::from(file))
        .status()
        .context("run bpftool to generate vmlinux.h from the build host BTF")?;
    if !status.success() {
        anyhow::bail!("bpftool failed while generating vmlinux.h: {status}");
    }

    let common_args = [
        format!("-I{}", out.display()),
        "-Isrc/bpf/include".to_string(),
        "-O2".to_string(),
        "-g".to_string(),
        "-Wall".to_string(),
        "-Werror".to_string(),
        "-Wno-missing-declarations".to_string(),
    ];
    SkeletonBuilder::new()
        .source("src/bpf/jonoffcpu_cookie.bpf.c")
        .clang_args(common_args.clone())
        .build_and_generate(out.join("jonoffcpu_cookie.skel.rs"))
        .context("compile CO-RE BPF and generate libbpf skeleton")?;
    SkeletonBuilder::new()
        .source("src/bpf/jonoffcpu_cookie.bpf.c")
        .clang_args(
            common_args
                .clone()
                .into_iter()
                .chain(["-DJONOFFCPU_FINISH_FENTRY".to_string()]),
        )
        .build_and_generate(out.join("jonoffcpu_cookie_finish_fentry.skel.rs"))
        .context("compile finish_task_switch fentry object")?;
    SkeletonBuilder::new()
        .source("src/bpf/jonoffcpu_cookie.bpf.c")
        .clang_args(
            common_args
                .clone()
                .into_iter()
                .chain(["-DJONOFFCPU_SCHED_EXIT_TP_BTF".to_string()]),
        )
        .build_and_generate(out.join("jonoffcpu_cookie_sched_exit.skel.rs"))
        .context("compile sched_exit_tp BTF tracepoint object")?;
    SkeletonBuilder::new()
        .source("src/bpf/jonoffcpu_cookie.bpf.c")
        .clang_args(
            common_args
                .clone()
                .into_iter()
                .chain(["-DJONOFFCPU_CONTROL_FENTRY".to_string()]),
        )
        .build_and_generate(out.join("jonoffcpu_cookie_control.skel.rs"))
        .context("compile labelled fentry control object")?;
    SkeletonBuilder::new()
        .source("src/bpf/endpoint_boundary.bpf.c")
        .clang_args(common_args)
        .build_and_generate(out.join("endpoint_boundary.skel.rs"))
        .context("compile finish_task_switch to sched_exit_tp measurement object")?;

    generate_capture_codec(&out)?;

    println!("cargo:rerun-if-changed=src/bpf/jonoffcpu_cookie.bpf.c");
    println!("cargo:rerun-if-changed=src/bpf/include/jonoffcpu_cookie.h");
    println!("cargo:rerun-if-changed=src/bpf/endpoint_boundary.bpf.c");
    Ok(())
}

/// Compiles the capture stream and collector protocol schemas with protox, a pure-Rust protobuf
/// compiler, so the pinned build containers need no protoc. prost generates the messages and
/// pbjson-build their proto3 JSON mapping, which the proof tools print. The imported
/// `google/protobuf/timestamp.proto` comes from protox's bundled well-known types and maps to
/// pbjson-types, whose `Timestamp` is a prost message that also has the proto3 JSON mapping.
fn generate_capture_codec(out: &std::path::Path) -> Result<()> {
    const PROTO_DIR: &str = "../jonoffcpu-capture-codec/src/main/proto";
    const SCHEMAS: [&str; 2] = ["jonoffcpu-capture.proto", "jonoffcpu-collector.proto"];
    let mut compiler = protox::Compiler::new([PROTO_DIR]).context("configure protox")?;
    compiler.include_imports(true);
    compiler
        .open_files(SCHEMAS)
        .context("compile capture and collector schemas")?;
    let encoded = compiler.encode_file_descriptor_set();
    prost_build::Config::new()
        .out_dir(out)
        .extern_path(".google.protobuf.Timestamp", "::pbjson_types::Timestamp")
        .compile_fds(compiler.file_descriptor_set())
        .context("generate capture codec")?;
    pbjson_build::Builder::new()
        .register_descriptors(&encoded)
        .context("register schema descriptors for pbjson")?
        .out_dir(out)
        .extern_path(".google.protobuf.Timestamp", "::pbjson_types::Timestamp")
        .build(&[".io.github.jonoffcpu.capture.v1"])
        .context("generate proto3 JSON mapping")?;
    for schema in SCHEMAS {
        println!("cargo:rerun-if-changed={PROTO_DIR}/{schema}");
    }
    Ok(())
}

fn link_static_gcc_unwind(out: &std::path::Path) -> Result<()> {
    if env::var_os("CARGO_CFG_TARGET_OS").as_deref() != Some(std::ffi::OsStr::new("linux"))
        || env::var_os("CARGO_CFG_TARGET_ENV").as_deref() != Some(std::ffi::OsStr::new("gnu"))
    {
        return Ok(());
    }

    // rustc links GNU targets against libgcc_s for the unwinder by default. The
    // native backend is distributed inside the agent JAR, so copy the toolchain's
    // matching static unwind archive under that link name and put it first on the
    // native-library search path. This keeps glibc as the only platform runtime
    // dependency without attempting to statically link glibc itself.
    let compiler = cc::Build::new().get_compiler();
    let output = compiler
        .to_command()
        .arg("-print-file-name=libgcc_eh.a")
        .output()
        .context("locate the target toolchain's static GCC unwind archive")?;
    if !output.status.success() {
        anyhow::bail!(
            "{} failed to locate libgcc_eh.a: {}",
            compiler.path().display(),
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    let source = PathBuf::from(String::from_utf8(output.stdout)?.trim());
    if !source.is_file() {
        anyhow::bail!(
            "{} returned a missing static GCC unwind archive: {}",
            compiler.path().display(),
            source.display()
        );
    }
    let unwind_dir = out.join("static-unwind");
    fs::create_dir_all(&unwind_dir).context("create static unwind link directory")?;
    fs::copy(&source, unwind_dir.join("libgcc_s.a")).context("stage static GCC unwind archive")?;
    println!("cargo:rustc-link-search=native={}", unwind_dir.display());
    Ok(())
}
