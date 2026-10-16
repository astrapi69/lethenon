"""Sums a record of src/test/native/iolog.c: bytes written and syncs, per file (#202)."""
import struct, sys, collections
data = open(sys.argv[1], 'rb').read()
written, writes, syncs = collections.Counter(), collections.Counter(), collections.Counter()
at = 0
while at < len(data):
    kind = chr(data[at]); (length,) = struct.unpack_from('=H', data, at + 1)
    path = data[at + 3:at + 3 + length].decode().rsplit('/', 1)[-1]; at += 3 + length
    if kind == 'W':
        _, size = struct.unpack_from('=qI', data, at); at += 12 + size
        written[path] += size; writes[path] += 1
    elif kind == 'T':
        at += 8
    elif kind == 'S':
        syncs[path] += 1
for path in sorted(set(written) | set(syncs)):
    print(f"{path}\t{written[path]} bytes in {writes[path]} writes\t{syncs[path]} syncs")
print(f"total\t{sum(written.values())} bytes in {sum(writes.values())} writes\t{sum(syncs.values())} syncs")
