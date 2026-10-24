# Development notes

## Architecture

```
 phone (BetterAuto)                                   head unit
 ──────────────────                                   ─────────
 USB accessory (AOA)  ◄──── "Android" / "Android Auto" ──── host
 AAP framing + TLS 1.2 (phone is the TLS server)
 control channel: version, TLS, service discovery, audio focus, ping
 video:  Compose launcher ─► VirtualDisplay ─► H.264 (MediaCodec) ─► channel 3
 audio:  phone playback capture ─► PCM ─────────────────────────────► channel 4
 input:  touch / keys / rotary knob ◄─────────────────────────────── channel 8
 sensors: night mode ◄────────────────────────────────────────────── channel 1
```

| Package | Role |
|---|---|
| `aap/` | Protocol: frame codec, TLS, session state machine, one class per service channel |
| `transport/` | Byte pipe to the head unit (USB accessory; TCP later for wireless) |
| `projection/` | The car screen: private `VirtualDisplay` rendering into the encoder, hosting the launcher in a `Presentation`; touch, keys and knob are injected into it |
| `video/` | H.264 Baseline encoder (`MediaCodec`) |
| `audio/` | Playback capture and delivery to the car, phone speaker muting |
| `media/` | Active media session of the phone (metadata, cover, controls), media apps |
| `ui/` | Jetpack Compose launcher: theme, focus handling, status bar, app rail, widgets, settings |
| `settings/` | Persisted launcher state |

UI sizes are in design units: 1 dp = 1 px on a 1280×720 screen, scaled to the real resolution.

## Build

Android Studio with AGP 9, Kotlin 2.4 (lifted by the Compose compiler plugin), Compose BOM.

```sh
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, `scripts\deploy.cmd` does the same in one go: it finds Java (Android Studio's own if none is
set) and adb (from `local.properties`), builds the debug APK, installs it on the connected phone,
pre-approves audio capture (`appops set ... PROJECT_MEDIA allow`) and opens the app. `-Tests` runs the
unit tests first, `-NoLaunch` skips opening the app, `-Serial <id>` picks a phone when more than one is
connected (a phone on both USB and wireless adb is taken by USB). Installing switches BetterAuto back on
if it was off, so the script warns when the official Android Auto is on too.

The protobuf files under `app/src/main/proto/aap_protobuf` are from opencardev/aasdk
(commit in `app/src/main/proto/AASDK_VERSION.txt`).

## Test bench

A Raspberry Pi 4 running Crankshaft (aasdk based) acts as the head unit, on a desk, with the phone
reaching the PC over wireless ADB while its USB port is plugged into the Pi:

```sh
adb tcpip 5555 && adb connect <phone-ip>:5555
```

Crankshaft needed fixes on the bench:

- patched `crankshaft-core` (source and build in `/home/pi/src/crankshaft-core`, original binary in
  `/usr/bin/crankshaft-core.orig`): hardware H.264 decode with `v4l2h264dec`, the configured video
  resolution actually advertised, audio playback;
- `/etc/crankshaft/crankshaft.json`: 1920×1080 video, smaller decode buffer, no pre-session USB
  reset, constant fast USB scan;
- `/etc/udev/rules.d/99-crankshaft.rules`: Crankshaft's discovery trigger run detached, so it no
  longer delays USB permissions by ~20 s;
- `/etc/udev/rules.d/99-crankshaft-aoap-restart.rules`: restarts the core when the phone is
  unplugged (the core misses the disconnect);
- `/etc/systemd/system/crankshaft-core.service.d/zz-audio-runtime.conf`: the core's PipeWire
  runtime directory and `ProtectHome=read-only`.

Each file explains itself and can be deleted to go back to the packaged behaviour.

## Disabling Android Auto

The official app must be disabled, or it opens the USB accessory before BetterAuto:

```sh
adb shell pm disable-user --user 0 com.google.android.projection.gearhead
adb shell pm enable com.google.android.projection.gearhead   # to bring it back
```

On Windows, `scripts\toggle-aa.cmd` swaps the two: whichever is on goes off (`pm disable-user`, data
kept) and the other comes on. `-Show` only tells which one is on, `-To BetterAuto` or `-To AndroidAuto`
picks one, `-Serial <id>` picks the phone.

## Debugging

- Protocol log: in the app, or `adb logcat -s BetterAuto`.
- Debug overlay (Settings → Debug overlay): frame-accurate clock, render/sent fps, encoder,
  audio format and state, link, night mode.
- Screenshot of the car screen, taken on the phone:

  ```sh
  adb shell dumpsys SurfaceFlinger --display-id   # find the "BetterAuto" display id
  adb exec-out screencap -p -d <id> > car.png
  ```

- Simulate the car's knob and keys (debug builds only; the receiver needs the DUMP permission, which
  the adb shell holds and other apps don't):

  ```sh
  adb shell am broadcast -p com.simoneluconi.betterauto -a com.simoneluconi.betterauto.DEBUG_INPUT --ei rotary 1
  adb shell am broadcast -p com.simoneluconi.betterauto -a com.simoneluconi.betterauto.DEBUG_INPUT --ei key 23  # center press
  adb shell am broadcast -p com.simoneluconi.betterauto -a com.simoneluconi.betterauto.DEBUG_INPUT --ei key 4   # back
  ```

- Try other car screens on the bench: the launcher is drawn only in a centered area of the frame,
  the same way head units apply their margins (`off` goes back to the real screen):

  ```sh
  adb shell am broadcast -p com.simoneluconi.betterauto -a com.simoneluconi.betterauto.DEBUG_INPUT --es screen 9:16      # portrait
  adb shell am broadcast -p com.simoneluconi.betterauto -a com.simoneluconi.betterauto.DEBUG_INPUT --es screen 1920x720  # wide dashboard
  ```

  Home widgets: `--es widgets "clock,calendar,weather"` fills the slots, the big one first (music,
  notifications, favorites, clock, calendar, weather, map; `default` resets them) and opens the home
  screen; `--ez widgetDemo true` shows pretend calendar events and favorites (`false` the phone's own);
  `--es vehicle demo` gives the clock an outside temperature; `--es formats uk` (italy, us, uk, auto)
  sets how dates and numbers are written, e.g. English dates for screenshots.
  Navigation prompts: `--es navPrompt "In 300 metres, turn right"` speaks it like a navigation app
  (guidance usage): with Shizuku it goes to the car's guidance stream, otherwise the phone speaks it
  and the music in the car is lowered meanwhile (`Car audio: navigation prompt ...` in the log).
  `--ez copyAudio true` copies the audio as without Shizuku for the current connection (`false` goes
  back), to try that path on a phone that has Shizuku. The bench Crankshaft takes the guidance stream
  and ducks its music but does not play 16 kHz audio: the prompt itself is heard only in a car.
  Other extras: `--es section music`, `--es layout trio`, `--ez musicPanel true`, `--es theme night`,
  `--ez night true` (the car's night sensor), `--es call ringing` (a pretend call: ringing, dialing,
  active, holding, ended or off; the call screen buttons work on it). The pretend call also goes to the
  car's phone status channel (instrument cluster), logged as `Phone status: …`; `--es cluster call`
  pretends the car's display pressed a button (call or enter answers a ringing call).
  Pretend messages: `--es notify "Text" --es from "Name"` (add `--ez group true` for a group chat);
  `--ez demo true` shows only pretend notifications, for screenshots without personal data
  (see `docs/screenshots`).
  Vehicle: `--es vehicle demo` fills the vehicle screen with pretend data (`off` clears it,
  `diagnostics` opens the fault codes); `--es dtc "P0300,P0171"` sets the fault codes;
  `--es passenger on` pretends someone sits beside the driver (`rear`, `off`, `none`), which the
  notification privacy follows when it is on Auto.
  App bar: `--es rail "MAP:GROUP_1,VEHICLE:GROUP_1"` arranges it (`default` resets it).
  Voice: `--es voice probe [--es phrase "chiama Mario Rossi"]` checks the phone's on-device speech
  recognizer with a synthesized phrase fed as raw audio, like the car's microphone; `--es voice mic`
  listens to the phone's microphone. Results and timings go to the log (`Voice probe:`).
  Voice commands: `--ei key 84` is the steering wheel voice button; `--es mic phone` (or `car`) picks the
  microphone. The bench Pi announces a microphone but sends no audio, so use `phone` there.

- *Car UI preview* in the app shows the launcher full screen on the phone.

## Home widgets

Each home slot holds a `WidgetChoice` (`settings/HomeSlots`): a `WidgetType` plus an optional parameter,
saved as `TYPE` or `TYPE:param`. `ui/widgets/HomeWidgets` lists one `HomeWidgetKind` per type: its name
and icon in the slot picker, the choices it offers on this phone (none hides it, one per parameter for a
kind that takes one, e.g. a smart-home group) and the composable. A new widget is one enum value and one
entry there; the car's and the phone's pickers follow.

Widgets watch their data only while on screen: the minute tick, a `ContentObserver` on the calendar or
the contacts. The weather widget is the phone's own weather app widget, hosted with `AppWidgetHost` like a
launcher (`home/WeatherWidgetHost`): the provider is picked by name (`WeatherProviders`), binding needs
the system dialog once (from the phone app; "always allow" makes later ones silent), the host listens
only while the widget is shown and the car shows our screen, and the hosted view never takes the focus
or a touch. It follows the phone's dark mode, not the car's night mode.

## Vehicle data sources

The screens read only canonical fields (`car/VehicleField`: one meaning, one metric unit each). Every
protocol is a `VehicleSource` that maps its own names to those fields and publishes them to
`VehicleData`; `VehicleMerger` picks each field from a live source first, the higher priority among
live ones (the Android Auto sensor channel over a car maker's protocol), else the newest last-known
value. A new protocol is one more source class with its mapping; values it can't map yet go to the
"All data" page as raw rows under the source's name.

Exlap (`exlap/`) reads every object once at login (logged as `Exlap: first values, ...`, objects with
personal data named without values), then subscribes only to what the screens need
(`VehicleData.demand`): seat occupancy always, the overview's objects while the vehicle screen is open,
everything while "All data" is (dropped 30 s after leaving). `ExlapMapping` holds the object names and
their sources.

## Smart home

Everything outside `smarthome/<service>/` works on the canonical model of `smarthome/` and never knows
the service: the car screen, the arrival buttons, the device selection, the phone section, the widget
card (`SmartHomeGroupCard`). A service is a `SmartHomeProvider`; its KDoc lists what adding one takes.
`SmartHome` asks providers to follow the chosen devices only while a car session runs (or a smart-home
screen is open), so the phone does no network work otherwise.

Sign-in: services with OAuth use a client id page per service on the project's GitHub Pages, branch
`gh-pages` (not on `main`): `https://simoneluconi.github.io/BetterAuto/auth/<provider id>/`, declaring
`<link rel="redirect_uri" href="betterauto://auth/<provider id>">` in its first 10 kB. The browser comes
back to `SignInActivity` (scheme `betterauto`, host `auth`), which hands the query to the provider with
that id. Adding a service with OAuth: its page on `gh-pages`, then the provider (client id and redirect
from `SmartHomeAuth.clientId(id)` / `redirectUri(id)`). Home Assistant: `/auth/authorize`, code
exchanged at `/auth/token`, refresh token kept (encrypted with `SecretStore`); "Advanced" takes a
long-lived token instead. After linking, `get_config` gives the home's name and, when the address typed
only works at home, its external URL (or the Nabu Casa remote one), used from then on.

Home Assistant data comes over the WebSocket API (`HaSocket`, OkHttp): `get_states`,
`config/area_registry/list`, `config/device_registry/list`, `config/entity_registry/list`,
`homeassistant/expose_entity/list` (admin only) and `get_config` when the phone app reads the devices;
in the car only `subscribe_entities` with the chosen entities and `call_service`. Pre-selection: what is
exposed to a voice assistant, of car-useful kinds; with nothing exposed, a guess by kind (no sensors, at
most 24). Hidden, disabled, configuration and diagnostic entities never show.

Arrival buttons: the place is the provider's home zone (radius) plus the driver's offset (300 m by
default); they show inside it, also when the session starts there, and hide 150 m past it. Position:
the car's GPS (Android Auto location sensor) when it talks, otherwise the phone's platform location,
one fix at a time, the next one when the car could at the earliest reach the nearest edge (distance /
speed, 40 m/s or the car's speed ×1.5, 5 s to 10 min), plus passive fixes. Each check is logged:

```
Smart home: homeassistant 812 m, outside, next check in 20 s
Smart home: entered the place of homeassistant at 380 m: arrival buttons shown
```

Debug (see `DebugInputReceiver`): `--es smarthome demo` adds a pretend service with devices and a home
place (`off` removes it, `open` opens its screen); `--es arrival inside|outside|"distance 2000,90"|panel|real`,
`--es location 45.46,9.19[,50]` (`off`), `--es drive 2000,50[,90]` (a drive through the home place, in
and out again) replace the position, with the real devices too, also without a car session;
`--ez carGps true` feeds them like the car's GPS instead of the phone's polled location.

## Shizuku (optional audio routing)

With [Shizuku](https://shizuku.rikka.app/) running and BetterAuto allowed, `CarAudio` starts
`AudioRouterService` as a Shizuku user service: it runs as the shell user (which holds
`MODIFY_AUDIO_ROUTING`), registers an audio policy whose loop-back mix takes media/game/unknown
audio away from the speaker, and streams it as float PCM through a pipe. Two details matter: the policy
calls run with the service's own identity (`Binder.clearCallingIdentity`), and the recorder is attributed
to `com.android.shell`, or the audio server refuses it. Everything falls back to playback capture.

Without root, Shizuku dies whenever the phone switches its USB port to accessory mode (adbd restarts),
so with the cable it only helps on rooted phones. Debug: `--es shizuku probe` routes audio for 3 s and
logs bytes and peak; `--es shizuku allow` asks for the permission.

## TLS certificate

The phone identity is read from the PEM files (`.pem`, `.crt`, `.cer`, `.key`) at the root of
`app/src/main/assets/`, whatever their names: the private key (`BEGIN PRIVATE KEY`, PKCS#8, unencrypted)
and the certificates are told apart by content, the key is paired with the certificate it signs for, and
any other certificates complete the chain. One file may hold everything. If no usable pair is found the
phone shows a "no usable certificate" notification and the log says what is missing.

The files in the repository are a throwaway self-signed development certificate. aasdk-based head units
do not verify the phone's certificate; factory head units expect one issued by Google, so BetterAuto only
works with head units that skip the check (see the README). When a car refuses the connection, the phone shows a
notification explaining the reason (`ConnectionProblem`); `--es problem <name>` on the debug broadcast
shows any of them without a car.
