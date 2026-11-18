import json
from pathlib import Path
import tempfile
import unittest

from audit_ngsim_approach import (fit_coordinates, load_recording, station_at_lateral,
                                 sustained_motion, track_inventory, transform_points)


def row(frame=600, vehicle=1, **values):
    return dict(vehicle_id=str(vehicle), frame_id=str(frame), global_time=str(1000+100*frame),
                section_id='1', direction='2', lane_id='1', movement='1', local_y='10',
                local_x='3', global_x='100', global_y='1000', v_class='2', total_frames='6',
                v_vel='0', **values)


class ApproachRegistration(unittest.TestCase):
    def test_same_world_feature_has_recording_specific_local_station(self):
        def source(offset):
            rows = []
            for i,(x,y) in enumerate([(0,0),(2,0),(0,4),(2,4)]):
                r = row(frame=600+i)
                r.update(global_x=str(2000+x), global_y=str(9000+y),
                         local_x=str(x), local_y=str(y+offset))
                rows.append(r)
            return rows
        a = transform_points(fit_coordinates(source(10)), [[2000,9002],[2002,9002]])
        b = transform_points(fit_coordinates(source(30)), [[2000,9002],[2002,9002]])
        self.assertAlmostEqual(station_at_lateral(b,1)-station_at_lateral(a,1),20*.3048)
        with self.assertRaisesRegex(ValueError,'extrapolation'):
            station_at_lateral(a,3)

    def test_loader_rejects_mixed_recording_origins_and_conflicting_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'page-000.json'
            a,b = row(),row(frame=601)
            b['global_time'] = str(int(b['global_time'])+1000)
            path.write_text(json.dumps([a,b]))
            with self.assertRaisesRegex(ValueError,'one recording'):
                load_recording(Path(directory))
            b = dict(a,local_y='11')
            path.write_text(json.dumps([a,b]))
            with self.assertRaisesRegex(ValueError,'Conflicting'):
                load_recording(Path(directory))

    def test_internal_track_birth_is_not_an_upstream_arrival(self):
        inventory = track_inventory([row(600,1),row(610,2)],600,700)
        self.assertEqual(inventory[0]['entry_classification'],'initial_snapshot')
        self.assertEqual(inventory[1]['entry_classification'],
                         'track_begins_inside_approach_not_verified_boundary_arrival')

    def test_motion_requires_consecutive_frames_and_retains_threshold_sensitivity(self):
        rows = [dict(row(f),v_vel='4') for f in [600,601,603,604,605,606,607]]
        self.assertEqual(sustained_motion(rows,600,1.),603)
        self.assertIsNone(sustained_motion(rows,600,2.))


if __name__ == '__main__':
    unittest.main()
