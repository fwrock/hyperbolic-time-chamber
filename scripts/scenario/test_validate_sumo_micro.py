"""Independent measurement regressions for the real SUMO comparison harness."""
import math
from pathlib import Path
import tempfile
import unittest
import shutil
import json
import xml.etree.ElementTree as ET

from validate_sumo_micro import compare, trajectory, run, model_checks, generate, sumo_samples, geometry_checks


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

    def test_multiple_sumo_internal_lanes_keep_one_continuous_connector_coordinate(self):
        self.fcd.write_text('<fcd-export><timestep time="0"><vehicle id="v" lane=":2_11_0" pos="3.9" speed="8" odometer="196.7"/></timestep>'
            '<timestep time="1"><vehicle id="v" lane=":2_15_0" pos="1" speed="8" odometer="197.87"/></timestep></fcd-export>')
        canonical=dict(sumoLaneMap={':2_11_0':dict(linkId='700',offset=0),':2_15_0':dict(linkId='700',offset=4.07)})
        samples=sumo_samples(self.fcd,canonical)
        self.assertEqual(samples['v',0]['link'],'700')
        self.assertEqual(samples['v',1]['link'],'700')
        self.assertAlmostEqual(samples['v',1]['position'],5.07)

    def test_fine_runtime_ticks_and_carried_entry_positions_use_physical_trace_times(self):
        self.fcd.write_text('<fcd-export><timestep time="0.1"><vehicle id="v" lane="6_0" pos="0.4" speed="10" odometer="200.4"/></timestep></fcd-export>')
        canonical=dict(links=['4','6'],lengths=[200,100],runtimeTickSeconds=0.1,comparisonStepSeconds=0.1)
        rows=[dict(event_type='enter_micro_link',data=dict(car_id='v',tick=1,link_id=6,initial_velocity=10,initial_position=0.4))]
        a=trajectory(rows,canonical)
        b=sumo_samples(self.fcd,canonical)
        self.assertEqual(set(a),set(b))
        self.assertAlmostEqual(a['v',0.1]['distance'],b['v',0.1]['distance'])

    def test_an_off_path_junction_coordinate_is_rejected(self):
        canonical=dict(vehicles=['v'],links=['4','700','6'],junctionGeometry=dict(
            shape=[dict(x=0,y=0),dict(x=3,y=4)],lengthMeters=5,speedLimitMps=2,outgoingLinkId=6))
        rows=[dict(event_type='enter_micro_link',data=dict(car_id='v',link_id=700,initial_velocity=2,
            junction_x=100,junction_y=100)),dict(event_type='junction_connector_admitted',
                data=dict(vehicle_id='v',connector_link_id=700,target_link_id=6))]
        checks=geometry_checks(rows,canonical)
        self.assertTrue(checks['junctionSelection'])
        self.assertFalse(checks['junctionCoordinates'])
        canonical['vehicles'].append('other')
        rows.append(rows[-1])
        self.assertFalse(geometry_checks(rows,canonical)['junctionSelection'])

    def test_independent_transit_distance_accounts_for_connector_cost_on_a_movement(self):
        from validate_walking_reports import shortest_distance
        graph={1:dict(**{'from':1,'to':2},length=100),2:dict(**{'from':2,'to':3},length=100),
               3:dict(**{'from':1,'to':3},length=250)}
        self.assertEqual(shortest_distance(graph,1,3),200)
        self.assertEqual(shortest_distance(graph,1,3,{(1,2):100}),250)

    @unittest.skipUnless(shutil.which('netconvert'),'requires real netconvert')
    def test_lookahead_topology_and_stop_gap_are_explicit_in_both_simulators(self):
        canonical=generate(self.root/'lookahead','signal',True,'right',dict(
            runtimeTickSeconds=0.1,physicsStepSeconds=0.1,carryRemainder=True,
            downstreamLookahead=True,stopLineGapM=1.0))
        records=json.loads((self.root/'lookahead/data/links_0.json').read_text())
        approach=next(r['data']['content'] for r in records if str(r['id'])==canonical['links'][0])
        self.assertEqual(approach['downstreamConnectors'],{canonical['links'][2]:int(canonical['links'][1])})
        self.assertTrue(all(r['data']['content']['downstreamLookahead'] for r in records))
        self.assertTrue(all(r['data']['content']['stopLineGapM']==1.0 for r in records))
        vehicle_type=ET.parse(self.root/'lookahead/sumo/routes.xml').getroot().find('vType')
        self.assertEqual(vehicle_type.get('jmStoplineGap'),'1.0')
        with self.assertRaises(AssertionError):
            generate(self.root/'coarse','signal',parameters=dict(downstreamLookahead=True))

    @unittest.skipUnless(shutil.which('netconvert'),'requires real netconvert')
    def test_geometry_import_uses_trimmed_roads_and_every_internal_lane_without_double_counting(self):
        for turn in ('straight','left','right'):
            root=self.root/turn
            canonical=generate(root,'crossing',True,turn)
            geometry=canonical['junctionGeometry']
            self.assertGreater(geometry['lengthMeters'],0)
            self.assertLess(canonical['lengths'][0],200)
            self.assertLess(canonical['lengths'][-1],100)
            if turn=='straight': self.assertAlmostEqual(sum(canonical['lengths']),300)
            net=ET.parse(root/'sumo/net.xml').getroot()
            lengths={lane.get('id'):float(lane.get('length')) for edge in net.findall('edge') for lane in edge.findall('lane')}
            self.assertAlmostEqual(sum(lengths[lane] for lane in canonical['sumoLaneMap']),geometry['lengthMeters'])
            records=json.loads((root/'data/links_0.json').read_text())
            connector=next(r['data']['content'] for r in records if str(r['id'])==canonical['links'][1])
            self.assertEqual(connector['from'],connector['to'])
            self.assertEqual(connector['junctionGeometry'],geometry)


if __name__=='__main__': unittest.main()
