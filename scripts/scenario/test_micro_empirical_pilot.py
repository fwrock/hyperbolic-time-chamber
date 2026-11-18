import copy
import json
from pathlib import Path
import tempfile
import unittest

from micro_empirical_data_audit import sha, summarize, write
from micro_empirical_pilot import causal_rigid_samples, load_audit
from test_micro_empirical_data_audit import protocol, row


class PilotIntegrity(unittest.TestCase):
    def test_future_head_cannot_change_previous_baseline_interval(self):
        episode = dict(id='e', followers=[dict(id=1, position=0, velocity=2)],
                       leader=[dict(position=10 + 2 * i, velocity=2) for i in range(4)])
        original = causal_rigid_samples([episode])
        changed = copy.deepcopy(episode)
        changed['leader'][2]['position'] += 100
        result = causal_rigid_samples([changed])
        self.assertEqual(original[:3], result[:3])
        self.assertNotEqual(original[3], result[3])
        self.assertEqual(original[0]['position'], 0)
        self.assertEqual(original[0]['velocity'], 2)

    def test_changed_raw_source_is_rejected_before_prediction(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write(root / 'protocol.json', protocol())
            write(root / 'status.json', dict(state='complete'))
            write(root / 'provenance.json', dict(protocol_sha256=sha(root / 'protocol.json')))
            write(root / 'page-000.json', [row(), row(2)])
            write(root / 'requests.json', [dict(file='page-000.json', sha256=sha(root / 'page-000.json'))])
            write(root / 'audit.json', summarize([row(), row(2)], protocol()))
            values, _ = load_audit(root)
            self.assertEqual(len(values), 2)
            write(root / 'page-000.json', [row(local_y='999')])
            with self.assertRaisesRegex(ValueError, 'source receipt'):
                load_audit(root)


if __name__ == '__main__':
    unittest.main()
