#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
image=jonoffcpu-cookie-bpf-toolchain:rust-1.85-libbpf-0.27.1

docker build -t "$image" -f "$repo_dir/jonoffcpu-native/tools/Dockerfile.bpf" "$repo_dir/jonoffcpu-native/tools"
docker run --rm --privileged \
  -v "$repo_dir:/work" \
  -v jonoffcpu-cookie-cargo-registry:/usr/local/cargo/registry \
  -v /sys/kernel/btf:/sys/kernel/btf:ro \
  -w /work/jonoffcpu-native \
  "$image" \
  sh -c 'cargo build --locked --bin jonoffcpu-time-namespace-negative && unshare --time --fork --monotonic 1 target/debug/jonoffcpu-time-namespace-negative'
