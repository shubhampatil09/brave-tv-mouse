# How to use Brave on the TV with the phone as a mouse

For build details and test results see `README.md`. This page is only about using it.

## What is installed

| Where | App | What it does |
|---|---|---|
| Mi TV | **Brave** | The browser. |
| Mi TV | **TV Mouse Receiver** | Runs in the background and gives the TV a mouse pointer. Starts by itself when the TV starts. |
| Phone (Xiaomi 14 Civi) | **TV Mouse** | The touchpad. |

The PC is not needed for everyday use. The phone and TV talk to each other over Wi-Fi, so both
must be on the same network.

## Every time you want to use it

1. Turn on the TV and wait about 30 seconds after the home screen appears. The mouse needs that
   long to become ready.
2. Open **Brave** on the TV with the TV remote (second row of the home screen).
3. Open **TV Mouse** on the phone. The status line should say `Connected: MiTV-MOOR2 1920x1080`.
   If it does not, touch the touchpad once, or tap **Find TV**.
4. Move a finger on the touchpad. A mouse pointer appears on the TV.

## The phone app

| Do this | Result on the TV |
|---|---|
| Drag one finger on the touchpad | Moves the pointer |
| Tap the touchpad once | Click (the pointer turns into a hand over things you can click) |
| Hold a finger still for about a second | Long press |
| Drag up or down on the darker strip along the right edge | Scroll |
| Drag with two fingers | Scroll |
| **Back** | Go back |
| **Home** | TV home screen |
| **Click** | Same as tapping the touchpad |

### Typing

1. On the TV, click the field you want to type in (address bar, search box).
2. On the phone, type in the box at the bottom ("Text to type on the TV") and tap **Send**.
3. Tap **Enter** to submit. **Del** removes one character.

The TV's own on-screen keyboard also opens; you can ignore it.

### Connection row (top of the phone app)

- **Find TV** searches the Wi-Fi network for the TV.
- **Connect** connects to the address typed in the box. The TV's address is shown in the
  **TV Mouse Receiver** app on the TV. You normally never need this: the app remembers the TV
  and looks for it again if the address changes.

## Websites

- **Open a site:** click Brave's address bar, type the address from the phone, tap **Enter**.
- **YouTube:** go to `youtube.com`. A video takes 5 to 8 seconds to start on this TV; after that
  it plays smoothly. The TV's own YouTube app starts faster.
- **Hotstar:** open the saved bookmark (below). If it shows "Download the App", open Brave's
  menu (three dots, top right), scroll down and tick **Desktop site**.
- **Bookmarks:** three dots, then **Bookmarks**, then **Mobile bookmarks**. To save the current
  page, open the three-dot menu and click the bookmark icon in its top row.
- **Tabs:** the number next to the three dots opens the tab list. Close tabs you do not need;
  the TV has little memory and gets slow with many tabs.

## If something does not work

| Problem | What to do |
|---|---|
| Phone says "Not connected" or "TV not reachable" | Check phone and TV are on the same Wi-Fi. Tap **Find TV**. |
| No pointer on the TV | Wait 30 seconds after the TV starts, then move a finger on the touchpad. |
| Pointer moves but taps do nothing on websites | On the TV open **TV Mouse Receiver**. It should say `Mode: virtual mouse`. If it says `element clicks`, see the next two rows. |
| TV shows "Allow USB debugging?" | With the TV remote tick **Always allow from this computer** and choose **Allow**. |
| Mode stays on "element clicks" | In the TV's Settings, Developer options, make sure **ADB debugging** is on. It must stay on. |
| TV Mouse Receiver says "Cursor service: OFF" | Choose **Open Accessibility settings** there and turn on **TV Mouse cursor**. |
| Everything is slow | Close Brave tabs and other TV apps (Netflix, Play Store), or restart the TV. |

## Reinstalling from the PC

Only needed after a TV reset or to update the apps. Run in PowerShell from `D:\rea-data\brave-tv-mouse`:

```powershell
adb connect 192.168.31.49:5555
adb -s 192.168.31.49:5555 install -r out\brave-tv-arm.apk
adb -s 192.168.31.49:5555 install -r out\tv-cursor.apk
adb -s <phone serial> install -r out\phone-mouse.apk
```

Then turn on "TV Mouse cursor" in the TV's accessibility settings and accept the debugging
prompt once, as in the table above.

## Good to know

- There is no pairing code. Anyone on the same Wi-Fi with the TV Mouse app can control the TV.
- Brave on the TV cannot be updated from the Play Store; a new version has to be rebuilt and
  installed from the PC.
- Not yet tried on the real TV: long press, the Back and Home buttons, and two-finger scroll.
