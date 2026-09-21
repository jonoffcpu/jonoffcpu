#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
image=jonoffcpu-cookie-bpf-toolchain:rust-1.85-libbpf-0.27.1

docker build -t "$image" -f "$repo_dir/jonoffcpu-native/tools/Dockerfile.bpf" "$repo_dir/jonoffcpu-native/tools"
docker run --rm --privileged --pid=host \
  -v "$repo_dir:/work" \
  -v jonoffcpu-cookie-cargo-registry:/usr/local/cargo/registry \
  -v /sys/kernel/btf:/sys/kernel/btf:ro \
  -v /sys/kernel/debug:/sys/kernel/debug \
  -v /sys/kernel/tracing:/sys/kernel/tracing \
  -w /work/jonoffcpu-native \
  "$image" \
  cargo run --locked --bin jonoffcpu-endpoint-boundary-measurement -- "$@"
