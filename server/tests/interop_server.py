"""Runs a dry-run server for the Android JVM interop test (android/app/src/test/.../InteropTest.kt).

    python tests/interop_server.py <workdir>

Writes <workdir>/qr.txt (pairing URI) and, on exit, <workdir>/calls.json (recorded input).
"""
import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from PIL import Image  # noqa: E402

from flipdesk.backend import DryRunBackend  # noqa: E402
from flipdesk.config import Config  # noqa: E402
from flipdesk.media_info import NowPlaying  # noqa: E402
from flipdesk.server import FlipDeskServer  # noqa: E402


def grabber(monitor):
    return Image.new("RGB", (1920, 1080), (40, 90, 160)), {"x": 0, "y": 0, "w": 1920, "h": 1080}


async def main(work: Path, seconds: float) -> None:
    work.mkdir(parents=True, exist_ok=True)
    backend = DryRunBackend(downloads=work / "Downloads")
    srv = FlipDeskServer(Config(work / "data"), backend, host="127.0.0.1", port=0, discovery=False,
                         grabber=grabber, now_playing=NowPlaying(enabled=False))
    await srv.start()
    s = srv.pairing.open()
    (work / "qr.txt").write_text(srv.pairing.qr_payload(s).replace(
        "h=" + srv.pairing.qr_payload(s).split("h=")[1].split("&")[0], "h=127.0.0.1"))
    print("READY", srv.port, flush=True)
    try:
        await asyncio.sleep(seconds)
    finally:
        (work / "calls.json").write_text(json.dumps([list(c) for c in backend.calls]))
        await srv.stop()


if __name__ == "__main__":
    asyncio.run(main(Path(sys.argv[1]), float(sys.argv[2]) if len(sys.argv) > 2 else 90))
