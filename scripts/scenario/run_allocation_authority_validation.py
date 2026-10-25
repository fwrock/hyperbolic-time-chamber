#!/usr/bin/env python3
"""Run the allocation/restore protocol over two owned, independent JVMs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time


def classpath_digest(classpath):
    digest = hashlib.sha256()
    for entry in classpath.split(os.pathsep):
        path = Path(entry)
        files = sorted(path.rglob("*")) if path.is_dir() else [path]
        for item in files:
            if not item.is_file():
                continue
            digest.update(str(item).encode() + b"\0")
            with item.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
    return digest.hexdigest()


def wait_json(path, processes, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            return json.loads(path.read_text())
        for process in processes:
            if process.poll() is not None:
                raise RuntimeError(f"validation JVM exited before {path.name}: {process.returncode}")
        time.sleep(0.1)
    raise RuntimeError(f"validation timed out waiting for {path.name}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--classpath-file", type=Path, default=Path("target/streams/test/fullClasspath/_global/streams/export"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = args.output or Path(tempfile.mkdtemp(prefix="htc-allocation-authority-"))
    if args.output:
        output.mkdir(parents=True, exist_ok=False)
    output = output.resolve()
    print(f"Validation output: {output}", flush=True)
    if not args.classpath_file.exists():
        raise RuntimeError("run sbt 'Test / compile' 'export Test / fullClasspath' before validation")
    classpath = args.classpath_file.read_text().strip()
    if not classpath or not all(Path(entry).exists() for entry in classpath.split(os.pathsep)):
        raise RuntimeError("missing test classpath; run sbt 'Test / compile' 'export Test / fullClasspath' first")
    executable = str(Path(os.environ.get("JAVA_HOME", "/usr")).joinpath("bin/java"))
    if not Path(executable).exists():
        executable = "java"
    base = [executable, "-Xms64m", "-Xmx512m", "-cp", classpath,
            "org.interscity.htc.model.test.AllocationAuthorityJvmProbe"]
    executable_digest = classpath_digest(classpath)
    processes, handles, commands = [], [], []
    try:
        for role in ("worker", "controller"):
            seed = [] if role == "worker" else [ready["address"]]
            command = base + [role, str(output)] + seed
            log = (output / f"{role}.log").open("w")
            handles.append(log)
            commands.append(command)
            processes.append(subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT))
            if role == "worker":
                ready = wait_json(output / "worker-ready.json", processes, 75)
        result = wait_json(output / "result.json", processes, 120)
        assert result["passed"] and result["controller_pid"] != result["worker_pid"]
        assert result["worker_pid"] == processes[0].pid and result["controller_pid"] == processes[1].pid
        assert result["callback_owner"] == result["restored_owner"] == ready["address"]
        assert result["epoch"] == ready["epoch"] and result["restored_value"] == 23
        assert classpath_digest(classpath) == executable_digest, "classpath changed during validation"
        manifest = {
            "commands": commands, "result": result,
            "classpath_contents_sha256": executable_digest,
            "launcher_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "scope": "allocator authority, registration and restore over independent JVMs; hash routing; explicit pause probe",
        }
        for role in ("worker", "controller"):
            data = (output / f"{role}.log").read_bytes()
            (output / f"{role}-execution.log").write_bytes(data)
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print(json.dumps({"passed": True, "output": str(output), "result": result}, indent=2), flush=True)
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
        for process in processes:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        for handle in handles:
            handle.close()


if __name__ == "__main__":
    main()
