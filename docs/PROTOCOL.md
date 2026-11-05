# FlipDesk protocol v1

One WebSocket over TLS (`wss://<pc-ip>:8765/`) per phone. The server uses a self-signed
certificate. The phone does not trust any CA. It **pins the SHA-256 fingerprint of the server's
DER certificate**, which it gets from the pairing QR code (or from discovery when pairing by PIN).

* **Text frames** hold JSON control messages: `{"t": "<type>", ...fields}`.
* **Binary frames** carry bulk data. The first byte says what kind of data it is:
  * `0x01` video frame (server → phone)
  * `0x02` file chunk (phone → server)

Integers in binary headers are big-endian.

## Discovery (UDP 8766)

The phone broadcasts `FLIPDESK?{"v":1}` to `255.255.255.255:8766`. Each server replies by unicast:

```json
{"t":"flipdesk","v":1,"id":"<server_id>","name":"DESKTOP-1","port":8765,"fp":"<sha256 hex>"}
```

The phone takes the reply whose `id` and `fp` match the PC it paired with, and uses the source
IP of that reply. A reply tells the phone only what the QR code already showed it. To get in,
the phone still needs its device token.

## Pairing

The tray's **Show pairing code** opens a 5-minute pairing window with two options:

* **QR code:** `flipdesk://pair?v=1&h=<ip>[,<ip>…]&p=<port>&fp=<sha256>&s=<secret>&id=<server_id>&n=<name>`
* **6-digit PIN:** used together with discovery, which supplies the IP, port and fingerprint. The
  window shows a short security code (the first 8 hex digits of the fingerprint). The phone shows
  the same code so you can compare them.

The first message after the TLS handshake must be `pair` or `hello`, sent within 10 s.
Any other first message closes the socket with code 4401.

| phone → server | fields |
|---|---|
| `pair` | `v`, `device_id` (8–128 chars, random per install), `device_name`, and either `secret` (from the QR) or `pin` |
| `hello` | `v`, `device_id`, `token` |

| server → phone | meaning |
|---|---|
| `paired` | `token` (long-lived device token; the server stores only its SHA-256), `server_id`, `server_name`. Followed by `auth_ok`. |
| `pair_fail` | `reason`, then the socket closes. After 5 wrong PINs the code is invalidated. |
| `auth_ok` | the full state snapshot (see `state`) |
| `auth_fail` | `reason`, then close 4401. The phone should offer to pair again. |

Secrets and PINs are single-use: a successful pairing consumes them.

## Control messages (phone → server, after auth)

| type | fields | notes |
|---|---|---|
| `ping` | `ts` | → `pong` {`ts`, `server_ts`} |
| `get_state` | | → `state` |
| `mouse_move` | `dx`, `dy` (px, float) | relative; the server keeps sub-pixel remainders |
| `mouse_abs` | `x`, `y` (0..1), `monitor` | direct-touch mode; coordinates are normalized to that monitor |
| `mouse_button` | `button` left/right/middle, `action` click/double/down/up | |
| `scroll` | `dx`, `dy` (wheel notches, float) | 1.0 = one notch; positive `dy` scrolls up, positive `dx` scrolls right |
| `key` | `key`, `action` press/down/up (default press), `mods` [ctrl,alt,shift,win] | key names: enter, backspace, tab, esc, space, arrows (`left`…), home, end, pageup, pagedown, insert, delete, f1–f12, printscreen, a–z, 0–9, or any single character |
| `text` | `text` (≤ 10 000 chars) | typed as Unicode; `\n` becomes Enter |
| `shortcut` | `name` | copy, paste, cut, undo, redo, select_all, find, save, new_tab, close_tab, refresh, alt_tab, app_next, app_prev, screenshot, show_desktop, task_view, close_window |
| `media` | `action` play_pause/next/prev/stop | uses the OS media session, falling back to media keys |
| `volume_set` | `level` 0..1 | |
| `volume_step` | `delta` (e.g. ±0.05) | |
| `mute` | optional `muted` bool | toggles when `muted` is omitted |
| `power` | `action` lock/sleep/restart/shutdown, `confirm` | sleep, restart and shutdown require `confirm: true` |
| `launch` | `id` | runs a launcher from the server's `launchers.json`. The phone can only pick by id, never send a command line. |
| `clipboard_push` | `text` | sets the PC clipboard |
| `clipboard_pull` | | → `clipboard` {`text`} |
| `open_url` | `url` | http(s) only; opens in the default browser |
| `file_begin` | `id` (u32), `name`, `size`, `mime` | → `file_ready` {`id`}. The file is saved to the PC's Downloads folder; the name is sanitized and never overwrites an existing file. |
| `file_end` | `id` | → `file_saved` {`id`, `name`, `path`}, or `error` if the size doesn't match |
| `file_cancel` | `id` | deletes the partial file |
| `stream_start` | `monitor`, optional `max_w`, `max_h`, `fps`, `quality` | → `stream_started` {`monitor`, `monitors`} |
| `stream_region` | `x`, `y`, `w`, `h` (monitor px, w/h ≥ 16), optional `max_w`, `max_h` | stream only this rectangle, fitted to `max_w × max_h` (default 2048; never upscaled, so a small region arrives at native resolution). Send it without coordinates to go back to the whole monitor. `stream_start` also resets it. |
| `stream_stop` | | |
| `frame_ack` | `seq` | send after a frame is decoded. This is the flow control (see below). |

Every message from a client is validated. An invalid message gets
`{"t":"error","msg":...}` back and the connection stays open.

## Server → phone pushes

| type | fields |
|---|---|
| `state` / `auth_ok` | `v`, `server_id`, `server_name`, `server_version`, `os`, `backend`, `monitors` [{index,x,y,w,h,primary,name}], `volume`, `muted`, `now_playing`, `launchers` [{id,name,type,target,icon}], `caps` |
| `volume` | `level`, `muted` (sent on change, polled every 1 s) |
| `now_playing` | `info`: {title, artist, album, app, playing, duration_ms, position_ms} or null |
| `cursor` | `x`, `y`, `monitor`. The cursor moved but the screen didn't change, so no frame was sent. (-1,-1) means the cursor is on another monitor. |
| `ok` | `for_t` plus context |
| `error` | `msg` |

## Video frames (binary `0x01`)

```
offset size field
0      1    0x01
1      4    seq (u32)
5      2    monitor width in px (u16)
7      2    monitor height in px (u16)
9      2    cursor x in monitor px (i16, -1 if off this monitor)
11     2    cursor y (i16)
13     1    monitor index
14     1    JPEG quality used
15     1    flags: bit 0 = region follows
16     8    (only if flags & 1) region x, y, w, h (u16 each, monitor px) that this JPEG covers
16/24  …    JPEG bytes (fitted to max_w × max_h)
```

Without a region, the JPEG covers the whole monitor. With one, it covers just that rectangle,
and the phone draws it at that position, on top of the last full frame.

**Flow control and adaptation.** The server keeps at most 2 unacknowledged frames in
flight. Once a second it looks at how long it was blocked waiting for acks and at the
smoothed round-trip time. If it was blocked a lot, it lowers the JPEG quality first and the
resolution scale second. When the link is idle, it raises them again. Unchanged frames (same
CRC of the downscaled pixels) are not sent; a `cursor` message goes out instead if only the
cursor moved.

## File chunks (binary `0x02`)

```
0  1  0x02
1  4  transfer id (u32, from file_begin)
5  …  payload (the phone uses 256 KiB chunks)
```
