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

## Debugging

- Protocol log: in the app, or `adb logcat -s BetterAuto`.
- Debug overlay (Settings → Debug overlay): frame-accurate clock, render/sent fps, encoder,
  audio format and state, link, night mode.
- Screenshot of the car screen, taken on the phone:

  ```sh
  adb shell dumpsys SurfaceFlinger --display-id   # find the "BetterAuto" display id
  adb exec-out screencap -p -d <id> > car.png
  ```

- Simulate the car's knob and keys (debug builds only):

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

  Other extras: `--es section music`, `--es layout trio`, `--ez musicPanel true`, `--es theme night`,
  `--ez night true` (the car's night sensor).

- *Car UI preview* in the app shows the launcher full screen on the phone.

## TLS certificate

`app/src/main/assets/phone_cert.pem` and `phone_key.pem` are a throwaway self-signed development
certificate. aasdk-based head units do not verify the phone's certificate; factory head units may.
