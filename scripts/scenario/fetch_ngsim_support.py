#!/usr/bin/env python3
"""Retrieve Peachtree support ZIP members with verified HTTP ranges, without raw trajectories."""
import argparse
import hashlib
import io
import json
import re
import struct
import urllib.request
import zipfile
from pathlib import Path

URL = ('https://data.transportation.gov/api/views/8ect-6jqj/files/'
       '7244a8b5-077f-4851-bc06-3cfc7a75f0b0?download=true&filename=Peachtree-Street-Atlanta-GA.zip')
MEMBERS = ['cad-diagram', 'data-analysis', 'gis-files', 'signal-timing', 'tube-data']


class RangeSource:
    """Keep one immutable source identity and reject full-file fallback responses."""
    def __init__(self, url):
        self.url, self.etag, self.size = url, None, None
        self.receipts = []

    def get(self, specification):
        request = urllib.request.Request(self.url, headers={
            'Range': specification, 'Accept-Encoding': 'identity', 'User-Agent': 'HTC-empirical-support/1'})
        with urllib.request.urlopen(request, timeout=45) as response:
            value = response.headers.get('Content-Range', '')
            match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)', value)
            if response.status != 206 or not match:
                raise ValueError('Source must support verified partial retrieval')
            start, end, size = map(int, match.groups())
            if end-start+1 > 8_000_000:
                raise ValueError('Unexpected support range size')
            payload = response.read(end-start+2)
            if len(payload) != end-start+1:
                raise ValueError('Partial response length mismatch')
            if specification.startswith('bytes=-'):
                if end != size-1 or start != max(0, size-int(specification[7:])):
                    raise ValueError('Suffix response range mismatch')
            elif specification != f'bytes={start}-{end}':
                raise ValueError('Response range differs from requested range')
            etag = response.headers.get('ETag')
            if self.size is not None and (size != self.size or etag != self.etag):
                raise ValueError('Remote archive identity changed during retrieval')
            self.etag, self.size = etag, size
            self.receipts.append(dict(requested=specification, content_range=value, etag=etag,
                                      sha256=hashlib.sha256(payload).hexdigest()))
            return payload


def fetch(output, selected):
    if output.exists():
        raise FileExistsError(output)
    source = RangeSource(URL)
    tail = source.get('bytes=-65557')
    end = tail.rfind(b'PK\x05\x06')
    if end < 0:
        raise ValueError('No ZIP end directory')
    _, disk, directory_disk, disk_entries, entries, size, offset, comment = struct.unpack_from('<4s4H2LH', tail, end)
    if disk or directory_disk or entries != disk_entries or entries == 65535:
        raise ValueError('Unsupported split/ZIP64 source')
    central = source.get(f'bytes={offset}-{offset+size-1}')
    # A small synthetic ZIP lets the standard parser verify the central directory.
    footer = struct.pack('<4s4H2LH', b'PK\x05\x06', 0, 0, entries, entries, size, 0, 0)
    archive = zipfile.ZipFile(io.BytesIO(central+footer))
    inventory = [dict(name=i.filename, method=i.compress_type, compressed_size=i.compress_size,
                      size=i.file_size, crc=i.CRC, offset=i.header_offset) for i in archive.infolist()]
    payloads = []
    for suffix in selected:
        name = f'peachtree-street-{suffix}.zip'
        info = archive.getinfo(name)
        if info.compress_type != zipfile.ZIP_STORED or info.file_size > 8_000_000:
            raise ValueError('Unexpected nested support member encoding')
        header = source.get(f'bytes={info.header_offset}-{info.header_offset+29}')
        if header[:4] != b'PK\x03\x04':
            raise ValueError('Invalid nested member local header')
        namesize, extrasize = struct.unpack_from('<HH', header, 26)
        begin = info.header_offset+30+namesize+extrasize
        payload = source.get(f'bytes={begin}-{begin+info.compress_size-1}')
        if zipfile.crc32(payload) != info.CRC:
            raise ValueError('Support member CRC mismatch')
        nested = zipfile.ZipFile(io.BytesIO(payload))
        if nested.testzip() is not None:
            raise ValueError('Nested member CRC mismatch')
        for member in nested.infolist():
            path = Path(member.filename)
            if path.is_absolute() or '..' in path.parts or '\\' in member.filename:
                raise ValueError('Unexpected nested path')
        payloads.append((name, payload, nested))
    output.mkdir(parents=True)
    manifest = dict(url=URL, etag=source.etag, archive_size=source.size, inventory=inventory,
                    ranges=source.receipts, extracted=[])
    for name, payload, nested in payloads:
        (output/name).write_bytes(payload)
        destination = output/'support'/Path(name).stem
        nested.extractall(destination)
        manifest['extracted'].append(dict(name=name, sha256=hashlib.sha256(payload).hexdigest(),
            files=[dict(name=member.filename, size=member.file_size, crc=member.CRC)
                   for member in nested.infolist()]))
    (output/'acquisition.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print(json.dumps(dict(output=str(output.resolve()), support_members=len(payloads),
                          retrieved_bytes=sum(int(r['content_range'].split('/')[0].split('-')[1])-
                            int(r['content_range'].split()[1].split('-')[0])+1 for r in source.receipts)), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--members', choices=MEMBERS, nargs='+', default=MEMBERS)
    args = parser.parse_args()
    fetch(args.output, args.members)


if __name__ == '__main__':
    main()
