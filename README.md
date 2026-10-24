# Ollee Badge

An Android app that shows on an **Ollee Watch** whether notifications are waiting on your phone.
The watch shows the count in the top-left corner **instead of the weekday**. With no
notifications, the weekday comes back.

| Notifications | Watch shows |
|---|---|
| 0 | the weekday (`MO`, `TU`, … or custom names set in the Ollee app) |
| 1–9 | `1` … `9` |
| 10, 11 | `10`, `11` |
| 12 or more | `11` |
| a marked chat | its own two characters, e.g. `A-` |

The weekday field has two characters. Its right-hand cell renders only `0` and `1` cleanly
(found by Gadgetbridge), hence the single digits.

This is a hobby project. It is **not affiliated with or endorsed by Ollee**. The app's interface
is currently in German.

## Requirements

- Android 12 or newer
- Ollee Watch One
- **"Bluetooth always on"** enabled in the official Ollee app. Without it the watch turns
  Bluetooth off after each session and the app only reaches it now and then.

## Build and install

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/ollee-badge-debug-1.0.apk
```

Run the tests with `./gradlew testDebugUnitTest`.

If you install the APK as a file (browser, file manager) instead of with `adb`, Android 13+
blocks notification access at first. Allow it under *Settings → Apps → Ollee Badge → ⋮ → Allow
restricted settings*.

## Setup

1. Open the app and grant **notification access**.
2. Tap **Uhr suchen** (find watch), allow Bluetooth and pick your Ollee Watch. A watch the Ollee
   app is connected to right now shows up too.
3. Tap **Test**: the watch shows `8` instead of the weekday for about 10 seconds.

Settings:

- **Jede App nur einmal zählen:** count apps with notifications instead of notifications.
- **Stumme Benachrichtigungen mitzählen:** also count silent notifications (off by default).
- **Apps ausschließen:** apps that never count.
- **Markierungen für Beeper-Chats:** one line per chat, e.g. `Alex = A-`. While that
  [Beeper](https://www.beeper.com) chat has a notification, the watch shows `A-` instead of the
  count. If several marked chats have notifications, the newest wins. Chats are matched by name:
  the group name for groups, otherwise the sender. `Alex` also matches `Alex Smith`. The LCD
  can't draw every character: `*` shows as `-` and lowercase as uppercase.

These notifications never count
([NotificationFilter.kt](app/src/main/java/com/programmieramt/olleebadge/NotificationFilter.kt)):

- **Media:** music, podcasts, videos, also when paused.
- **Persistent notifications:** anything not dismissible or marked ongoing, no-clear or
  foreground service, even where Android 14+ lets you swipe it away.
- **Status categories:** service, progress (downloads), navigation, stopwatch, location sharing,
  system status.
- **Group summaries:** the individual messages count instead.

Missed calls do count.

## How it works

The official Ollee app is closed source, so this is a separate app that talks to the watch over
Bluetooth itself. The protocol comes from the Ollee support in
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) (PR #6411, reverse-engineered
and verified on hardware):

- Nordic UART Service `6e400001-…`, frames `00 LEN AA 55 CRC16 02 TARGET PAYLOAD`
- Register `02 34` (write) / `02 35` (read, reply `0x55`): a 4-byte World Time offset and
  14 characters of weekday names. The badge puts the same two characters in all seven slots.
- The app reads the register back before every write, which keeps the World Time offset and
  custom weekday names intact. When the badge goes away, the original weekday names come back.

**Short sessions only.** When the count changes, the app waits 10 seconds for more changes, then
connects for a second or two (connect, read, write, disconnect) and asks for the low-power
connection interval. It never stays connected: Android shares one link between all apps talking
to the watch, and the Ollee app fails to set up its own connection ("Connection error in OTP")
while another app holds one.

- While the Ollee app is connected to the watch, Ollee Badge waits up to a minute so the Ollee
  app can finish setting up, then briefly joins the existing link. **Test** and **Jetzt
  übertragen** (sync now) don't wait.
- If the connection setup fails, the app retries after 3 seconds (twice). If the watch is out of
  reach, it retries after 30 seconds and then less often, up to every 15 minutes. Until then the
  last value stays on the watch.
- If the Ollee app rewrites the weekday register, a badge is restored within 15 minutes or with
  the next change.
- Turning the app off or revoking notification access puts the weekdays back first.

It runs as a `NotificationListenerService`, with no foreground service and no permanent
notification.

## Privacy

The app has no internet permission. It reads notifications only to count them and to match chat
names for markers. The only thing that leaves the phone is the two characters sent to your
watch over Bluetooth. Chat names are logged only if you enable debug logging (see below).

## Troubleshooting

- **Connections die a few seconds in** (Ollee app says "Failed to save settings", logcat shows
  `LINK_SUPERVISION_TIMEOUT`): replace the watch battery (CR2016) first. A weak cell can still
  read about 2.9 V at rest and sag as soon as the radio works. A new battery resets all watch
  settings, including "Bluetooth always on".
- **Updates take long or fail with `CONN_FAILED_ESTABLISHMENT`:** check whether the phone keeps
  trying to reconnect another Bluetooth device, such as a sports watch that is out of range.
  That retry storm can occupy the radio. It stops once that device is connected or its app
  stops trying.

## Development

- `adb logcat -s OlleeBadge` shows each session: start, the weekday characters read and written,
  success or failure.
- `adb shell setprop log.tag.OlleeBadge DEBUG` also logs which Beeper chat names were matched.
- Debug builds have a receiver for experiments. It needs adb, no app can trigger it:

  ```sh
  # show two characters on the watch for about 10 seconds
  adb shell "am broadcast -a com.programmieramt.olleebadge.debug.PLAY_CHIME -n com.programmieramt.olleebadge/.DebugReceiver --es text 'A-'"
  # play alarm chime 0..14 once, with an optional test label 1..11 on the display
  adb shell am broadcast -a com.programmieramt.olleebadge.debug.PLAY_CHIME -n com.programmieramt.olleebadge/.DebugReceiver --ei chime 3 --ei label 1
  ```

- Playing a chime uses the alarm record `02 25` with its write-only `playNow` byte
  ([`AlarmRecord`](app/src/main/java/com/programmieramt/olleebadge/OlleeProtocol.kt)). It plays
  one of the 15 alarm chimes for a few seconds and leaves the stored alarm untouched. Higher
  numbers play a default tone. Writing the record again does not stop a chime early, and the
  short hourly or Bluetooth beeps are not reachable this way.

## Alternative

[Gadgetbridge](https://gadgetbridge.org/gadgets/wearables/ollee/) (nightly) pairs with the Ollee
Watch directly and shows the same notification count. It then also handles time sync, alarms and
step data in place of the Ollee app.

## License

Copyright © 2026 programmieramt.
GNU Affero General Public License v3 or later, see [LICENSE](LICENSE). The protocol code in
[OlleeProtocol.kt](app/src/main/java/com/programmieramt/olleebadge/OlleeProtocol.kt) is ported
from Gadgetbridge (Copyright © 2026 Ken Blizzard-Caron, AGPL-3.0-or-later).

"Ollee" is a trademark of its owner and is used here only to name the watch this app works with.
