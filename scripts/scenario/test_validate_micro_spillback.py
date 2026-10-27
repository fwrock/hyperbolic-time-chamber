import copy
import unittest

from validate_micro_spillback import inspect


class SpillbackMeasurementTests(unittest.TestCase):
    def fixture(self):
        canonical = dict(vehicles=["11", "12"], links=["5", "7", "9"], lengths=[100, 50, 100],
                         capacities={"5": 100, "7": 1, "9": 100}, expectedCompleted=0,
                         parameters=dict(vehicleLengthM=4.5, minGapM=2, runtimeTickSeconds=1,
                                         horizonSeconds=2, greenStartSeconds=60, speedLimitKmh=50))

        def row(label, **data):
            return dict(event_type=label, data=data)

        rows = [row("signal_phase_change", tick=0, phase_state="Red"),
                row("enter_micro_link", car_id="11", tick=0, link_id=5,
                    initial_position=90, initial_velocity=0),
                row("enter_micro_link", car_id="12", tick=0, link_id=5,
                    initial_position=80, initial_velocity=0),
                row("leave_micro_link", car_id="11", tick=1, link_id=5,
                    final_position=100, final_velocity=0, distance_traveled=100),
                row("micro_update", car_id="12", tick=1, link_id=5, position=90, velocity=0),
                row("micro_exit_wait", car_id="11", tick=2, link_id=5, position=100, velocity=0),
                row("micro_update", car_id="12", tick=2, link_id=5, position=93.5, velocity=0),
                row("sumo_tripinfo", vehicle_id="11", arrival=-1, routeLength=100, horizon_truncated=True),
                row("sumo_tripinfo", vehicle_id="12", arrival=-1, routeLength=0, horizon_truncated=True)]
        return rows, canonical

    def test_complete_blocked_horizon_trace(self):
        rows, canonical = self.fixture()
        result = inspect(rows, canonical)
        self.assertTrue(result["passed"], result["checks"])
        self.assertEqual(result["minGapM"], 2)
        self.assertEqual(result["censored"], 2)

    def test_missing_node_vehicle_cannot_pass_spacing_on_partial_trace(self):
        rows, canonical = self.fixture()
        rows = [r for r in rows if r["event_type"] != "micro_exit_wait"]
        result = inspect(rows, canonical)
        self.assertFalse(result["checks"]["continuousBlockedTrace"])
        self.assertFalse(result["checks"]["completePhysicalTrace"])
        self.assertFalse(result["passed"])

    def test_front_to_rear_gap_includes_vehicle_length(self):
        rows, canonical = self.fixture()
        rows[6]["data"]["position"] = 94
        self.assertFalse(inspect(rows, canonical)["checks"]["physicalSpacing"])

    def test_gap_continues_across_road_boundaries(self):
        rows, canonical = self.fixture()
        rows[5] = dict(event_type="enter_micro_link", data=dict(
            car_id="11", tick=2, link_id=7, initial_position=1, initial_velocity=0))
        rows[6]["data"]["position"] = 95
        result = inspect(rows, canonical)
        self.assertEqual(result["minGapM"], 1.5)
        self.assertFalse(result["checks"]["physicalSpacing"])
        self.assertFalse(result["checks"]["stoppedDelayedEntry"])

    def test_duplicate_entry_and_trip_fail(self):
        rows, canonical = self.fixture()
        rows.extend([copy.deepcopy(rows[1]), copy.deepcopy(rows[-1])])
        result = inspect(rows, canonical)
        self.assertFalse(result["checks"]["uniqueOrderedLinkEntries"])
        self.assertFalse(result["checks"]["oneTripPerVehicle"])

    def test_horizon_does_not_credit_an_unfinished_link(self):
        rows, canonical = self.fixture()
        rows[-1]["data"]["routeLength"] = 93.5
        self.assertFalse(inspect(rows, canonical)["checks"]["censoredDistances"])


if __name__ == "__main__":
    unittest.main()
