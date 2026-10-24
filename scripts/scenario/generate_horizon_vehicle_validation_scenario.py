#!/usr/bin/env python3
"""Generate a seed-42 owned vehicle trip whose first link finishes beyond tick 310."""
import argparse
import json
from pathlib import Path
from generate_hybrid_scenario import build_scenario


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--vehicle', choices=('car','bicycle','motorcycle'), default='car')
    args = parser.parse_args()
    root = args.output_dir.resolve()
    build_scenario(root, str(root), 2, 2, 1, int(args.vehicle == 'car'),
                   int(args.vehicle == 'bicycle'), int(args.vehicle == 'motorcycle'),
                   0, 0, 0.0, None, 2, 'travel-time', 42)
    path = root/'data/persons_0.json'
    persons = json.loads(path.read_text())
    state = persons[0]['data']['content']
    origin = state['originalPlan'][0]['nodeId']
    state['originalPlan'][-1].update(nodeId={1:4,2:3,3:2,4:1}[origin], activityType='work')
    state['originalPlan'][1]['decision']['allowedModes'] = [args.vehicle.capitalize()]
    state['modeChoiceWeights']['includedModes'] = [args.vehicle]
    path.write_text(json.dumps(persons,indent=2)+'\n')
    config = json.loads((root/'simulation.json').read_text())
    config.update(duration=310,endTick=310)
    for policy in ('discrete-event','time-warp'):
        config.update(timeManagerType=policy,id=f'{args.vehicle}-horizon-{policy}')
        (root/(policy+'.json')).write_text(json.dumps(config,indent=2)+'\n')
    (root/'simulation.json').unlink()
    print(root)


if __name__ == '__main__':
    main()
