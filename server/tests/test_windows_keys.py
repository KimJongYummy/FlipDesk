"""Which key events a `key` message turns into. Nothing is injected: SendInput is replaced."""
import sys

import pytest

pytestmark = pytest.mark.skipif(sys.platform != "win32", reason="Windows backend")


@pytest.fixture
def sent(monkeypatch):
    from flipdesk.backend import windows
    events = []
    monkeypatch.setattr(windows, "_send", lambda *inputs: events.extend(inputs))
    return windows, events


def keys(events):
    """(vk, is_up, is_unicode) for each event."""
    from flipdesk.backend.windows import KEYEVENTF_KEYUP, KEYEVENTF_UNICODE
    return [(e.ki.wVk, bool(e.ki.dwFlags & KEYEVENTF_KEYUP), bool(e.ki.dwFlags & KEYEVENTF_UNICODE)) for e in events]


def test_punctuation_shortcut_is_a_real_key(sent, monkeypatch):
    windows, events = sent
    monkeypatch.setattr(windows, "_char_vk", lambda ch: (0xBF, False))
    windows.WindowsBackend().key("/", mods=["ctrl"])
    ctrl = windows.VK["ctrl"]
    assert keys(events) == [(ctrl, False, False), (0xBF, False, False), (0xBF, True, False), (ctrl, True, False)]


def test_shifted_character_holds_shift_around_the_key(sent, monkeypatch):
    windows, events = sent
    monkeypatch.setattr(windows, "_char_vk", lambda ch: (0xBB, True))
    windows.WindowsBackend().key("+")
    shift = windows.VK["shift"]
    assert keys(events) == [(shift, False, False), (0xBB, False, False), (0xBB, True, False), (shift, True, False)]


def test_character_with_no_plain_key_falls_back_to_unicode(sent, monkeypatch):
    windows, events = sent
    monkeypatch.setattr(windows, "_char_vk", lambda ch: None)
    windows.WindowsBackend().key("é")
    assert keys(events) == [(0, False, True), (0, True, True)]
    assert events[0].ki.wScan == ord("é")


def test_char_vk_does_not_treat_names_or_astral_characters_as_keys():
    from flipdesk.backend.windows import _char_vk
    assert _char_vk("enter") is None
    assert _char_vk("😀") is None
