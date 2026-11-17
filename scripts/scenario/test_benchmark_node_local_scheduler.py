import unittest

from benchmark_node_local_scheduler import extract_measurements, summarize


class NodeLocalBenchmarkTest(unittest.TestCase):
    def rows(self):
        return [dict(event_type='execution_timing_summary', data=dict(operation='simulation_loop',
                    total_seconds=2, count=1, active_count=0, failed_count=0)),
                dict(event_type='remote_transport_summary', data=dict(process_id='a',
                    direction='sent', category='scheduling', messages=10, serialized_bytes=100)),
                dict(event_type='remote_transport_summary', data=dict(process_id='b',
                    direction='sent', category='model', messages=5, serialized_bytes=75)),
                dict(event_type='remote_transport_summary', data=dict(process_id='b',
                    direction='received', category='scheduling', messages=10, serialized_bytes=100))]

    def test_outbound_totals_do_not_double_count_received_envelopes(self):
        result = extract_measurements(self.rows())
        self.assertEqual(result['simulationSeconds'], 2)
        self.assertEqual(result['trafficByCategory'], {'scheduling': {'messages': 10, 'bytes': 100},
                                                     'model': {'messages': 5, 'bytes': 75}})
        self.assertEqual(result['remoteMessages'], 15)
        self.assertEqual(result['remoteBytes'], 175)
        self.assertEqual(result['schedulingRemoteMessages'], 10)

    def test_missing_node_duplicate_snapshot_and_active_clock_are_rejected(self):
        rows = self.rows()
        with self.assertRaisesRegex(ValueError, 'missing final'):
            extract_measurements(rows[:2])
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            extract_measurements(rows + [rows[1]])
        rows[0]['data']['active_count'] = 1
        with self.assertRaisesRegex(ValueError, 'stopwatch'):
            extract_measurements(rows)

    def test_summary_excludes_warmups_and_retains_regressions(self):
        fields = ('simulationSeconds', 'completionSeconds', 'synchronizationSeconds', 'cpuSeconds',
                  'peakRssBytes', 'remoteMessages', 'remoteBytes', 'schedulingRemoteMessages',
                  'schedulingRemoteBytes')
        def row(strategy, value, warmup=False):
            return dict(strategy=strategy, warmup=warmup, **dict.fromkeys(fields, value))
        result = summarize([row('cluster-pool', 1000, True), row('cluster-pool', 10),
                            row('cluster-pool', 12), row('node-local-tick', 20),
                            row('node-local-tick', 24)])
        self.assertEqual(result['byStrategy']['cluster-pool']['simulationSeconds']['median'], 11)
        self.assertEqual(result['reductionPercent']['simulationSeconds'], -100)


if __name__ == '__main__':
    unittest.main()
