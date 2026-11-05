"""End-to-end: a real TLS WebSocket server with the dry-run backend, driven by the fake phone."""
import asyncio
import socket

import pytest
from PIL import Image

from flipdesk import protocol as P
from flipdesk.backend import DryRunBackend
from flipdesk.config import Config
from flipdesk.media_info import NowPlaying
from flipdesk.server import FlipDeskServer
from flipdesk.testclient import FakePhone, PinMismatch, discover


def free_udp_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class FakeScreen:
    """Deterministic grabber: returns the same image until .change() is called."""

    def __init__(self):
        self.color = (10, 20, 30)
        self.grabs = 0

    def change(self):
        self.color = tuple((c + 60) % 256 for c in self.color)

    def __call__(self, monitor):
        self.grabs += 1
        return Image.new("RGB", (1920, 1080), self.color), {"x": 0, "y": 0, "w": 1920, "h": 1080}


@pytest.fixture
async def env(tmp_path):
    cfg = Config(tmp_path / "data")
    cfg.settings["discovery_port"] = free_udp_port()
    backend = DryRunBackend(downloads=tmp_path / "Downloads")
    screen = FakeScreen()
    srv = FlipDeskServer(cfg, backend, host="127.0.0.1", port=0, grabber=screen,
                         now_playing=NowPlaying(enabled=False))
    await srv.start()
    yield srv, backend, screen
    await srv.stop()


async def paired_phone(srv) -> FakePhone:
    s = srv.pairing.open()
    phone = FakePhone()
    await phone.connect("127.0.0.1", srv.port, srv.fingerprint)
    res = await phone.pair(secret=s.secret)
    assert res["t"] == "paired"
    return phone


async def until(pred, timeout=3.0):
    end = asyncio.get_running_loop().time() + timeout
    while not pred():
        if asyncio.get_running_loop().time() > end:
            raise AssertionError("condition not met in time")
        await asyncio.sleep(0.02)


# --- auth -----------------------------------------------------------------------
async def test_unpaired_device_is_rejected(env):
    srv, backend, _ = env
    phone = FakePhone()
    await phone.connect("127.0.0.1", srv.port, srv.fingerprint)
    res = await phone.hello(token="made-up-token")
    assert res["t"] == "auth_fail"
    with pytest.raises(ConnectionError):
        await phone.recv(timeout=2)
    assert not srv.sessions


async def test_commands_before_auth_close_connection(env):
    srv, backend, _ = env
    phone = FakePhone()
    await phone.connect("127.0.0.1", srv.port, srv.fingerprint)
    await phone.send("mouse_move", dx=50, dy=50)
    with pytest.raises(ConnectionError):
        await phone.recv(timeout=2)
    assert backend.calls == []


async def test_wrong_fingerprint_is_refused(env):
    srv, *_ = env
    with pytest.raises(PinMismatch):
        await FakePhone().connect("127.0.0.1", srv.port, "00" * 32)


async def test_pair_then_reconnect_with_token(env):
    srv, *_ = env
    phone = await paired_phone(srv)
    welcome = phone.welcome
    assert welcome["server_id"] == srv.config.server_id
    assert welcome["monitors"] and "launchers" in welcome
    token = phone.token
    await phone.close()

    again = FakePhone(device_id=phone.device_id)
    await again.connect("127.0.0.1", srv.port, srv.fingerprint)
    assert (await again.hello(token))["t"] == "auth_ok"
    await again.close()


async def test_reconnect_replaces_stale_connection(env):
    srv, *_ = env
    phone = await paired_phone(srv)
    await until(lambda: len(srv.sessions) == 1)
    # The phone comes back on a new socket while the old one is still open (e.g. Wi-Fi blip).
    again = FakePhone(device_id=phone.device_id)
    await again.connect("127.0.0.1", srv.port, srv.fingerprint)
    assert (await again.hello(phone.token))["t"] == "auth_ok"
    with pytest.raises(ConnectionError):  # the old connection is closed by the server
        while True:
            await phone.recv(timeout=2)
    await until(lambda: len(srv.sessions) == 1)
    await again.send("ping", ts=1)
    assert (await again.recv("pong"))["ts"] == 1
    await again.close()


async def test_pin_pairing(env):
    srv, *_ = env
    s = srv.pairing.open()
    phone = FakePhone()
    await phone.connect("127.0.0.1", srv.port, srv.fingerprint)
    assert (await phone.pair(pin=s.pin))["t"] == "paired"
    await phone.close()
    late = FakePhone()
    await late.connect("127.0.0.1", srv.port, srv.fingerprint)
    assert (await late.pair(pin=s.pin))["t"] == "pair_fail"  # consumed


# --- input ------------------------------------------------------------------------
async def test_input_commands_reach_backend(env):
    srv, backend, _ = env
    phone = await paired_phone(srv)
    for _ in range(4):
        await phone.send("mouse_move", dx=0.5, dy=-0.25)  # fractions accumulate
    await phone.send("mouse_button", button="left", action="click")
    await phone.send("mouse_button", button="right", action="click")
    await phone.send("scroll", dx=0, dy=-0.5)
    await phone.send("key", key="t", mods=["ctrl", "shift"])
    await phone.send("text", text="héllo\n")
    await phone.send("shortcut", name="copy")
    await phone.send("mouse_abs", x=0.5, y=0.5, monitor=0)
    await phone.send("ping", ts=42)
    assert (await phone.recv("pong"))["ts"] == 42

    moves = [c for c in backend.calls if c[0] == "mouse_move"]
    assert sum(c[1] for c in moves) == 2 and sum(c[2] for c in moves) == -1
    assert ("mouse_button", "left", "click") in backend.calls
    assert ("mouse_button", "right", "click") in backend.calls
    assert ("scroll", 0, -60) in backend.calls
    assert ("key", "t", "press", ("ctrl", "shift")) in backend.calls
    assert ("text", "héllo\n") in backend.calls
    assert ("key", "c", "press", ("ctrl",)) in backend.calls
    mon = srv.monitor(0)
    assert ("mouse_abs", mon["x"] + round(0.5 * (mon["w"] - 1)),
            mon["y"] + round(0.5 * (mon["h"] - 1))) in backend.calls
    await phone.close()


async def test_bad_message_reports_error_but_keeps_connection(env):
    srv, backend, _ = env
    phone = await paired_phone(srv)
    await phone.send("shortcut", name="delete_system32")
    assert "unknown shortcut" in (await phone.recv("error"))["msg"]
    await phone.send("ping", ts=1)
    assert (await phone.recv("pong"))["ts"] == 1
    await phone.close()


async def test_power_requires_confirmation(env):
    srv, backend, _ = env
    phone = await paired_phone(srv)
    await phone.send("power", action="shutdown")
    assert "confirm" in (await phone.recv("error"))["msg"]
    assert ("power", "shutdown") not in backend.calls
    await phone.send("power", action="lock")
    await phone.recv("ok")
    await phone.send("power", action="restart", confirm=True)
    await phone.recv("ok")
    await until(lambda: ("power", "restart") in backend.calls)
    assert ("power", "lock") in backend.calls
    await phone.close()


async def test_volume_and_mute(env):
    srv, backend, _ = env
    phone = await paired_phone(srv)
    await phone.send("volume_set", level=0.8)
    msg = await phone.recv("volume")
    assert msg["level"] == 0.8
    await phone.send("volume_step", delta=-0.3)
    assert (await phone.recv("volume"))["level"] == 0.5
    await phone.send("mute")
    assert (await phone.recv("volume"))["muted"] is True
    await phone.close()


async def test_clipboard_url_launcher(env):
    srv, backend, _ = env
    phone = await paired_phone(srv)
    await phone.send("clipboard_push", text="from phone")
    await phone.recv("ok")
    backend.clip = "from pc"
    await phone.send("clipboard_pull")
    assert (await phone.recv("clipboard"))["text"] == "from pc"

    await phone.send("open_url", url="javascript:alert(1)")
    assert "http" in (await phone.recv("error"))["msg"]
    await phone.send("open_url", url="file:///C:/Windows/System32/calc.exe")
    await phone.recv("error")
    await phone.send("open_url", url="https://example.com/a?b=c")
    await phone.recv("ok")
    assert ("open_url", "https://example.com/a?b=c") in backend.calls

    await phone.send("launch", id="notepad")
    await phone.recv("ok")
    await phone.send("launch", id="nope")
    await phone.recv("error")
    assert ("launch", "notepad") in backend.calls
    await phone.close()


async def test_file_transfer(env):
    srv, backend, _ = env
    phone = await paired_phone(srv)
    data = bytes(range(256)) * 2000  # 512 KB, sent in 3 chunks
    await phone.send("file_begin", id=1, name="../../evil:name.bin", size=len(data), mime="x/y")
    await phone.recv("file_ready")
    for i in range(0, len(data), 200_000):
        await phone.send_bytes(P.pack_file_chunk(1, data[i:i + 200_000]))
    await phone.send("file_end", id=1)
    saved = await phone.recv("file_saved")
    path = backend.downloads_dir() / saved["name"]
    assert path.parent == backend.downloads_dir()  # no path traversal
    assert path.read_bytes() == data

    await phone.send("file_begin", id=2, name="short.txt", size=10)
    await phone.recv("file_ready")
    await phone.send_bytes(P.pack_file_chunk(2, b"abc"))
    await phone.send("file_end", id=2)
    assert "incomplete" in (await phone.recv("error"))["msg"]
    assert not list(backend.downloads_dir().glob("short*"))
    await phone.close()


# --- screen streaming -------------------------------------------------------------
async def test_stream_frames_ack_and_skip_unchanged(env):
    srv, backend, screen = env
    phone = await paired_phone(srv)
    await phone.send("stream_start", monitor=0, max_w=640, max_h=640, fps=30)
    await phone.recv("stream_started")
    f1 = P.unpack_video(await phone.recv("binary"))
    assert f1.jpeg[:2] == b"\xff\xd8" and (f1.mon_w, f1.mon_h) == (1920, 1080)
    with Image.open(__import__("io").BytesIO(f1.jpeg)) as im:
        assert im.size == (640, 360)  # downscaled for the phone, aspect kept
    await phone.send("frame_ack", seq=f1.seq)

    grabs = screen.grabs
    await until(lambda: screen.grabs > grabs + 5)  # still capturing...
    with pytest.raises(asyncio.TimeoutError):  # ...but nothing changed, so nothing sent
        await phone.recv("binary", timeout=0.3)

    screen.change()
    f2 = P.unpack_video(await phone.recv("binary"))
    assert f2.seq == f1.seq + 1
    await phone.send("stream_stop")
    await phone.close()


async def test_stream_flow_control_without_acks(env):
    srv, backend, screen = env
    phone = await paired_phone(srv)
    await phone.send("stream_start", monitor=0, max_w=320, max_h=320, fps=60)
    await phone.recv("binary")
    screen.change()
    await phone.recv("binary")
    for _ in range(5):
        screen.change()
        await asyncio.sleep(0.05)
    # No acks were sent, so the server must stop at MAX_IN_FLIGHT frames.
    with pytest.raises(asyncio.TimeoutError):
        await phone.recv("binary", timeout=0.4)
    await phone.close()


# --- discovery --------------------------------------------------------------------
async def test_discovery_reply(env):
    srv, *_ = env
    found = await asyncio.get_running_loop().run_in_executor(
        None, lambda: discover(timeout=1.0, port=srv.config.settings["discovery_port"],
                               target="127.0.0.1"))
    assert found and found[0]["fp"] == srv.fingerprint and found[0]["port"] == srv.port
