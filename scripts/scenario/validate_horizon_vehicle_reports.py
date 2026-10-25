#!/usr/bin/env python3
"""Validate the tick-310 owned-vehicle horizon fixture without counting an unfinished link."""
import argparse
import json
from pathlib import Path
import re


def validate(args):
    config = json.loads(args.simulation.read_text())
    horizon = config['startTick'] + config['duration']
    assert horizon == 310, 'validator scope is the generated tick-310 fixture'
    rows = [json.loads(line) for path in args.reports.rglob('*.jsonl')
            for line in path.read_text().splitlines() if line.strip()]
    journeys = [row['data'] for row in rows if row['event_type'] == 'journey_completed']
    sumo = [row['data'] for row in rows if row['event_type'] == 'sumo_tripinfo']
    assert len(journeys) == len(sumo) == 1, 'missing/duplicate final reporting'
    assert journeys[0]['horizon_truncated'] and not journeys[0]['reached_destination']
    assert journeys[0]['final_node'] is None and journeys[0]['tick'] == horizon
    assert journeys[0]['total_distance'] == 0.0, 'counted unfinished link distance'
    assert sumo[0]['arrival'] == -1 and sumo[0]['routeLength'] == 0.0
    assert sumo[0]['vehicle_id'] == journeys[0]['vehicle_id'] and sumo[0]['horizon_truncated']
    entries = [row['data'] for row in rows if row['event_type'] == 'enter_link']
    assert len(entries) == 1 and entries[0]['tick'] < horizon, 'fixture did not enter a link'
    assert not any(row['event_type'] in ('leave_link','person_schedule_complete','person_trip_completed') for row in rows)
    log = args.log.read_text(errors='replace')
    assert not re.search(r'\[ERROR\]|RUNTIME-FAILED|SIMULATION-FAILED|\[STALL|\[GTM-STALL|STUCK|null.state|state is null', log, re.I)
    assert 'Received StopSimulationEvent. Flushing/stopping reporters' in log
    assert 'status=horizon-truncated' in log
    return dict(passed=True,physicalRows=len(rows),horizon=horizon,vehicleJourneys=1,
                vehicleType=sumo[0]['vehicle_type'],personTrips=0,completedDistance=0.0,
                linkEntries=1,linkExits=0,engineStopped=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ('simulation','reports','log','output'):
        parser.add_argument('--'+field,type=Path,required=True)
    args = parser.parse_args()
    try:
        result = validate(args)
    except (AssertionError,KeyError,ValueError) as error:
        result = dict(passed=False,error=str(error))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
