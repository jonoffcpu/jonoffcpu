#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
image=jonoffcpu-cookie-musl-toolchain:rust-1.85-alpine3.21-libbpf-0.27.1

docker build -t "$image" -f "$repo_dir/jonoffcpu-native/tools/Dockerfile.musl" "$repo_dir/jonoffcpu-native/tools"
docker run --rm \
  -v "$repo_dir:/work" \
  -v jonoffcpu-cookie-cargo-registry-musl:/usr/local/cargo/registry \
  -v /sys/kernel/btf:/sys/kernel/btf:ro \
  -e CARGO_TARGET_DIR=/work/jonoffcpu-native/target-musl \
  -e 'RUSTFLAGS=-C target-feature=-crt-static -C link-arg=-lzstd -C link-arg=-llzma' \
  -w /work/jonoffcpu-native \
  "$image" \
  sh -c 'cargo build --release --locked && scanelf -n target-musl/release/libjonoffcpu_native.so'
