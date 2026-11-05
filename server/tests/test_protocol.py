import json

import pytest

from flipdesk import protocol as P


def test_encode_decode_roundtrip():
    raw = P.encode("mouse_move", dx=1.5, dy=-2)
    assert json.loads(raw) == {"t": "mouse_move", "dx": 1.5, "dy": -2}
    assert P.decode(raw)["dx"] == 1.5


@pytest.mark.parametrize("raw", ["not json", "[]", '{"x":1}', '{"t":5}'])
def test_decode_rejects_garbage(raw):
    with pytest.raises(P.ProtocolError):
        P.decode(raw)


@pytest.mark.parametrize("msg", [
    {"t": "mouse_move", "dx": 1, "dy": 2},
    {"t": "mouse_button", "button": "left", "action": "click"},
    {"t": "key", "key": "c", "mods": ["ctrl"]},
    {"t": "key", "key": "f5", "action": "down"},
    {"t": "shortcut", "name": "copy"},
    {"t": "media", "action": "next"},
    {"t": "power", "action": "lock"},
    {"t": "stream_start", "monitor": 0, "max_w": 800},
    {"t": "mute"},
])
def test_validate_accepts(msg):
    assert P.validate(msg) is msg


@pytest.mark.parametrize("msg", [
    {"t": "format_disk"},                                   # unknown type
    {"t": "mouse_move", "dx": "1", "dy": 2},                # wrong type
    {"t": "mouse_move", "dx": True, "dy": 2},               # bool is not a number here
    {"t": "mouse_move", "dx": 1},                           # missing field
    {"t": "mouse_button", "button": "left", "action": "smash"},
    {"t": "key", "key": "a", "mods": ["hyper"]},
    {"t": "key", "key": "a", "action": "hold"},
    {"t": "shortcut", "name": "rm_rf"},
    {"t": "power", "action": "self_destruct"},
    {"t": "text", "text": "x" * 10_001},
])
def test_validate_rejects(msg):
    with pytest.raises(P.ProtocolError):
        P.validate(msg)


def test_every_shortcut_has_a_key_binding():
    from flipdesk.backend.base import SHORTCUT_KEYS
    assert P.SHORTCUTS == set(SHORTCUT_KEYS)


def test_video_frame_roundtrip():
    f = P.VideoFrame(seq=2**32 + 5, mon_w=1920, mon_h=1080, cursor_x=-1, cursor_y=99999,
                     monitor=2, quality=70, jpeg=b"\xff\xd8jpeg")
    data = P.pack_video(f)
    assert data[0] == P.BIN_VIDEO and len(data) == 16 + 6
    g = P.unpack_video(data)
    assert g.seq == 5  # wraps to u32
    assert (g.mon_w, g.mon_h, g.cursor_x, g.cursor_y) == (1920, 1080, -1, 32767)
    assert (g.monitor, g.quality, g.jpeg) == (2, 70, b"\xff\xd8jpeg")


def test_file_chunk_roundtrip():
    data = P.pack_file_chunk(7, b"abc")
    assert P.unpack_file_chunk(data) == (7, b"abc")
    with pytest.raises(P.ProtocolError):
        P.unpack_file_chunk(b"\x01\x00")


def test_protocol_doc_lists_every_message():
    """docs/PROTOCOL.md must document every client message type."""
    from pathlib import Path
    doc = (Path(__file__).resolve().parents[2] / "docs" / "PROTOCOL.md").read_text(encoding="utf-8")
    missing = [t for t in P.CLIENT_MESSAGES if f"`{t}`" not in doc]
    assert not missing, f"undocumented message types: {missing}"
