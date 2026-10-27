import unittest
from benchmark_metrics import imbalance, summarize_samples, parse_runtime
from run_cluster_validation import execution_outcome


class BenchmarkMetricsTest(unittest.TestCase):
    def test_idle_load_is_unavailable_and_invalid_loads_fail(self):
        self.assertIsNone(imbalance([0,0]))
        self.assertEqual(imbalance([1,1]),dict(cv=0,maxOverMean=1))
        self.assertEqual(imbalance([0,2]),dict(cv=1,maxOverMean=2))
        with self.assertRaises(ValueError): imbalance([-1,2])

    def test_memory_is_simultaneous_peak_and_cpu_is_last_per_pid(self):
        samples=[dict(nodes=[dict(pid=1,rssBytes=100,cpuSeconds=1),dict(pid=2,rssBytes=10,cpuSeconds=2)],hostLoad1=3),
                 dict(nodes=[dict(pid=1,rssBytes=10,cpuSeconds=2),dict(pid=2,rssBytes=100,cpuSeconds=4)],hostLoad1=4),
                 dict(nodes=[None,None],hostLoad1=4)]
        m=summarize_samples(samples)
        self.assertEqual(m['peakAggregateRssBytes'],110)
        self.assertEqual(m['cpuSeconds'],6)
        self.assertEqual(m['validSamples'],2)

    def test_pause_is_not_sum_of_overlapping_shard_moves(self):
        log="""2026-10-27 08:00:00.100 [LOAD-BALANCE-METRICS] round=2 nodes=2 shards=4 live_entities=10 weighting=live-entities node_loads=[pekko://s@127.0.0.1:1=2.0,pekko://s@127.0.0.1:2=8.0]
Migration wave 'b': 2 dynamic plan(s), 10 entities
Migration 'a' placement and restore verified: 700ms
Migration 'b' placement and restore verified: 700ms
[MIGRATION-TIMING] request=r phase=resumed totalNanos=1000000000 freezeNanos=100000000 relocationNanos=800000000 resumeNanos=100000000
"""
        r=parse_runtime(log)
        self.assertEqual(r['loads'][0]['values'],[2,8])
        self.assertAlmostEqual(r['loads'][0]['imbalance']['cv'],.6)
        self.assertEqual(r['pauses'][0]['totalSeconds'],1)
        self.assertEqual(len(r['verifiedMoves']),2)
        self.assertEqual(r['waves'][0]['entities'],10)

    def test_missing_phase_is_not_invented_and_failure_is_not_success(self):
        self.assertEqual(parse_runtime('')['phases'],[])
        self.assertEqual(execution_outcome(['[SIMULATION-FAILED] timeout']), (False,False,True))


if __name__=='__main__': unittest.main()
