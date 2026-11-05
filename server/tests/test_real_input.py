"""One tiny real mouse nudge to prove SendInput works. Opt-in: set FLIPDESK_REAL_INPUT=1."""
import os
import sys
import time

import pytest

pytestmark = [
    pytest.mark.real_input,
    pytest.mark.skipif(sys.platform != "win32" or os.environ.get("FLIPDESK_REAL_INPUT") != "1",
                       reason="real input test is opt-in (FLIPDESK_REAL_INPUT=1)"),
]


def test_real_mouse_nudge():
    from flipdesk.backend.windows import WindowsBackend
    b = WindowsBackend()
    x0, y0 = b.cursor_pos()
    b.mouse_abs(x0 + 3, y0)   # absolute path (direct-touch mode)
    time.sleep(0.05)
    x1, _ = b.cursor_pos()
    b.mouse_move(-3, 0)       # relative path (trackpad mode)
    time.sleep(0.05)
    x2, y2 = b.cursor_pos()
    b.mouse_abs(x0, y0)       # leave the cursor exactly where it was
    time.sleep(0.05)
    assert x1 == x0 + 3, "absolute SendInput did not land on the target pixel"
    assert (x2, y2) == (x0, y0), "relative move did not move exactly 3 px back"
    assert b.cursor_pos() == (x0, y0)
