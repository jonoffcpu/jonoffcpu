#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Check abrupt JVM loss and AP-first finalization with the actual native agent."""

import argparse
import json
import os
import subprocess
import time
from pathlib import Path
def capture_rows(jdk, classpath, source):
    """The capture stream as JSON rows; the stream itself is length-delimited protobuf."""
    dumped = subprocess.run(
        [str(jdk / "bin/java"), "-cp", classpath,
         "io.github.lhotari.jonoffcpu.offline.OffCpuCorrelator", "--dump", "--source", str(source)],
        check=True, capture_output=True, text=True).stdout
    return [json.loads(line) for line in dumped.splitlines()]


MODES = ("halt", "sigkill", "timeout", "master-stop")


def run(command, log, check=True):
    with log.open("w") as output:
        return subprocess.run([str(arg) for arg in command], stdout=output,
                              stderr=subprocess.STDOUT, check=check).returncode


def make_readable(case):
    subprocess.run([
        "docker", "run", "--rm", "-v", f"{case}:/out",
        "jonoffcpu-agent-runtime:ubuntu24.04", "chmod", "-R", "a+rX", "/out",
    ], check=True)


def java_command(module, ap, jdk, case, mode, seconds):
    build = module / "build"
    ap_first = mode in ("timeout", "master-stop")
    controller = "sampling-policy=uniform,sampling-probability=1,min-off-cpu-micros=5000," if ap_first else ""
    profiler = "timeout=2s," if mode == "timeout" else ""
    workload_mode = "wait" if mode == "sigkill" else mode
    return [
        "docker", "run", "--rm", "--privileged", "--memory=1g", "--ulimit", "core=0",
        "-v", f"{build}:/agent:ro", "-v", f"{ap}:/ap:ro", "-v", f"{jdk}:/jdk:ro",
        "-v", "/sys/kernel/btf:/sys/kernel/btf:ro",
        "-v", "/sys/kernel/tracing:/sys/kernel/tracing",
        "-v", f"{case}:/out", "-w", "/out", "jonoffcpu-agent-runtime:ubuntu24.04",
        "/jdk/bin/java", "--enable-native-access=ALL-UNNAMED", "-Xms128m", "-Xmx256m",
        "-agentpath:/agent/lib/libjonoffcpu.so=jonoffcpuoutput=/out/jonoffcpu-capture.pb,"
        f"{controller}shutdowntimeoutmillis=30000,nativestoptimeoutmillis=10000,"
        "asprofpath=/ap/build/lib/libasyncProfiler.so,"
        f"{profiler}event=cpu,alloc=1m,wall=10ms,lock=1ms,jfrsync=profile,file=/out/jonoffcpu-capture.jfr",
        "-cp", "/agent/jonoffcpu-agent.jar:/agent/test-classes",
        "io.github.lhotari.jonoffcpu.agent.NativeAgentShutdownWorkload", workload_mode, str(seconds), "/out/ready",
    ]


def run_sigkill(command, case):
    name = f"jonoffcpu-kill-{os.getpid()}-{time.time_ns()}"
    command[2:2] = ["--name", name]
    (case / "command.json").write_text(json.dumps(command, indent=2) + "\n")
    with (case / "process.log").open("w") as output:
        process = subprocess.Popen([str(arg) for arg in command], stdout=output,
                                   stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 60
        while not (case / "ready").is_file():
            if process.poll() is not None:
                raise RuntimeError(f"SIGKILL target exited before ready with {process.returncode}")
            if time.monotonic() >= deadline:
                subprocess.run(["docker", "kill", name], check=False)
                raise TimeoutError("SIGKILL target did not become ready")
            time.sleep(0.1)
        time.sleep(2)
        killed = subprocess.run(["docker", "kill", "--signal", "KILL", name],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        (case / "docker-kill.log").write_text(killed.stdout)
        if killed.returncode != 0:
            raise RuntimeError(f"docker kill failed with {killed.returncode}")
        return_code = process.wait(timeout=30)
        (case / "process-exit.json").write_text(json.dumps({"returnCode": return_code}) + "\n")
        if return_code != 137:
            raise RuntimeError(f"SIGKILL container exited with {return_code}")


def footer(case, jdk, classpath):
    rows = capture_rows(jdk, classpath, case / "jonoffcpu-capture.pb")
    footers = [row for row in rows if row.get("recordType") == "captureFinalized"]
    if len(footers) != 1 or rows[-1] is not footers[0] or footers[0].get("state") != "complete":
        raise RuntimeError("Expected exactly one complete terminal footer")
    return footers[0]


def verify_abrupt(module, jdk, case):
    source = case / "jonoffcpu-capture.pb"
    jfr = case / "jonoffcpu-capture.jfr"
    manifest_path = case / "jonoffcpu-capture.manifest.json"
    if not source.is_file() or source.stat().st_size == 0 or not jfr.exists():
        raise RuntimeError("Abrupt exit did not retain its partial source/JFR paths")
    if b"captureFinalized" in source.read_bytes():
        raise RuntimeError("Abrupt exit published a complete footer")
    manifest = json.loads(manifest_path.read_text())
    if manifest.get("complete") is not False or manifest.get("state") == "complete":
        raise RuntimeError("Abrupt exit published a complete manifest")
    analysis = case / "analysis"
    classpath = f"{module / 'build/jonoffcpu-agent.jar'}:{module / 'build/test-classes'}"
    result = run([jdk / "bin/java", "-cp", classpath, "io.github.lhotari.jonoffcpu.offline.OffCpuCorrelator",
                  "--source", source, "--jfr", jfr, "--output", analysis],
                 case / "analysis-rejected.log", check=False)
    if result == 0 or (analysis / "jonoffcpu-report.json").exists():
        raise RuntimeError("Abrupt partial artifacts were accepted as complete analysis")
    (case / "partial-artifacts.json").write_text(json.dumps({
        "sourceBytes": source.stat().st_size,
        "jfrBytes": jfr.stat().st_size,
        "manifestState": manifest.get("state"),
        "offlineExitCode": result,
    }, indent=2) + "\n")


def verify_ap_first(module, jdk, case, expected_reason):
    classpath = f"{module / 'build/jonoffcpu-agent.jar'}:{module / 'build/test-classes'}"
    terminal = footer(case, jdk, classpath)
    receipt = terminal.get("apStopResponse", "")
    if f" reason={expected_reason}" not in receipt or " finalized=true " not in receipt:
        raise RuntimeError(f"Unexpected retained AP receipt: {receipt}")
    manifest = json.loads((case / "jonoffcpu-capture.manifest.json").read_text())
    if manifest.get("complete") is not True or manifest.get("state") != "complete":
        raise RuntimeError("AP-first audit manifest is incomplete")
    retained = manifest.get("asyncProfilerStop", {}).get("response", "")
    if retained != receipt.strip():
        raise RuntimeError("Footer and audit manifest retained different AP receipts")
    classpath = f"{module / 'build/jonoffcpu-agent.jar'}:{module / 'build/test-classes'}"
    run([jdk / "bin/java", "-cp", classpath, "io.github.lhotari.jonoffcpu.agent.MixedRecordingCheck",
         case / "jonoffcpu-capture.jfr", case / "event-counts.json"], case / "category-check.log")
    run([jdk / "bin/java", "-cp", classpath, "io.github.lhotari.jonoffcpu.offline.OffCpuCorrelator",
         "--source", case / "jonoffcpu-capture.pb", "--jfr", case / "jonoffcpu-capture.jfr",
         "--output", case / "analysis"], case / "analysis.log")

    report = json.loads((case / "analysis/jonoffcpu-report.json").read_text())
    source_total = report["matched"] + report["unmatchedSource"] + report["invalidSource"]
    jfr_total = report["matched"] + report["orphanJfr"] + report["invalidJfr"]
    if source_total != report["sourceRows"] or jfr_total != report["jfrSamples"]:
        raise RuntimeError("Offline source/JFR classifications do not reconcile")
    cutoff = int(report["apStoppedAtNanos"])
    after = []
    for line in (case / "analysis/jonoffcpu-classified-records.jsonl").read_text().splitlines():
        row = json.loads(line)
        if row["stream"] != "source":
            continue
        record = row["record"]
        if int(record["endMonotonicNanos"]) > cutoff:
            after.append(row)
            if (row["classification"] != "unmatched"
                    or row.get("reason") != "source-interval-after-ap-stop"):
                raise RuntimeError("Post-cutoff source row was not explicitly unmatched")
    if not after:
        raise RuntimeError("Fixture produced no source row after the AP cutoff")
    counts = json.loads((case / "event-counts.json").read_text())
    if counts.get("profiler.SignalCaptureStats") != 1:
        raise RuntimeError("Combined JFR does not contain exactly one terminal stats record")
    (case / "cutoff-check.json").write_text(json.dumps({
        "reason": expected_reason,
        "apStoppedAtNanos": str(cutoff),
        "postCutoffRows": len(after),
        "postCutoffClassifications": sorted({row["classification"] for row in after}),
        "matched": report["matched"],
        "unmatchedSource": report["unmatchedSource"],
        "invalidSource": report["invalidSource"],
    }, indent=2) + "\n")


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
            if mode == "sigkill":
                run_sigkill(command, case)
            else:
                return_code = run(command, case / "process.log", check=False)
                (case / "process-exit.json").write_text(json.dumps({"returnCode": return_code}) + "\n")
                expected = 23 if mode == "halt" else 0
                if return_code != expected:
                    raise RuntimeError(f"{mode} container exited with {return_code}, expected {expected}")
        finally:
            make_readable(case)
        if mode in ("halt", "sigkill"):
            verify_abrupt(module, jdk, case)
        else:
            verify_ap_first(module, jdk, case, mode)
    print(f"Native interruption integration passed for {', '.join(modes)} in {output}")


if __name__ == "__main__":
    main()
