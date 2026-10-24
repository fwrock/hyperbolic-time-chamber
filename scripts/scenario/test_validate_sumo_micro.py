"""Independent measurement regressions for the real SUMO comparison harness."""
import math
from pathlib import Path
import tempfile
import unittest

from validate_sumo_micro import compare, trajectory, run, model_checks


class SumoComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.canonical=dict(vehicles=['v'],departures=[0],links=['4','6'],lengths=[100,200])
        def row(label, tick, **data):
            return dict(event_type=label,data=dict(car_id='v',tick=tick,**data))
        self.rows=[row('enter_micro_link',0,link_id=4,initial_velocity=0),
            row('micro_update',1,link_id=4,position=99,speed=2,velocity=2),
            row('leave_micro_link',2,link_id=4,final_position=100,final_velocity=2),
            row('enter_micro_link',2,link_id=6,initial_velocity=2),
            row('leave_micro_link',5,link_id=6,final_position=200,final_velocity=2),
            dict(event_type='sumo_tripinfo',data=dict(vehicle_id='v',depart=0,arrival=5,routeLength=300))]
        self.fcd=self.root/'fcd.xml'
        self.fcd.write_text('<fcd-export><timestep time="0"><vehicle id="v" lane="4_0" pos="0" speed="0" odometer="0"/></timestep>'
            '<timestep time="1"><vehicle id="v" lane="6_0" pos="1" speed="2" odometer="101"/></timestep></fcd-export>')
        self.trip=self.root/'trip.xml'
        self.trip.write_text('<tripinfos><tripinfo id="v" depart="0" arrival="4.25" duration="4.25"/></tripinfos>')

    def test_route_distance_is_continuous_across_links_without_comparing_unrelated_local_positions(self):
        result=compare(self.rows,self.fcd,self.trip,self.canonical)['vehicles'][0]
        self.assertAlmostEqual(result['routeDistanceRmseM'],math.sqrt(2))
        self.assertEqual(result['differentLinkSamples'],1)
        self.assertEqual(result['sameLinkSamples'],1)
        self.assertEqual(result['sameLinkPositionRmseM'],0)

    def test_duration_uses_explicit_terminal_records_not_the_observed_sample_window(self):
        result=compare(self.rows,self.fcd,self.trip,self.canonical)['vehicles'][0]
        self.assertEqual(result['htcDuration'],5)
        self.assertEqual(result['sumoDuration'],4.25)
        self.assertEqual(result['durationDelta'],0.75)

    def test_same_tick_transition_uses_the_downstream_link_at_the_same_route_position(self):
        samples=trajectory(list(reversed(self.rows)),self.canonical)
        self.assertEqual(samples['v',2.0]['distance'],100)
        self.assertEqual(samples['v',2.0]['link'],'6')

    def test_missing_completion_and_missing_reference_vehicles_fail(self):
        with self.assertRaisesRegex(AssertionError,'missing actual HTC completion'):
            compare(self.rows[:-1],self.fcd,self.trip,self.canonical)
        self.trip.write_text('<tripinfos/>')
        with self.assertRaisesRegex(AssertionError,'missing actual SUMO completion'):
            compare(self.rows,self.fcd,self.trip,self.canonical)

    def test_a_departure_mismatch_is_reported_instead_of_time_shifting_traces(self):
        self.rows[-1]['data']['depart']=1
        with self.assertRaisesRegex(AssertionError,'departure mismatch'):
            compare(self.rows,self.fcd,self.trip,self.canonical)

    def test_an_existing_output_directory_cannot_reuse_stale_traces(self):
        from types import SimpleNamespace
        with self.assertRaises(FileExistsError):
            run(SimpleNamespace(output=self.root))

    def test_a_long_node_wait_fails_even_when_the_overlapping_trajectory_looks_close(self):
        self.canonical.update(signal=False,speedLimitMps=50/3.6)
        self.rows[2]['data']['travel_time_seconds']=2
        self.rows[4]['data']['travel_time_seconds']=3
        metrics=compare(self.rows,self.fcd,self.trip,self.canonical)
        self.assertTrue(all(model_checks(self.rows,metrics,self.canonical).values()))
        metrics['vehicles'][0]['htcNodeDelayTicks']=[30]
        metrics['maxAbsDurationDelta']=31.7
        checks=model_checks(self.rows,metrics,self.canonical)
        self.assertFalse(checks['noArtificialNodeDelay'])
        self.assertFalse(checks['tripTimeWithinClockResolution'])


if __name__=='__main__': unittest.main()
