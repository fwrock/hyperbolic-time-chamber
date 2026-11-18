#!/usr/bin/env python3
"""Inventory every observed through-green head release in an already exposed recording."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path

from audit_ngsim_approach import FEET_M, load_recording, sustained_motion
from ngsim_observed_signals import read_changes


def causal_speed_rows(rows):
    """Past-one-second position slope, with no gap interpolation or future observations."""
    ordered = sorted(rows, key=lambda r: int(r['frame_id']))
    result = []
    for i in range(10,len(ordered)):
        window = ordered[i-10:i+1]
        if any(int(b['frame_id']) != int(a['frame_id'])+1 for a,b in zip(window,window[1:])):
            continue
        speed = sum((j-5)*float(r['local_y'])*FEET_M for j,r in enumerate(window))/11.
        result.append(dict(ordered[i],v_vel=str(max(0.,speed)/FEET_M)))
    return result


def inventory(rows, changes, stations):
    groups = defaultdict(list)
    by_frame = defaultdict(list)
    for row in rows:
        groups[int(row['vehicle_id'])].append(row)
        if row['section_id']=='1' and row['direction']=='2':
            by_frame[int(row['frame_id'])].append(row)
    causal = {identity:causal_speed_rows(group) for identity,group in groups.items()}
    horizon = max(int(row['frame_id']) for row in rows)
    output = []
    for green, phase in changes:
        if phase != 'Green' or green > horizon:
            continue
        for lane, station in stations.items():
            candidates = [r for r in by_frame.get(green-1,[]) if r['lane_id']==lane
                          and station-15 <= float(r['local_y'])*FEET_M <= station+.762]
            item = dict(green_frame=green,source_lane=lane,photo_stop_station_m=station,
                        observed_candidates=len(candidates))
            if not candidates:
                output.append(dict(item,status='no_observed_candidate_at_green'))
                continue
            head = max(candidates,key=lambda r:(float(r['local_y']),-int(r['vehicle_id'])))
            identity = int(head['vehicle_id'])
            prefix = next((r for r in causal[identity] if int(r['frame_id'])==green-1),None)
            item.update(vehicle=identity,source_class=head['v_class'],
                        position_at_green_m=float(head['local_y'])*FEET_M,
                        prefix_causal_speed_mps=float(prefix['v_vel'])*FEET_M if prefix else None)
            if prefix is None or float(prefix['v_vel'])*FEET_M > .2:
                output.append(dict(item,status='missing_prefix' if prefix is None else 'candidate_not_stopped'))
                continue
            window = [r for r in causal[identity] if green-1 <= int(r['frame_id']) <= green+200]
            releases=[]
            for threshold in [.1,.5,1.]:
                frame = sustained_motion(window,green,threshold)
                releases.append(dict(threshold_mps=threshold,first_frame=frame,
                    delay_seconds=(frame-green)*.1 if frame is not None else None))
            output.append(dict(item,status='observed_stopped_candidate',releases=releases,
                complete_twenty_second_observation=bool(window and int(window[-1]['frame_id'])>=green+200),
                interpretation='Nearest observed stopped candidate, not proof of complete queue or causal driver reaction time'))
    return output


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw',type=Path,required=True)
    parser.add_argument('--signal',type=Path,required=True)
    parser.add_argument('--approach-audit',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.output.exists():
        raise FileExistsError(args.output)
    audit=json.loads(args.approach_audit.read_text())
    rows, provenance=load_recording(args.raw)
    if provenance != audit['provenance']:
        raise ValueError('Release inventory must use the exact audited recording and source hashes')
    protocol=dict(status='EXPOSED_DATA_DIAGNOSTIC_NOT_A_NEW_HELDOUT_STUDY',
        sampling='Every measured through-green and each through lane; every absence/nonstopped case retained',
        candidate='Nearest observed front at green-1 within [photo station-15 m, photo station+0.762 m]',
        stopped='Past-1-s causal position slope <=0.2 m/s; no missing source frame',
        release='Five consecutive causal speed samples above each fixed threshold 0.1/0.5/1.0 m/s within 20 s',
        code_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        approach_audit_sha256=hashlib.sha256(args.approach_audit.read_bytes()).hexdigest())
    seal=args.output.with_suffix('.protocol.json')
    if seal.exists():
        raise FileExistsError(seal)
    seal.write_text(json.dumps(protocol,indent=2)+'\n')
    stations={r['source_lane']:r['photo_stop_station_m'] for r in audit['lane_stop_stations']
              if r['source_lane'] in ['1','2']}
    results=inventory(rows,read_changes(args.signal)['Thru'],stations)
    args.output.write_text(json.dumps(dict(protocol=protocol,results=results),indent=2)+'\n')
    for r in results:
        print(r['green_frame'],r['source_lane'],r['status'],r.get('vehicle'),r.get('releases'))


if __name__=='__main__':
    main()
