#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Exercise the packaged agent and correlator JARs against the native host kernel."""

import argparse
import json
import platform
import subprocess
from pathlib import Path


def run(command, log, cwd=None):
    with log.open("w") as output:
        result = subprocess.run(
            [str(argument) for argument in command],
            cwd=cwd,
            stdout=output,
            stderr=subprocess.STDOUT,
            text=True,
        )
    if result.returncode != 0:
        tail = log.read_text(errors="replace").splitlines()[-80:]
        raise RuntimeError(
            f"Command failed with exit code {result.returncode}: {' '.join(map(str, command))}\n"
            + "\n".join(tail)
        )


def require_file(path, description):
    resolved = path.resolve(strict=True)
    if not resolved.is_file():
        raise ValueError(f"{description} is not a file: {resolved}")
    return resolved


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-jar", required=True, type=Path)
    parser.add_argument("--correlator-jar", required=True, type=Path)
    parser.add_argument("--test-classes", required=True, type=Path)
    parser.add_argument("--java-home", type=Path,
                        help="glibc JDK mounted into the runtime container; required for --libc glibc")
    parser.add_argument("--libc", choices=("glibc", "musl"), default="glibc",
                        help="glibc runs on Ubuntu with the mounted JDK; musl runs on Alpine with its own JDK")
    parser.add_argument("--output", required=True, type=Path, help="New output directory")
    parser.add_argument("--seconds", type=int, default=3)
    args = parser.parse_args()

    if not 1 <= args.seconds <= 60:
        parser.error("--seconds must be in 1..60")
    agent = require_file(args.agent_jar, "Agent JAR")
    correlator = require_file(args.correlator_jar, "Correlator JAR")
    test_classes = args.test_classes.resolve(strict=True)
    if not test_classes.is_dir():
        parser.error(f"Test classes are not a directory: {test_classes}")
    if args.libc == "glibc":
        if args.java_home is None:
            parser.error("--java-home is required for --libc glibc")
        java_home = args.java_home.resolve(strict=True)
        require_file(java_home / "bin/java", "Java launcher")
    elif args.java_home is not None:
        parser.error("--java-home cannot be combined with --libc musl; the Alpine image provides the JDK")

    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    (output / "jonoffcpu.yaml").write_text(
        "correlationOutput: /out/correlation.ndjson\n"
        "asyncProfilerOptions: "
        "event=cpu,alloc=1m,wall=10ms,lock=1ms,jfrsync=profile,file=/out/original.jfr\n"
        "signalDelivery: queued\n"
        "sampleProbability: 0.01\n"
        "minOffCpuMicros: 100\n"
    )

    module = Path(__file__).resolve().parents[1]
    dockerfile = "tools/Dockerfile.runtime" if args.libc == "glibc" else "tools/Dockerfile.runtime-musl"
    image = f"jonoffcpu-agent-runtime-{args.libc}:{platform.machine()}"
    run(
        ["docker", "build", "-t", image, "-f", module / dockerfile, module / "tools"],
        output / "runtime-build.log",
    )
    mounts = [
        "-v", f"{agent}:/artifacts/jonoffcpu-agent.jar:ro",
        "-v", f"{correlator}:/artifacts/jonoffcpu-correlator.jar:ro",
        "-v", f"{test_classes}:/test-classes:ro",
        *(["-v", f"{java_home}:/jdk:ro"] if args.libc == "glibc" else []),
        "-v", f"{output}:/out",
        "-v", "/sys/kernel/btf:/sys/kernel/btf:ro",
        "-v", "/sys/kernel/tracing:/sys/kernel/tracing",
    ]
    common = [
        "docker", "run", "--rm", "--privileged", "--memory=1g", "--ulimit", "core=0",
        *mounts, "-w", "/out", image,
    ]
    java = "/jdk/bin/java" if args.libc == "glibc" else "java"
    if args.libc == "musl":
        # The bundle is chosen from the JVM's own loader, so prove the container JVM really is musl.
        run([*common, "sh", "-c", "grep -q ld-musl /proc/self/maps && test -f /lib/ld-musl-*.so.1"],
            output / "musl-check.log")
    run(
        [
            *common,
            java, "--enable-native-access=ALL-UNNAMED", "-Xms128m", "-Xmx256m",
            "-javaagent:/artifacts/jonoffcpu-agent.jar=/out/jonoffcpu.yaml",
            "-cp", "/artifacts/jonoffcpu-agent.jar:/test-classes",
            "io.github.lhotari.jonoffcpu.agent.NativeAgentWorkload", args.seconds,
        ],
        output / "agent.log",
    )

    manifest_path = output / "correlation.ndjson.manifest.json"
    manifest = json.loads(require_file(manifest_path, "Capture manifest").read_text())
    if manifest.get("complete") is not True or manifest.get("state") != "complete":
        raise RuntimeError(f"Capture did not complete: {manifest_path}")
    for path in (output / "correlation.ndjson", output / "original.jfr"):
        if not path.is_file() or path.stat().st_size == 0:
            raise RuntimeError(f"Capture artifact is missing or empty: {path}")

    run(
        [
            *common,
            java, "-cp", "/artifacts/jonoffcpu-agent.jar:/test-classes",
            "io.github.lhotari.jonoffcpu.agent.MixedRecordingCheck",
            "/out/original.jfr", "/out/event-counts.json",
        ],
        output / "recording-check.log",
    )

    run(
        [
            *common,
            java, "-jar", "/artifacts/jonoffcpu-correlator.jar",
            "--source", "/out/correlation.ndjson",
            "--jfr", "/out/original.jfr",
            "--output", "/out/analysis",
        ],
        output / "correlator.log",
    )
    report_path = output / "analysis/report.json"
    report = json.loads(require_file(report_path, "Correlation report").read_text())
    if report.get("matched", 0) <= 0:
        raise RuntimeError(f"Correlator produced no matched off-CPU samples: {report_path}")
    if any(report.get(field, 0) != 0 for field in ("invalidSource", "invalidJfr", "identityUnverified")):
        raise RuntimeError(f"Correlator reported invalid or unverified samples: {report_path}")

    print(
        f"Packaged agent and correlator smoke passed on {platform.machine()} ({args.libc}): "
        f"{report['matched']} matched samples"
    )


if __name__ == "__main__":
    main()
