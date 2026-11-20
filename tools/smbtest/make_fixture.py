"""Generates the SMB fixture used by SmbStreamIntegrationTest.

The file is a 40 MB MP4 whose `moov` box deliberately sits at the very end, which is
how many real-world recordings are stored. Reading metadata therefore requires a ranged
request far into the file -- the exact case that breaks naive SMB streaming.

Usage:
    python make_fixture.py <output.mp4> [megabytes]
"""
import os
import struct
import sys

MOOV_PAYLOAD_BYTES = 100


MOOV_PAYLOAD_BYTES = 100
MOOV_BOX_BYTES = 116  # 4 length + 4 moov tag + 108 mvhd bytes

MVHD_PAYLOAD_BYTES = 100


def build_moov():
    """Builds an moov box exactly MOOV_BOX_BYTES long.

    Layout: [4 length][4 'moov'][4 mvhd length][4 'mvhd'][100 zero bytes] = 116.
    """
    mvhd = struct.pack(">I", 8 + MVHD_PAYLOAD_BYTES) + b"mvhd" + b"\x00" * MVHD_PAYLOAD_BYTES
    moov = struct.pack(">I", 8 + len(mvhd)) + b"moov" + mvhd
    if len(moov) != MOOV_BOX_BYTES:
        raise AssertionError(f"moov is {len(moov)} bytes, expected {MOOV_BOX_BYTES}")
    return moov


def main():
    out = sys.argv[1]
    megabytes = int(sys.argv[2]) if len(sys.argv) > 2 else 40
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)

    ftyp = struct.pack(">I", 0x20) + b"ftypisom" + struct.pack(">I", 0x200) + b"isomiso2avc1mp41"
    moov = build_moov()
    mdat_payload = megabytes * 1024 * 1024

    with open(out, "wb") as handle:
        handle.write(ftyp)
        handle.write(struct.pack(">I", mdat_payload + 8) + b"mdat")
        handle.write(b"\x00" * mdat_payload)
        handle.write(moov)

    size = os.path.getsize(out)
    assert size == len(ftyp) + 8 + mdat_payload + len(moov), "unexpected layout"
    # The tests rely on the moov box ending exactly at EOF.
    assert size - MOOV_BOX_BYTES == len(ftyp) + 8 + mdat_payload, "moov is not flush with EOF"
    print(f"wrote {out} ({size} bytes, moov occupies the last {len(moov)} bytes)")


if __name__ == "__main__":
    main()