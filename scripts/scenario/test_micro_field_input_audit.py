import copy
import json
import unittest
from pathlib import Path

from audit_micro_field_inputs import queue_observation_window
import test_validate_micro_field as field_fixtures
from validate_micro_field import quality
from validate_micro_field_v2 import identity, following_context, queue_release


class FieldInputAuditTests(unittest.TestCase):
    def setUp(self):
        self.protocol = json.loads(Path("scripts/scenario/micro_field_protocol_v2.json").read_text())
        self.synthetic = field_fixtures.FieldAssessmentTests()
        self.synthetic.setUp()

    def field_rows(self, kind):
        rows, ids = self.synthetic.rows(kind)
        for r in rows:
            r.update(section_id="3", int_id="0", location="lankershim")
        return rows, ids

    def test_identity_contains_recording_origin_and_vehicle_id(self):
        a = dict(global_time="1100", frame_id="1", vehicle_id="42")
        b = dict(global_time="2100", frame_id="1", vehicle_id="42")
        self.assertNotEqual(identity(a), identity(b))

    def test_following_rejects_independent_control_context_and_far_leader(self):
        rows, ids = self.field_rows("following")
        e = quality(rows, ids, 1000, "following", self.protocol)
        self.assertEqual(following_context(e, rows, self.protocol)["section"], "3")
        changed = copy.deepcopy(rows)
        changed[0]["int_id"] = "2"
        with self.assertRaisesRegex(ValueError, "intersection"):
            following_context(e, changed, self.protocol)
        e["leader"][0]["position"] += 100
        with self.assertRaisesRegex(ValueError, "horizon"):
            following_context(e, rows, self.protocol)

    def test_queue_event_aligns_to_observed_head_release_with_no_follower_prediction(self):
        rows, ids = self.field_rows("queue")
        e = queue_release(rows, ids, 1000, self.protocol)
        self.assertGreater(e["releaseMilliseconds"], 2000)
        self.assertEqual(e["startMilliseconds"] + 1000, e["releaseMilliseconds"] - 100)
        self.assertLessEqual(e["queueObservationWindow"]["observationEndSeconds"], 20)
        self.assertTrue(all(t is not None for t in e["observedCrossings"]))

    def test_a_stationary_head_is_not_fabricated_as_a_green_release(self):
        rows, ids = self.field_rows("queue")
        for r in rows:
            r["v_vel"] = "0"
        with self.assertRaisesRegex(ValueError, "no qualifying"):
            queue_release(rows, ids, 1000, self.protocol)

    def test_event_window_needs_observed_crossings_and_padding(self):
        rows, ids = self.field_rows("queue")
        window = queue_observation_window(dict(ids=ids), rows, 1000, self.protocol)
        self.assertGreaterEqual(window["observationEndSeconds"], max(window["crossingsSeconds"]) + 1)
        with self.assertRaises(ValueError):
            queue_observation_window(dict(ids=ids), rows[:60], 1000, self.protocol)


if __name__ == "__main__":
    unittest.main()
