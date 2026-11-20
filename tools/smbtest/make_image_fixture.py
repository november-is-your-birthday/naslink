"""Generates the PNG fixture used by SmbStreamIntegrationTest's image cases.

The test suite expects a square 64x64 truecolour PNG named `red.png` under the
share's `media/` subdirectory (same layer as probe.mp4). A real decodable PNG is
required -- the integration test feeds the bytes served over loopback HTTP into
BitmapFactory and asserts the decoded size, so a renamed or truncated file would
not pass.

Usage:
    python make_image_fixture.py <output.png> [edge]
"""
import os
import struct
import sys
import zlib


def build_png(edge: int) -> bytes:
    """Builds a genuine edge x edge RGB PNG with a smooth red-dominant gradient."""
    raw = b"".join(
        b"\x00" + b"".join(
            bytes([(x * 4) % 256, (y * 4) % 256, ((x + y) * 2) % 256])
            for x in range(edge)
        )
        for y in range(edge)
    )

    def chunk(tag: bytes, data: bytes) -> bytes:
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    ihdr = struct.pack(">IIBBBBB", edge, edge, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw))
            + chunk(b"IEND", b""))


def main():
    out = sys.argv[1]
    edge = int(sys.argv[2]) if len(sys.argv) > 2 else 64
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    png = build_png(edge)
    with open(out, "wb") as handle:
        handle.write(png)
    print(f"wrote {out} ({len(png)} bytes, {edge}x{edge} truecolour PNG)")


if __name__ == "__main__":
    main()
