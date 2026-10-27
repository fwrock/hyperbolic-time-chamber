import copy
import json
from pathlib import Path
import unittest
import hashlib
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

from validate_micro_field import FT, PROTOCOL, PROTOCOL_SHA256, assess, crossing, evaluate, quality, run, fetch


class FieldAssessmentTests(unittest.TestCase):
    def setUp(self):
        self.protocol = json.loads(PROTOCOL.read_text())

    def rows(self, kind="following"):
        ids = [1, 2] if kind == "following" else [1, 2, 3]
        rows = []
        for tick in range(111 if kind == "following" else 211):
            for index, identity in enumerate(ids):
                t = max(0, (tick - 10) / 10 - index)
                y = 50 - index * 10 + (tick / 2 if kind == "following" else t * t / 2)
                speed = 5 if kind == "following" else t
                rows.append(dict(vehicle_id=str(identity), global_time=str(1000 + tick * 100),
                    frame_id=str(tick), local_y=str(y / FT), v_vel=str(speed / FT), v_length=str(4.5 / FT),
                    lane_id="1", direction="2", movement="1", v_class="2", preceding=str(index)))
        return rows, ids

    def episode(self, kind="following"):
        rows, ids = self.rows(kind)
        result = quality(rows, ids, 1000, kind, self.protocol)
        result.update(id="synthetic", block=0, source=dict(file="synthetic"))
        return result

    def predictions(self, episode, stationary=False):
        return [dict(episode=episode["id"], vehicle=identity, tick=tick,
            position=episode["observations"][i][0 if stationary else tick],
            velocity=0 if stationary else episode["observedSpeeds"][i][tick], acceleration=0)
            for i, identity in enumerate(episode["ids"][1:], 1) for tick in range(len(episode["leader"]))]

    def test_prefix_units_and_forecast_are_disjoint(self):
        episode = self.episode()
        self.assertAlmostEqual(episode["followers"][0]["velocity"], 5)
        self.assertEqual(len(episode["leader"]), 101)
        rows, ids = self.rows()
        for row in rows:
            if row["vehicle_id"] == "2" and int(row["global_time"]) > 2000:
                row["local_y"] = str(float(row["local_y"]) + 1)
        changed = quality(rows, ids, 1000, "following", self.protocol)
        self.assertEqual(episode["followers"], changed["followers"])
        self.assertEqual(episode["leader"], changed["leader"])

    def test_duplicates_missing_samples_and_leader_changes(self):
        rows, ids = self.rows()
        result = quality(rows + [dict(rows[0])], ids, 1000, "following", self.protocol)
        self.assertEqual(result["quality"]["exactDuplicatesRemoved"], 1)
        with self.assertRaisesRegex(ValueError, "conflicting"):
            quality(rows + [dict(rows[0], local_y="999")], ids, 1000, "following", self.protocol)
        with self.assertRaisesRegex(ValueError, "missing"):
            quality(rows[:-1], ids, 1000, "following", self.protocol)
        rows[-1]["preceding"] = "3"
        with self.assertRaisesRegex(ValueError, "leader identity"):
            quality(rows, ids, 1000, "following", self.protocol)

    def test_measured_lengths_and_impossible_observed_overlap(self):
        rows, ids = self.rows()
        rows[-1]["local_y"] = rows[-2]["local_y"]
        with self.assertRaisesRegex(ValueError, "overlap"):
            quality(rows, ids, 1000, "following", self.protocol)

    def test_complete_queue_has_interpolated_crossings_and_zero_oracle_error(self):
        episode = self.episode("queue")
        result = evaluate(episode, self.predictions(episode))
        self.assertAlmostEqual(result["meanPositionRmseM"], 0)
        self.assertAlmostEqual(result["headwayMaeSeconds"], 0)
        self.assertEqual(result["crossingCoverage"], 1)
        self.assertIsNotNone(crossing([0, 2], 1))
        self.assertAlmostEqual(crossing([0, 2], 1), .05)

    def test_missing_predictions_are_measurement_failures(self):
        episode = self.episode()
        samples = self.predictions(episode)
        with self.assertRaisesRegex(ValueError, "missing replay"):
            evaluate(episode, samples[:-1])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            evaluate(episode, samples + [copy.deepcopy(samples[0])])

    def test_a_model_that_never_discharges_is_not_excluded_or_counted_as_pass(self):
        episode = self.episode("queue")
        result = evaluate(episode, self.predictions(episode, stationary=True))
        self.assertLess(result["crossingCoverage"], 1)
        self.assertIsNone(result["headwayMaeSeconds"])
        self.assertFalse(assess([result], dict(targetsMet=True))["checks"]["queueCrossingCoverage"])

    def test_insufficient_eligible_data_cannot_establish_acceptance(self):
        self.assertFalse(assess([], dict(targetsMet=False))["passed"])

    def test_changed_raw_snapshot_fails_before_network_or_prediction(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            snapshot = root / "snapshot"
            snapshot.mkdir()
            metadata = snapshot / "metadata.json"
            metadata.write_text("{}")
            selection = dict(stage="development", protocolSha256=PROTOCOL_SHA256,
                metadataSource=dict(file="metadata.json", sha256="0" * 64), sources=[], selected=[])
            manifest = snapshot / "selection.json"
            manifest.write_text(json.dumps(selection))
            (snapshot / "selection-seal.json").write_text(json.dumps(dict(
                selectionSha256=hashlib.sha256(manifest.read_bytes()).hexdigest())))
            jar = root / "unused.jar"
            jar.write_bytes(b"unused")
            args = SimpleNamespace(output=root / "output", snapshot=snapshot, jar=jar, stage="development")
            with patch("validate_micro_field.urlopen", side_effect=AssertionError("unexpected network")):
                with self.assertRaisesRegex(AssertionError, "snapshot hash mismatch"):
                    run(args)

    def test_interrupted_download_reuses_only_matching_frozen_queries(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cached = root / "cache"
            cached.mkdir()
            data = [dict(location="lankershim", global_time="1000", vehicle_id="1")]
            (cached / "block.json").write_text(json.dumps(data))
            url = "https://data.transportation.gov/resource/8ect-6jqj.json?%24where=location%3D%27lankershim%27+and+global_time%3D1000"
            with patch("validate_micro_field.urlopen", side_effect=AssertionError("unexpected network")):
                actual, source = fetch(url, root / "block.json", self.protocol, cached)
                self.assertEqual(actual, data)
                self.assertIn("recoveredFrom", source)
                with self.assertRaisesRegex(ValueError, "frozen query"):
                    fetch(url.replace("1000", "2000"), root / "mismatch" / "block.json", self.protocol, cached)

    def test_network_failures_have_bounded_retries(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch("validate_micro_field.urlopen", side_effect=TimeoutError("test timeout")) as request:
                with patch("validate_micro_field.time.sleep"):
                    with self.assertRaises(TimeoutError):
                        fetch("https://example.invalid", Path(temporary) / "data.json", self.protocol)
            self.assertEqual(request.call_count, 3)


if __name__ == "__main__":
    unittest.main()
