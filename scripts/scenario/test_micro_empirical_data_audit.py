import unittest

from micro_empirical_data_audit import summarize, unique_rows, validate_protocol, validate_rows


def protocol():
    return dict(classification='Development input audit only; no holdout trajectories or predictions accessed',
                development_recording_origin=1000, reserved_recording_origin=2000,
                development_frame_range=[1, 100], location='peachtree')


def row(frame=1, **changes):
    value = dict(vehicle_id='1', frame_id=str(frame), global_time=str(1000 + 100 * frame),
                 location='peachtree', local_y=str(frame), direction='2', v_class='2', movement='1', section_id='1')
    value.update(changes)
    return value


class DevelopmentBoundary(unittest.TestCase):
    def test_recordings_with_overlapping_raw_times_are_separated(self):
        validate_rows([row(11)], protocol())
        # Same global time as development frame 11, but reserved origin/frame.
        with self.assertRaisesRegex(ValueError, 'recording boundary'):
            validate_rows([row(frame_id='1', global_time='2100')], protocol())

    def test_location_and_frame_boundaries(self):
        for value in [row(location='lankershim'), row(101)]:
            with self.assertRaises(ValueError):
                validate_rows([value], protocol())

    def test_overlapping_partition_configuration_is_rejected(self):
        value = protocol()
        value['reserved_recording_origin'] = 1000
        with self.assertRaisesRegex(ValueError, 'overlap'):
            validate_protocol(value)

    def test_duplicate_conflicts_cannot_be_silently_removed(self):
        values, duplicates = unique_rows([row(), row()])
        self.assertEqual((len(values), duplicates), (1, 1))
        with self.assertRaisesRegex(ValueError, 'Conflicting duplicate'):
            unique_rows([row(), row(local_y='999')])

    def test_audit_cannot_exceed_recording_budget(self):
        value = protocol()
        value['development_frame_range'] = [1, 11001]
        with self.assertRaisesRegex(ValueError, 'bounded frame scope'):
            validate_protocol(value)

    def test_audit_reports_missing_frames_and_no_predictions(self):
        result = summarize([row(1), row(2), row(4)], protocol())
        self.assertEqual(result['frame_gap_counts'], {'1': 1, '2': 1})
        self.assertFalse(result['reserved_recording_accessed'])
        self.assertFalse(result['predictions_produced'])


if __name__ == '__main__':
    unittest.main()
