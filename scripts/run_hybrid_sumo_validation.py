#!/usr/bin/env python3
"""
End-to-end HTC x SUMO validation pipeline.

Capabilities:
1) Generate HTC hybrid scenario from legacy mobility input (via migrate_to_hybrid.py)
2) Copy HTC scenario to /home/dean/hyperbolic-time-chamber/simulations/input/<scenario_name>
3) Generate SUMO scenario from HTC scenario (via htc_to_sumo_scenario.py)
4) Run SUMO simulation
5) Run HTC simulation (optional; using build-and-run.sh)
6) Compare SUMO vs HTC outputs (via compare_sumo_htc_results.py)

Usage examples:
  python scripts/run_hybrid_sumo_validation.py --config scripts/example_configs/hybrid_sumo_validation.yaml full
  python scripts/run_hybrid_sumo_validation.py --interactive
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import yaml


DEFAULT_HTC_INPUT_ROOT = Path("/home/dean/hyperbolic-time-chamber/simulations/input")
DEFAULT_HTC_REPORTS_ROOT = Path("/home/dean/hyperbolic-time-chamber/output/reports/json")


@dataclass
class PipelineConfig:
    repo_root: Path
    mobility_input_dir: Path
    scenario_name: str
    workspace_output_dir: Path
    htc_input_root: Path = DEFAULT_HTC_INPUT_ROOT
    htc_reports_root: Path = DEFAULT_HTC_REPORTS_ROOT
    htc_report_subdir: str = "sumo_run"
    sumo_end_time: int = 300
    micro_ratio: float = 1.0
    micro_strategy: str = "random"
    generate_public_transport: bool = False
    generate_persons: bool = False
    generate_traffic_signals: bool = False
    run_sumo: bool = True
    run_htc: bool = False
    compare: bool = True
    htc_target_tick: Optional[int] = None
    htc_timeout_sec: int = 1800
    htc_first_event_timeout_sec: int = 300
    htc_no_progress_sec: int = 180
    require_htc_termination_log: bool = True
    clean_output: bool = True

    @property
    def generated_htc_scenario(self) -> Path:
        return self.workspace_output_dir / "htc" / self.scenario_name

    @property
    def deployed_htc_scenario(self) -> Path:
        return self.htc_input_root / self.scenario_name

    @property
    def sumo_scenario(self) -> Path:
        return self.workspace_output_dir / "sumo" / self.scenario_name

    @property
    def comparison_dir(self) -> Path:
        return self.workspace_output_dir / "comparison" / self.scenario_name

    @property
    def htc_events_dir(self) -> Path:
        return self.htc_reports_root / self.htc_report_subdir

    @property
    def htc_marker_file(self) -> Path:
        return self.comparison_dir / "htc_marker.txt"


def run_cmd(cmd: list[str], cwd: Optional[Path] = None) -> None:
    print(f"$ {' '.join(str(c) for c in cmd)}")
    subprocess.run(cmd, cwd=str(cwd) if cwd else None, check=True)


def load_config(path: Path) -> PipelineConfig:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    sumo_end_time = int(data.get("sumo_end_time", 300))
    htc_target_tick = data.get("htc_target_tick")
    if htc_target_tick is None:
        htc_target_tick = sumo_end_time
    return PipelineConfig(
        repo_root=Path(data["repo_root"]),
        mobility_input_dir=Path(data["mobility_input_dir"]),
        scenario_name=data["scenario_name"],
        workspace_output_dir=Path(data["workspace_output_dir"]),
        htc_input_root=Path(data.get("htc_input_root", str(DEFAULT_HTC_INPUT_ROOT))),
        htc_reports_root=Path(data.get("htc_reports_root", str(DEFAULT_HTC_REPORTS_ROOT))),
        htc_report_subdir=data.get("htc_report_subdir", "sumo_run"),
        sumo_end_time=sumo_end_time,
        micro_ratio=float(data.get("micro_ratio", 1.0)),
        micro_strategy=str(data.get("micro_strategy", "random")),
        generate_public_transport=bool(data.get("generate_public_transport", False)),
        generate_persons=bool(data.get("generate_persons", False)),
        generate_traffic_signals=bool(data.get("generate_traffic_signals", False)),
        run_sumo=bool(data.get("run_sumo", True)),
        run_htc=bool(data.get("run_htc", False)),
        compare=bool(data.get("compare", True)),
        htc_target_tick=int(htc_target_tick),
        htc_timeout_sec=int(data.get("htc_timeout_sec", 1800)),
        htc_first_event_timeout_sec=int(data.get("htc_first_event_timeout_sec", 300)),
        htc_no_progress_sec=int(data.get("htc_no_progress_sec", 180)),
        require_htc_termination_log=bool(data.get("require_htc_termination_log", True)),
        clean_output=bool(data.get("clean_output", True)),
    )


def interactive_config(repo_root: Path) -> PipelineConfig:
    print("=== HTC x SUMO Validation - Interactive Setup ===")
    mobility_input = input("Mobility input directory [scripts/input/cenario_1000_viagens]: ").strip() or "scripts/input/cenario_1000_viagens"
    scenario_name = input("Scenario name [htc_scenario]: ").strip() or "htc_scenario"
    workspace_output = input("Workspace output dir [scripts/output/validation_runs]: ").strip() or "scripts/output/validation_runs"
    micro_ratio = float(input("MICRO ratio [1.0]: ").strip() or "1.0")
    run_htc = (input("Run HTC automatically? [y/N]: ").strip().lower() == "y")
    generate_public_transport = (input("Generate public transport? [y/N]: ").strip().lower() == "y")
    generate_persons = (input("Generate persons? [y/N]: ").strip().lower() == "y")
    generate_traffic_signals = (input("Generate traffic signals? [y/N]: ").strip().lower() == "y")

    return PipelineConfig(
        repo_root=repo_root,
        mobility_input_dir=(repo_root / mobility_input).resolve(),
        scenario_name=scenario_name,
        workspace_output_dir=(repo_root / workspace_output).resolve(),
        micro_ratio=micro_ratio,
        generate_public_transport=generate_public_transport,
        generate_persons=generate_persons,
        generate_traffic_signals=generate_traffic_signals,
        run_htc=run_htc,
    )


def ensure_paths(cfg: PipelineConfig) -> None:
    if not cfg.mobility_input_dir.exists():
        raise FileNotFoundError(f"mobility_input_dir not found: {cfg.mobility_input_dir}")
    cfg.workspace_output_dir.mkdir(parents=True, exist_ok=True)
    cfg.comparison_dir.mkdir(parents=True, exist_ok=True)


def generate(cfg: PipelineConfig) -> None:
    print("\n=== [1/3] Generating HTC scenario from mobility input ===")
    htc_output_parent = cfg.workspace_output_dir / "htc"
    htc_output_parent.mkdir(parents=True, exist_ok=True)

    if cfg.clean_output and cfg.generated_htc_scenario.exists():
        shutil.rmtree(cfg.generated_htc_scenario)

    migrate_script = cfg.repo_root / "scripts" / "migrate_to_hybrid.py"
    migrate_cmd = [
        sys.executable,
        str(migrate_script),
        "--input",
        str(cfg.mobility_input_dir),
        "--output",
        str(cfg.generated_htc_scenario),
        "--micro-ratio",
        str(cfg.micro_ratio),
        "--micro-strategy",
        cfg.micro_strategy,
    ]
    if not cfg.generate_public_transport:
        migrate_cmd.append("--no-public-transport")
    if not cfg.generate_persons:
        migrate_cmd.append("--no-persons")
    if not cfg.generate_traffic_signals:
        migrate_cmd.append("--no-signals")

    run_cmd(migrate_cmd, cwd=cfg.repo_root)

    print("\n=== [2/3] Deploying HTC scenario to runtime input root ===")
    cfg.htc_input_root.mkdir(parents=True, exist_ok=True)
    if cfg.deployed_htc_scenario.exists():
        shutil.rmtree(cfg.deployed_htc_scenario)
    shutil.copytree(cfg.generated_htc_scenario, cfg.deployed_htc_scenario)
    print(f"✅ Deployed scenario at: {cfg.deployed_htc_scenario}")

    print("\n=== [3/3] Generating SUMO scenario from HTC scenario ===")
    sumo_script = cfg.repo_root / "scripts" / "htc_to_sumo_scenario.py"
    if cfg.clean_output and cfg.sumo_scenario.exists():
        shutil.rmtree(cfg.sumo_scenario)

    run_cmd(
        [
            sys.executable,
            str(sumo_script),
            "--htc-scenario",
            str(cfg.deployed_htc_scenario),
            "--sumo-output",
            str(cfg.sumo_scenario),
            "--end-time",
            str(cfg.sumo_end_time),
            "--clean",
        ],
        cwd=cfg.repo_root,
    )


def run_sumo(cfg: PipelineConfig) -> None:
    print("\n=== Running SUMO ===")
    sumo_bin = shutil.which("sumo")
    if not sumo_bin:
        raise RuntimeError("SUMO binary not found in PATH")
    run_cmd([sumo_bin, "-c", "run.sumocfg"], cwd=cfg.sumo_scenario)


def marker_timestamp(marker_file: Path) -> float:
    if not marker_file.exists():
        return 0.0
    try:
        return float(marker_file.read_text(encoding="utf-8").strip())
    except (ValueError, OSError):
        return 0.0


def has_new_event_files(events_dir: Path, marker_file: Path) -> bool:
    if not events_dir.exists():
        return False
    marker_ts = marker_timestamp(marker_file)
    for file in events_dir.glob("**/*_events.jsonl"):
        if file.stat().st_mtime >= marker_ts:
            return True
    return False


def read_max_tick(events_dir: Path, marker_file: Optional[Path] = None) -> int:
    max_tick = 0
    marker_ts = marker_timestamp(marker_file) if marker_file else 0.0
    files = list(events_dir.glob("**/*_events.jsonl"))
    for file in files:
        if marker_file and file.stat().st_mtime < marker_ts:
            continue
        with file.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue
                tick = obj.get("tick")
                if isinstance(tick, int) and tick > max_tick:
                    max_tick = tick
    return max_tick


def read_monitor_stats(events_dir: Path, marker_file: Optional[Path] = None) -> tuple[int, int, int, int]:
    max_tick = 0
    started = 0
    completed = 0
    files_count = 0
    marker_ts = marker_timestamp(marker_file) if marker_file else 0.0
    files = list(events_dir.glob("**/*_events.jsonl"))
    for file in files:
        if marker_file and file.stat().st_mtime < marker_ts:
            continue
        files_count += 1
        with file.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue
                tick = obj.get("tick")
                if isinstance(tick, int) and tick > max_tick:
                    max_tick = tick
                event_type = obj.get("event_type")
                if event_type == "journey_started":
                    started += 1
                elif event_type == "journey_completed":
                    completed += 1
    return max_tick, started, completed, files_count


def run_htc(cfg: PipelineConfig) -> None:
    print("\n=== Running HTC (build-and-run.sh) ===")
    if cfg.clean_output and cfg.htc_events_dir.exists():
        try:
            shutil.rmtree(cfg.htc_events_dir)
        except PermissionError:
            print("⚠ Could not remove old HTC reports due to permissions. Using marker-based filtering instead.")
    cfg.htc_events_dir.mkdir(parents=True, exist_ok=True)

    cfg.htc_marker_file.parent.mkdir(parents=True, exist_ok=True)
    cfg.htc_marker_file.write_text(str(time.time()), encoding="utf-8")

    run_env = os.environ.copy()
    run_env["HTC_SCENARIO_NAME"] = cfg.scenario_name
    process = subprocess.Popen(["bash", "-lc", "./build-and-run.sh"], cwd=str(cfg.repo_root), env=run_env)

    target_tick = cfg.htc_target_tick if cfg.htc_target_tick is not None else cfg.sumo_end_time
    deadline = time.time() + cfg.htc_timeout_sec
    # 0 means disabled (no first-event timeout)
    first_event_deadline = (time.time() + cfg.htc_first_event_timeout_sec) if cfg.htc_first_event_timeout_sec > 0 else None
    reached = False
    last_progress_at = time.time()
    last_max_tick = -1
    saw_event_files = False
    saw_termination_log = False
    stop_reason: Optional[str] = None
    try:
        while time.time() < deadline:
            if process.poll() is not None:
                stop_reason = f"HTC process ended before reaching target tick (exit={process.returncode})."
                print(f"⚠ {stop_reason}")
                break
            time.sleep(12)
            max_tick, started, completed, files_count = read_monitor_stats(cfg.htc_reports_root, cfg.htc_marker_file)
            print(
                f"[HTC monitor] max_tick={max_tick} target={target_tick} "
                f"started={started} completed={completed} files={files_count}"
            )
            if files_count > 0 and not saw_event_files:
                saw_event_files = True
                last_progress_at = time.time()
            if not saw_event_files and first_event_deadline is not None and time.time() >= first_event_deadline:
                stop_reason = (
                    "No new HTC event files were produced within "
                    f"{cfg.htc_first_event_timeout_sec}s after startup."
                )
                print(f"⚠ {stop_reason}")
                break
            if max_tick > last_max_tick:
                last_max_tick = max_tick
                last_progress_at = time.time()
            elif saw_event_files and cfg.htc_no_progress_sec > 0 and time.time() - last_progress_at >= cfg.htc_no_progress_sec:
                stop_reason = (
                    "HTC monitor detected no progress "
                    f"for {cfg.htc_no_progress_sec}s (max_tick={max_tick})."
                )
                print(f"⚠ {stop_reason}")
                break
            if max_tick >= target_tick:
                reached = True
                break
        if not reached:
            print("⚠ HTC target tick not reached before timeout.")
    finally:
        try:
            logs_result = subprocess.run(
                ["docker", "compose", "logs", "--no-color", "node_1"],
                cwd=str(cfg.repo_root),
                capture_output=True,
                text=True,
                check=False,
            )
            all_logs = f"{logs_result.stdout}\n{logs_result.stderr}"
            if "Global simulation terminated" in all_logs:
                saw_termination_log = True
        except Exception as e:
            print(f"⚠ Could not read node logs to verify termination: {e}")

        try:
            run_cmd(["docker", "compose", "down", "-v"], cwd=cfg.repo_root)
        except Exception as e:
            print(f"⚠ Failed to stop docker compose cleanly: {e}")
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()

    if not reached:
        if stop_reason is None:
            stop_reason = "HTC target tick not reached before timeout."
        raise RuntimeError(f"HTC run did not finish as expected: {stop_reason}")

    if cfg.require_htc_termination_log and not saw_termination_log:
        raise RuntimeError(
            "HTC reached target tick but did not emit 'Global simulation terminated' in node logs."
        )


def compare(cfg: PipelineConfig) -> None:
    print("\n=== Comparing SUMO vs HTC ===")
    compare_script = cfg.repo_root / "scripts" / "compare_sumo_htc_results.py"
    sumo_tripinfo = cfg.sumo_scenario / "tripinfo.xml"
    if not sumo_tripinfo.exists():
        raise FileNotFoundError(f"SUMO tripinfo not found: {sumo_tripinfo}")

    out_json = cfg.comparison_dir / "comparison.json"
    out_md = cfg.comparison_dir / "comparison.md"

    htc_events_path = cfg.htc_events_dir
    if not has_new_event_files(htc_events_path, cfg.htc_marker_file):
        if has_new_event_files(cfg.htc_reports_root, cfg.htc_marker_file):
            htc_events_path = cfg.htc_reports_root
        else:
            raise FileNotFoundError(
                "No HTC event files found after marker timestamp in either "
                f"{cfg.htc_events_dir} or {cfg.htc_reports_root}"
            )

    run_cmd(
        [
            sys.executable,
            str(compare_script),
            "--sumo-tripinfo",
            str(sumo_tripinfo),
            "--htc-events",
            str(htc_events_path),
            "--htc-newer-than",
            str(cfg.htc_marker_file),
            "--output",
            str(out_json),
            "--markdown",
            str(out_md),
        ],
        cwd=cfg.repo_root,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run HTC x SUMO validation pipeline")
    parser.add_argument("step", nargs="?", default="full", choices=["generate", "run", "compare", "full"])
    parser.add_argument("--config", type=Path, help="YAML config path")
    parser.add_argument("--interactive", action="store_true", help="Interactive mode")
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[1]

    if args.interactive:
        cfg = interactive_config(repo_root)
    elif args.config:
        cfg = load_config(args.config)
    else:
        default_cfg = repo_root / "scripts" / "example_configs" / "hybrid_sumo_validation.yaml"
        cfg = load_config(default_cfg)

    ensure_paths(cfg)

    if args.step in ("generate", "full"):
        generate(cfg)

    if args.step in ("run", "full"):
        if cfg.run_sumo:
            run_sumo(cfg)
        if cfg.run_htc:
            run_htc(cfg)

    if args.step in ("compare", "full") and cfg.compare:
        compare(cfg)

    print("\n✅ Pipeline step(s) completed.")
    print(f"- HTC scenario: {cfg.deployed_htc_scenario}")
    print(f"- SUMO scenario: {cfg.sumo_scenario}")
    print(f"- Comparison dir: {cfg.comparison_dir}")


if __name__ == "__main__":
    main()