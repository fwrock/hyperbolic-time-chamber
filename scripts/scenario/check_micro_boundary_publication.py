#!/usr/bin/env python3
"""Check a controlled actor fixture, retaining admission and physical coverage failures."""
import argparse
import json
from pathlib import Path

from analyze_ngsim_actor_approach import canonical, events


def check(run, expected_ticks, reference=None):
    rows=events(run)
    result=json.loads((run/'result.json').read_text())
    entries=[r for r in rows if r['event_type']=='enter_micro_link']
    admission={int(r['data']['car_id']):(r['data']['initial_position'],r['tick']) for r in entries}
    trips=[r['data'] for r in rows if r['event_type']=='sumo_tripinfo']
    updates=[r['data'] for r in rows if r['event_type']=='micro_update']
    positions={identity:[r['position'] for r in updates if r['car_id']==str(identity)]
               for identity in [101,102,103]}
    checks=dict(engine_completed=result['engineCompleted'],no_engine_errors=not any(result['engineErrors']),
        exact_admissions=len(entries)==3 and admission=={101:(35.,expected_ticks[0]),
            102:(20.,expected_ticks[1]),103:(3.,expected_ticks[2])},
        three_censored_journeys=len(trips)==3 and all(r.get('horizon_truncated') is True
            and r.get('arrival')==-1 and r.get('reached_destination') is False for r in trips),
        no_road_exit=not any(r['event_type']=='leave_micro_link' for r in rows),
        all_subjects_sampled=all(positions.values()),
        exact_sampling_ticks=all(sorted(int(r['tick']) for r in updates if r['car_id']==str(identity))
            == list(range(start+1,51)) for identity,start in zip([101,102,103],expected_ticks)),
        downstream_stays_at_stop=bool(positions[103]) and all(abs(x-3.)<1e-9 for x in positions[103]),
        upstream_front_respects_downstream_tail=bool(positions[101]) and bool(positions[102])
            and all(x<=46.5+1e-9 for identity in [101,102] for x in positions[identity]))
    if reference:
        checks['committed_physical_events_equal']=canonical(rows)==canonical(events(reference))
    return dict(run=str(run.resolve()),jar_sha256=result['jarSha256'],nodes=result['nodes'],
        policy=result['policy'],checks=checks,passed=all(checks.values()),
        physical_events=len(canonical(rows)),position_ranges={str(i):[min(p),max(p)] if p else None
            for i,p in positions.items()})


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',type=Path,required=True)
    parser.add_argument('--expected-ticks',type=int,nargs=3,required=True)
    parser.add_argument('--reference',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    result=check(args.run,args.expected_ticks,args.reference)
    with args.output.open('x') as stream:
        json.dump(result,stream,indent=2)
        stream.write('\n')
    print(json.dumps(result,indent=2))
    raise SystemExit(0 if result['passed'] else 1)
