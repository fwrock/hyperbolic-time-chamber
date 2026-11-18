import unittest

from fetch_ngsim_support import DEFAULT_MEMBERS, read_range_chunks


class SupportMemberRanges(unittest.TestCase):
    def test_large_member_is_read_without_overlap_or_missing_tail(self):
        class Source:
            def __init__(self):
                self.calls = []

            def get(self, specification):
                self.calls.append(specification)
                start, end = map(int, specification.removeprefix('bytes=').split('-'))
                return bytes(range(start, end+1))

        source = Source()
        self.assertEqual(read_range_chunks(source, 9, 11, chunk_size=4), bytes(range(9, 20)))
        self.assertEqual(source.calls, ['bytes=9-12', 'bytes=13-16', 'bytes=17-19'])
        self.assertNotIn('aerial-ortho-photos', DEFAULT_MEMBERS)

    def test_invalid_ranges_fail_before_any_download(self):
        for begin, length, chunk in [(-1, 4, 4), (0, 0, 4), (0, 4, 0), (0, 4, 8_000_001)]:
            with self.assertRaises(ValueError):
                read_range_chunks(None, begin, length, chunk)


if __name__ == '__main__':
    unittest.main()
