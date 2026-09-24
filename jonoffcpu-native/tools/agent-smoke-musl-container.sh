#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

: "${JONOFFCPU_DELIVERY:?}"
: "${JONOFFCPU_SECONDS:?}"
: "${JONOFFCPU_HOST_UID:?}"
: "${JONOFFCPU_HOST_GID:?}"

work=/tmp/jonoffcpu-agent-musl
jonoffcpu="$work/jonoffcpu"
ap="$work/async-profiler"
native_target="$work/native-target"

mkdir -p "$jonoffcpu" "$ap" "$native_target"
tar -C /jonoffcpu-source \
  --exclude='jonoffcpu-agent/build' \
  --exclude='jonoffcpu-native/target' \
  --exclude='jonoffcpu-native/target-musl' \
  --exclude='jonoffcpu-native/target-musl-agent-smoke' \
  -cf - jonoffcpu-agent jonoffcpu-native | tar -C "$jonoffcpu" -xf -
tar -C /ap-source --exclude='.git' --exclude='build' -cf - . | tar -C "$ap" -xf -

(
  cd "$ap"
  make -j"$(nproc)" all
) > /out/ap-musl-build.log 2>&1

(
  cd "$jonoffcpu/jonoffcpu-native"
  CARGO_TARGET_DIR="$native_target" \
  RUSTFLAGS='-C target-feature=-crt-static -C link-arg=-lzstd -C link-arg=-llzma' \
    cargo build --release --locked
  scanelf -n "$native_target/release/libjonoffcpu_native.so"
) > /out/native-musl-build.log 2>&1

(
  cd "$jonoffcpu/jonoffcpu-agent"
  make all test-java \
    JAVA_HOME="$JAVA_HOME" \
    AP_DIR="$ap" \
    AP_API_JAR="$ap/build/jar/async-profiler.jar" \
    NATIVE_DIR="$jonoffcpu/jonoffcpu-native" \
    NATIVE_TARGET_DIR="$native_target" \
    NATIVE_PROFILE=release \
    NATIVE_BUILD_COMMAND=true
  scanelf -n build/lib/libjonoffcpu.so build/lib/libjonoffcpu_native.so "$ap/build/lib/libasyncProfiler.so"
) > /out/agent-musl-build.log 2>&1

agent_build="$jonoffcpu/jonoffcpu-agent/build"
"$JAVA_HOME/bin/java" -version > /out/java-version.log 2>&1
java_command=(
  "$JAVA_HOME/bin/java"
  --enable-native-access=ALL-UNNAMED
  -Xms128m
  -Xmx256m
  "-agentpath:$agent_build/lib/libjonoffcpu.so=jonoffcpuoutput=/out/jonoffcpu-capture.pb,jonoffcpudelivery=$JONOFFCPU_DELIVERY,asprofpath=$ap/build/lib/libasyncProfiler.so,event=cpu,alloc=1m,wall=10ms,lock=1ms,jfrsync=profile,file=/out/jonoffcpu-capture.jfr"
  -cp "$agent_build/jonoffcpu-agent.jar:$agent_build/test-classes"
  io.github.jonoffcpu.agent.NativeAgentWorkload
  "$JONOFFCPU_SECONDS"
)
printf '%q ' "${java_command[@]}" > /out/java-command.txt
printf '\n' >> /out/java-command.txt
"${java_command[@]}" > /out/process.log 2>&1

classpath="$agent_build/jonoffcpu-agent.jar:$agent_build/test-classes"
"$JAVA_HOME/bin/java" -cp "$classpath" io.github.jonoffcpu.agent.MixedRecordingCheck \
  /out/jonoffcpu-capture.jfr /out/event-counts.json > /out/category-check.log 2>&1
"$JAVA_HOME/bin/java" -cp "$classpath" io.github.jonoffcpu.correlator.OffCpuCorrelator \
  --source /out/jonoffcpu-capture.pb \
  --jfr /out/jonoffcpu-capture.jfr \
  --output /out/analysis > /out/analysis.log 2>&1
"$JAVA_HOME/bin/jfr" summary /out/analysis/jonoffcpu-offcpu-synthetic.jfr \
  > /out/synthetic-jfr-summary.log 2>&1
"$ap/build/bin/jfrconv" --cpu /out/analysis/jonoffcpu-offcpu-synthetic.jfr \
  /out/analysis/compatibility-view.collapsed > /out/converter.log 2>&1

python3 - <<'PY'
import json
from pathlib import Path

out = Path('/out')
report = json.loads((out / 'analysis/jonoffcpu-report.json').read_text())
events = json.loads((out / 'event-counts.json').read_text())
collapsed = (out / 'analysis/compatibility-view.collapsed').read_text().splitlines()
converted = sum(int(line.rsplit(' ', 1)[1]) for line in collapsed)
# Counters are uint64 in the report, printed as decimal strings by the proto3 JSON mapping.
expected = int(report['syntheticJfr']['syntheticEvents'])
required = ('jdk.ExecutionSample', 'profiler.SignalSample', 'profiler.WallClockSample',
            'jdk.JavaMonitorEnter', 'jdk.JVMInformation', 'jdk.GCHeapSummary', 'jonoffcpu.IntegrationMarker')
missing = [event for event in required if int(events.get(event, 0)) <= 0]
allocations = int(events.get('jdk.ObjectAllocationInNewTLAB', 0)) \
    + int(events.get('jdk.ObjectAllocationOutsideTLAB', 0))
if allocations <= 0:
    missing.append('allocation samples')
failures = {
    'unmatchedSource': int(report['unmatchedSource']),
    'orphanJfr': int(report['orphanJfr']),
    'invalidSource': int(report['invalidSource']),
    'invalidJfr': int(report['invalidJfr']),
    'identityUnverified': int(report['identityUnverified']),
}
matched = int(report['matched'])
if matched <= 0 or any(failures.values()):
    raise SystemExit(f'invalid correlation result: matched={matched}, failures={failures}')
if missing:
    raise SystemExit(f'missing mixed event categories: {missing}')
if converted != expected:
    raise SystemExit(f'converter count {converted} != synthetic events {expected}')
summary = {
    'schemaVersion': 1,
    'state': 'complete',
    'libc': 'musl',
    'javaVersion': 17,
    'delivery': __import__('os').environ['JONOFFCPU_DELIVERY'],
    'matched': matched,
    **failures,
    'syntheticEvents': expected,
    'convertedEvents': converted,
    'collapsedStacks': len(collapsed),
    'eventCounts': {**{event: events[event] for event in required}, 'allocations': allocations},
}
(out / 'musl-integration-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, sort_keys=True))
PY

mkdir -p /out/distribution
install -m 755 "$agent_build/lib/libjonoffcpu.so" /out/distribution/libjonoffcpu.so
install -m 755 "$agent_build/lib/libjonoffcpu_native.so" /out/distribution/libjonoffcpu_native.so
install -m 644 "$agent_build/jonoffcpu-agent.jar" /out/distribution/jonoffcpu-agent.jar
install -m 755 "$ap/build/lib/libasyncProfiler.so" /out/distribution/libasyncProfiler.so
install -m 644 "$ap/build/jar/async-profiler.jar" /out/distribution/async-profiler.jar
install -m 755 "$ap/build/bin/jfrconv" /out/distribution/jfrconv
scanelf -n /out/distribution/*.so > /out/distribution/scanelf-needed.txt
(
  cd /out/distribution
  sha256sum async-profiler.jar jonoffcpu-agent.jar jfrconv libasyncProfiler.so libjonoffcpu.so libjonoffcpu_native.so \
    > SHA256SUMS
)

chown -R "$JONOFFCPU_HOST_UID:$JONOFFCPU_HOST_GID" /out
