# BetterAuto — notes for working on this project

BetterAuto is the **phone side of the Android Auto protocol (AAP)**: a replacement for the Android Auto
app. The head unit (car) is unchanged; the phone renders its own launcher on a virtual display, encodes it
as H.264 and streams it, plus audio, over USB (wireless is planned). Kotlin, Jetpack Compose (foundation
only, no Material), minSdk 29. User-facing docs: `README.md`; build, bench and debugging details:
`docs/DEVELOPMENT.md` (read it before touching the protocol or the bench).

This repository is **public**: never commit credentials, IP addresses, device serials, personal data or
anything copied from closed-source apps.

## Working agreements

- **Language**: code, comments, logs and commit messages in English. UI strings in English
  (`res/values`) with an Italian translation (`res/values-it`) for every string; the phone's locale picks.
  The user talks in Italian.
- **Comments**: short, saying *why*, in the tone of the surrounding code. KDoc on classes and non-obvious
  functions. Match the existing naming and idioms; don't add frameworks or libraries casually.
- **Line endings**: files are a mix of CRLF and LF; keep each file's own. Edit with tools that preserve
  them (a Python script reading/writing with `newline=""` is safe; `sed -i` in Git Bash is not).
- **Every batch ends with**: build, unit tests, and a check on the real phone + head unit
  (`scripts\deploy.cmd -Tests`, then a session and a look at the car screen / log).
- **Ask before** big designs or user-visible behaviour changes; small bug fixes can just be done and
  reported. Commit/push only when asked; stage files **by name**, never `git add -A`.
- **Certificates**: `app/src/main/assets/` holds the phone's TLS identity, found by content. Only our own
  self-signed development pair (`phone_cert.pem`, `phone_key.pem`) is public. Anything else put there
  (e.g. a certificate to try a factory head unit) is ignored by `.gitignore` and must never be committed;
  neither must the deletion of our pair. Check `git status` on that folder before every commit.
- **Exlap credentials** (VW group vehicle data) are typed by the user in the phone settings, stored
  encrypted with the Android Keystore. They never go in code, logs, docs or commits.

## Map of the code (`app/src/main/java/com/simoneluconi/betterauto/`)

| Package | What lives there |
|---|---|
| `transport/` | Byte pipe to the head unit: `Transport` interface, `UsbAccessoryTransport` (TCP for wireless will plug in here) |
| `aap/` | The protocol: `AapSession` (control channel: version, TLS, auth, service discovery, opens channels), `FrameCodec`, `AapTls`, one class per service channel (`VideoChannel`, `AudioSinkChannel`, `InputChannel`, `SensorChannel`, `MicrophoneChannel`, `MediaStatusChannel`, `VendorExtensionChannel`), `ConnectionProblem` (why a car refused us) |
| `AapService` | Foreground service owning the session: USB attach → session, teardown, notifications (including connection-problem explanations) |
| `video/` | `VideoEncoder` (MediaCodec H.264 from a Surface); a stall watchdog in `VideoChannel` rebuilds the pipeline |
| `projection/` | `Projection` (virtual display + `LauncherPresentation`), touch/key/rotary injection into the UI |
| `audio/` | Phone audio to the car: `CarAudio` picks the way. With Shizuku ready, `ShizukuCapture` + `AudioRouterService` (an AIDL user service running as the shell user, audio policy loopback mix, system APIs by reflection) *move* it; otherwise `AudioCapture` *copies* it (MediaProjection) and `PhoneSpeakerMute` mutes the phone. `PcmQuantizer` (float → dithered PCM) is shared. Also the car microphone |
| `media/` | `NowPlaying` (follows the active media session), media apps, library browsing |
| `phone/` | Calls (InCallService companion), contacts/call log, phone status (signal, battery) |
| `notifications/` | Notifications shown in the car |
| `voice/` | Voice commands: on-device speech recognition, `CommandParser`, `VoiceAssistant` |
| `car/` | Data from the car: `VehicleData`/`CarState`, `FaultCodes`, `Units` (metric/imperial, °C/°F), `Viewport` |
| `exlap/` | VW group Exlap (extra vehicle data, read only): `ExlapClient` (protocol), `ExlapSettings`, `ExlapData`, `ExlapRows`, `ExlapPhoneSettings` (phone-only settings card) |
| `settings/` | `LauncherSettings` (persisted, StateFlows), `SettingsCatalog` (one declaration shown both in the car and on the phone), `RailLayout` |
| `ui/` | The car launcher: `launcher/` (root, status bar, app rail, focus zones, settings screen), `widgets/` (music), `phone/`, `vehicle/`, `notifications/`, `voice/`, `theme/`, shared bits (`CarFocus`, `CarText`, `CarIcons`, `Scrollbar`, `AlphabetJump`) |
| `MainActivity`, `PhoneSettings` | The phone app: setup steps, log, settings (the catalog plus phone-only sections) |
| `src/debug/…/DebugInputReceiver` | Debug-only broadcasts to drive the car UI without a car (see its KDoc for the full list) |

## Car UI style

The car screen must look like a realistic, modern car UI (reference: an Audi A4 B9 MMI): calm, big,
legible at a glance, the same in every section. Before adding UI, copy an existing screen's patterns.

- **Colors**: only from `LocalCarColors` (`CarTheme`): `bg`, `surface`, `surface2` (cards inside a surface,
  chips), `line` (hairlines), `text`, `text2` (labels, secondary), `focus` (selection/focus), `album`
  (accent from the cover, music only). Day and night palettes switch with the car's night signal or the
  setting. Warnings use `WarningAmber`. No hard-coded colors elsewhere.
- **Text**: always `CarLabel` (`ui/CarText.kt`): size in sp, `FontWeight.Bold/ExtraBold` for values and
  titles, `tabularNumbers` for anything that changes (times, speeds), `shrinkToFit` where a value may be
  long. Typical sizes: screen title 26 ExtraBold, card title 21 Bold, label 18 `text2`, value 19 Bold,
  big tile values 32–40 ExtraBold.
- **Shapes and spacing**: cards rounded 24–28 dp on `surface`/`surface2`, padding 18 dp; chips and pill
  buttons fully rounded; round icon buttons 52 dp; gaps 10–14 dp. Rows inside cards: label left
  (`text2`), value right (Bold), a 1 dp `line` between rows.
- **Icons**: only `CarIcons` — our own 24 dp stroke icons (2 px lines, path strings). Add new ones there in
  the same style; no icon libraries.
- **Layout**: responsive. The frame can be landscape, portrait or very wide, and `Viewport` gives the part
  the car really shows (margins, insets). Use `BoxWithConstraints` and adapt (rows vs columns), never
  fixed screen sizes. Lists that scroll get a scrollbar (`LazyColumnWithBar`, `LazyVerticalGridWithBar`,
  `verticalScrollWithBar`, `Modifier.scrollbar(state)`).
- **Don't repeat the cluster**: main screens show only what the instrument cluster doesn't (no speed,
  no fuel gauge on the home screen); everything the car shares goes to the Vehicle section's "All data".
- **Status bar**: buttons that are always there (mic, bell) stay at the right, next to Wi-Fi/battery
  (which keep a steady width); buttons that come and go sit to their left, so nothing moves under the
  driver's finger.
- **Phone app**: same components and colors (it uses the car theme), cards per section; phone-only
  settings (e.g. Exlap) are separate sections after the shared catalog.

## Input: touch and the car's knob

Every screen must work fully with **touch** and with the **rotary knob / d-pad / keys** alone.

- Every interactive element uses `Modifier.carClickable(shape, label) { … }` (`ui/CarFocus.kt`): always
  focusable, activated by tap or center/enter, focus ring + slight zoom when focused, press feedback.
  Things that must react to touch but not take the knob (e.g. the notification banner) use a private
  tap-only modifier, like `carTapOnly` in `NotificationsWidget`.
- The knob turns = Tab / Shift+Tab through controls in order; the first knob move only *reveals* the
  focus (`LauncherFocus`). Nudges move between two zones (`FocusZones`): the app bar and the screen;
  mark **one** main control per screen with `Modifier.contentFocus()`.
- Back (car key or Escape): closes the topmost thing (voice panel, detail, notifications go back to the
  previous section), otherwise goes home. Handle it in `onKeyEvent`/`onPreviewKeyEvent` like the
  existing screens.
- Steering-wheel media keys act on the player directly; the voice key (long press on VW) starts voice
  commands. Keys arriving from the car are logged as `Input: key …`.
- Overlays that wait for the driver (voice panel) take the focus on their main action (e.g. Cancel)
  and are dismissed by a tap outside.

## Settings

Declare a user setting once in `SettingsCatalog` (with a `LauncherSettings` StateFlow + setter) and it
appears both in the car's settings screen and in the phone app. Use `ChoiceSetting` for chips; reuse
`option_auto`, `option_on/off` etc. Phone-only or car-specific features get their own section outside
the catalog.

## Testing and debugging

- `scripts\deploy.cmd [-Tests] [-NoLaunch] [-Serial <id>]`: finds Java and adb, builds the debug APK,
  installs it, pre-approves audio capture, opens the app. `scripts\toggle-aa.cmd [-Show|-To BetterAuto|-To AndroidAuto]`
  switches between BetterAuto and the official Android Auto (both answer the head unit).
- Installing kills the running session: the head unit must reconnect (re-plug, or restart the bench head
  unit's core service; see `docs/DEVELOPMENT.md`).
- The log: `adb logcat -s BetterAuto` (also shown in the phone app). Car screen screenshot:
  `adb exec-out screencap -p -d <display id>` with the id of the "BetterAuto" display from
  `adb shell dumpsys SurfaceFlinger --display-id`.
- `DebugInputReceiver` (debug builds): `adb shell am broadcast -p com.simoneluconi.betterauto -a com.simoneluconi.betterauto.DEBUG_INPUT …`
  with extras for knob/keys, touches and drags in video pixels (`--es touch x,y` / `x1,y1,x2,y2`),
  section, theme, units, pretend calls/notifications/vehicle data, voice panel previews, connection
  problem notifications, video pipeline rebuild. Never drive the UI blindly: take a screenshot first.
- Unit tests live in `app/src/test` (JVM; `android.util.Log` returns defaults). Protocol pieces are
  written so they can be tested without Android (e.g. `ExlapClient` against a scripted server).

## Known open points

- The H.264 encoder sometimes stops producing frames without an error (car screen frozen); the
  `VideoChannel` watchdog rebuilds the pipeline. Root cause not found yet.
- Factory head units verify the phone certificate against Google's; ours is self-signed.
- Shizuku started without root dies whenever the phone connects to the car by USB (the switch to
  accessory mode restarts adbd); BetterAuto then falls back to copying. Works with root, and will with
  wireless Android Auto.
- Channels the cars offer that are not implemented yet: guidance/system audio, Bluetooth pairing,
  navigation status, phone status, notifications, media browser, radio. Wireless (TCP) transport is planned.
