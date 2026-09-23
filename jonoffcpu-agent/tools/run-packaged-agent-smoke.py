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
        "correlationOutput: /out/jonoffcpu-capture.pb\n"
        "asyncProfilerOptions: "
        "event=cpu,alloc=1m,wall=10ms,lock=1ms,jfrsync=profile,file=/out/jonoffcpu-capture.jfr\n"
        "signalDelivery: queued\n"
        "sampling:\n"
        "  minOffCpuMicros: 100\n"
        "  admission:\n"
        "    policy: proportional\n"
        "    recordAllAboveMicros: 10000\n"
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

    manifest_path = output / "jonoffcpu-capture.manifest.json"
    manifest = json.loads(require_file(manifest_path, "Capture manifest").read_text())
    if manifest.get("complete") is not True or manifest.get("state") != "complete":
        raise RuntimeError(f"Capture did not complete: {manifest_path}")
    for path in (output / "jonoffcpu-capture.pb", output / "jonoffcpu-capture.jfr"):
        if not path.is_file() or path.stat().st_size == 0:
            raise RuntimeError(f"Capture artifact is missing or empty: {path}")
    # The configuration names no reasons, so the resolved policy records blocked intervals only.
    if manifest.get("sampling", {}).get("reasons") != ["blocked"]:
        raise RuntimeError(f"Capture did not resolve the default switch-out reasons: {manifest_path}")
    # Nor does it name a time-split source, so each interval's run-queue part is read from sched_info.
    if manifest.get("timeSplit") != {"source": "schedInfo"}:
        raise RuntimeError(f"Capture did not resolve the default time-split source: {manifest_path}")

    run(
        [
            *common,
            java, "-cp", "/artifacts/jonoffcpu-agent.jar:/test-classes",
            "io.github.lhotari.jonoffcpu.agent.MixedRecordingCheck",
            "/out/jonoffcpu-capture.jfr", "/out/event-counts.json",
        ],
        output / "recording-check.log",
    )

    run(
        [
            *common,
            java, "-jar", "/artifacts/jonoffcpu-correlator.jar",
            "--source", "/out/jonoffcpu-capture.pb",
            "--jfr", "/out/jonoffcpu-capture.jfr",
            "--output", "/out/analysis",
        ],
        output / "correlator.log",
    )
    report_path = output / "analysis/jonoffcpu-report.json"
    report = json.loads(require_file(report_path, "Correlation report").read_text())
    if report.get("matched", 0) <= 0:
        raise RuntimeError(f"Correlator produced no matched off-CPU samples: {report_path}")
    if any(report.get(field, 0) != 0 for field in ("invalidSource", "invalidJfr", "identityUnverified")):
        raise RuntimeError(f"Correlator reported invalid or unverified samples: {report_path}")
    # Every matched interval is classified by its switch-out reason, and only selected reasons appear.
    reasons = report.get("offCpuReasons") or {}
    matched_by_reason = reasons.get("matched", {})
    if list(matched_by_reason) != ["blocked"] or int(matched_by_reason["blocked"]["intervals"]) != report["matched"]:
        raise RuntimeError(f"Switch-out reasons do not account for every matched interval: {report_path}")
    switch_outs = reasons.get("kernelSwitchOuts", {})
    if int(switch_outs.get("blocked") or 0) <= 0:
        raise RuntimeError(f"The kernel counted no blocked switch-outs: {report_path}")
    profile_path = output / "analysis/jonoffcpu-offcpu-profile.pb"
    if not profile_path.is_file() or profile_path.stat().st_size == 0:
        raise RuntimeError(f"Stack profile is missing or empty: {profile_path}")
    # The profile renders back to the collapsed stacks the correlator wrote.
    run(
        [
            *common,
            java, "-jar", "/artifacts/jonoffcpu-correlator.jar", "stacks",
            "--profile", "/out/analysis/jonoffcpu-offcpu-profile.pb",
            "--output", "/out/rendered.collapsed",
        ],
        output / "stacks.log",
    )
    if (output / "rendered.collapsed").read_bytes() != (output / "analysis/jonoffcpu-offcpu-stacks.collapsed").read_bytes():
        raise RuntimeError("The stack profile does not reproduce the collapsed stacks")

    # Every matched interval carries its run-queue part, so its time splits into sleeping and run-queue time.
    time_split = reasons.get("timeSplit", {})
    if time_split.get("source") != "schedInfo" or time_split.get("available") is not True:
        raise RuntimeError(f"The report does not announce the time split: {report_path}")
    unsplit = time_split.get("unsplitIntervals", {})
    if int(unsplit.get("withoutReading", -1)) != 0 or int(unsplit.get("readingExceedsInterval", -1)) != 0:
        raise RuntimeError(f"Matched intervals were left unsplit: {report_path}")
    blocked = matched_by_reason["blocked"]
    if int(blocked["sleepingNanos"]) + int(blocked["runqueueNanos"]) != int(blocked["observedNanos"]):
        raise RuntimeError(f"Blocked sleeping and run-queue time do not add up: {report_path}")
    # The split slice renders the same total, one [sleeping] or [runqueue] leaf per part.
    totals = {}
    for time in ("total", "split"):
        run(
            [
                *common,
                java, "-jar", "/artifacts/jonoffcpu-correlator.jar", "stacks",
                "--profile", "/out/analysis/jonoffcpu-offcpu-profile.pb",
                "--time", time,
                "--output", f"/out/{time}.collapsed",
                "--summary", f"/out/{time}.summary.json",
            ],
            output / f"stacks-{time}.log",
        )
        totals[time] = json.loads((output / f"{time}.summary.json").read_text())["totalNanos"]
    if totals["split"] != totals["total"]:
        raise RuntimeError(f"The split slice does not add up to the total: {totals}")
    split_lines = (output / "split.collapsed").read_text().splitlines()
    if not split_lines or not all(";[sleeping] " in line or ";[runqueue] " in line for line in split_lines):
        raise RuntimeError("The split slice has lines without a sleeping or run-queue leaf")

    print(
        f"Packaged agent and correlator smoke passed on {platform.machine()} ({args.libc}): "
        f"{report['matched']} matched samples"
    )


if __name__ == "__main__":
    main()
