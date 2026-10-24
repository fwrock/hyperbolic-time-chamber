import unittest
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
from unittest.mock import patch
from ngsim_field_pilot import quality_control, run, PROTOCOL


class FieldQualityTest(unittest.TestCase):
    def rows(self):
        return [dict(global_time=str(1000+100*i),frame_id=str(i),local_y=str(i),v_vel='10',
                     preceding='0',v_class='2',lane_id='1') for i in range(111)]

    def test_duplicate_observations_are_removed_and_forecast_is_disjoint_from_initialization(self):
        rows=self.rows()
        result=quality_control(rows+[dict(rows[0])],1000)
        self.assertAlmostEqual(result['initialSpeedMps'],3.048)
        self.assertEqual(result['quality']['exactDuplicatesRemoved'],1)
        self.assertEqual(len(result['observations']),100)
        self.assertEqual(result['observations'][0]['timeSeconds'],0.1)
        self.assertAlmostEqual(result['observations'][0]['positionM'],0.3048)
        self.assertAlmostEqual(result['observations'][-1]['positionM'],30.48)

    def test_conflicting_duplicates_gaps_and_lane_changes_fail_quality_control(self):
        rows=self.rows()
        bad=dict(rows[0],local_y='999')
        with self.assertRaisesRegex(ValueError,'conflicting'): quality_control(rows+[bad],1000)
        with self.assertRaisesRegex(ValueError,'missing'): quality_control(rows[:-1],1000)
        rows[40]['lane_id']='2'
        with self.assertRaisesRegex(ValueError,'lane change'): quality_control(rows,1000)

    def test_an_altered_field_snapshot_fails_before_network_access_or_simulation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            snapshot=root/'snapshot';snapshot.mkdir()
            (snapshot/'metadata.json').write_text('{}')
            source=dict(file='metadata.json',sha256='0'*64)
            frozen=dict(protocol=PROTOCOL,metadataSource=source,candidateSource=source,selected=[])
            (snapshot/'quality-control.json').write_text(json.dumps(frozen))
            args=SimpleNamespace(output=root/'output',snapshot=snapshot,jar=root/'unused.jar',base_port=23500)
            with patch('ngsim_field_pilot.urlopen',side_effect=AssertionError('unexpected network access')):
                with self.assertRaisesRegex(AssertionError,'snapshot hash mismatch'): run(args)


if __name__=='__main__': unittest.main()
