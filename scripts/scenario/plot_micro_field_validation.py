#!/usr/bin/env python3
"""Plot all reserved field errors and the first selected following/queue trajectories."""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    record = json.loads((args.root / "assessment.json").read_text())
    selection = json.loads((args.root / "selection.json").read_text())
    samples = json.loads((args.root / "predictions.json").read_text())
    plt.rcParams.update({"font.size": 9, "axes.spines.top": False, "axes.spines.right": False})
    fig, axes = plt.subplots(2, 2, figsize=(12, 8), constrained_layout=True)
    following = [r for r in record["results"] if r["kind"] == "following"]
    queues = [r for r in record["results"] if r["kind"] == "queue"]
    x = np.arange(len(following))
    for offset, key, label, colour in [(-.25, "meanPositionRmseM", "HTC", "#166a9a"),
            (0, "meanConstantVelocityRmseM", "Constant velocity", "#d89429"),
            (.25, "meanRigidSpacingRmseM", "Rigid spacing", "#80858c")]:
        axes[0, 0].bar(x + offset, [r[key] for r in following], .25, label=label, color=colour)
    axes[0, 0].set(xticks=x, xticklabels=[r["id"].rsplit("-", 1)[-1] for r in following],
                   xlabel="Reserved follower ID", ylabel="Position RMSE (m)", title="All selected following episodes")
    axes[0, 0].legend(fontsize=8)
    x = np.arange(len(queues))
    for offset, key, label, colour in [(-.18, "headwayMaeSeconds", "HTC", "#166a9a"),
                                     (.18, "rigidHeadwayMaeSeconds", "Rigid spacing", "#80858c")]:
        axes[0, 1].bar(x + offset, [r[key] if r[key] is not None else np.nan for r in queues],
                      .36, label=label, color=colour)
    axes[0, 1].set(xticks=x, xticklabels=[r["id"].rsplit("-", 1)[-1] for r in queues],
                   xlabel="Reserved queue episode (last follower ID)", ylabel="Headway MAE (s)",
                   title="All selected queue episodes; missing crossings are failures")
    axes[0, 1].legend(fontsize=8)
    for ax, kind in zip(axes[1], ("following", "queue")):
        episode = next((e for e in selection["selected"] if e["kind"] == kind), None)
        if episode is None:
            ax.text(.5, .5, "No eligible episode", ha="center", transform=ax.transAxes)
            continue
        origin = episode["followers"][-1]["position"]
        times = np.arange(len(episode["leader"])) * .1
        ax.plot(times, np.array(episode["observations"][0]) - origin, color="#666666",
                linestyle=":", label="Observed head leader (input)")
        for i, identity in enumerate(episode["ids"][1:], 1):
            colour = ("#166a9a", "#bf5b45")[i - 1]
            ax.plot(times, np.array(episode["observations"][i]) - origin, color=colour,
                    label=f"Observed follower {identity}")
            data = sorted((s for s in samples if s["episode"] == episode["id"] and s["vehicle"] == identity),
                          key=lambda s: s["tick"])
            ax.plot([s["tick"] * .1 for s in data], [s["position"] - origin for s in data],
                    color=colour, linestyle="--", label=f"HTC follower {identity}")
        if kind == "queue":
            ax.axhline(episode["station"] - origin, color="#a47d29", linewidth=.8, label="Discharge station")
        ax.set(xlabel="Time after initialization (s)", ylabel="Front position from initial last follower (m)",
               title=f"First selected {kind}: {episode['id']}")
        ax.legend(fontsize=7)
    for ax in axes.flat:
        ax.grid(axis="y", alpha=.18)
        ax.set_axisbelow(True)
    fig.suptitle("Conditional urban field assessment — observed leader input, fixed HTC parameters\n"
                 + f"Data gate: {record['dataGate']}; scientific gate: {record['scientificGate']}", fontsize=12)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for extension in ("svg", "png"):
        fig.savefig(args.output.with_suffix("." + extension), dpi=170)
    plt.close(fig)


if __name__ == "__main__":
    main()
