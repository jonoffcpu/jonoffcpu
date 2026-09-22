#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Exercise native-agent finalization from JVM shutdown without an explicit stop call."""

import argparse
import json
import os
import subprocess
import time
from pathlib import Path


MODES = ("return", "exit", "sigterm")


def run(command, log, cwd=None):
    with log.open("w") as output:
        subprocess.run([str(arg) for arg in command], cwd=cwd, stdout=output,
                       stderr=subprocess.STDOUT, check=True)


def java_command(module, ap, jdk, case, mode, seconds):
    build = module / "build"
    return [
        "docker", "run", "--rm", "--privileged", "--memory=1g", "--ulimit", "core=0",
        "-v", f"{build}:/agent:ro", "-v", f"{ap}:/ap:ro", "-v", f"{jdk}:/jdk:ro",
        "-v", "/sys/kernel/btf:/sys/kernel/btf:ro",
        "-v", "/sys/kernel/tracing:/sys/kernel/tracing",
        "-v", f"{case}:/out", "-w", "/out", "jonoffcpu-agent-runtime:ubuntu24.04",
        "/jdk/bin/java", "--enable-native-access=ALL-UNNAMED", "-Xms128m", "-Xmx256m",
        "-agentpath:/agent/lib/libjonoffcpu.so=jonoffcpuoutput=/out/jonoffcpu-capture.ndjson,"
        "shutdowntimeoutmillis=30000,nativestoptimeoutmillis=10000,"
        "asprofpath=/ap/build/lib/libasyncProfiler.so,event=cpu,alloc=1m,wall=10ms,"
        "lock=1ms,jfrsync=profile,file=/out/jonoffcpu-capture.jfr",
        "-cp", "/agent/jonoffcpu-agent.jar:/agent/test-classes",
        "io.github.lhotari.jonoffcpu.agent.NativeAgentShutdownWorkload", mode, str(seconds), "/out/ready",
    ]


def run_sigterm(command, case):
    name = f"jonoffcpu-shutdown-{os.getpid()}-{time.time_ns()}"
    command[2:2] = ["--name", name]
    (case / "command.json").write_text(json.dumps(command, indent=2) + "\n")
    with (case / "process.log").open("w") as output:
        process = subprocess.Popen([str(arg) for arg in command], stdout=output,
                                   stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 60
        while not (case / "ready").is_file():
            if process.poll() is not None:
                raise RuntimeError(f"SIGTERM target exited before ready with {process.returncode}")
            if time.monotonic() >= deadline:
                subprocess.run(["docker", "kill", name], check=False)
                raise TimeoutError("SIGTERM target did not become ready")
            time.sleep(0.1)
        time.sleep(2)
        stop = subprocess.run(["docker", "stop", "--time", "45", name],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        (case / "docker-stop.log").write_text(stop.stdout)
        if stop.returncode != 0:
            raise RuntimeError(f"docker stop failed with {stop.returncode}")
        return_code = process.wait(timeout=60)
        (case / "process-exit.json").write_text(json.dumps({"returnCode": return_code}) + "\n")
        if return_code not in (0, 143):
            raise RuntimeError(f"SIGTERM container exited with {return_code}")


def make_readable(case):
    subprocess.run([
        "docker", "run", "--rm", "-v", f"{case}:/out",
        "jonoffcpu-agent-runtime:ubuntu24.04", "chmod", "-R", "a+rX", "/out",
    ], check=True)


def verify_footer(case):
    source = case / "jonoffcpu-capture.ndjson"
    data = source.read_bytes()
    if not data.endswith(b"\n"):
        raise RuntimeError("Correlation artifact is not LF terminated")
    rows = [json.loads(line) for line in data.splitlines()]
    footers = [row for row in rows if row.get("recordType") == "captureFinalized"]
    if len(footers) != 1 or rows[-1] is not footers[0]:
        raise RuntimeError("Expected exactly one terminal captureFinalized row")
    footer = footers[0]
    if footer.get("state") != "complete" or footer.get("analysisInputs") is None:
        raise RuntimeError("Shutdown footer is not complete")
    receipt = footer.get("apStopResponse", "")
    if " finalized=true " not in receipt or not receipt.startswith("signal-capture-v1 stopped "):
        raise RuntimeError("Shutdown footer lacks a finalized async-profiler receipt")
    manifest = json.loads((case / "jonoffcpu-capture.manifest.json").read_text())
    if manifest.get("complete") is not True or manifest.get("state") != "complete":
        raise RuntimeError("Audit manifest is incomplete")


def verify_case(module, ap, jdk, case):
    verify_footer(case)
    classpath = f"{module / 'build/jonoffcpu-agent.jar'}:{module / 'build/test-classes'}"
    run([jdk / "bin/java", "-cp", classpath, "io.github.lhotari.jonoffcpu.agent.MixedRecordingCheck",
         case / "jonoffcpu-capture.jfr", case / "event-counts.json"], case / "category-check.log")
    run([jdk / "bin/java", "-cp", classpath, "io.github.lhotari.jonoffcpu.offline.OffCpuCorrelator",
         "--source", case / "jonoffcpu-capture.ndjson", "--jfr", case / "jonoffcpu-capture.jfr",
         "--output", case / "analysis"], case / "analysis.log")
    report = json.loads((case / "analysis/jonoffcpu-report.json").read_text())
    if report["matched"] <= 0 or report["invalidSource"] or report["invalidJfr"]:
        raise RuntimeError("Shutdown artifacts did not produce verified off-CPU matches")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ap-dir", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="New output directory")
    parser.add_argument("--seconds", type=int, default=4)
    parser.add_argument("--mode", choices=("all",) + MODES, default="all")
    args = parser.parse_args()
    if not 2 <= args.seconds <= 60:
        parser.error("--seconds must be in 2..60")
    module = Path(__file__).resolve().parents[1]
    ap = args.ap_dir.resolve(strict=True)
    jdk = args.java_home.resolve(strict=True)
    output = args.output.resolve()
    required = (module / "build/lib/libjonoffcpu.so", module / "build/jonoffcpu-agent.jar",
                module / "build/test-classes/io/github/lhotari/jonoffcpu/agent/NativeAgentShutdownWorkload.class",
                ap / "build/lib/libasyncProfiler.so", jdk / "bin/java")
    for file in required:
        if not file.is_file():
            parser.error(f"Missing required artifact: {file}")
    if output.exists():
        parser.error(f"Output directory already exists: {output}")
    output.mkdir(parents=True)

    modes = MODES if args.mode == "all" else (args.mode,)
    for mode in modes:
        case = (output / mode).resolve()
        case.mkdir()
        command = java_command(module, ap, jdk, case, mode, args.seconds)
        (case / "command.json").write_text(json.dumps(command, indent=2) + "\n")
        try:
            if mode == "sigterm":
                run_sigterm(command, case)
            else:
                run(command, case / "process.log")
        finally:
            make_readable(case)
        verify_case(module, ap, jdk, case)
    print(f"Native shutdown integration passed for {', '.join(modes)} in {output}")


if __name__ == "__main__":
    main()
