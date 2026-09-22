#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Run the J20 known-wait attribution gate with the production native agent.

The gate records exact CLOCK_MONOTONIC wait intervals and target TIDs for sleep, park, JNI
clock_nanosleep, and rapid re-block threads. Its offline check reports scheduler source-duration
totals independently from post-resumption signal-delivery stacks and handler delay.
"""

import argparse
import json
import subprocess
from pathlib import Path


def run(command, log, cwd=None):
    with log.open("w") as output:
        subprocess.run([str(arg) for arg in command], cwd=cwd, stdout=output,
                       stderr=subprocess.STDOUT, check=True)


def make_readable(output):
    subprocess.run([
        "docker", "run", "--rm", "-v", f"{output}:/out",
        "jonoffcpu-agent-runtime:ubuntu24.04", "chmod", "-R", "a+rX", "/out",
    ], check=True)


def compile_helper(module, jdk, log):
    library = module / "build/test-lib/libjonoffcpu_known_wait.so"
    library.parent.mkdir(parents=True, exist_ok=True)
    run(["cc", "-D_GNU_SOURCE", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
         "-fPIC", "-shared", "-I", jdk / "include", "-I", jdk / "include/linux",
         "-o", library, module / "src/test/c/known_wait_helper.c", "-pthread"], log)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ap-dir", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="New evidence directory")
    parser.add_argument("--iterations", type=int, default=80)
    parser.add_argument("--wait-nanos", type=int, default=20_000_000)
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    if not 10 <= args.iterations <= 10_000:
        parser.error("--iterations must be in 10..10000")
    if not 1_000_000 <= args.wait_nanos <= 1_000_000_000:
        parser.error("--wait-nanos must be in 1000000..1000000000")

    repo = Path(__file__).resolve().parents[2]
    module = repo / "jonoffcpu-agent"
    ap = args.ap_dir.resolve(strict=True)
    jdk = args.java_home.resolve(strict=True)
    output = args.output.resolve()
    if output.exists():
        parser.error(f"Output directory already exists: {output}")
    for file in (ap / "build/lib/libasyncProfiler.so", ap / "build/jar/async-profiler.jar",
                 jdk / "bin/java"):
        if not file.is_file():
            parser.error(f"Missing required artifact: {file}")
    output.mkdir(parents=True)

    if not args.skip_build:
        run([repo / "jonoffcpu-native/tools/build-in-docker.sh"], output / "native-build.log", repo)
        run(["make", "all", "test-java", f"JAVA_HOME={jdk}", f"AP_DIR={ap}",
             f"AP_API_JAR={ap / 'build/jar/async-profiler.jar'}", "NATIVE_BUILD_COMMAND=true"],
            output / "agent-build.log", module)
    compile_helper(module, jdk, output / "helper-build.log")
    required = (module / "build/lib/libjonoffcpu.so", module / "build/jonoffcpu-agent.jar",
                module / "build/test-classes/io/github/lhotari/jonoffcpu/agent/KnownWaitAttributionWorkload.class",
                module / "build/test-classes/io/github/lhotari/jonoffcpu/offline/KnownWaitAttributionCheck.class")
    for file in required:
        if not file.is_file():
            raise RuntimeError(f"Missing built fixture artifact: {file}")

    image = "jonoffcpu-agent-runtime:ubuntu24.04"
    run(["docker", "build", "-t", image, "-f", module / "tools/Dockerfile.runtime", module / "tools"],
        output / "runtime-build.log")
    build = module / "build"
    options = (
        "jonoffcpuoutput=/out/jonoffcpu-capture.pb,jonoffcpudelivery=queued,"
        # Every switch-out reason is recorded, so the check can show the known waits are classified blocked.
        "sampling-policy=uniform,sampling-probability=1,sampling-reasons=blocked+runnable+preempted,"
        "min-off-cpu-micros=1000,deliverygracemillis=3000,"
        "nativestoptimeoutmillis=30000,shutdowntimeoutmillis=30000,"
        "asprofpath=/ap/build/lib/libasyncProfiler.so,"
        "event=cpu,jfrsync=profile,file=/out/jonoffcpu-capture.jfr"
    )
    command = [
        "docker", "run", "--rm", "--privileged", "--memory=1g", "--ulimit", "core=0",
        "-v", f"{build}:/agent:ro", "-v", f"{ap}:/ap:ro", "-v", f"{jdk}:/jdk:ro",
        "-v", "/sys/kernel/btf:/sys/kernel/btf:ro",
        "-v", "/sys/kernel/tracing:/sys/kernel/tracing",
        "-v", f"{output}:/out", "-w", "/out", image,
        "/jdk/bin/java", "--enable-native-access=ALL-UNNAMED", "-Xms128m", "-Xmx256m",
        "-XX:ActiveProcessorCount=2", "-Djonoffcpu.knownWaitLibrary=/agent/test-lib/libjonoffcpu_known_wait.so",
        f"-agentpath:/agent/lib/libjonoffcpu.so={options}",
        "-cp", "/agent/jonoffcpu-agent.jar:/agent/test-classes",
        "io.github.lhotari.jonoffcpu.agent.KnownWaitAttributionWorkload", str(args.iterations), str(args.wait_nanos),
        "/out/workload.json",
    ]
    (output / "command.json").write_text(json.dumps(command, indent=2) + "\n")
    try:
        run(command, output / "process.log")
    finally:
        make_readable(output)

    classpath = f"{build / 'jonoffcpu-agent.jar'}:{build / 'test-classes'}"
    run([jdk / "bin/java", "-cp", classpath, "io.github.lhotari.jonoffcpu.offline.OffCpuCorrelator",
         "--source", output / "jonoffcpu-capture.pb", "--jfr", output / "jonoffcpu-capture.jfr",
         "--output", output / "analysis"], output / "analysis.log")
    run([jdk / "bin/java", "-cp", classpath, "io.github.lhotari.jonoffcpu.offline.KnownWaitAttributionCheck",
         output / "jonoffcpu-capture.pb", output / "jonoffcpu-capture.jfr", output / "workload.json",
         output / "known-wait-report.json"], output / "known-wait-check.log")
    report = json.loads((output / "known-wait-report.json").read_text())
    if report.get("result") != "pass" or set(report.get("threads", {})) != {
            "jonoffcpu-known-sleep", "jonoffcpu-known-park", "jonoffcpu-known-native-wait", "jonoffcpu-known-rapid-reblock"}:
        raise RuntimeError("Known-wait attribution report is incomplete")
    print(f"J20 known-wait attribution passed in {output}")


if __name__ == "__main__":
    main()
