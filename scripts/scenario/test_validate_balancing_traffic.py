import json
from pathlib import Path
import tempfile
import unittest

from validate_balancing_traffic import validate


class BalancingTrafficValidationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.simulation = self.root / "simulation.json"
        (self.root / "graph.json").write_text(json.dumps({"edges": [
            {"source_id": 1, "target_id": 2, "weight": 100., "label": {"length": 100.}}
        ]}))
        (self.root / "persons.json").write_text(json.dumps([{"id": 7, "data": {"content": {
            "originalPlan": [{"kind": "Activity", "nodeId": 1, "endTime": {"tick": 5}},
                             {"kind": "Activity", "nodeId": 2, "endTime": {"tick": 100}}]
        }}}]))
        self.simulation.write_text(json.dumps({"endTick": 100, "cityMapFile": "graph.json", "actorsDataSources": [
            {"classType": "hybrid.actor.Person", "dataSource": {"sourceType": "json", "info": {"path": "persons.json"}}}
        ]}))
        (self.root / "reports").mkdir()
        self.rows = [
            {"event_type": "person_trip_metrics", "data": {"trip_id": "7:trip:1", "person_id": "7",
                "mode": "walk", "traveled_distance": 100., "departure_time": 5, "arrival_time": 77}},
            {"event_type": "person_schedule_complete", "data": {"person_id": "7", "total_trips": 1,
                "total_distance": 100.}}
        ]
        (self.root / "runtime.log").write_text("[CONSERVATIVE-COMPLETE] tick=100 status=completed\n")
        self.save()

    def save(self):
        (self.root / "reports" / "events.jsonl").write_text("\n".join(map(json.dumps, self.rows)))

    def test_expected_outcomes_come_from_plan_and_road_distance(self):
        result = validate(self.simulation, self.root)
        self.assertEqual((result["persons"], result["trips"], result["completedVehicleJourneys"]), (1, 1, 0))

    def test_missing_trip_and_duplicate_completion_fail(self):
        original = self.rows.copy()
        for rows in (original[1:], original + [original[-1]]):
            self.rows = rows
            self.save()
            with self.assertRaises(AssertionError):
                validate(self.simulation, self.root)

    def test_fabricated_distance_and_incomplete_horizon_fail(self):
        self.rows[0]["data"]["traveled_distance"] = 101.
        self.save()
        with self.assertRaises(AssertionError):
            validate(self.simulation, self.root)
        self.rows[0]["data"]["traveled_distance"] = 100.
        self.rows[0]["data"]["arrival_time"] = 101
        self.save()
        with self.assertRaises(AssertionError):
            validate(self.simulation, self.root)

    def test_no_move_cannot_pass_migration_acceptance(self):
        with self.assertRaises(AssertionError):
            validate(self.simulation, self.root, require_migration=True)

    def test_engine_errors_cannot_be_hidden_by_completion_marker(self):
        with (self.root / "runtime.log").open("a") as stream:
            stream.write("[ERROR] failed\n")
        with self.assertRaises(AssertionError):
            validate(self.simulation, self.root)

    def test_active_migration_requires_a_restored_car_inside_an_actual_traversal(self):
        persons = json.loads((self.root / "persons.json").read_text())
        persons[0]["data"]["content"]["ownedVehicles"] = {"car": {"id": 8}}
        (self.root / "persons.json").write_text(json.dumps(persons))
        self.rows[0]["data"]["mode"] = "car"
        self.rows += [
            {"event_type": "enter_link", "data": {"car_id": "8", "link_id": "road", "tick": 5}},
            {"event_type": "leave_link", "data": {"car_id": "8", "link_id": "road", "tick": 77, "link_length": 100.}},
            {"event_type": "journey_completed", "data": {"vehicle_id": "8", "total_distance": 100.,
                "reached_destination": True, "destination": 2, "final_node": 2}},
            {"event_type": "sumo_tripinfo", "data": {"vehicle_id": "8", "origin": 1, "destination": 2,
                "final_node": 2, "routeLength": 100., "depart": 5, "arrival": 77}}
        ]
        self.save()
        for tick in (0, 10, 77):
            (self.root / "runtime.log").write_text(
                f"TimeManager paused at tick {tick}\nMigration wave 'batch'\n"
                "Triggering Pekko hand-off\nsending MigrationContextEvent to '8' (batch=batch)\n"
                "Migration 'cell' placement and restore verified\n[CONSERVATIVE-COMPLETE]\n")
            if tick == 10:
                result = validate(self.simulation, self.root, True, True)
                self.assertEqual(result["activeCarRestores"], [{"vehicle": "8", "batch": "batch", "tick": 10}])
            else:
                with self.assertRaises(AssertionError):
                    validate(self.simulation, self.root, True, True)


if __name__ == "__main__":
    unittest.main()
