#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Opt-in privileged Docker smoke: native launcher -> combined JFR -> offline outputs.

Requires a built glibc async-profiler cookie branch and a glibc JDK 17+.
This is a functional integration check, not a throughput or overhead benchmark.
"""

import argparse
import json
import subprocess
from pathlib import Path


def run(command, log, cwd=None):
    with log.open("w") as output:
        subprocess.run([str(arg) for arg in command], cwd=cwd, stdout=output,
                       stderr=subprocess.STDOUT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ap-dir", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="New output directory")
    parser.add_argument("--delivery", choices=("queued", "coalescing"), default="queued")
    parser.add_argument("--seconds", type=int, default=5)
    parser.add_argument("--skip-build", action="store_true", help="Use already built agent artifacts")
    args = parser.parse_args()
    if not 1 <= args.seconds <= 60:
        parser.error("--seconds must be in 1..60")
    module = Path(__file__).resolve().parents[1]
    repo = module.parent
    ap = args.ap_dir.resolve(strict=True)
    jdk = args.java_home.resolve(strict=True)
    output = args.output.resolve()
    for file in (ap / "build/lib/libasyncProfiler.so", ap / "build/jar/async-profiler.jar", jdk / "bin/java"):
        if not file.is_file():
            parser.error(f"Missing required artifact: {file}")
    output.mkdir(parents=True)
    if not args.skip_build:
        run([repo / "jonoffcpu-native/tools/build-in-docker.sh"], output / "native-build.log", repo)
        run(["make", "all", "test-java", f"JAVA_HOME={jdk}", f"AP_DIR={ap}",
             f"AP_API_JAR={ap / 'build/jar/async-profiler.jar'}", "NATIVE_BUILD_COMMAND=true"],
            output / "agent-build.log", module)
    image = "jonoffcpu-agent-runtime:ubuntu24.04"
    run(["docker", "build", "-t", image, "-f", module / "tools/Dockerfile.runtime", module / "tools"],
        output / "runtime-build.log")
    build = module / "build"
    command = ["docker", "run", "--rm", "--privileged", "--memory=1g", "--ulimit", "core=0",
               "-v", f"{build}:/agent:ro", "-v", f"{ap}:/ap:ro", "-v", f"{jdk}:/jdk:ro",
               "-v", "/sys/kernel/btf:/sys/kernel/btf:ro", "-v", "/sys/kernel/tracing:/sys/kernel/tracing",
               "-v", f"{output}:/out", "-w", "/out", image, "/jdk/bin/java",
               "--enable-native-access=ALL-UNNAMED", "-Xms128m", "-Xmx256m",
               "-agentpath:/agent/lib/libjonoffcpu.so=jonoffcpuoutput=/out/jonoffcpu-capture.ndjson,"
               f"jonoffcpudelivery={args.delivery},asprofpath=/ap/build/lib/libasyncProfiler.so,"
               "event=cpu,alloc=1m,wall=10ms,lock=1ms,jfrsync=profile,file=/out/jonoffcpu-capture.jfr",
               "-cp", "/agent/jonoffcpu-agent.jar:/agent/test-classes", "io.github.lhotari.jonoffcpu.agent.NativeAgentWorkload", args.seconds]
    (output / "command.json").write_text(json.dumps([str(part) for part in command], indent=2) + "\n")
    run(command, output / "process.log")
    classpath = f"{build / 'jonoffcpu-agent.jar'}:{build / 'test-classes'}"
    run([jdk / "bin/java", "-cp", classpath, "io.github.lhotari.jonoffcpu.agent.MixedRecordingCheck",
         output / "jonoffcpu-capture.jfr", output / "event-counts.json"], output / "category-check.log")
    run([jdk / "bin/java", "-cp", classpath, "io.github.lhotari.jonoffcpu.offline.OffCpuCorrelator",
         "--source", output / "jonoffcpu-capture.ndjson", "--jfr", output / "jonoffcpu-capture.jfr",
         "--output", output / "analysis"], output / "analysis.log")
    report = json.loads((output / "analysis/jonoffcpu-report.json").read_text())
    if report["matched"] <= 0:
        raise RuntimeError("No matched off-CPU observations")
    if report["invalidSource"] or report["invalidJfr"] or report["identityUnverified"]:
        raise RuntimeError("Invalid or unverified matches: inspect analysis/jonoffcpu-report.json")
    converted = output / "analysis/compatibility-view.collapsed"
    run([ap / "build/bin/jfrconv", "--cpu", output / "analysis/jonoffcpu-offcpu-synthetic.jfr", converted],
        output / "converter.log")
    count = sum(int(line.rsplit(" ", 1)[1]) for line in converted.read_text().splitlines())
    if count != int(report["syntheticJfr"]["syntheticEvents"]):
        raise RuntimeError("Converter lost synthetic duration samples")
    print(f"Native {args.delivery} integration passed: {report['matched']} verified matches in {output}")


if __name__ == "__main__":
    main()
