"""Wire protocol shared by the server, the tests and the fake phone client.

Control messages are JSON text frames: {"t": "<type>", ...fields}.
Bulk data uses binary frames whose first byte is the frame kind.
See docs/PROTOCOL.md for the full description.
"""
from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from typing import Any

PROTOCOL_VERSION = 1
DEFAULT_PORT = 8765
DISCOVERY_PORT = 8766
DISCOVERY_PROBE = b"FLIPDESK?"
DISCOVERY_REPLY_TYPE = "flipdesk"

# Binary frame kinds (first byte).
BIN_VIDEO = 0x01
BIN_FILE_CHUNK = 0x02

# Video frame header: kind u8, seq u32, mon_w u16, mon_h u16, cursor_x i16, cursor_y i16,
# monitor u8, quality u8, flags u8  -> 16 bytes, big endian. If flags & VIDEO_FLAG_REGION,
# an 8-byte region (x, y, w, h u16, monitor pixels) follows. Then the JPEG bytes.
VIDEO_HEADER = struct.Struct(">BIHHhhBBB")
VIDEO_REGION = struct.Struct(">HHHH")
VIDEO_FLAG_REGION = 0x01
assert VIDEO_HEADER.size == 16

# File chunk header: kind u8, transfer id u32 -> followed by raw bytes.
FILE_CHUNK_HEADER = struct.Struct(">BI")

# Messages a client may send before it is authenticated.
PRE_AUTH_TYPES = {"hello", "pair"}

# Every message type a client may send once authenticated, with required fields.
CLIENT_MESSAGES: dict[str, dict[str, type | tuple[type, ...]]] = {
    "ping": {},
    "get_state": {},
    "mouse_move": {"dx": (int, float), "dy": (int, float)},
    "mouse_abs": {"x": (int, float), "y": (int, float), "monitor": int},
    "mouse_button": {"button": str, "action": str},
    "scroll": {"dx": (int, float), "dy": (int, float)},
    "key": {"key": str},
    "text": {"text": str},
    "shortcut": {"name": str},
    "media": {"action": str},
    "volume_set": {"level": (int, float)},
    "volume_step": {"delta": (int, float)},
    "mute": {},
    "power": {"action": str},
    "launch": {"id": str},
    "clipboard_push": {"text": str},
    "clipboard_pull": {},
    "open_url": {"url": str},
    "file_begin": {"id": int, "name": str, "size": int},
    "file_end": {"id": int},
    "file_cancel": {"id": int},
    "stream_start": {"monitor": int},
    "stream_region": {},
    "stream_stop": {},
    "frame_ack": {"seq": int},
}

MOUSE_BUTTONS = {"left", "right", "middle"}
MOUSE_ACTIONS = {"click", "double", "down", "up"}
KEY_ACTIONS = {"press", "down", "up"}
MODIFIERS = {"ctrl", "alt", "shift", "win"}
MEDIA_ACTIONS = {"play_pause", "next", "prev", "stop"}
POWER_ACTIONS = {"lock", "sleep", "restart", "shutdown"}
# Power actions that the client must explicitly confirm.
DESTRUCTIVE_POWER = {"sleep", "restart", "shutdown"}
SHORTCUTS = {
    "copy", "paste", "cut", "undo", "redo", "select_all", "alt_tab", "screenshot",
    "show_desktop", "task_view", "app_next", "app_prev", "close_window", "find", "save",
    "new_tab", "close_tab", "refresh",
}


class ProtocolError(ValueError):
    """Raised for malformed or invalid client messages."""


def encode(msg_type: str, **fields: Any) -> str:
    return json.dumps({"t": msg_type, **fields}, separators=(",", ":"), ensure_ascii=False)


def decode(raw: str | bytes) -> dict[str, Any]:
    try:
        msg = json.loads(raw)
    except (ValueError, UnicodeDecodeError) as e:
        raise ProtocolError(f"invalid JSON: {e}") from None
    if not isinstance(msg, dict) or not isinstance(msg.get("t"), str):
        raise ProtocolError("message must be an object with a string 't'")
    return msg


def validate(msg: dict[str, Any]) -> dict[str, Any]:
    """Check an authenticated client message's type, required fields and enum values."""
    t = msg["t"]
    spec = CLIENT_MESSAGES.get(t)
    if spec is None:
        raise ProtocolError(f"unknown message type {t!r}")
    for field, typ in spec.items():
        value = msg.get(field)
        # bool is an int subclass; never accept it for numeric fields.
        if value is None or isinstance(value, bool) or not isinstance(value, typ):
            raise ProtocolError(f"{t}: field {field!r} missing or wrong type")
    if t == "mouse_button":
        if msg["button"] not in MOUSE_BUTTONS or msg["action"] not in MOUSE_ACTIONS:
            raise ProtocolError("mouse_button: bad button/action")
    elif t == "key":
        if msg.get("action", "press") not in KEY_ACTIONS:
            raise ProtocolError("key: bad action")
        mods = msg.get("mods", [])
        if not isinstance(mods, list) or not set(mods) <= MODIFIERS:
            raise ProtocolError("key: bad mods")
    elif t == "shortcut" and msg["name"] not in SHORTCUTS:
        raise ProtocolError(f"unknown shortcut {msg['name']!r}")
    elif t == "media" and msg["action"] not in MEDIA_ACTIONS:
        raise ProtocolError("media: bad action")
    elif t == "power" and msg["action"] not in POWER_ACTIONS:
        raise ProtocolError("power: bad action")
    elif t == "text" and len(msg["text"]) > 10_000:
        raise ProtocolError("text too long")
    elif t == "stream_region" and any(k in msg for k in ("x", "y", "w", "h")):
        vals = [msg.get(k) for k in ("x", "y", "w", "h")]
        if not all(isinstance(v, int) and not isinstance(v, bool) and v >= 0 for v in vals) \
                or vals[2] < 16 or vals[3] < 16:
            raise ProtocolError("stream_region: x, y, w, h must be ints (w, h >= 16)")
    elif t == "clipboard_push" and len(msg["text"]) > 1_000_000:
        raise ProtocolError("clipboard text too long")
    return msg


@dataclass(frozen=True)
class VideoFrame:
    seq: int
    mon_w: int
    mon_h: int
    cursor_x: int
    cursor_y: int
    monitor: int
    quality: int
    jpeg: bytes
    region: tuple[int, int, int, int] | None = None  # (x, y, w, h) in monitor px; None = whole monitor


def pack_video(f: VideoFrame) -> bytes:
    flags = VIDEO_FLAG_REGION if f.region else 0
    head = VIDEO_HEADER.pack(BIN_VIDEO, f.seq & 0xFFFFFFFF, f.mon_w, f.mon_h,
                             _clamp_i16(f.cursor_x), _clamp_i16(f.cursor_y),
                             f.monitor, f.quality, flags)
    if f.region:
        head += VIDEO_REGION.pack(*f.region)
    return head + f.jpeg


def unpack_video(data: bytes) -> VideoFrame:
    if len(data) < VIDEO_HEADER.size or data[0] != BIN_VIDEO:
        raise ProtocolError("not a video frame")
    _, seq, w, h, cx, cy, mon, q, flags = VIDEO_HEADER.unpack_from(data)
    off, region = VIDEO_HEADER.size, None
    if flags & VIDEO_FLAG_REGION:
        if len(data) < off + VIDEO_REGION.size:
            raise ProtocolError("truncated video region")
        region = VIDEO_REGION.unpack_from(data, off)
        off += VIDEO_REGION.size
    return VideoFrame(seq, w, h, cx, cy, mon, q, data[off:], region)


def pack_file_chunk(transfer_id: int, payload: bytes) -> bytes:
    return FILE_CHUNK_HEADER.pack(BIN_FILE_CHUNK, transfer_id) + payload


def unpack_file_chunk(data: bytes) -> tuple[int, bytes]:
    if len(data) < FILE_CHUNK_HEADER.size or data[0] != BIN_FILE_CHUNK:
        raise ProtocolError("not a file chunk")
    _, tid = FILE_CHUNK_HEADER.unpack_from(data)
    return tid, data[FILE_CHUNK_HEADER.size:]


def _clamp_i16(v: int) -> int:
    return max(-32768, min(32767, int(v)))
