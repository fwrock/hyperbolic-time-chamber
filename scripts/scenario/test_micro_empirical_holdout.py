import unittest

from micro_empirical_holdout import assess, upper_bound, verify_partition


class HoldoutIntegrity(unittest.TestCase):
    def test_overlapping_time_is_not_enough_to_authorize_a_row(self):
        protocol = dict(location='peachtree', recordingOrigin=2000, frameRange=[1, 100])
        verify_partition([dict(location='peachtree', frame_id='1', global_time='2100')], protocol)
        with self.assertRaisesRegex(ValueError, 'partition'):
            verify_partition([dict(location='peachtree', frame_id='11', global_time='2100')], protocol)

    def test_strict_skill_bound_does_not_accept_zero_improvement(self):
        self.assertEqual(upper_bound([0] * 10, 42), 0)
        self.assertEqual(upper_bound([-1] * 10, 42), -1)

    def test_missing_queue_crossings_remain_failed_coverage(self):
        cases = [dict(kind='queue', model='HTC', variant='deterministic', episode='e',
                      headway_mae_s=None, crossing_coverage=.5),
                 dict(kind='queue', model='Causal rigid spacing', variant='baseline', episode='e',
                      headway_mae_s=.5, crossing_coverage=1)]
        followers = [dict(kind='queue', model='HTC', variant='deterministic', minimum_gap_m=2)]
        result = assess(cases, followers, dict(bootstrapSeed=42, requiredEpisodes=dict(following=1, queue=1)))
        self.assertTrue(result['queue']['data_gate_passed'])
        self.assertFalse(result['queue']['crossing_or_prediction_coverage_passed'])
        self.assertFalse(result['queue']['comparisons']['Causal rigid spacing']['available'])
        self.assertFalse(result['queue']['conditional_skill_gate_passed'])


if __name__ == '__main__':
    unittest.main()
