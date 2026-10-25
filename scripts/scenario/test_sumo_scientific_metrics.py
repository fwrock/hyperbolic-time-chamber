import math
import unittest
from sumo_scientific_metrics import percentile, paired_seed_summary


class ScientificMetricsTest(unittest.TestCase):
    def test_percentiles_report_a_distribution_without_treating_samples_as_replicates(self):
        self.assertAlmostEqual(percentile([0,1,2,3,4],0.95),3.8)
        self.assertIsNone(percentile([],0.95))

    def test_confidence_interval_uses_seed_level_pairs_and_small_sample_student_t(self):
        records=[dict(seed=i,metrics=dict(signedDurationBiasSeconds=v)) for i,v in enumerate([-1,0,1,0,0])]
        result=paired_seed_summary(records)
        self.assertEqual(result['sampleSize'],5)
        self.assertEqual(result['studentTDegreesOfFreedom'],4)
        self.assertAlmostEqual(result['meanDifferenceCiSeconds'][0],-2.776445105/math.sqrt(10))
        self.assertAlmostEqual(result['meanDifferenceCiSeconds'][1],2.776445105/math.sqrt(10))
        records[-1]['seed']=records[0]['seed']
        with self.assertRaises(ValueError): paired_seed_summary(records)


if __name__=='__main__': unittest.main()
