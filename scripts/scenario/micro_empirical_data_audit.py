#!/usr/bin/env python3
"""Audit a predeclared development recording without opening a reserved recording."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen

SOURCE = 'https://data.transportation.gov/resource/8ect-6jqj.json'


def write(path, value):
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + '\n')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_protocol(protocol):
    if protocol['classification'] != 'Development input audit only; no holdout trajectories or predictions accessed':
        raise ValueError('This tool only permits a development input audit')
    if protocol['development_recording_origin'] == protocol['reserved_recording_origin']:
        raise ValueError('Development and reserved recording identities overlap')
    first, last = protocol['development_frame_range']
    if not 1 <= first <= last <= 11000:
        raise ValueError('Development audit exceeds its bounded frame scope')


def validate_rows(rows, protocol):
    first, last = protocol['development_frame_range']
    for row in rows:
        if row['location'] != protocol['location']:
            raise ValueError('Response contains an unauthorized location')
        frame = int(row['frame_id'])
        origin = int(row['global_time']) - 100 * frame
        if origin != protocol['development_recording_origin']:
            raise ValueError('Response crosses the declared development recording boundary')
        if not first <= frame <= last:
            raise ValueError('Response exceeds the declared development frame range')


def unique_rows(rows):
    records = {}
    duplicates = 0
    for row in rows:
        key = int(row['vehicle_id']), int(row['frame_id'])
        if key in records:
            if records[key] != row:
                raise ValueError('Conflicting duplicate vehicle/frame observations')
            duplicates += 1
        records[key] = row
    return list(records.values()), duplicates


def summarize(rows, protocol):
    validate_rows(rows, protocol)
    rows, duplicates = unique_rows(rows)
    vehicles = defaultdict(list)
    for row in rows:
        vehicles[int(row['vehicle_id'])].append(row)
    intervals = Counter()
    backwards = Counter()
    for values in vehicles.values():
        ordered = sorted(values, key=lambda r: int(r['frame_id']))
        for a, b in zip(ordered, ordered[1:]):
            delta = int(b['frame_id']) - int(a['frame_id'])
            intervals[delta] += 1
            if delta == 1 and float(b['local_y']) < float(a['local_y']) - .05 / .3048:
                backwards[str(a['direction'])] += 1
    return dict(
        classification=protocol['classification'], rows=len(rows), vehicles=len(vehicles),
        exact_duplicates_removed=duplicates,
        frame_range=[min(int(r['frame_id']) for r in rows), max(int(r['frame_id']) for r in rows)] if rows else None,
        frame_gap_counts={str(k): v for k, v in sorted(intervals.items())},
        direction_rows=dict(Counter(str(r['direction']) for r in rows)),
        class_rows=dict(Counter(str(r['v_class']) for r in rows)),
        movement_rows=dict(Counter(str(r['movement']) for r in rows)),
        section_rows=dict(Counter(str(r['section_id']) for r in rows)),
        reverse_steps_over_005m_by_direction=dict(backwards),
        reserved_recording_accessed=False, predictions_produced=False,
        time_interpretation='Physical elapsed seconds = frame difference * 0.1. Raw global_time is not a verified epoch clock.',
    )


def acquire(protocol, root):
    first, last = protocol['development_frame_range']
    # Global-time ranges overlap between Peachtree recordings. The origin predicate
    # is essential to prevent downloading the reserved recording accidentally.
    where = (f"location='{protocol['location']}' and "
             f"global_time - 100 * frame_id = {protocol['development_recording_origin']} "
             f"and frame_id >= {first} and frame_id <= {last}")
    all_rows = []
    receipts = []
    page_size = 5000
    for page in range(64):
        params = {'$where': where, '$order': 'frame_id,vehicle_id', '$limit': page_size,
                  '$offset': page * page_size}
        url = SOURCE + '?' + urlencode(params)
        receipt = dict(page=page, url=url, retrieved_utc=datetime.now(timezone.utc).isoformat())
        with urlopen(url, timeout=25) as response:
            raw = response.read(5_000_001)
        if len(raw) > 5_000_000:
            raise ValueError('Audit response exceeds the fixed byte budget')
        path = root / f'page-{page:03d}.json'
        path.write_bytes(raw)
        receipt.update(file=path.name, sha256=sha(path), bytes=len(raw))
        receipts.append(receipt)
        write(root / 'requests.json', receipts)
        rows = json.loads(raw)
        if not isinstance(rows, list) or len(rows) > page_size:
            raise ValueError('Unexpected or oversized API response')
        validate_rows(rows, protocol)
        all_rows.extend(rows)
        print(f'Page {page}: {len(rows)} development rows', flush=True)
        if len(rows) < page_size:
            return all_rows
    raise ValueError('Development acquisition exceeded the fixed page budget')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    protocol = json.loads(args.protocol.read_text())
    validate_protocol(protocol)
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    (root / 'protocol.json').write_bytes(args.protocol.read_bytes())
    (root / 'acquisition-harness.py').write_bytes(Path(__file__).read_bytes())
    write(root / 'provenance.json', dict(created_utc=datetime.now(timezone.utc).isoformat(),
        protocol_sha256=sha(root / 'protocol.json'), harness_sha256=sha(Path(__file__))))
    try:
        rows = acquire(protocol, root)
        summary = summarize(rows, protocol)
        write(root / 'audit.json', summary)
        write(root / 'status.json', dict(state='complete', development_only=True))
        print(json.dumps(summary, indent=2), flush=True)
    except Exception as error:
        write(root / 'status.json', dict(state='failed', error_type=type(error).__name__, error=str(error)))
        raise


if __name__ == '__main__':
    main()
