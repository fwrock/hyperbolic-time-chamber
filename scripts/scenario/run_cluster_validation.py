#!/usr/bin/env python3
"""Run a bounded HTC scenario in separate JVMs, optionally delaying TCP or killing a worker.

This records evidence; outcome acceptance still requires a model-specific validator.
Use a fresh output directory. Paths in the scenario must be available to every JVM.
"""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
import subprocess
import threading
import time

from benchmark_metrics import ProcessMonitor, parse_runtime


def execution_outcome(logs):
    stopped = any("Received StopSimulationEvent. Flushing/stopping reporters" in log for log in logs)
    completed = any("[CONSERVATIVE-COMPLETE]" in log or "[ACCOUNTED-COMPLETE]" in log for log in logs)
    failed = any(any(marker in log for marker in (
        "[RUNTIME-FAILED]", "[SIMULATION-FAILED]", "[CONSERVATIVE-FAILED]",
        "Load balancing failed;")) for log in logs)
    return stopped, completed, failed


class DelayedTransport:
    def __init__(self, advertised, bound, delay_ms):
        self.advertised, self.bound = advertised, bound
        self.delay = delay_ms / 1000
        self.ready = threading.Event()
        self.stopping = threading.Event()
        self.error = None
        self.connections = self.chunks = self.bytes = 0
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        try:
            asyncio.run(self.serve())
        except Exception as error:
            self.error = error
            self.ready.set()

    async def serve(self):
        servers = []
        try:
            for advertised, bound in zip(self.advertised, self.bound):
                async def relay(reader, writer, destination=bound):
                    upstream = None
                    try:
                        other_reader, upstream = await asyncio.open_connection("127.0.0.1", destination)
                        self.connections += 1

                        async def pump(source, target):
                            while data := await source.read(65536):
                                await asyncio.sleep(self.delay)
                                target.write(data)
                                await target.drain()
                                self.chunks += 1
                                self.bytes += len(data)
                            if target.can_write_eof():
                                target.write_eof()

                        await asyncio.gather(pump(reader, upstream), pump(other_reader, writer))
                    except (ConnectionError, OSError):
                        pass
                    finally:
                        writer.close()
                        if upstream is not None:
                            upstream.close()

                servers.append(await asyncio.start_server(relay, "127.0.0.1", advertised))
            self.ready.set()
            while not self.stopping.is_set():
                await asyncio.sleep(.05)
        finally:
            for server in servers:
                server.close()
                await server.wait_closed()

    def start(self):
        self.thread.start()
        if not self.ready.wait(5):
            raise RuntimeError("TCP relays did not start")
        if self.error:
            raise self.error

    def stop(self):
        self.stopping.set()
        if self.thread.is_alive():
            self.thread.join(5)


def run(args):
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    jar, scenario = args.jar.resolve(), args.simulation.resolve()
    with jar.open("rb") as stream:
        initial_digest = hashlib.file_digest(stream, "sha256").hexdigest()
    configuration = json.loads(scenario.read_text())
    city_map = Path(configuration["cityMapFile"])
    if not city_map.is_absolute():
        city_map = scenario.parent / city_map
    ports = [args.base_port + i for i in range(args.nodes)]
    bound = [port + 100 for port in ports] if args.delay_ms else ports
    relay = DelayedTransport(ports, bound, args.delay_ms) if args.delay_ms else None
    processes, streams, runtime_logs = [], [], []
    stopped = completed = failed = killed = False
    kill_elapsed = None
    started = time.monotonic()
    measuring = getattr(args, "measure", False)
    cpu_ids = getattr(args, "cpu_ids", None)
    if cpu_ids:
        cpu_ids = [int(v) for v in cpu_ids.split(",")]
        if len(cpu_ids) != 4 or len(set(cpu_ids)) != 4 or not set(cpu_ids) <= os.sched_getaffinity(0):
            raise ValueError("measurement requires four distinct available logical CPUs")
    monitor = ProcessMonitor(root, started, metrics_ports=[args.base_port + 20 + i for i in range(args.nodes)]) if measuring else None
    completion_seconds = None
    measurement = None
    extra = args.extra_config.read_text() if args.extra_config else ""
    try:
        if relay:
            relay.start()
        for index, port in enumerate(ports):
            node = root / f"node-{index}"
            node.mkdir()
            config = f'''include classpath("application.conf")
clustering.ip="127.0.0.1"
clustering.port={port}
clustering.seed-ip="127.0.0.1"
clustering.seed-port={ports[0]}
clustering.management-http-port={args.base_port + 10 + index}
pekko.management.http.port={args.base_port + 10 + index}
pekko.remote.artery.bind.hostname="127.0.0.1"
pekko.remote.artery.bind.port={bound[index]}
pekko.cluster.min-nr-of-members={args.nodes}
htc.api.enabled=false
htc.routing.cache-strategy="disabled"
htc.time-manager.total-instances={args.nodes}
htc.time-manager.max-instances-per-node=1
htc.report-manager.enabled-strategies=["json"]
htc.report-manager.default-strategy="json"
htc.report-manager.json.directory={json.dumps(str(root / "reports"))}
htc.report-manager.json.number-of-instances=1
htc.report-manager.json.number-of-instances-per-node=1
htc.report-manager.json.batch-size=1
{extra}
'''
            (node / "run.conf").write_text(config)
            env = dict(os.environ, HTC_METRICS_PORT=str(args.base_port + 20 + index),
                       HTC_SIMULATION_CONFIG_FILE=str(scenario), HTC_MOBILITY_CITY_MAP_FILE=str(city_map))
            command = ["java", "--add-opens=java.base/java.lang=ALL-UNNAMED", "-Xms128m", "-Xmx2g",
                       "-XX:ActiveProcessorCount=4", "-Dconfig.file=" + str(node / "run.conf"), "-jar", str(jar)]
            if measuring:
                command[command.index("-Xmx2g")] = "-Xmx4g" if args.nodes == 1 else "-Xmx2g"
                command[command.index("-XX:ActiveProcessorCount=4")] = f"-XX:ActiveProcessorCount={4 // args.nodes}"
                if cpu_ids:
                    assigned = cpu_ids if args.nodes == 1 else cpu_ids[index*2:index*2+2]
                    command = ["taskset", "-c", ",".join(map(str, assigned)), *command]
            (node / "command.json").write_text(json.dumps(command, indent=2) + "\n")
            stream = (node / "run.log").open("w")
            streams.append(stream)
            processes.append(subprocess.Popen(command, env=env, stdout=stream, stderr=subprocess.STDOUT))
            if monitor:
                monitor.add(processes[-1].pid)
            if index == 0 and args.nodes > 1:
                time.sleep(.4)

        def logs():
            return [(root / f"node-{i}/run.log").read_text(errors="replace") for i in range(args.nodes)]

        while time.monotonic() - started < args.timeout:
            runtime_logs = logs()
            stopped, completed, failed = execution_outcome(runtime_logs)
            if stopped or failed:
                completion_seconds = time.monotonic() - started
                if monitor:
                    measurement = monitor.finish()
                    monitor = None
                time.sleep(2)
                break
            if args.kill_node is not None and not killed and any(args.kill_after in log for log in runtime_logs):
                processes[args.kill_node].kill()
                killed = True
                kill_elapsed = round(time.monotonic() - started, 3)
            if any(proc.poll() is not None for i, proc in enumerate(processes)
                   if not (killed and i == args.kill_node)):
                break
            if relay and relay.error:
                raise relay.error
            time.sleep(.1)
        runtime_logs = logs()
        stopped, completed, failed = execution_outcome(runtime_logs)
        for index, log in enumerate(runtime_logs):
            (root / f"node-{index}/runtime.log").write_text(log)
        # Keep lifecycle teardown out of execution acceptance while retaining full raw logs.
        (root / "runtime.log").write_text("\n".join(runtime_logs))
    finally:
        if monitor:
            measurement = monitor.finish()
        for proc in processes:
            if proc.poll() is None:
                proc.terminate()
        deadline = time.monotonic() + 5
        for proc in processes:
            try:
                proc.wait(timeout=max(.1, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
        for stream in streams:
            stream.close()
        if relay:
            relay.stop()

    with jar.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    engine_errors = [log.count("[ERROR]") for log in runtime_logs]
    result = dict(jar=str(jar), jarSha256=digest, simulationFile=str(scenario),
                  executableUnchanged=digest == initial_digest, engineErrors=engine_errors,
                  policy=configuration.get("timeManagerType", "discrete-event"), nodes=args.nodes,
                  localTimeManagersPerNode=1, advertisedPorts=ports, bindPorts=bound,
                  engineReportedStop=stopped, engineCompleted=completed, runtimeFailed=failed,
                  faultInjected=killed, killNode=args.kill_node, killAfter=args.kill_after,
                  killElapsedSeconds=kill_elapsed, delayMsPerRead=args.delay_ms,
                  elapsedSeconds=round(time.monotonic() - started, 2),
                  returncodes=[proc.returncode for proc in processes])
    if measuring:
        result["measurement"] = dict(measurement or {}, completionSeconds=completion_seconds,
            deadlineExceeded=completion_seconds is None and time.monotonic()-started >= args.timeout,
            sampleIntervalSeconds=.25, cpuIds=cpu_ids, totalMaxHeapGiB=4,
            runtime=parse_runtime("\n".join(runtime_logs)))
    if relay:
        result["relay"] = dict(connections=relay.connections, chunks=relay.chunks, bytes=relay.bytes)
    (root / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    if args.kill_node is not None:
        return 0 if killed and failed and not completed and digest == initial_digest else 1
    return 0 if stopped and completed and not failed and not any(engine_errors) and digest == initial_digest else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--simulation", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base-port", type=int, required=True)
    parser.add_argument("--nodes", type=int, choices=(1, 2), default=2)
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--delay-ms", type=float, default=0)
    parser.add_argument("--kill-node", type=int, choices=(1,))
    parser.add_argument("--kill-after", default="[ACCOUNTED-GVT] round=1 ")
    parser.add_argument("--extra-config", type=Path)
    parser.add_argument("--measure", action="store_true", help="Sample process resources before teardown")
    parser.add_argument("--cpu-ids", help="Four comma-separated logical CPUs for measured runs")
    args = parser.parse_args()
    if not (1024 <= args.base_port <= 65300) or args.delay_ms < 0 or args.timeout <= 0:
        parser.error("invalid port, delay or timeout")
    if args.kill_node is not None and args.nodes != 2:
        parser.error("worker-loss injection requires two JVMs")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
