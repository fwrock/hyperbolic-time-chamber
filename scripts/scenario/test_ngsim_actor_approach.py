import hashlib
import json
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path

from analyze_ngsim_actor_approach import analyze, canonical
from ngsim_actor_approach import generate


class ActorApproachCoverage(unittest.TestCase):
    def test_unknown_initial_lane_cannot_be_shifted_to_a_later_known_observation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = [dict(vehicle_id='1', frame_id=str(f), global_time=str(1000+100*f),
                         section_id='1', direction='2', lane_id=lane, movement='1')
                    for f, lane in [(600, '0'), (601, '1')]]
            (root/'page-000.json').write_text(json.dumps(rows))
            args = Namespace(output=root/'output', raw=root, boundary_m=50., speed_limit_kmh=50.,
                             start_frame=600, end_frame=700)
            with self.assertRaisesRegex(ValueError, 'cannot silently exclude or delay'):
                generate(args)
            self.assertFalse(args.output.exists())

    def test_early_model_exit_retains_all_later_observed_approach_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw, scenario, run = root/'page.json', root/'input', root/'run'
            (scenario/'data').mkdir(parents=True)
            reports = run/'reports'/'fixture'
            reports.mkdir(parents=True)
            raw.write_text(json.dumps([dict(vehicle_id='1', frame_id=str(frame), local_y='10',
                section_id='1', direction='2') for frame in [600, 601, 602, 603]]))
            subject = dict(source_vehicle=1, native_vehicle=10001, frame=600,
                           position_m=3.048, speed_mps=0., lane=0)
            metadata = dict(start_frame=600, end_frame=603, assumptions=dict(boundary_m=20.),
                subjects=[subject], sources=[dict(path=str(raw), sha256=hashlib.sha256(raw.read_bytes()).hexdigest())])
            (scenario/'scenario_metadata.json').write_text(json.dumps(metadata))
            (scenario/'data/traffic_signals_0.json').write_text(json.dumps([
                dict(data=dict(content=dict(program=dict(stages=[dict(durationSeconds=1.,green=[],yellow=[])]))))]))
            rows = [dict(event_type='enter_micro_link',tick=0,data=dict(car_id='10001', link_id=11,
                initial_position=3.048,initial_velocity=0.,lane=0,tick=0)),
                dict(event_type='leave_micro_link',tick=1,data=dict(car_id='10001',link_id=11)),
                dict(event_type='signal_phase_change',tick=0,data=dict(phase_state='Red'))]
            (reports/'fixture.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in rows))
            result = analyze(scenario,run)
            self.assertEqual(result['source_approach_samples'],4)
            self.assertEqual(result['subjects'][0]['predicted_samples'],4)
            self.assertAlmostEqual(result['absorbing_boundary_position_rmse_m'], (3*(20-3.048)**2/4)**.5)
            self.assertEqual(result['missing_prediction_samples'],0)
            rows.pop(0)
            (reports/'fixture.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in rows))
            self.assertEqual(analyze(scenario,run)['missing_prediction_samples'],1)

    def test_policy_comparison_ignores_wall_time_but_retains_physical_differences(self):
        first = dict(tick=1,event_type='micro_update',data=dict(position=2),real_time=1)
        second = dict(first,real_time=99)
        self.assertEqual(canonical([first]),canonical([second]))
        second = dict(second,data=dict(position=3))
        self.assertNotEqual(canonical([first]),canonical([second]))


if __name__ == '__main__':
    unittest.main()
