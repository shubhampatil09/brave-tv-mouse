# Brave on Android TV with a phone as the mouse

Three APKs, all in `out\`:

| APK | Installs on | What it is |
|---|---|---|
| `brave-tv-x86.apk` | TV (x86 emulator) | Stock Brave 1.102.3 with a TV launcher entry. Package `com.brave.browser_nightly`. |
| `tv-cursor.apk` | TV | "TV Mouse Receiver": draws a pointer over every app and performs clicks, scrolling, Back, Home and text entry. Package `com.tvmouse.receiver`. |
| `phone-mouse.apk` | Phone | "TV Mouse": a touchpad that drives the pointer. Package `com.tvmouse.remote`. |

## Build

Needs Java, Python 3 and the Android SDK (build-tools 35.0.0, platform android-35). No Gradle.

```powershell
.\build-app.ps1 tv-cursor
.\build-app.ps1 phone-mouse
.\build-brave-tv.ps1          # uses brave\stock\BraveMonox86-1.102.3.apk
```

For a real ARM TV, download `BraveMonoarm.apk` from the same Brave GitHub release and run
`.\build-brave-tv.ps1 -Stock <path> -Name brave-tv-arm`. That build has not been made or tested.

### How Brave is repackaged

`brave\patch_tv_manifest.py` edits only the binary `AndroidManifest.xml` and copies every other
APK entry unchanged (the APK is never extracted, because Brave has resource files whose names
differ only by case). It adds:

- `<uses-feature android:name="android.software.leanback" android:required="false"/>`
- `<category android:name="android.intent.category.LEANBACK_LAUNCHER"/>` on the main launcher entry

`android.hardware.touchscreen` is already `required="false"` in stock Brave. No Brave code is
changed, so the "only the first site loads" defect of the earlier 1.100.5 build does not apply.
The APK is debug-signed, so it cannot update a store-installed Brave.

## Install and pair

On the TV:

```powershell
adb -s <tv> install -r out\brave-tv-x86.apk
adb -s <tv> install -r out\tv-cursor.apk
```

Then turn on the cursor service: open "TV Mouse Receiver" on the TV, choose "Open Accessibility
settings" and enable "TV Mouse cursor". Over adb the same thing is:

```powershell
adb -s <tv> shell appops set com.tvmouse.receiver ACCESS_RESTRICTED_SETTINGS allow
adb -s <tv> shell settings put secure enabled_accessibility_services com.tvmouse.receiver/com.tvmouse.receiver.CursorService
adb -s <tv> shell settings put secure accessibility_enabled 1
```

On the phone: `adb -s <phone> install -r out\phone-mouse.apk`, open "TV Mouse", then either tap
**Find TV** (same Wi-Fi network) or type the address shown on the TV's "TV Mouse Receiver" screen
and tap **Connect**. The address is remembered.

TV emulator on this PC with a phone on USB (what the test used):

```powershell
adb -s emulator-5554 forward tcp:8765 tcp:8765
adb -s <phone> reverse tcp:8765 tcp:8765
```

and connect the phone app to `127.0.0.1`.

## Real TV (Mi TV MOOR2, Android 11)

Built and installed on 13 Nov 2026: `outrave-tv-arm.apk` (stock `BraveMonoarm.apk` 1.102.3,
same manifest patch) and `out	v-cursor.apk`. The cursor service was added to the TV's
accessibility list next to the two `com.gathin.tvhome` services already there.

This TV reports no touchscreen, and Android then cancels every touch gesture an accessibility
service tries to inject. The cursor service therefore has three modes and picks one itself
(shown on the "TV Mouse Receiver" screen):

| Mode | When | How it clicks |
|---|---|---|
| Touch gestures | Device has touch support (the emulator) | Injected taps and drags |
| Virtual mouse | No touch support, and the TV's own adb is reachable | A virtual USB mouse made with the system `hid` tool: real pointer, real clicks, wheel scrolling |
| Element clicks | No touch support and no adb | Clicks the on-screen element under the pointer. Many web pages (YouTube) ignore these. |

Virtual mouse mode needs shell rights, which an app does not have. The service gets them by
connecting to the TV's own adb daemon at `127.0.0.1:5555` with its own key (`AdbStream.java`)
and running `hid -` there. For that:

1. "ADB debugging" must stay on in the TV's Developer options (it already is; port 5555).
2. The first time, the TV shows "Allow USB debugging?" for the app's key. Tick
   **Always allow from this computer** and choose **Allow** with the TV remote. This is asked once.

If the prompt is dismissed, the service asks again every couple of minutes and uses element
clicks meanwhile.

Nothing on the PC is needed afterwards: the phone talks to the TV directly over Wi-Fi, the
service starts with the TV, and the phone app reconnects by itself (saved address first, then
discovery, so a changed TV address is handled).

### Video start time on the TV

`testideo-start.ps1` opens a YouTube watch page in Brave on the TV and reports the time to
the hardware decoder being created and to the first decoded frame. Measured 13 Nov 2026,
1080p VP9, Wi-Fi link 780 Mbps:

| Cursor service | First frame after opening the page (4 runs each, first "on" run was right after a cold start) |
|---|---|
| On | 7.8 s, 4.7 s, 5.3 s (and 17.0 s for the cold-start warm-up) |
| Off | 5.8 s, 6.3 s, 5.4 s, 7.7 s |

The cursor service makes no measurable difference. During the load the kernel's memory
reclaim thread (`kswapd`) uses 45-70% of a core, the low-memory killer fires, and the new page's
renderer is pinned at over 100% CPU: the TV (4 cores at 1.5 GHz, 1.8 GB RAM, about 450 MB
available) is short on memory and CPU for the mobile YouTube page. Once started, playback is a
steady 25 fps through the hardware decoder.

### Hotstar (JioHotstar) on the TV

The mobile site only shows "Download the App". Tick **Desktop site** in Brave's menu (three
dots, scroll down) while on hotstar.com; the full catalogue then loads and titles play in the
browser. Checked 13 Nov 2026: Brave's media session reported an episode playing and the
hardware decoder ran at 1280x720, about 24 frames a second. Screenshots come back empty while
it plays, so the picture itself was not captured (`tv-14-hotstar-desktop.png` shows the
catalogue before playback).

## Using the phone app

- Drag one finger: move the pointer. Tap: click. Hold without moving: long press.
- Two fingers, or one finger on the strip along the right edge: scroll.
- **Back**, **Home**, **Click** buttons.
- Text box + **Send**: types into whatever field has focus on the TV (replaces a selection, which
  is how the address bar behaves after a click). **Enter** submits, **Del** deletes one character.

Protocol: TCP 8765, one text command per line (`M dx dy`, `C`, `L`, `S dx dy`, `B`, `H`,
`T <base64>`, `E`, `D`); discovery on UDP 8766. There is no pairing code or encryption: anyone
on the same network can control the TV while the service is on.

## What was tested (13 Nov 2026)

Setup: `Television_720p` Android TV emulator (API 36, x86, 1280x720) started from Android Studio,
and a real Xiaomi 14 Civi (Android 16) on USB running the phone app. No phone emulator was used.
Every step below was performed by injecting touches into the phone app with `adb shell input`,
not by sending commands to the TV directly. Screenshots are in `evidence\`.

| Step | Result | Evidence |
|---|---|---|
| Brave installs, resolves for `LEANBACK_LAUNCHER`, launches | Pass | `01-brave-launched.png` |
| Phone connects to the TV service | Pass | `phone-01-connected.png` |
| Drag moves the pointer | Pass | `02-`, `03-` |
| Tap clicks the Brave address bar (keyboard opens) | Pass | `04-address-bar-clicked.png` |
| Typed URL is sent and submitted (`example.com`) | Pass | `05-`, `06-first-site-loaded.png` |
| Back closes the keyboard, then leaves the address bar | Pass | `07a-before-scroll.png` |
| Scroll (right-edge strip) | Pass | `07-page-scrolled.png` |
| Click a link; a second, different site loads (`iana.org`) | Pass | `08-`, `09-link-clicked-second-site.png` |
| Back returns to the first site | Pass | `10-back-to-first-site.png` |
| Typing over the selected URL loads a third site (Wikipedia) | Pass | `11-`, `12-third-site-loaded.png` |
| Long press opens the link menu | Pass | `13-long-press-menu.png` |
| Home | Pass | `14-home.png` |

On the real TV (same day), driven from the phone over Wi-Fi with no cable link:

| Step | Result |
|---|---|
| Brave 1.102.3 ARM installs over the old 1.100.5 build and starts | Pass (`tv-01-brave-launched.png`) |
| Phone finds the TV by discovery and connects (saved address was wrong on purpose) | Pass |
| Phone reconnects by itself after the TV service restarts | Pass |
| Touch-gesture clicks | Fail: cancelled by Android (no touch support). Led to the modes above. |
| Element click on Brave's own buttons (first-run screens) | Pass (`tv-02-onboarding-continue.png`) |
| Element click on YouTube's search box | Fail: the page ignores it |
| Virtual mouse created with `hid` from the PC's adb shell | Pass: the TV lists a mouse device "TV Mouse" |
| Virtual mouse created by the service itself, after "Allow USB debugging?" was accepted on the TV | Pass |
| Mouse pointer moves; click opens YouTube's search box | Pass (`tv-05-mouse-pointer.png`, `tv-06-youtube-search-clicked.png`) |
| Text typed on the phone goes into the YouTube search box; Enter runs the search | Pass (`tv-07-youtube-results.png`) |
| Wheel scrolling from the right-edge strip | Pass (`tv-08-scrolled.png`) |
| After a TV reboot: service starts, virtual mouse comes up with no prompt (about 30 s after boot), phone reconnects on first touch, pointer moves | Pass (`tv-09-after-reboot-pointer.png`) |

Not tested on the real TV: long press, Back and Home buttons in virtual mouse mode, video
playback, opening Brave from the home screen with the pointer, a phone restart, a change of the
TV's address.

Not tested:

- **Two-finger scroll**: adb cannot inject multi-touch on a stock phone. The right-edge strip
  uses the same scroll path and was tested instead.
- **Find TV (Wi-Fi discovery)** and a direct Wi-Fi connection: the emulator is not reachable on
  the LAN, so the phone was linked through adb port forwarding.
- **Del** and the **Click** button.
- **Brave's tile on the TV home screen**: the launcher intent resolves, but the Apps row was not
  opened to look at the tile. Brave has no TV banner image, so the tile will show its icon.
- Video playback, downloads, and Brave on API levels other than 36.

Notes: one stray click reached the TV during the run that the test script did not send (possibly
a touch on the phone); it was recovered with Back. `test\lib.ps1` has the helper functions used
to drive the phone and capture the TV; the device serials at its top are specific to this PC.
