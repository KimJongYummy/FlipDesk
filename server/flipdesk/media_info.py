"""Now-playing info and transport control via Windows' System Media Transport Controls."""
from __future__ import annotations

import logging
import sys
from typing import Any

log = logging.getLogger(__name__)

_PLAYING = 4  # GlobalSystemMediaTransportControlsSessionPlaybackStatus.PLAYING


class NowPlaying:
    """All methods are coroutines meant to run on the server's event loop."""

    def __init__(self, enabled: bool = True, read_only: bool = False) -> None:
        self._manager = None
        self.read_only = read_only
        self.available = False
        if enabled and sys.platform == "win32":
            try:
                from winrt.windows.media.control import \
                    GlobalSystemMediaTransportControlsSessionManager as Manager
                self._manager_cls = Manager
                self.available = True
            except Exception as e:  # winrt missing or broken
                log.info("now-playing unavailable: %s", e)

    async def _session(self):
        if not self.available:
            return None
        try:
            if self._manager is None:
                self._manager = await self._manager_cls.request_async()
            return self._manager.get_current_session()
        except Exception as e:
            log.debug("media session query failed: %s", e)
            self._manager = None
            return None

    async def snapshot(self) -> dict[str, Any] | None:
        s = await self._session()
        if s is None:
            return None
        try:
            props = await s.try_get_media_properties_async()
            info = s.get_playback_info()
            timeline = s.get_timeline_properties()
            return {
                "title": props.title or "",
                "artist": props.artist or props.album_artist or "",
                "album": props.album_title or "",
                "app": s.source_app_user_model_id or "",
                "playing": int(info.playback_status) == _PLAYING,
                "duration_ms": int(timeline.end_time.total_seconds() * 1000),
                "position_ms": int(timeline.position.total_seconds() * 1000),
            }
        except Exception as e:
            log.debug("media properties failed: %s", e)
            return None

    async def control(self, action: str) -> bool:
        """Returns True if the session handled it; False means fall back to media keys."""
        if self.read_only:
            return False
        s = await self._session()
        if s is None:
            return False
        try:
            op = {
                "play_pause": s.try_toggle_play_pause_async,
                "next": s.try_skip_next_async,
                "prev": s.try_skip_previous_async,
                "stop": s.try_stop_async,
            }[action]
            return bool(await op())
        except Exception as e:
            log.debug("media control failed: %s", e)
            return False
