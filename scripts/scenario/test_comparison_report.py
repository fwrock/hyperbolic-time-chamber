import unittest
from build_comparison_report import describe, intervals


class ComparisonReportTest(unittest.TestCase):
    def test_empty_measurement_stays_unavailable(self):
        self.assertEqual(describe([]),dict(n=0,median=None,minimum=None,maximum=None))
        self.assertIsNone(intervals("[ACCOUNTED-COMPLETE] no timestamp"))

    def test_request_interval_uses_recorded_completion_not_harness_cleanup(self):
        log='''[2026-10-27 00:00:01,000] simulation_start_request_sent_to_gtm
[2026-10-27 00:00:03,500] [CONSERVATIVE-COMPLETE]
[2026-10-27 00:00:10,000] system shutdown'''
        self.assertEqual(intervals(log),2.5)

    def test_descriptive_range_keeps_unfavorable_values(self):
        self.assertEqual(describe([10,11,12,13,100]),dict(n=5,median=12,minimum=10,maximum=100))


if __name__=='__main__':unittest.main()
