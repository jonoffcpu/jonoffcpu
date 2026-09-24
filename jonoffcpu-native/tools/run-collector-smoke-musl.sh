#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
workspace_dir=$(dirname "$repo_dir")
evidence_dir="$workspace_dir/pulsar-profiling/generic-signal-jfr-implementation/logs"
image=jonoffcpu-cookie-musl-toolchain:rust-1.85-alpine3.21-libbpf-0.27.1

"$repo_dir/jonoffcpu-native/tools/build-musl-in-docker.sh"
docker run --rm --privileged \
  -v "$repo_dir:/work" \
  -v "$evidence_dir:/evidence" \
  -v /sys/kernel/btf:/sys/kernel/btf:ro \
  -v /sys/kernel/debug:/sys/kernel/debug \
  -v /sys/kernel/tracing:/sys/kernel/tracing \
  -e JONOFFCPU_SMOKE_OUTPUT=/evidence/jonoffcpu-native-collector-musl-source.pb \
  -w /work/jonoffcpu-native \
  "$image" \
  target-musl/release/jonoffcpu-native-collector-smoke
