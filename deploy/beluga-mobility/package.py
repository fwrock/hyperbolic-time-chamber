#!/usr/bin/env python3
"""Build a self-contained Beluga engine experiment, without registry publication."""
import hashlib
import json
import argparse
from datetime import date
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).resolve().parent
BROKER_IMAGE = "docker.redpanda.com/redpandadata/redpanda:v26.2.1"


def run(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def checksum(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def compose(image, count):
    services = {
        "redpanda": {
            "image": BROKER_IMAGE,
            "network_mode": "host",
            "entrypoint": ["/bin/sh", "/start-broker.sh"],
            "command": ["--mode=dev-container", "--smp=1", "--memory=1G",
                        "--kafka-addr=0.0.0.0:39092", "--advertise-kafka-addr=127.0.0.1:39092",
                        "--rpc-addr=0.0.0.0:43145",
                        "--advertise-rpc-addr=127.0.0.1:43145", "--pandaproxy-addr=0.0.0.0:18082",
                        "--schema-registry-addr=0.0.0.0:18081", "--default-log-level=warn"],
            "volumes": ["broker:/var/lib/redpanda/data", "./start-broker.sh:/start-broker.sh:ro"],
            "healthcheck": {"test": ["CMD", "rpk", "cluster", "health", "-X", "admin.hosts=127.0.0.1:19644"],
                            "interval": "5s", "timeout": "5s", "retries": 30},
        }
    }
    for rank in range(count):
        flags = [line.split("=", 1)[0] for line in (SOURCE / "flags-on.env").read_text().splitlines()]
        env = {name: "${" + name + ":?Select flags-on.env or flags-off.env}" for name in flags}
        env.update({
            "JAVA_OPTS": "${HTC_JAVA_OPTS:--Xms256m -Xmx32g -XX:ActiveProcessorCount=28}",
            "HTC_MODEL_NAMESPACE": "mobility", "HTC_RUNTIME_MODE": "single-node" if count == 1 else "cluster",
            "HTC_CONFIG_RESOURCE": "beluga.conf" if count == 1 else "beluga-multi.conf",
            "HTC_COHORT_SIZE": str(count), "HTC_NODE_RANK": str(rank),
            "CLUSTER_IP": "127.0.0.1", "CLUSTER_PORT": str(2600 + rank),
            "SEED_PORT_1600_TCP_ADDR": "127.0.0.1", "SEED_PORT_1600_TCP_PORT": "2600",
            "MANAGEMENT_HTTP_PORT": str(18558 + rank), "HTC_METRICS_PORT": str(19101 + rank),
            "HTC_SIMULATION_CONFIG_FILE": "/scenarios/${HTC_SCENARIO:-smoke}/simulation.json",
            "HTC_API_ENABLED": "false", "HTC_CACHE_STRATEGY": "disabled", "HTC_USE_DYNAMIC_WEIGHTS": "false",
            "HTC_LOAD_BALANCE_ENABLED": "false", "HTC_CONSERVATIVE_TRANSPORT": "direct",
            "HTC_TIME_MANAGER_SCHEDULING_STRATEGY": "node-local-tick",
            "HTC_NODE_LOCAL_SCHEDULER_NODES": str(count),
            "HTC_NODE_LOCAL_SCHEDULER_WORKERS": "${HTC_SCHEDULER_WORKERS:-4}",
            "HTC_TIME_MANAGER_INSTANCES": str(count), "HTC_TIME_MANAGER_PER_NODE": "1",
            "HTC_REPORT_PARQUET_INSTANCES": str(count), "HTC_REPORT_PARQUET_PER_NODE": "1",
            "HTC_REPORT_KAFKA_INSTANCES": str(count), "HTC_REPORT_KAFKA_PER_NODE": "1",
            "HTC_REPORT_PARQUET_DIRECTORY": "/reports",
            "HTC_KAFKA_BOOTSTRAP_SERVERS": "127.0.0.1:39092",
            "KAFKA_BOOTSTRAP_SERVERS": "127.0.0.1:39092",
            "HTC_BUS_KAFKA_BROKERS": "127.0.0.1:39092",
            "HTC_SCHEMA_REGISTRY_URL": "http://127.0.0.1:18081",
            "HTC_MICRO_UPDATE_REPORT_EVERY": "0",
        })
        services[f"node{rank + 1}"] = {
            "image": image, "pull_policy": "never", "network_mode": "host", "init": True,
            "mem_limit": "${HTC_MEMORY_LIMIT:-40G}", "cpus": "${HTC_CPUS:-28}",
            "environment": env, "restart": "no", "stop_grace_period": "30s",
            "depends_on": {"redpanda": {"condition": "service_healthy"}},
            "volumes": ["${HTC_SCENARIOS_DIR:-./scenarios}:/scenarios:ro", "reports:/reports"],
        }
    return {"services": services, "volumes": {"reports": {}, "broker": {}}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resume", action="store_true", help="Resume packaging an interrupted release directory")
    parser.add_argument("--offline", action="store_true", help="Use the broker image already loaded locally")
    args = parser.parse_args()
    commit = run("git", "rev-parse", "HEAD", cwd=ROOT)
    if run("git", "diff", "HEAD", "--", "src", "build.sbt", "project", cwd=ROOT):
        raise SystemExit("Commit or isolate engine source changes before making a release image.")
    tag = f"beluga-mobility-{date.today():%Y.%m.%d}-{commit[:7]}"
    destination = ROOT.parent / "dist" / tag
    destination.mkdir(parents=True, exist_ok=args.resume)
    for filename in ["Dockerfile", ".dockerignore", "launch.sh", "start-broker.sh", "run.sh", "flags-on.env", "flags-off.env", "README.md"]:
        shutil.copy2(SOURCE / filename, destination / filename)
    subprocess.run(["sbt", "assembly"], cwd=ROOT, check=True)
    jar = ROOT / "target/scala-3.3.5/hyperbolic-time-chamber-3.10.0.jar"
    shutil.copy2(jar, destination / "engine.jar")
    image = f"htc-mobility:{tag}"
    subprocess.run(["docker", "build", "--platform", "linux/amd64", "--build-arg", f"HTC_COMMIT={commit}",
                    "-t", image, str(destination)], check=True)
    generator = ROOT.parent / "htc-scenarios/scripts/scenario/generate_hybrid_scenario.py"
    for name, size, cars, micro in [("smoke", 4, 40, 0), ("micro-smoke", 4, 40, 100), ("meso-300", 8, 300, 0)]:
        subprocess.run(["python3", str(generator), "--model", "mobility", "--rows", str(size), "--cols", str(size),
                        "--num-persons", str(cars), "--num-cars", str(cars), "--num-bus-lines", "0",
                        "--num-subway-lines", "0", "--micro-pct", str(micro), "--seed", "11",
                        "--data-path-prefix", f"/scenarios/{name}", "--output-dir", str(destination / "scenarios" / name)], check=True)
    for name, count in [("single", 1), ("distributed", 4)]:
        (destination / f"compose-{name}.yml").write_text(json.dumps(compose(image, count), indent=2) + "\n")
    (destination / ".env.example").write_text(
        "HTC_SCENARIOS_DIR=./scenarios\nHTC_SCENARIO=smoke\n"
        "HTC_JAVA_OPTS=-Xms256m -Xmx32g -XX:ActiveProcessorCount=28\n"
        "HTC_MEMORY_LIMIT=40G\nHTC_CPUS=28\nHTC_SCHEDULER_WORKERS=4\n")
    shutil.copy2(destination / ".env.example", destination / ".env")
    for topology in ["single", "distributed"]:
        for arm in ["off", "on"]:
            subprocess.run(["bash", "run.sh", topology, arm, "config", "-q"], cwd=destination, check=True)
    broker = BROKER_IMAGE
    if args.offline:
        subprocess.run(["docker", "image", "inspect", broker], check=True, stdout=subprocess.DEVNULL)
    else:
        subprocess.run(["docker", "pull", "--platform", "linux/amd64", broker], check=True)
    subprocess.run(["docker", "save", "-o", str(destination / "images.tar"), image, broker], check=True)
    manifest = {"commit": commit, "engine_jar_sha256": checksum(jar), "micro_delegation": True,
                "images": json.loads(run("docker", "image", "inspect", image, broker))}
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    paths = sorted(p for p in destination.rglob("*") if p.is_file() and p.name != "SHA256SUMS")
    (destination / "SHA256SUMS").write_text("".join(f"{checksum(p)}  {p.relative_to(destination)}\n" for p in paths))
    archive = destination.parent / f"{tag}.tar.gz"
    with tarfile.open(archive, "w:gz") as output:
        output.add(destination, arcname=tag)
    archive.with_name(archive.name + ".sha256").write_text(f"{checksum(archive)}  {archive.name}\n")
    print(archive)


if __name__ == "__main__":
    main()
