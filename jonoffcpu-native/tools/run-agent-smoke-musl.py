#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Build and run the full native-agent pipeline on Alpine/musl in a private PID namespace."""

import argparse
import json
import os
import subprocess
from pathlib import Path


def run(command, log, cwd=None):
    with log.open("w") as output:
        subprocess.run([str(part) for part in command], cwd=cwd, stdout=output,
                       stderr=subprocess.STDOUT, check=True)


def revision(path):
    return subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()


def dirty(path):
    return bool(subprocess.check_output(["git", "-C", str(path), "status", "--porcelain"], text=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ap-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="New evidence directory")
    parser.add_argument("--delivery", choices=("queued", "coalescing"), default="queued")
    parser.add_argument("--seconds", type=int, default=5)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 60:
        parser.error("--seconds must be in 1..60")

    repo = Path(__file__).resolve().parents[2]
    ap = args.ap_dir.resolve(strict=True)
    if not (ap / "Makefile").is_file():
        parser.error(f"Not an async-profiler source tree: {ap}")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)

    (output / "source-revisions.json").write_text(json.dumps({
        "jonoffcpu": {"revision": revision(repo), "dirty": dirty(repo)},
        "asyncProfiler": {"revision": revision(ap), "dirty": dirty(ap)},
        "baseImage": "rust:1.85-alpine3.21@sha256:4333721398de61f53ccbe53b0b855bcc4bb49e55828e8f652d7a8ac33dd0c118",
    }, indent=2) + "\n")

    image = "jonoffcpu-agent-musl-integration:rust-1.85-alpine3.21-jdk17"
    run(["docker", "build", "-t", image, "-f",
         repo / "jonoffcpu-native/tools/Dockerfile.agent-musl", repo / "jonoffcpu-native/tools"],
        output / "image-build.log", repo)
    command = [
        "docker", "run", "--rm", "--privileged", "--memory=2g", "--ulimit", "core=0",
        "-e", f"JONOFFCPU_DELIVERY={args.delivery}", "-e", f"JONOFFCPU_SECONDS={args.seconds}",
        "-e", f"JONOFFCPU_HOST_UID={os.getuid()}", "-e", f"JONOFFCPU_HOST_GID={os.getgid()}",
        "-v", f"{repo}:/jonoffcpu-source:ro", "-v", f"{ap}:/ap-source:ro",
        "-v", f"{output}:/out", "-v", "jonoffcpu-cookie-cargo-registry-musl:/usr/local/cargo/registry",
        "-v", "/sys/kernel/btf:/sys/kernel/btf:ro",
        "-v", "/sys/kernel/debug:/sys/kernel/debug",
        "-v", "/sys/kernel/tracing:/sys/kernel/tracing",
        image, "/jonoffcpu-source/jonoffcpu-native/tools/agent-smoke-musl-container.sh",
    ]
    (output / "docker-command.json").write_text(json.dumps(command, indent=2) + "\n")
    run(command, output / "container.log", repo)
    summary = json.loads((output / "musl-integration-summary.json").read_text())
    print(f"Alpine/musl native-agent integration passed: {summary['matched']} verified matches in {output}")


if __name__ == "__main__":
    main()
