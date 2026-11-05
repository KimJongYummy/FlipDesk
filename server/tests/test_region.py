"""Region (zoomed-view) streaming: only the visible rectangle, at native resolution."""
import io

import pytest
from PIL import Image

from flipdesk import protocol as P
from test_server_e2e import env, paired_phone  # noqa: F401  (fixture + helper)

QUADS = {(0, 0): (255, 0, 0), (1, 0): (0, 255, 0), (0, 1): (0, 0, 255), (1, 1): (255, 255, 0)}


class QuadScreen:
    """1920x1080 with a different solid color in each quadrant."""

    def __call__(self, monitor):
        img = Image.new("RGB", (1920, 1080))
        for (qx, qy), c in QUADS.items():
            img.paste(c, (qx * 960, qy * 540, qx * 960 + 960, qy * 540 + 540))
        return img, {"x": 0, "y": 0, "w": 1920, "h": 1080}


def test_region_header_roundtrip():
    f = P.VideoFrame(7, 1920, 1080, 5, 6, 1, 80, b"\xff\xd8x", region=(100, 200, 640, 360))
    data = P.pack_video(f)
    assert data[15] & P.VIDEO_FLAG_REGION and len(data) == 16 + 8 + 3
    g = P.unpack_video(data)
    assert g.region == (100, 200, 640, 360) and g.jpeg == b"\xff\xd8x"
    assert P.unpack_video(P.pack_video(P.VideoFrame(1, 1, 1, 0, 0, 0, 1, b"j"))).region is None
    with pytest.raises(P.ProtocolError):
        P.unpack_video(data[:20])


@pytest.mark.parametrize("msg,ok", [
    ({"t": "stream_region", "x": 0, "y": 0, "w": 640, "h": 360}, True),
    ({"t": "stream_region"}, True),  # back to full monitor
    ({"t": "stream_region", "x": 0, "y": 0, "w": 8, "h": 360}, False),
    ({"t": "stream_region", "x": -5, "y": 0, "w": 640, "h": 360}, False),
    ({"t": "stream_region", "x": 0, "y": 0, "w": 640}, False),
    ({"t": "stream_region", "x": 0.5, "y": 0, "w": 640, "h": 360}, False),
])
def test_region_validation(msg, ok):
    if ok:
        P.validate(msg)
    else:
        with pytest.raises(P.ProtocolError):
            P.validate(msg)


def _decode(frame):
    return Image.open(io.BytesIO(frame.jpeg)).convert("RGB")


async def test_region_frames_are_native_crops(env):  # noqa: F811
    srv, backend, _ = env
    phone = await paired_phone(srv)
    # Swap in the quadrant screen for this session's streamer.
    next(iter(srv.sessions)).streamer.grab = QuadScreen()
    await phone.send("stream_start", monitor=0, max_w=480, max_h=480)
    full = P.unpack_video(await phone.recv("binary"))
    assert full.region is None and _decode(full).size == (480, 270)  # downscaled whole monitor
    await phone.send("frame_ack", seq=full.seq)

    # Zoom into the bottom-right quadrant: 600x300 px of the monitor, phone has room for 1200x600.
    await phone.send("stream_region", x=1100, y=600, w=600, h=300, max_w=1200, max_h=600)
    f = P.unpack_video(await phone.recv("binary"))
    assert f.region == (1100, 600, 600, 300)
    img = _decode(f)
    assert img.size == (600, 300), "region must arrive at native resolution, never upscaled"
    r, g, b = img.getpixel((300, 150))
    assert r > 200 and g > 200 and b < 60  # yellow = bottom-right quadrant
    await phone.send("frame_ack", seq=f.seq)

    # A region bigger than the phone asked for is downscaled to fit.
    await phone.send("stream_region", x=0, y=0, w=1600, h=800, max_w=800, max_h=800)
    f2 = P.unpack_video(await phone.recv("binary"))
    assert f2.region == (0, 0, 1600, 800) and _decode(f2).size == (800, 400)
    await phone.send("frame_ack", seq=f2.seq)

    # Region overhanging the edge is clamped to the monitor.
    await phone.send("stream_region", x=1800, y=1000, w=600, h=300)
    f3 = P.unpack_video(await phone.recv("binary"))
    assert f3.region == (1800, 1000, 120, 80)
    await phone.send("frame_ack", seq=f3.seq)

    # Clearing the region returns to whole-monitor frames.
    await phone.send("stream_region")
    f4 = P.unpack_video(await phone.recv("binary"))
    assert f4.region is None and _decode(f4).size == (480, 270)
    await phone.close()


def test_real_region_capture_is_faster_than_full():
    """mss can grab just the rectangle; check it works on this machine's real screen."""
    import sys
    import time
    if sys.platform != "win32":
        pytest.skip("windows only")
    from flipdesk.screen import ScreenStreamer, StreamParams
    s = ScreenStreamer(None, None, None)
    p = StreamParams(monitor=0, max_w=1280, max_h=1280)

    def bench(region):
        p.region = region
        s._capture_encode(p, None)  # warm-up
        t = time.perf_counter()
        for _ in range(5):
            jpeg, _, geo, reg = s._capture_encode(p, None)
        return (time.perf_counter() - t) / 5, jpeg, reg

    full_t, _, _ = bench(None)
    reg_t, jpeg, reg = bench((400, 300, 640, 360))
    assert reg == (400, 300, 640, 360)
    assert Image.open(io.BytesIO(jpeg)).size == (640, 360)
    assert reg_t < full_t
