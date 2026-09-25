# Setting up a host

What `jonoffcpu` needs from the kernel, the JVM and the container runtime, and
how to provide it on a Linux host, in Docker, and in the Linux VM behind Docker
Desktop. The agent checks what it needs at startup and fails closed: a host
that lacks a feature gets an error, never a capture with degraded data.

- [Requirements](#requirements)
- [The bundled native libraries](#the-bundled-native-libraries)
- [Kernel settings](#kernel-settings)
  - [Applying them to a VM's kernel](#applying-them-to-a-vms-kernel)
  - [Running the collector without root](#running-the-collector-without-root)
- [Profiling in Docker](#profiling-in-docker)
  - [Docker Desktop on macOS and Windows](#docker-desktop-on-macos-and-windows)

## Requirements

- 64-bit Linux on x86-64 or arm64, with [BTF](https://docs.kernel.org/bpf/btf.html),
  eBPF task storage, the `tp_btf/sched_exit_tp` tracepoint, and the
  `bpf_send_signal_task` helper. The
  agent checks the running kernel and fails closed if any of these is missing.
  This checks the two you can see from a shell; it prints a non-zero count on a
  suitable kernel:

  ```sh
  test -r /sys/kernel/btf/vmlinux && grep -ac btf_trace_sched_exit_tp /sys/kernel/btf/vmlinux
  ```

- Privileges to load and attach the BPF programs: the
  [capabilities](https://man7.org/linux/man-pages/man7/capabilities.7.html)
  `CAP_BPF` and `CAP_PERFMON`, or root. See [Kernel settings](#kernel-settings) for the
  sysctls that async-profiler needs alongside them.
- Java 17 or newer for the agent; Java 21 or newer for the correlator.
- On Java 24 and newer, add `--sun-misc-unsafe-memory-access=allow` to the JVM
  being profiled and to the correlator. The bundled protobuf codec that reads
  and writes the capture stream uses `sun.misc.Unsafe`, which the JDK reports
  once per JVM as a terminally deprecated call; the flag silences that warning
  and changes nothing else.
- For the sleeping and run-queue split (on by default), a kernel built with
  `CONFIG_SCHED_INFO`; see
  [Sleeping and run-queue time](off-cpu-profiling.md#sleeping-and-run-queue-time).

## The bundled native libraries

The agent JAR is self-contained. It embeds the JNI bridge, the native
collector, and the patched async-profiler for Linux x86-64 and arm64, each
linked against both [glibc](https://en.wikipedia.org/wiki/Glibc) and
[musl](https://en.wikipedia.org/wiki/Musl) (Alpine), verifies them against a SHA-256
manifest, and extracts them to a private temporary directory at startup.
Nothing needs to be installed on the host. The agent picks the glibc or musl
bundle from the C library mapped into the running JVM and never falls back to
the other one; on an unusual host,
`-Dio.github.jonoffcpu.agent.nativeLibc=glibc` or `=musl` selects it
explicitly. If the temporary directory is mounted `noexec`, point
`-Dio.github.jonoffcpu.agent.nativeWorkDir` at an executable location.

## Kernel settings

jonoffcpu's own eBPF collector needs privileges (`CAP_BPF` and `CAP_PERFMON`, or
root), and nothing else. The settings below are about the *other* half of the
capture: async-profiler runs inside the JVM, usually unprivileged, and the
kernel restricts by default what an unprivileged process may observe. Without
them the capture still completes, but parts of it are degraded — typically
missing kernel frames, truncated native stacks, or no `cpu` event at all.

| Setting | Suggested value | Why |
| --- | --- | --- |
| [`kernel.perf_event_paranoid`](https://docs.kernel.org/admin-guide/sysctl/kernel.html#perf-event-paranoid) | `1` | The gate on `perf_event_open`. The common default `2` lets an unprivileged process measure only its own user space, so async-profiler's `cpu` engine cannot sample kernel stacks; `>= 2` is also the usual reason `perf_event_open` fails outright and the profiler falls back or errors. `1` allows per-process profiling including kernel stacks. `CAP_PERFMON` bypasses the check. |
| [`kernel.kptr_restrict`](https://docs.kernel.org/admin-guide/sysctl/kernel.html#kptr-restrict) | `0` | Kernel symbol addresses in [`/proc/kallsyms`](https://man7.org/linux/man-pages/man5/proc_kallsyms.5.html) read back as zeros unless the reader has `CAP_SYSLOG` (`1`), or for everyone (`2`). Both async-profiler and jonoffcpu's collector symbolize kernel frames from that file, so with addresses hidden the kernel part of a stack stays as raw addresses. |
| [`kernel.perf_event_max_stack`](https://docs.kernel.org/admin-guide/sysctl/kernel.html#perf-event-max-stack) | `1024` | The maximum call-chain depth `perf_events` records, `127` by default, which silently truncates deep JVM native stacks. Raising it only affects async-profiler: jonoffcpu's BPF stack map has a fixed depth of 127. Do not lower it below 127 — the collector's stack map cannot be created if the sysctl is smaller than the map's depth. |
| [`kernel.perf_event_mlock_kb`](https://docs.kernel.org/admin-guide/sysctl/kernel.html#perf-event-mlock-kb) | `2048` | async-profiler mmaps an 8 KB perf buffer per thread, bounded by `ulimit -l` plus this value times the number of CPUs. On a thread-heavy application the default `516` runs out and native stacks are dropped for the remaining threads. |

Apply them for the current boot:

```sh
sudo sysctl -w kernel.perf_event_paranoid=1
sudo sysctl -w kernel.kptr_restrict=0
sudo sysctl -w kernel.perf_event_max_stack=1024
sudo sysctl -w kernel.perf_event_mlock_kb=2048
```

Use `sysctl` rather than `sudo echo 1 > /proc/sys/…`: the redirection is
performed by the calling shell, which is still unprivileged, so that form fails
with "Permission denied" before `sudo` runs. `sudo tee`
(`echo 1 | sudo tee /proc/sys/kernel/perf_event_paranoid`) works as well. To
make the values persist across reboots, put them in
`/etc/sysctl.d/99-jonoffcpu.conf` as `key = value` lines.

In a container, these are host-wide kernel settings: `kernel.perf_event_*` and
`kernel.kptr_restrict` are not namespaced, so set them on the host, not inside
the container. For the same reason `docker run --sysctl` refuses them, since it
accepts only namespaced keys.

### Applying them to a VM's kernel

With Docker Desktop on macOS or Windows, and with Colima, Lima or any other
Linux VM, the kernel that matters is the VM's: `sysctl` on the workstation
changes nothing that the containers can see. Write the values from a privileged
container, which shares the VM kernel's `/proc/sys`:

```sh
docker run --rm --privileged alpine sh -c '
  echo 1    > /proc/sys/kernel/perf_event_paranoid
  echo 0    > /proc/sys/kernel/kptr_restrict
  echo 1024 > /proc/sys/kernel/perf_event_max_stack
  echo 2048 > /proc/sys/kernel/perf_event_mlock_kb
  echo 0    > /proc/sys/kernel/unprivileged_bpf_disabled'
```

`--privileged` is what makes `/proc/sys` writable; without it the container gets
it read-only, and adding `--cap-add SYS_ADMIN` or
`--security-opt seccomp=unconfined` changes nothing that `--privileged` has not
already granted. The writes affect the whole VM and last until it restarts, so
this is a per-boot step rather than a one-time setup. The commands are listed
one per line on purpose: the last one fails with `EPERM` on a kernel where
`unprivileged_bpf_disabled` already reads `1`, and chaining them with `&&` would
hide the earlier successes behind that failure. It is also the one line that is
optional — see [Running the collector without root](#running-the-collector-without-root).

### Running the collector without root

[`kernel.unprivileged_bpf_disabled`](https://docs.kernel.org/admin-guide/sysctl/kernel.html#unprivileged-bpf-disabled)
`= 0` re-enables the `bpf()` syscall for
callers that hold no BPF capability:

```sh
sudo sysctl -w kernel.unprivileged_bpf_disabled=0
```

It does **not** make jonoffcpu work unprivileged. Unprivileged `bpf()` only ever
permitted socket-filter programs, while the collector loads tracepoint programs
and uses helpers that require `CAP_BPF` plus `CAP_PERFMON`; with those
capabilities the sysctl is not consulted at all. It is worth setting only where
something else in the toolchain trips over the syscall gate. Note that the value
`1` is a one-way latch: once the sysctl reads `1`, the kernel refuses to change
it until the next boot, so a host that has disabled unprivileged BPF that way
has to be rebooted (distributions that default to `2` can be changed at
runtime).

## Profiling in Docker

The agent and the collector both run inside the container with the JVM: the
agent extracts the collector from its JAR and starts it as a child process, and
the eBPF program resolves thread ids inside the target's own PID namespace. So
the container is profiled as it is: neither `--pid=host` nor `--net=host` is
needed, and no kernel headers have to be mounted, because the collector is CO-RE
and reads the kernel's own BTF. General-purpose BPF toolbox images ask for all
of these because they trace the whole host from outside, and because BCC
compiles its programs against kernel headers at runtime. The only case that
needs `--pid=host`, or `--pid=container:<id>`, is running the standalone
collector against a target in another container, which is what this
repository's proof scripts do.

| The container needs | How | Why |
| --- | --- | --- |
| BPF and perf capabilities | `--cap-add BPF --cap-add PERFMON` | Loading and attaching the programs is `bpf()`; both scheduler hooks are BTF raw tracepoints attached through BPF links. Docker's default [seccomp profile](https://docs.docker.com/engine/security/seccomp/) permits it once the matching capabilities are present, so `--security-opt seccomp=unconfined` is not required. |
| [`tracefs`](https://docs.kernel.org/trace/ftrace.html#the-file-system) on `/sys/kernel/tracing` | a `local` volume, below | Both hooks are BTF raw tracepoints, which do not read `tracefs`: on a Linux host the packaged smoke passes without it mounted, both `--privileged` and with only `--cap-add BPF --cap-add PERFMON --cap-add SYSLOG`. Keep the mount on Docker Desktop, where that has not been verified. |
| Kernel symbols | `kernel.kptr_restrict=0` on the host, or `--cap-add SYSLOG` | Otherwise `/proc/kallsyms` reads back as zeros and kernel frames stay raw addresses. |
| An executable temporary directory | `-Dio.github.jonoffcpu.agent.nativeWorkDir=…` if `/tmp` is `noexec` | The agent extracts the native bundle and executes it. |

BTF needs nothing: `/sys/kernel/btf/vmlinux` is part of the container's own
`sysfs` and is readable already.

Mount `tracefs` with a `local` volume, which passes its options straight to
`mount`:

```sh
docker volume create --driver local \
  --opt type=tracefs --opt device=tracefs --opt o=ro tracefs

docker run --rm \
  --cap-add BPF --cap-add PERFMON \
  -v tracefs:/sys/kernel/tracing \
  your-image \
  java -javaagent:jonoffcpu-agent.jar=jonoffcpu.yaml -jar application.jar
```

Read-only is enough, because nothing writes to it. The equivalent in Compose:

```yaml
volumes:
  tracefs:
    driver: local
    driver_opts: { type: tracefs, device: tracefs, o: ro }
services:
  app:
    cap_add: [BPF, PERFMON]
    volumes:
      - tracefs:/sys/kernel/tracing
```

Docker performs this mount itself, before the container starts, so it works in
an unprivileged container and needs nothing bind-mounted from the host. Bind
mounting the host's `/sys/kernel/tracing` is equivalent where the host is Linux.
Mounting `tracefs` from inside the container instead requires `--privileged`:
`/sys` is mounted read-only and locked, so `--cap-add SYS_ADMIN` alone cannot do
it. If a hardened runtime refuses the capability-based setup, `--privileged` is
the blunt alternative; it is what this repository's own proof scripts use.

### Docker Desktop on macOS and Windows

There the containers run in a Linux VM, and the kernel is the VM's, not the
host operating system's. Bind mounting `/sys/kernel/tracing` cannot work, since
that path would be resolved on macOS or Windows; the `local` volume above does
work, because Docker mounts it inside the VM. The sysctls are the VM's too, and
are set as described in
[Applying them to a VM's kernel](#applying-them-to-a-vms-kernel).

The kernel features are the real question. jonoffcpu needs BTF, eBPF task
storage, `bpf_send_signal_task`, and the `tp_btf/sched_exit_tp` tracepoint,
which is recent enough that Docker Desktop's LinuxKit kernel and a stock WSL2
kernel may not have it; the agent then fails closed rather than producing
degraded data. Check the VM you have before going further:

```sh
docker run --rm --privileged alpine sh -c '
  uname -r
  ls -l /sys/kernel/btf/vmlinux
  mount -t tracefs tracefs /sys/kernel/tracing &&
    cat /sys/kernel/tracing/events/sched/sched_switch/id
  grep -ac btf_trace_sched_exit_tp /sys/kernel/btf/vmlinux'
```

All four must succeed, the last one printing a non-zero count. If they do not,
supply a newer kernel — on Windows through `kernel=` in `.wslconfig`, on macOS
through a VM manager that lets you choose the image — or profile on a Linux
host. Either way, only a JVM running inside that Linux VM can be profiled; a
JVM running natively on macOS or Windows is invisible to it.
