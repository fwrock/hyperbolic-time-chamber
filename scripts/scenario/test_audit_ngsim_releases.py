import unittest

from audit_ngsim_releases import causal_speed_rows, inventory


class CausalReleaseInventory(unittest.TestCase):
    def test_future_positions_cannot_change_an_earlier_causal_speed(self):
        rows=[dict(frame_id=str(i),local_y=str(i*.1/.3048)) for i in range(21)]
        original=causal_speed_rows(rows)
        rows[-1]['local_y']='10000'
        changed=causal_speed_rows(rows)
        self.assertAlmostEqual(float(original[0]['v_vel'])*.3048,1.)
        self.assertEqual(original[:-1],changed[:-1])
        rows.pop(5)
        self.assertEqual([int(r['frame_id']) for r in causal_speed_rows(rows)],[16,17,18,19,20])

    def test_empty_green_lane_is_retained_in_the_inventory(self):
        row=dict(frame_id='20',vehicle_id='1',section_id='2',direction='2',local_y='10')
        results=inventory([row],[(15,'Green')],{'1':40.,'2':40.})
        self.assertEqual(len(results),2)
        self.assertTrue(all(r['status']=='no_observed_candidate_at_green' for r in results))


if __name__=='__main__':
    unittest.main()
