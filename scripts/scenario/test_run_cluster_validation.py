import unittest

from run_cluster_validation import execution_outcome


class ClusterOutcomeTest(unittest.TestCase):
    stop = "Received StopSimulationEvent. Flushing/stopping reporters"

    def test_reporter_flush_after_abort_is_not_engine_completion(self):
        self.assertEqual(execution_outcome(["[SIMULATION-FAILED] worker lost\n" + self.stop]),
                         (True, False, True))

    def test_normal_completion_requires_the_runtime_completion_marker(self):
        self.assertEqual(execution_outcome([self.stop]), (True, False, False))
        for marker in ("[CONSERVATIVE-COMPLETE]", "[ACCOUNTED-COMPLETE]"):
            self.assertEqual(execution_outcome([marker + "\n" + self.stop]), (True, True, False))

    def test_late_failure_cannot_be_hidden_by_an_earlier_completion(self):
        self.assertEqual(execution_outcome(["[CONSERVATIVE-COMPLETE]\n" + self.stop,
                                           "[CONSERVATIVE-FAILED] late request"]), (True, True, True))

    def test_legacy_balancer_failure_is_recognized_before_teardown(self):
        self.assertEqual(execution_outcome(["Load balancing failed; stopping the simulation: lost member"]),
                         (False, False, True))


if __name__ == "__main__":
    unittest.main()
