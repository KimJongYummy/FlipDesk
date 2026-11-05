# FlipDesk

Control your Windows PC from your phone: trackpad, keyboard, a live view of the screen,
media and volume, power and launchers, clipboard, and share-to-PC. It's built for the
Galaxy Z Flip8, with a Flex Mode "mini laptop" layout, a cover-screen remote, and a big-screen
mode for DeX and HDMI. It works on any Android 12+ phone.

Everything stays on your home network. There is no cloud, no account, no ads and no analytics.
Phone and PC talk over a WebSocket encrypted with TLS. The phone pins the PC's self-signed
certificate, and every message must carry a device token the PC issued during pairing.

> **Made with Claude.** This project was designed and written with
> [Claude Code](https://claude.com/claude-code), Anthropic's AI coding assistant, working from
> my feature requests and testing on my own phone and PC. It's a personal project, shared as-is
> and open source. Security is "good for a home network", not audited, so harden it if you need
> more. Issues and pull requests are welcome.

There are two parts, and you need both:

| Part | Runs on | What it does |
|---|---|---|
| **FlipDesk server** | your Windows 10/11 PC | Sits in the system tray, shares the screen, and moves the mouse and keyboard when the phone asks |
| **FlipDesk app** | your Android phone | The remote: trackpad, keyboard, screen view, media and more |

The phone and PC must be on the **same home Wi-Fi or network**.

---

## Quick start (about 5 minutes)

Download the files from the [**Releases page**](../../releases/latest).

### Part 1: the PC

1. Download **`FlipDesk.exe`** and **`allow-firewall.ps1`** from the latest release.
2. **Open the firewall for home networks:** right-click `allow-firewall.ps1` → **Run with
   PowerShell** → approve the admin prompt.
   * If it asks to switch your network to **Private**, say **y** for your home network.
   * The rules only allow FlipDesk on Private networks, never public Wi-Fi.
3. **Run `FlipDesk.exe`.**
   * Windows may say *"Windows protected your PC"*, because the exe isn't code-signed. Click
     **More info → Run anyway**.
   * A FlipDesk icon appears in the system tray near the clock. You may need to click **^** to
     see it.
   * A pairing window with a **QR code** opens on the first run. Keep it open for Part 2.
   * It turns on **Start with Windows** by itself, so you only do this once. You can untick it
     in the tray menu.

Prefer running from source? See [Running the server from source](#running-the-server-from-source).

### Part 2: the phone

1. On the phone, open this repo's [Releases page](../../releases/latest) and download
   **`FlipDesk.apk`**.
2. Open the downloaded file. Android asks to allow installs from your browser: allow it, then
   tap **Install**.
3. Open **FlipDesk** and allow:
   * **Nearby devices / local network**, to find and talk to your PC.
   * **Notifications**, for the connection status and media controls.
   * **Camera**, only for scanning the pairing QR code.
4. Tap **Scan QR** and point the camera at the code on the PC. You're connected.
   * If there's no QR window on the PC, open it from the tray icon → **Show pairing code…**
   * No camera? Tap **Enter PIN** and type the 6-digit code shown under the QR instead.
5. When FlipDesk asks to **run in the background**, allow it. Otherwise One UI / Android puts
   it to sleep and the connection drops.

That's it. From now on the phone reconnects on its own whenever you're home.

Not connecting? See [Troubleshooting](#troubleshooting). The usual cause is the network being
set to Public on the PC, or a guest Wi-Fi network.

---

## Running the server from source

1. Install **Python 3.11 or newer** from python.org.
2. Double-click **`server\Start FlipDesk.bat`**.
   * The first run creates `server\.venv` and installs the dependencies (about a minute).
   * After that, FlipDesk runs in the **system tray** (the rounded icon near the clock).
     Grey means waiting, green means a phone is connected, and amber means pairing is open.
3. The tray menu has:
   * **Show pairing code…**
   * **Paired devices** (forget a phone)
   * **Edit launchers…**
   * **Open data folder**
   * **Start with Windows** (a checkbox)
   * **Quit**

Other ways to run it:

* `server\Start FlipDesk (console).bat` runs the server in a console window with live logs.
  Add `--dry-run` to log input instead of injecting it.
* **Standalone exe (optional):** run `server\build-exe.bat` to build `server\dist\FlipDesk.exe`
  with PyInstaller. Copy the exe anywhere; it doesn't need Python.

Settings, the certificate, the paired devices and `launchers.json` live in
`%APPDATA%\FlipDesk`. The log is in `flipdesk.log` in the same folder.

### Allow it through the firewall (private networks only)

The phone connects to TCP **8765**, and discovery uses UDP **8766**. Right-click
**`server\allow-firewall.ps1` → Run with PowerShell** and approve the admin prompt. The
script adds rules for the **Private** profile only.

Also make sure Windows treats your home Wi-Fi or Ethernet as **Private**:
Settings → Network & internet → (your connection) → Network profile type → **Private network**.

If Windows shows its own "Allow access?" popup the first time, tick **Private networks** only.

### Launchers

Edit `launchers.json` (tray → Edit launchers…). Changes apply the next time the phone
connects:

```json
{"launchers": [
  {"id": "spotify", "name": "Spotify", "type": "app", "target": "spotify:", "icon": "play"},
  {"id": "mail", "name": "Gmail", "type": "url", "target": "https://mail.google.com", "icon": "web"},
  {"id": "code", "name": "VS Code", "type": "app", "target": "C:\\Users\\me\\AppData\\Local\\Programs\\Microsoft VS Code\\Code.exe"},
  {"id": "backup", "name": "Backup", "type": "command", "target": "robocopy C:\\Docs D:\\Docs /MIR"}
]}
```

* `type` is one of:
  * `app`: an exe, a `.lnk` shortcut, or a URI such as `ms-settings:`
  * `url`: opens in the default browser
  * `command`: runs via `cmd`
* `icon` is optional. The choices are `web`, `play`, `folder`, `note`, `calc`, `monitor`,
  `settings` and `terminal`.

The phone can only ask the PC to run a launcher by its `id`. It can never send a command line.

---

## Pairing in detail

1. On the PC, open the tray menu → **Show pairing code…** This also opens automatically the
   first time the server runs. The window shows a QR code and a 6-digit PIN, valid for
   5 minutes.
2. On the phone, open FlipDesk → **Scan QR** and point the camera at the code. That's all.
3. **PIN fallback:** tap **Enter PIN**. FlipDesk searches the Wi-Fi for computers. Pick
   yours, check that the **security code** on the phone matches the one under the PIN on
   the PC, then type the PIN.

The phone stores a long-lived token and the PC's certificate fingerprint. From then on it
reconnects on its own, with backoff, when Wi-Fi comes back, when you reopen the app, or when
the PC's IP address changes (it finds the PC again with a UDP broadcast). Any device that
hasn't paired is refused.

---

## Using it

**Bottom navigation:** Trackpad · Keys · Screen · Media · Launch. Settings is the ⚙ in the
top bar.

| Trackpad gesture | Action |
|---|---|
| 1-finger move | pointer, with acceleration (speed slider under the pad and in Settings) |
| tap / 2-finger tap / 3-finger tap | left / right / middle click |
| quick double-tap | double-click |
| double-tap and hold (or tap, then touch and move) | drag |
| 2-finger drag | smooth scroll, vertical and horizontal |
| 2-finger pinch | zoom (Ctrl + wheel on the PC) |
| 3-finger swipe up / down | Task View / show desktop |
| 3-finger swipe left / right | switch apps (Alt+Tab / Alt+Shift+Tab) |
| hold the **air** button and tilt | gyroscope air mouse |

* **Keys:** whatever you type streams live, including backspace and Enter.
  * Ctrl/Alt/Shift/Win are sticky: tap once for the next key only, tap twice to lock,
    tap again to release. With Ctrl latched, typing `c` sends Ctrl+C.
  * You also get Esc, Tab, arrows, Home/End/PgUp/PgDn and F1–F12.
  * Shortcut buttons: copy, paste, undo, redo, Alt-Tab, snip (screenshot), show desktop,
    Task View.
  * Clipboard buttons: Phone → PC and PC → Phone.
* **Screen:** the live desktop as JPEG frames, downscaled to fit the phone.
  * Quality and frame rate adapt to the link, and frames are skipped when nothing changed.
  * Pinch to zoom and drag with two fingers to pan. When zoomed in, the view follows the
    cursor.
  * The toolbar picks the monitor (1/3, 2/3…) and switches the input mode:
    * **Touch:** tap = click there, long-press = right-click, drag = drag.
    * **Pad:** the stream acts as a trackpad.
* **Media:** play/pause, next and previous, the system volume slider and mute. Shows what's
  playing on the PC (from Windows' media session: Spotify, browsers, etc.).
* **Launch:** lock, sleep, restart and shut down (the last three ask first), "Send files to
  PC", and your launcher grid.
* **Share to PC:** share anything to **Send to PC** in Android's share sheet:
  * Links open in the PC's browser.
  * Other text goes to the PC clipboard.
  * Files and photos are saved to the PC's **Downloads** folder.
* **Volume keys** control the PC volume while the app is open and connected. You can turn
  this off in Settings.

### Flip8 layouts (automatic)

* **Flex Mode / laptop:** half-fold the phone so the hinge is horizontal. The top half
  becomes the live screen. The bottom half becomes a trackpad with a slim key row
  (Esc, Tab, Ctrl/Alt/Shift/Win, a keyboard toggle, arrows). No touch targets sit over the
  hinge, and the layout animates between postures.
  * **Zoom:** entering Flex Mode glides from the whole desktop into a zoomed view centered
    on the cursor (your last zoom level, 2.5× at first). Unfolding glides back out.
  * **Follow:** the view follows the cursor with eased motion. The cursor moves freely in
    the middle of the view, and the view only pans once it nears an edge.
  * **Top-half gestures:** pinch to zoom (1× to 4×), drag to look around, double-tap to toggle
    between the full desktop and your last zoom level. The top half doesn't click; use the
    trackpad below.
  * **Sharpness:** while zoomed, the PC sends only the visible area (plus a margin) at native
    resolution. Until a new area arrives, the previous frame stays on screen scaled, so
    nothing blurs or blanks out.
  * **Overlays:** a "2.5×" indicator fades in on changes. A mini-map (bottom-left; toggle with
    the map icon or in Settings) shows where you are on the desktop.
* **Fully open, portrait:** a live preview at the top (it can be turned off in Settings),
  controls below, and the navigation bar at your thumb.
* **Landscape:** a split layout, with the live screen on the left and controls on the right.
* **Cover screen:** a compact remote with two swipeable pages. The first has now playing,
  big media buttons, volume and Lock PC. The second has a mini trackpad with left/right
  buttons. It is padded clear of the rounded corners and camera cutouts.
* **Fold-close action:** Settings → *When I close the phone* → Nothing / Pause media /
  Lock PC.
* The screen stays awake on the trackpad, screen and laptop layouts. You can turn this off
  in Settings.

The connection lives in a foreground service, not in the screen. Folding, unfolding,
rotating or moving to the cover screen never drops it.

### Lock screen, notification shade and cover-screen media controls

While connected, FlipDesk mirrors the PC's playback into an Android media session. The
PC's track and its play, pause and skip buttons appear in the notification shade, on the
lock screen, and in the cover screen's media controls, even when the app is closed.
**Disconnect** is on the same notification.

### Quick Settings tile and widgets

* **Tile:** pull down Quick Settings → edit (pencil) → drag **FlipDesk** in. Tap it to
  connect or disconnect.
* **Home-screen widget "FlipDesk remote":** now playing, volume −/+, play/pause, lock PC.
* **Cover-screen widget "FlipDesk cover remote":** a 352×339 dp layout with big buttons.
  It declares Samsung's FlexWindow widget metadata (`display="sub_screen"`, keyguard
  category) as described in Samsung's Flex Window widget docs.

### Adding FlipDesk to the cover screen on One UI 9

I couldn't test these steps on a real Flip8. Samsung moves and renames these menus between
One UI versions, so names may differ slightly.

1. **Widget:** Settings → **Cover screen** → **Widgets** → turn on **FlipDesk cover remote**,
   then reorder it if you like. Swipe across the cover screen to reach it.
2. **Full app on the cover screen:** Settings → **Advanced features** → **Labs** →
   **Apps allowed on cover screen** → turn on **FlipDesk**.
   * Then open it from the cover screen's apps widget. FlipDesk switches to its cover
     layout automatically.
   * If FlipDesk isn't listed there, install **Good Lock** from the Galaxy Store, then go to
     **MultiStar** → *I ♡ Galaxy Foldable* → **Launcher Widget**, and add FlipDesk.
3. Media controls on the cover screen need no setup. They come from the media session
   while FlipDesk is connected.
4. If the cover layout ever doesn't appear on its own, use Settings → Layout →
   *Always cover remote*.

### Big screen: Samsung DeX, HDMI or Smart View

Plug the phone into a monitor or TV (USB-C to HDMI, a USB-C monitor, or DeX), or cast with
Smart View, while FlipDesk is open. The PC screen opens **full screen on the big display**
and the phone turns into its laptop deck: a big trackpad with Left/Right buttons, a key row
(Esc, Tab, Ctrl, Alt, Shift, Win, Keys) and arrow keys. Half-fold the phone and the keys move
to the top half and the trackpad to the bottom half.

- **Mouse and keyboard on the big screen work too.** A Bluetooth or USB mouse moves the PC
  cursor exactly where you point, with left/right/middle buttons and the wheel. A physical
  keyboard types on the PC, including shortcuts (Ctrl+C, Alt+Tab, Shift+arrows).
- **On the phone:** the TV icon in the top bar of the deck stops the big screen, and the grid
  icon switches to the normal tabs (Media, Keys, Launch). Tap the TV icon in the top bar to
  get back to the deck, or to put the PC on the big screen again after stopping it.
- In DeX, FlipDesk asks to go full screen. If it opens as a window instead, maximize it.
  Opening FlipDesk from the DeX taskbar also puts the PC there.
- Unplugging closes the big screen, and the phone goes back to its normal layout.
- Turn it off in **Settings → PC on external display**.
- The stream gets sharper (up to 1920 px on Auto) while it's on the big screen. Use
  **Settings → Quality → Sharp** for 2560 px on a fast Wi-Fi network.

### Staying connected at home

* **On the PC:** FlipDesk turns on **Start with Windows** the first time it runs (untick it
  in the tray menu if you don't want that). If you start it twice, it tells you it's
  already running.
* **On the phone:**
  * The connection lives in a background service with a small ongoing notification.
  * It reconnects by itself after Wi-Fi drops, the PC restarts or wakes, the phone sleeps,
    the phone reboots, or the app updates. While you're home it retries at least every
    10 seconds.
  * Opening the app reconnects immediately and checks that the link is still alive.
* **Away from home:** when you leave Wi-Fi it goes idle ("Not on Wi-Fi") and uses no data or
  battery. It reconnects automatically when you're back on your Wi-Fi.
* **Disconnect** (notification, tile or Settings) means off: nothing reconnects until you
  open the app or tap Connect.
* **Allow background use:** after pairing, FlipDesk asks once to be allowed to run in the
  background. Allow it. One UI otherwise puts "unused" apps to sleep, which drops the
  connection. You can check it in Settings → Paired computer → **Stay connected in the
  background**, or set it by hand: Android Settings → Apps → FlipDesk → Battery → **Unrestricted**.

---

## Troubleshooting

* **"PC not reachable":**
  * Check that the phone and PC are on the same Wi-Fi, the server is running (look for the
    tray icon), the firewall rule is in place, and Windows treats the network as Private.
  * Guest Wi-Fi networks and "AP isolation" block device-to-device traffic.
* **Discovery finds nothing, but pairing by QR works:** your router blocks broadcasts. The
  QR code includes the PC's IP addresses, so normal reconnects still work.
* **"Security check failed":** the certificate doesn't match. This can mean you deleted
  `%APPDATA%\FlipDesk` (which creates a new certificate) or something is intercepting the
  connection. Pair again.
* **"Pairing lost":** the phone was removed from the PC's Paired devices. Pair again.
* **Mouse feels too fast or slow:** Settings → Pointer speed / Acceleration. FlipDesk
  positions the cursor itself, so your Windows pointer-speed setting doesn't stack on top.
* **Can't control admin windows (Task Manager, UAC prompts):** Windows blocks input from
  normal apps into elevated windows. Run the server as administrator if you need that.

---

## Building it yourself

```bat
:: server tests (protocol, pairing, end-to-end with a fake phone, dry-run input)
cd server
.venv\Scripts\python -m pytest -q
:: the one real 3-pixel mouse nudge (moves the cursor and puts it back)
set FLIPDESK_REAL_INPUT=1 && .venv\Scripts\python -m pytest -q tests\test_real_input.py

:: Android: unit tests, lint, signed release APK
cd android
gradlew testDebugUnitTest lintRelease assembleRelease
copy app\build\outputs\apk\release\app-release.apk ..\dist\FlipDesk.apk
```

* **Interop test:** runs the app's own Kotlin TLS pinning and protocol code against the
  real Python server. Start `python server\tests\interop_server.py %TEMP%\fd` and run
  `gradlew testDebugUnitTest --tests *InteropTest` with `FLIPDESK_INTEROP_DIR=%TEMP%\fd`.
* **Fake phone:** `python -m flipdesk.testclient --qr "flipdesk://pair?..."` pairs and
  sends a few commands. `--discover` lists servers on the LAN.
* **Protocol:** [docs/PROTOCOL.md](docs/PROTOCOL.md). JSON control messages and binary
  video/file frames.

### Signing your own APK

Release builds are signed with a key in `keystore/`, which is git-ignored and not in this repo.
To make your own:

```bat
keytool -genkeypair -v -keystore keystore\flipdesk-release.jks -alias flipdesk -keyalg RSA -keysize 4096 -validity 36500
```

Then create `keystore\keystore.properties`:

```properties
storeFile=flipdesk-release.jks
storePassword=your-password
keyAlias=flipdesk
keyPassword=your-password
```

Without it, `assembleRelease` gives an unsigned APK. Use `gradlew assembleDebug` for a quick
test build instead. Back up your key: Android only installs an update over an existing app
signed with the same key. An APK you sign yourself can't update one installed from this repo's
Releases (uninstall first, then pair again).

Building the app needs JDK 21 and the Android SDK (Android Studio installs both). The server
needs Python 3.11 or newer.

---

## License

MIT. See [LICENSE](LICENSE).
