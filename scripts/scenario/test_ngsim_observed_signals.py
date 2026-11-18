from pathlib import Path
import tempfile
import unittest

from ngsim_observed_signals import compile_program, read_changes


class ObservedSignals(unittest.TestCase):
    def test_original_tab_and_converted_csv_agree(self):
        with tempfile.TemporaryDirectory() as directory:
            expected = {'Thru': [(100, 'Green'), (180, 'Yellow'), (200, 'Red')]}
            for suffix, delimiter in [('.txt', '\t'), ('.csv', ',')]:
                path = Path(directory) / ('signal' + suffix)
                path.write_text(delimiter.join(['BG_Thru', 'BY_Thru', 'BR_Thru']) + '\n' + delimiter.join(['100', '180', '200']) + '\n')
                self.assertEqual(read_changes(path), expected)

    def test_exact_changes_and_last_aspect_cover_the_inclusive_horizon(self):
        changes = {7: [(100, 'Green'), (180, 'Yellow'), (200, 'Red'), (300, 'Green'), (380, 'Yellow'), (400, 'Red')]}
        program = compile_program(changes, [dict(origin=7)], 220, 400)
        self.assertEqual([round(stage['durationSeconds'], 8) for stage in program['stages']], [8, 8, 2, .1])
        self.assertEqual([stage['green'] for stage in program['stages']], [[], [7], [], []])
        self.assertEqual([stage['yellow'] for stage in program['stages']], [[], [], [7], []])
        self.assertGreater(sum(stage['durationSeconds'] for stage in program['stages']), (400 - 220) * .1)

    def test_missing_initial_observation_and_unmapped_movement_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'unknown'):
            compile_program({7: [(100, 'Green')]}, [dict(origin=7)], 0, 200)
        with self.assertRaisesRegex(ValueError, 'exactly one'):
            compile_program({7: [(100, 'Green')]}, [dict(origin=8)], 100, 200)

    def test_conflicting_or_reversed_source_transitions_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'signal.txt'
            path.write_text('BG_Thru\tBY_Thru\tBR_Thru\n100\t99\t200\n')
            with self.assertRaises(ValueError):read_changes(path)

    def test_partial_source_cycles_do_not_invent_transitions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'signal.txt'
            path.write_text('BG_Thru\tBY_Thru\tBR_Thru\n\t80\t100\n200\t\t\n')
            changes = read_changes(path)
            self.assertEqual(changes['Thru'], [(80, 'Yellow'), (100, 'Red'), (200, 'Green')])
            with self.assertRaisesRegex(ValueError, 'unknown'):
                compile_program({7: changes['Thru']}, [dict(origin=7)], 0, 250)

    def test_zero_duration_green_is_not_extended_into_a_physics_interval(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'signal.txt'
            path.write_text('BG_Thru\tBY_Thru\tBR_Thru\n100\t100\t136\n')
            self.assertEqual(read_changes(path), {'Thru': [(100, 'Yellow'), (136, 'Red')]})


if __name__ == '__main__':
    unittest.main()
