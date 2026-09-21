#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Run actual JVM/AP cookie-signal pressure with blocked Java target threads.

This privileged Docker gate deliberately constrains RLIMIT_SIGPENDING. It validates exact-cookie
joins and reports native-handler delay; it is not an application throughput benchmark.
"""

import argparse
import json
import os
import subprocess
from collections import Counter, defaultdict
from pathlib import Path


DELIVERIES = ("queued", "coalescing")
DELAY_LIMIT_NS = 5_000_000
SIGPENDING_LIMIT = 64
QUEUED_SIGNAL = 42
COALESCING_SIGNAL = 16


def run(command, log, cwd=None, check=True):
    with log.open("w") as output:
        return subprocess.run([str(arg) for arg in command], cwd=cwd, stdout=output,
                              stderr=subprocess.STDOUT, check=check).returncode


def make_readable(case):
    subprocess.run([
        "docker", "run", "--rm", "-v", f"{case}:/out",
        "jonoffcpu-agent-runtime:ubuntu24.04", "chmod", "-R", "a+rX", "/out",
    ], check=True)


def compile_mask_helper(module, jdk, log):
    output = module / "build/test-lib/libjonoffcpu_signal_mask.so"
    output.parent.mkdir(parents=True, exist_ok=True)
    run(["cc", "-D_GNU_SOURCE", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
         "-fPIC", "-shared", "-I", jdk / "include", "-I", jdk / "include/linux",
         "-o", output, module / "src/test/c/signal_mask_helper.c", "-pthread"], log)
    return output


def cpu_model():
    for line in Path("/proc/cpuinfo").read_text().splitlines():
        if line.startswith("model name"):
            return line.split(":", 1)[1].strip()
    return "unavailable"


def java_command(module, ap, jdk, case, delivery, signo, blocked_ms, recovery_ms):
    build = module / "build"
    options = (
        f"jonoffcpuoutput=/out/correlation.ndjson,jonoffcpudelivery={delivery},"
        "samplethreshold=1,min-off-cpu-micros=1000,deliverygracemillis=3000,"
        "nativestoptimeoutmillis=30000,shutdowntimeoutmillis=30000,"
        "asprofpath=/ap/build/lib/libasyncProfiler.so,"
        f"cookiesignal={signo},event=cpu,alloc=1m,wall=10ms,lock=1ms,"
        "jfrsync=profile,file=/out/original.jfr"
    )
    return [
        "docker", "run", "--rm", "--privileged", "--memory=1g", "--ulimit", "core=0",
        "--ulimit", f"sigpending={SIGPENDING_LIMIT}:{SIGPENDING_LIMIT}",
        "-v", f"{build}:/agent:ro", "-v", f"{ap}:/ap:ro", "-v", f"{jdk}:/jdk:ro",
        "-v", "/sys/kernel/btf:/sys/kernel/btf:ro",
        "-v", "/sys/kernel/tracing:/sys/kernel/tracing",
        "-v", f"{case}:/out", "-w", "/out", "jonoffcpu-agent-runtime:ubuntu24.04",
        "/jdk/bin/java", "--enable-native-access=ALL-UNNAMED", "-Xms128m", "-Xmx256m",
        "-XX:ActiveProcessorCount=2",
        "-Djonoffcpu.signalMaskLibrary=/agent/test-lib/libjonoffcpu_signal_mask.so",
        f"-agentpath:/agent/lib/libjonoffcpu.so={options}",
        "-cp", "/agent/jonoffcpu-agent.jar:/agent/test-classes",
        "io.github.lhotari.jonoffcpu.agent.SignalPressureWorkload", str(signo), str(blocked_ms), str(recovery_ms),
        "/out/workload.json",
    ]


def rows(path):
    return [json.loads(line) for line in path.read_text().splitlines()]


def classified(path):
    return [json.loads(line) for line in path.read_text().splitlines()]


def reconcile(report):
    source = report["matched"] + report["unmatchedSource"] + report["invalidSource"]
    jfr = report["matched"] + report["orphanJfr"] + report["invalidJfr"]
    if source != report["sourceRows"] or jfr != report["jfrSamples"]:
        raise RuntimeError("Offline classifications do not reconcile")


def exact_cookie_check(records, allow_delay_rejections):
    grouped = defaultdict(dict)
    for row in records:
        cookie = row["record"].get("correlationId")
        if cookie is None:
            raise RuntimeError("Classified row has no cookie")
        if row["stream"] in grouped[cookie]:
            raise RuntimeError("Duplicate stream/cookie in classified output")
        grouped[cookie][row["stream"]] = row

    reasons = Counter()
    identifier_reasons = Counter()
    delay_pairs = 0
    shifted = []
    for cookie, pair in grouped.items():
        source = pair.get("source")
        sample = pair.get("jfr")
        for row in pair.values():
            if row.get("reason"):
                reasons[row["reason"]] += 1
                if "identity" in row["reason"] or "cookie" in row["reason"]:
                    identifier_reasons[row["reason"]] += 1
        if source and sample:
            if source["record"]["correlationId"] != sample["record"]["correlationId"]:
                shifted.append(cookie)
            if source["classification"] == "matched" and sample["classification"] != "matched":
                shifted.append(cookie)
            if sample["classification"] == "matched" and source["classification"] != "matched":
                shifted.append(cookie)
            if source.get("reason") == "handler-delay-limit-exceeded" \
                    or sample.get("reason") == "handler-delay-limit-exceeded":
                if (source.get("reason") != "handler-delay-limit-exceeded"
                        or sample.get("reason") != "handler-delay-limit-exceeded"):
                    shifted.append(cookie)
                delay_pairs += 1
        elif (source and source["classification"] == "matched") \
                or (sample and sample["classification"] == "matched"):
            shifted.append(cookie)
    if shifted:
        raise RuntimeError("One-sided or shifted exact-cookie classification")
    if identifier_reasons:
        raise RuntimeError(f"Identifier/cookie mismatches observed: {dict(identifier_reasons)}")
    if allow_delay_rejections and delay_pairs == 0:
        raise RuntimeError("Delay-filtered run rejected no exact pairs")
    return reasons, delay_pairs


def analyze_case(module, ap, jdk, case, delivery, signo):
    classpath = f"{module / 'build/jonoffcpu-agent.jar'}:{module / 'build/test-classes'}"
    run([jdk / "bin/java", "-cp", classpath, "io.github.lhotari.jonoffcpu.agent.MixedRecordingCheck",
         case / "original.jfr", case / "event-counts.json"], case / "category-check.log")
    for name, extra in (("analysis-exact", []),
                        ("analysis-delay-filtered", ["--max-handler-delay-ns", str(DELAY_LIMIT_NS)])):
        run([jdk / "bin/java", "-cp", classpath, "io.github.lhotari.jonoffcpu.offline.OffCpuCorrelator",
             "--source", case / "correlation.ndjson", "--jfr", case / "original.jfr",
             "--output", case / name, "--max-retained-bytes", str(1024 * 1024 * 1024),
             *extra], case / f"{name}.log")

    exact = json.loads((case / "analysis-exact/report.json").read_text())
    delayed = json.loads((case / "analysis-delay-filtered/report.json").read_text())
    reconcile(exact)
    reconcile(delayed)
    exact_reasons, _ = exact_cookie_check(
        classified(case / "analysis-exact/classified-records.jsonl"), False)
    delayed_reasons, delay_pairs = exact_cookie_check(
        classified(case / "analysis-delay-filtered/classified-records.jsonl"), True)
    if exact["matched"] <= 0 or exact["unmatchedSource"] <= 0:
        raise RuntimeError("Pressure run needs both delivered and lost/coalesced source observations")
    if exact["orphanJfr"] or exact["invalidSource"] or exact["invalidJfr"] \
            or exact["identityUnverified"]:
        raise RuntimeError("Exact analysis contains orphan, invalid, or unverified rows")

    source_rows = rows(case / "correlation.ndjson")
    start = next(row for row in source_rows if row.get("recordType") == "captureStart")
    end = next(row for row in source_rows if row.get("recordType") == "captureEnd")
    footer = source_rows[-1]
    if footer.get("recordType") != "captureFinalized" or footer.get("state") != "complete":
        raise RuntimeError("Missing complete captureFinalized footer")
    if start["signal"] != signo or start["signalDelivery"] != delivery:
        raise RuntimeError("Effective native signal policy differs from requested policy")
    kernel = end["counters"]["kernel"]
    userspace = end["counters"]["userspace"]
    if int(kernel["ringReserveFailures"]) or int(kernel["targetNamespaceFailures"]):
        raise RuntimeError("Native source overflowed or lost namespace identity")
    if int(userspace["receivedObservations"]) != exact["sourceRows"]:
        raise RuntimeError("Native observation and offline source counts differ")

    environment = end["signalEnvironment"]
    if environment["selectedSignal"] != signo or environment["signalDelivery"] != delivery:
        raise RuntimeError("Terminal signal diagnostics have the wrong policy")
    limit = environment["rlimitSigpending"]
    if limit["status"] != "ok" or limit["soft"] != str(SIGPENDING_LIMIT):
        raise RuntimeError("Constrained RLIMIT_SIGPENDING was not observed")
    workload = json.loads((case / "workload.json").read_text())
    audit_tid = workload["targetTids"]["jonoffcpu-pressure-audit-blocked"]
    mask_audit = environment["threadMaskAudit"]
    if int(mask_audit["blockedThreads"]) < 1 or audit_tid not in mask_audit["blockedTidExamples"]:
        raise RuntimeError("Terminal mask audit did not observe the deliberately blocked Java thread")

    converted = case / "analysis-exact/compatibility-view.collapsed"
    run([ap / "build/bin/jfrconv", "--cpu", case / "analysis-exact/offcpu-synthetic.jfr", converted],
        case / "converter.log")
    converted_count = sum(int(line.rsplit(" ", 1)[1]) for line in converted.read_text().splitlines())
    if converted_count != int(exact["syntheticJfr"]["syntheticEvents"]):
        raise RuntimeError("AP converter lost synthetic compatibility events")

    ap_stats = exact["analysisInputs"]["apStats"]
    selected = int(kernel["selectedIntervals"])
    signal_failures = int(kernel["signalFailures"])
    source_pre_signal_drops = (int(kernel["sequenceContentions"])
                               + int(kernel["sequenceExhaustions"])
                               + int(kernel["ringReserveFailures"])
                               + int(kernel["targetNamespaceFailures"]))
    if selected != exact["sourceRows"] + source_pre_signal_drops:
        raise RuntimeError("Selected interval/source accounting differs")
    if int(ap_stats["submittedSamples"]) != exact["jfrSamples"]:
        raise RuntimeError("AP submitted/JFR sample accounting differs")

    summary = {
        "schemaVersion": 1,
        "delivery": delivery,
        "signal": signo,
        "signalClass": environment["signalClass"],
        "sigpendingLimit": SIGPENDING_LIMIT,
        "sigQAtDetach": environment["sigQ"],
        "blockedAuditTid": audit_tid,
        "blockedThreadsAtDetach": mask_audit["blockedThreads"],
        "selectedIntervals": selected,
        "signalHelperFailures": signal_failures,
        "sourcePreSignalDrops": source_pre_signal_drops,
        "sequenceContentions": int(kernel["sequenceContentions"]),
        "sequenceExhaustions": int(kernel["sequenceExhaustions"]),
        "sourceRows": exact["sourceRows"],
        "jfrSamples": exact["jfrSamples"],
        "matched": exact["matched"],
        "unmatchedSource": exact["unmatchedSource"],
        "orphanJfr": exact["orphanJfr"],
        "invalidSource": exact["invalidSource"],
        "invalidJfr": exact["invalidJfr"],
        "handlerDelayNanos": exact["handlerDelayNanos"],
        "exactAnalysisReasons": dict(exact_reasons),
        "delayLimitNanos": DELAY_LIMIT_NS,
        "delayRejectedExactPairs": delay_pairs,
        "delayFilteredMatched": delayed["matched"],
        "delayFilteredInvalidSource": delayed["invalidSource"],
        "delayFilteredInvalidJfr": delayed["invalidJfr"],
        "delayFilteredReasons": dict(delayed_reasons),
        "apStats": ap_stats,
        "exactCookieShiftedJoins": 0,
        "identityMismatchReasons": 0,
        "syntheticEvents": exact["syntheticJfr"]["syntheticEvents"],
        "converterEvents": converted_count,
        "interpretation": (
            "Handler delay is source interval end to native AP handler entry. Unmatched source rows "
            "show absent cookie events after exact joining; the kernel helper cannot report deferred "
            "queue allocation failure. Standard-signal coalescing does not promise freshness."
        ),
    }
    (case / "pressure-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ap-dir", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="New output directory")
    parser.add_argument("--delivery", choices=("all",) + DELIVERIES, default="all")
    parser.add_argument("--blocked-millis", type=int, default=2500)
    parser.add_argument("--recovery-millis", type=int, default=2500)
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    if not 250 <= args.blocked_millis <= 30_000 or not 250 <= args.recovery_millis <= 30_000:
        parser.error("phase durations must be 250..30000 milliseconds")
    module = Path(__file__).resolve().parents[2] / "jonoffcpu-agent"
    repo = module.parent
    ap = args.ap_dir.resolve(strict=True)
    jdk = args.java_home.resolve(strict=True)
    output = args.output.resolve()
    required = (ap / "build/lib/libasyncProfiler.so", ap / "build/jar/async-profiler.jar",
                ap / "build/bin/jfrconv", jdk / "bin/java", jdk / "bin/javac")
    for file in required:
        if not file.is_file():
            parser.error(f"Missing required artifact: {file}")
    if output.exists():
        parser.error(f"Output directory already exists: {output}")
    output.mkdir(parents=True)
    context = {
        "uname": subprocess.run(["uname", "-a"], text=True, stdout=subprocess.PIPE,
                                 check=True).stdout.strip(),
        "cpuModel": cpu_model(),
        "logicalCpus": os.cpu_count(),
        "loadavgBefore": Path("/proc/loadavg").read_text().strip(),
        "dockerVersion": subprocess.run(["docker", "--version"], text=True,
                                           stdout=subprocess.PIPE, check=True).stdout.strip(),
        "javaVersion": subprocess.run([jdk / "bin/java", "-version"], text=True,
                                        stderr=subprocess.STDOUT, stdout=subprocess.PIPE,
                                        check=True).stdout.strip(),
        "container": "privileged Docker, private PID namespace, 1 GiB memory, 2 active JVM CPUs",
        "sigpendingSoftHard": f"{SIGPENDING_LIMIT}:{SIGPENDING_LIMIT}",
    }
    (output / "host-context.json").write_text(json.dumps(context, indent=2) + "\n")

    if not args.skip_build:
        run([repo / "jonoffcpu-native/tools/build-in-docker.sh"], output / "native-build.log", repo)
        run(["make", "all", "test-java", f"JAVA_HOME={jdk}", f"AP_DIR={ap}",
             f"AP_API_JAR={ap / 'build/jar/async-profiler.jar'}", "NATIVE_BUILD_COMMAND=true"],
            output / "agent-build.log", module)
    helper = compile_mask_helper(module, jdk, output / "mask-helper-build.log")
    build_required = (module / "build/lib/libjonoffcpu.so", module / "build/lib/libjonoffcpu_native.so",
                      module / "build/jonoffcpu-agent.jar",
                      module / "build/test-classes/io/github/lhotari/jonoffcpu/agent/SignalPressureWorkload.class", helper)
    for file in build_required:
        if not file.is_file():
            parser.error(f"Missing built artifact: {file}")
    run(["docker", "build", "-t", "jonoffcpu-agent-runtime:ubuntu24.04", "-f",
         module / "tools/Dockerfile.runtime", module / "tools"], output / "runtime-build.log")

    selected = DELIVERIES if args.delivery == "all" else (args.delivery,)
    # Signal 42 was already proven free in the transport fixture on this runtime; SIGSTKFLT is
    # one of the two deliberately restricted standard-signal choices accepted by AP.
    signals = {"queued": QUEUED_SIGNAL, "coalescing": COALESCING_SIGNAL}
    summaries = []
    for delivery in selected:
        case = (output / delivery).resolve()
        case.mkdir()
        command = java_command(module, ap, jdk, case, delivery, signals[delivery],
                               args.blocked_millis, args.recovery_millis)
        (case / "command.json").write_text(json.dumps(command, indent=2) + "\n")
        try:
            return_code = run(command, case / "process.log", check=False)
        finally:
            make_readable(case)
        (case / "process-exit.json").write_text(json.dumps({"returnCode": return_code}) + "\n")
        if return_code != 0:
            raise RuntimeError(f"{delivery} pressure process exited with {return_code}")
        summaries.append(analyze_case(module, ap, jdk, case, delivery, signals[delivery]))

    (output / "summary.json").write_text(json.dumps({
        "schemaVersion": 1,
        "hardwareContext": "See host-context.json and per-case command.json",
        "delayScope": "source interval end to async-profiler's near-entry native handler timestamp, before stack capture",
        "cases": summaries,
    }, indent=2) + "\n")
    context["loadavgAfter"] = Path("/proc/loadavg").read_text().strip()
    (output / "host-context.json").write_text(json.dumps(context, indent=2) + "\n")
    print(f"Actual JVM/AP signal pressure passed for {', '.join(selected)} in {output}")


if __name__ == "__main__":
    main()
