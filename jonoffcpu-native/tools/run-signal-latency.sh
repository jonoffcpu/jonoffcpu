#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
workspace_dir=$(dirname "$repo_dir")
evidence_dir="$workspace_dir/pulsar-profiling/generic-signal-jfr-implementation/logs"
image=jonoffcpu-cookie-bpf-toolchain:rust-1.85-libbpf-0.27.1

docker build -t "$image" -f "$repo_dir/jonoffcpu-native/tools/Dockerfile.bpf" "$repo_dir/jonoffcpu-native/tools"
docker run --rm --privileged \
  -v "$repo_dir:/work" \
  -v "$evidence_dir:/evidence" \
  -v jonoffcpu-cookie-cargo-registry:/usr/local/cargo/registry \
  -v /sys/kernel/btf:/sys/kernel/btf:ro \
  -v /sys/kernel/debug:/sys/kernel/debug \
  -v /sys/kernel/tracing:/sys/kernel/tracing \
  -e JONOFFCPU_LATENCY_SAMPLES="${JONOFFCPU_LATENCY_SAMPLES:-5000}" \
  -e JONOFFCPU_LATENCY_SIGNAL="${JONOFFCPU_LATENCY_SIGNAL:-27}" \
  -e JONOFFCPU_LATENCY_BLOCKED_SLEEPS="${JONOFFCPU_LATENCY_BLOCKED_SLEEPS:-0}" \
  -e JONOFFCPU_LATENCY_MAX_SECONDS="${JONOFFCPU_LATENCY_MAX_SECONDS:-30}" \
  -e JONOFFCPU_LATENCY_PENDING_LIMIT="${JONOFFCPU_LATENCY_PENDING_LIMIT:-}" \
  -e JONOFFCPU_LATENCY_SOURCE="${JONOFFCPU_LATENCY_SOURCE:-/evidence/jonoffcpu-native-signal-latency-source.pb}" \
  -w /work/jonoffcpu-native \
  "$image" \
  cargo run --release --locked --bin jonoffcpu-native-signal-latency
