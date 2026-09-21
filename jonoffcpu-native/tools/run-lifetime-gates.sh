#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
workspace_dir=$(dirname "$repo_dir")
evidence_dir=${JONOFFCPU_LIFETIME_EVIDENCE_DIR:-"$workspace_dir/pulsar-profiling/generic-signal-jfr-implementation/remaining-gates/t07-t09-evidence"}
image=jonoffcpu-cookie-bpf-toolchain:rust-1.85-libbpf-0.27.1

mkdir -p "$evidence_dir"
git -C "$repo_dir" rev-parse HEAD >"$evidence_dir/source-revision.txt"
git -C "$repo_dir" status --short >"$evidence_dir/source-status.txt"
cp "$repo_dir/jonoffcpu-native/tools/run-lifetime-gates.sh" "$evidence_dir/runner.sh"

docker build -t "$image" -f "$repo_dir/jonoffcpu-native/tools/Dockerfile.bpf" \
  "$repo_dir/jonoffcpu-native/tools" >"$evidence_dir/docker-build.log" 2>&1

docker run --rm --privileged --pid=host \
  -v "$repo_dir:/work" \
  -v jonoffcpu-cookie-cargo-registry:/usr/local/cargo/registry \
  -v /sys/kernel/btf:/sys/kernel/btf:ro \
  -w /work/jonoffcpu-native "$image" \
  sh -c 'cargo build --locked --bin jonoffcpu-task-lifetime-proof && cargo test --locked --lib --no-run' \
  >"$evidence_dir/compile.log" 2>&1

proof_binary="$repo_dir/jonoffcpu-native/target/debug/jonoffcpu-task-lifetime-proof"
test_binary=$(find "$repo_dir/jonoffcpu-native/target/debug/deps" -maxdepth 1 -type f -name 'jonoffcpu_native-*' -perm -111 | sort | tail -1)
sha256sum "$proof_binary" "$test_binary" >"$evidence_dir/binary-sha256.txt"
sha256sum "$repo_dir/jonoffcpu-native/src/bpf/jonoffcpu_cookie.bpf.c" \
  "$repo_dir"/jonoffcpu-native/target/debug/build/jonoffcpu-native-*/out/jonoffcpu_cookie_sched_exit.skel.rs \
  >"$evidence_dir/bpf-object-sha256.txt"
stat -Lc '%d:%i' /proc/self/ns/pid >"$evidence_dir/outer-pidns-identity.txt"

docker run --rm --privileged \
  -v "$evidence_dir/outer-pidns-identity.txt:/outer-pidns-identity:ro" \
  -v /sys/kernel/btf:/sys/kernel/btf:ro \
  -v /sys/kernel/debug:/sys/kernel/debug \
  -v /sys/kernel/tracing:/sys/kernel/tracing \
  "$image" sh -c '
    uname -a
    printf "architecture="; uname -m
    printf "innerPidNamespace="; stat -Lc "%d:%i" /proc/self/ns/pid
    printf "outerPidNamespace="; cat /outer-pidns-identity
    printf "procMount="; grep " - proc proc " /proc/self/mountinfo | head -1
    printf "nsLastPidMode="; stat -Lc "%A %u:%g" /proc/sys/kernel/ns_last_pid
    printf "nsLastPid="; cat /proc/sys/kernel/ns_last_pid
    printf "pidMax="; cat /proc/sys/kernel/pid_max
    printf "capabilities="; grep "^Cap" /proc/self/status | tr "\n" " "; echo
    printf "signalSet="; kill -l | tr "\n" " "; echo
    grep -E "CONFIG_(CHECKPOINT_RESTORE|BPF|BPF_SYSCALL)=" /proc/config.gz 2>/dev/null || true
  ' >"$evidence_dir/environment-preflight.txt" 2>&1

set +e
docker run --rm --privileged \
  -v "$repo_dir:/work:ro" \
  -v "$evidence_dir/outer-pidns-identity.txt:/outer-pidns-identity:ro" \
  -v /sys/kernel/btf:/sys/kernel/btf:ro \
  -v /sys/kernel/debug:/sys/kernel/debug \
  -v /sys/kernel/tracing:/sys/kernel/tracing \
  "$image" /work/jonoffcpu-native/target/debug/jonoffcpu-task-lifetime-proof \
  >"$evidence_dir/t07-t08.json" 2>"$evidence_dir/t07-t08.stderr"
t07_t08_status=$?

docker run --rm --privileged \
  -v "$repo_dir:/work:ro" \
  -v /sys/kernel/btf:/sys/kernel/btf:ro \
  -v /sys/kernel/debug:/sys/kernel/debug \
  -v /sys/kernel/tracing:/sys/kernel/tracing \
  "$image" "/work/${test_binary#"$repo_dir/"}" \
  'collector::tests::privileged_prepare_failure_unwinds_all_owned_bpf_objects' \
  --ignored --exact --nocapture \
  >"$evidence_dir/t09.log" 2>"$evidence_dir/t09.stderr"
t09_status=$?

docker run --rm --privileged \
  -v "$repo_dir:/work:ro" \
  -v /sys/kernel/btf:/sys/kernel/btf:ro \
  -v /sys/kernel/debug:/sys/kernel/debug \
  -v /sys/kernel/tracing:/sys/kernel/tracing \
  "$image" "/work/${test_binary#"$repo_dir/"}" \
  'collector::tests::privileged_source_handle_and_epoch_are_one_shot' \
  --ignored --exact --nocapture \
  >"$evidence_dir/t14.log" 2>"$evidence_dir/t14.stderr"
t14_status=$?
set -e

printf 't07_t08=%s\nt09=%s\nt14=%s\n' "$t07_t08_status" "$t09_status" "$t14_status" \
  >"$evidence_dir/exit-status.txt"

if (( t07_t08_status != 0 || t09_status != 0 || t14_status != 0 )); then
  exit 1
fi
