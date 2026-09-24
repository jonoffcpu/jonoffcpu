#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
workspace_dir=$(dirname "$repo_dir")
evidence_dir="$workspace_dir/pulsar-profiling/generic-signal-jfr-implementation/logs"
image=jonoffcpu-cookie-bpf-toolchain:rust-1.85-libbpf-0.27.1
smoke_output=${JONOFFCPU_SMOKE_OUTPUT:-/evidence/jonoffcpu-native-collector-pidns-source.pb}

docker build -t "$image" -f "$repo_dir/jonoffcpu-native/tools/Dockerfile.bpf" "$repo_dir/jonoffcpu-native/tools"
# Deliberately omit --pid=host. The target sees its container PID while BPF
# records the initial-namespace host TGID learned through exact task storage.
docker run --rm --privileged \
  -v "$repo_dir:/work" \
  -v "$evidence_dir:/evidence" \
  -v jonoffcpu-cookie-cargo-registry:/usr/local/cargo/registry \
  -v /sys/kernel/btf:/sys/kernel/btf:ro \
  -v /sys/kernel/debug:/sys/kernel/debug \
  -v /sys/kernel/tracing:/sys/kernel/tracing \
  -e JONOFFCPU_SMOKE_OUTPUT="$smoke_output" \
  -e JONOFFCPU_SMOKE_SIGNAL="${JONOFFCPU_SMOKE_SIGNAL:-}" \
  -w /work/jonoffcpu-native \
  "$image" \
  cargo run --locked --bin jonoffcpu-native-collector-smoke -- "$@"
