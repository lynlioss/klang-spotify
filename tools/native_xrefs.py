#!/usr/bin/env python3
"""Find ARM64 ADRP/ADD candidate references to selected native endpoint literals.

This performs bounded instruction matching, not control-flow/dataflow analysis.
Candidate references require review of their surrounding disassembly.
"""
import argparse
import json
import struct
from pathlib import Path


def signed(value, bits):
    return value - (1 << bits) if value & (1 << (bits-1)) else value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('binary', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    data = args.binary.read_bytes()
    if data[:6] != b'\x7fELF\x02\x01':
        raise ValueError('Expected little-endian ELF64')
    phoff = struct.unpack_from('<Q', data, 32)[0]
    entsize, count = struct.unpack_from('<HH', data, 54)
    segments = []
    for i in range(count):
        kind, flags, offset, address, _, size, _, _ = struct.unpack_from('<IIQQQQQQ', data, phoff+i*entsize)
        if kind == 1:
            segments.append((flags, offset, address, size))
    needles = [b'https://clienttoken.spotify.com/v1/clienttoken',
               b'https://login5.spotify.com/v3/login', b'https://login5.spotify.com/v4/login',
               b'storage-resolve/v2/files/audio/', b'https://apresolve.spotify.com/v2',
               b'wss://dealer.g2.spotify.com:443', b'sessiontransfer/v1/token']
    targets = {}
    for needle in needles:
        offset = data.find(needle)
        if offset == -1:
            continue
        for _, file_start, address, size in segments:
            if file_start <= offset < file_start+size:
                targets[address+offset-file_start] = {'literal': needle.decode(), 'fileOffset': hex(offset)}
    rows = []
    pages = {addr & ~0xFFF for addr in targets}
    for flags, offset, address, size in segments:
        if not flags & 1:
            continue
        for pos in range(offset, offset+size-4, 4):
            insn = struct.unpack_from('<I', data, pos)[0]
            if insn & 0x9F000000 != 0x90000000:
                continue
            pc = address + pos-offset
            immediate = ((insn >> 5) & 0x7FFFF) << 2 | ((insn >> 29) & 3)
            page = (pc & ~0xFFF) + (signed(immediate, 21) << 12)
            if page not in pages:
                continue
            register = insn & 31
            for step in range(1, 9):
                current = struct.unpack_from('<I', data, pos+4*step)[0]
                if current & 0xFF000000 != 0x91000000 or (current >> 5) & 31 != register:
                    continue
                imm = ((current >> 10) & 0xFFF) << (12 if current & (1 << 22) else 0)
                target = page+imm
                if target in targets:
                    rows.append({**targets[target], 'stringAddress': hex(target),
                                 'adrpAddress': hex(pc), 'addAddress': hex(pc+4*step),
                                 'register': register, 'status': 'candidate-needs-disassembly-review'})
    args.output.write_text(json.dumps(rows, indent=2)+'\n')
    print(json.dumps({'candidateReferences': len(rows)}, indent=2))


if __name__ == '__main__':
    main()
