import unittest
import io
import json
import tempfile
import threading
from pathlib import Path
from unittest.mock import patch, MagicMock
from types import SimpleNamespace
from benchmark_metrics import ProcessMonitor, imbalance, summarize_samples, parse_runtime
from run_cluster_validation import execution_outcome, run


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

    def test_metrics_are_written_before_shutdown_and_finish_closes_valid_json(self):
        ready = threading.Event()
        calls = []
        payload = 'metric{label="quote"} 1\n' * 1000
        def response(*args, **kwargs):
            calls.append(1)
            if len(calls) >= 3:
                ready.set()
            return io.BytesIO(payload.encode())
        with tempfile.TemporaryDirectory() as directory, patch('benchmark_metrics.urllib.request.urlopen', side_effect=response):
            root = Path(directory)
            monitor = ProcessMonitor(root, interval=.005, metrics_ports=[12345])
            try:
                self.assertTrue(ready.wait(2), 'metrics sampler never progressed')
                self.assertGreater((root/'prometheus-samples.json').stat().st_size, len(payload))
            finally:
                monitor.finish()
            records = json.loads((root/'prometheus-samples.json').read_text())
            self.assertGreaterEqual(len(records), 3)
            self.assertTrue(all(r['text']==payload and r['port']==12345 for r in records))
            self.assertEqual([r['seconds'] for r in records], sorted(r['seconds'] for r in records))
            self.assertTrue(json.loads((root/'process-samples.json').read_text()))

    def test_monitor_io_failure_is_reported_instead_of_accepting_missing_measurements(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'file';path.write_text('not a directory')
            monitor=ProcessMonitor(path)
            with self.assertRaisesRegex(RuntimeError, 'failed to retain'):
                monitor.finish()

    def test_monitor_failure_still_stops_the_jvm_and_records_the_engine_outcome(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);jar=root/'engine.jar';jar.write_bytes(b'test executable')
            scenario=root/'simulation.json';scenario.write_text(json.dumps({'cityMapFile':'city.json'}))
            (root/'city.json').write_text('{}')
            process=MagicMock(pid=123,returncode=None)
            process.poll.return_value=None
            def terminate():process.returncode=0
            process.terminate.side_effect=terminate
            def launch(*args, **kwargs):
                kwargs['stdout'].write('[CONSERVATIVE-COMPLETE] tick=1 status=horizon-truncated\n'
                    'Received StopSimulationEvent. Flushing/stopping reporters\n')
                kwargs['stdout'].flush()
                return process
            args=SimpleNamespace(jar=jar,simulation=scenario,output=root/'run',nodes=1,base_port=24000,
                timeout=1,measure=True,cpu_ids=None,extra_config=None,delay_ms=0,kill_node=None,kill_after=None)
            with patch('run_cluster_validation.ProcessMonitor') as monitor, \
                    patch('run_cluster_validation.subprocess.Popen',side_effect=launch), \
                    patch('run_cluster_validation.time.sleep'),patch('builtins.print'):
                monitor.return_value.finish.side_effect=OSError('measurement storage failed')
                self.assertEqual(run(args),1)
            process.terminate.assert_called_once()
            receipt=json.loads((root/'run/result.json').read_text())
            self.assertTrue(receipt['engineCompleted'])
            self.assertTrue(receipt['engineReportedStop'])
            self.assertIn('measurement storage failed',receipt['measurement']['error'])

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
