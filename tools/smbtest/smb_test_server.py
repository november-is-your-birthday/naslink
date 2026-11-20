"""
Local SMB2 server used to verify NasLink streaming against a real server.

impacket speaks SMB2/3, so smbj on the Android side exercises exactly the code path a
NAS would drive: negotiate, session setup, tree connect, query info, ranged reads.

Anonymous auth is used on purpose: impacket 0.13 computes NTLMv2 via hmac.new(key=str),
which Python 3.13 rejects. Skipping NTLM still exercises the full SMB2 transport.

Usage:
    python smb_test_server.py <root-dir> [port]
"""
import sys

from impacket import smbserver

SHARE = "media"


def main():
    root = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 445
    server = smbserver.SimpleSMBServer(listenAddress="0.0.0.0", listenPort=port)
    server.addShare(SHARE.upper(), root, "NasLink media share")
    server.setSMB2Support(True)
    server.setSMBChallenge("")
    server.setAuthCallback(lambda domain, user, addr: ("", "", True))
    print(f"serving {root} as share '{SHARE}' on port {port} (anonymous)", flush=True)
    server.start()


if __name__ == "__main__":
    main()