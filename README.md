# BetterAuto

**Your car's Android Auto screen, with a launcher of your own.**

BetterAuto is an Android app that plugs into any car with Android Auto and shows its own
interface instead of the stock one: a clean, widget-based home screen designed for the road. The car
doesn't know the difference: it thinks it is talking to Android Auto.

> ⚠️ Personal, experimental project, not affiliated with or endorsed by the makers of Android Auto.
> Android Auto is a trademark of its owner.

## Features

- 🎵 **Music player** for whatever plays on your phone (Spotify, YouTube Music, local FLAC
  players…), with cover art, colors taken from the album and big, easy controls.
- 🔊 **Car audio over the USB cable**, uncompressed, in the best format your car supports.
- 🧩 **Widget home screen** like Android Auto: one, two or three widgets side by side,
  remembered exactly as you left them.
- 🌗 **Day and night themes** that follow the car's lights, with a smooth fade instead of a flash.
- 🎛️ **Works without a touchscreen**: rotary knob, joystick and steering wheel buttons.
- 📐 **Fits any screen**, from small 800×480 units to wide dashboards and portrait displays.
- 🌍 English and Italian, following your phone's language.

Coming next: maps with turn-by-turn directions (also on the instrument cluster), notifications,
calls, voice commands, wireless connection.

## Requirements

- An Android phone (Android 10 or newer).
- A car or head unit with **wired** Android Auto.
- A computer with [ADB](https://developer.android.com/tools/releases/platform-tools) for the
  one-time setup below.

## Getting started

1. **Install the app** on your phone (build it with Android Studio, see
   [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)).

2. **Disable the official Android Auto app**, otherwise it grabs the connection first:
   *Settings → Apps → Android Auto → Disable*. On phones where that button is greyed out, use the
   `adb` command in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#disabling-android-auto). Enable it
   again the same way whenever you want it back.

3. **Optional, recommended: skip the audio prompt.** Without this, Android asks for permission to
   capture audio every time you connect (see [Car audio](#car-audio-what-to-know)):

   ```sh
   adb shell appops set com.simoneluconi.betterauto PROJECT_MEDIA allow
   ```

4. **Open BetterAuto once** and tap *Allow music access*, so it can show what is playing.

5. **Plug the phone into the car.** When Android asks which app to open, pick BetterAuto and
   choose *Always*.

## Car audio: what to know

The official Android Auto is a **system app**, preinstalled with special permissions that let it
silently *move* all of the phone's sound to the car. BetterAuto is a regular app, and Android
does not give those permissions to regular apps. So BetterAuto takes another route, which works
well but has a few side effects:

- **A permission prompt when you connect.** Android only lets an app pick up other apps' sound
  through its *screen capture* permission. BetterAuto only uses the audio; nothing on the screen
  is recorded. The prompt disappears for good with the one-time `adb` command in step 3. If you
  skip it or say *Not now*, the music stays on the phone and the BetterAuto notification offers
  *Send audio to the car* whenever you want.
- **A screen-sharing indicator** is shown by Android while audio goes to the car. Apps cannot
  hide it.
- **The phone's own speaker is muted** while you are connected (BetterAuto copies the sound rather
  than moving it, so it turns the phone's media volume down and puts it back when you unplug).
  Use the car's volume knob as usual.
- **Some apps may refuse** to let their sound be captured (typically DRM-protected video apps).
- **Navigation voice prompts** from other apps cannot be captured; they play from the phone or
  over Bluetooth.
- **Phone calls go over Bluetooth**, with the car's microphones and speakers. This is how the
  official Android Auto handles calls too, even on a cable: pair the phone with the car once.

**Sound quality.** Android Auto carries uncompressed audio (PCM), usually 48 kHz / 16-bit stereo
depending on the car: CD quality, and better than Bluetooth, which compresses. High-resolution
files (for example 24-bit / 96 kHz FLAC) are converted to the car's format; the official app has
the same limit. BetterAuto keeps that conversion as clean as possible: it takes the phone's sound
in full precision and reduces it to the car's format only at the very end, with dithering, the
technique used in music mastering to avoid conversion artifacts.

**Planned:** support for [Shizuku](https://shizuku.rikka.app/) (no root needed), which lets
BetterAuto move the audio exactly like Android Auto does: no prompt, no indicator, no muting.

## Status

Early development. Tested on a Samsung Galaxy S25 Ultra (Android 17) with a Raspberry Pi 4
running [Crankshaft](https://github.com/opencardev/crankshaft) as the head unit. Factory car head
units may check the phone's certificate and refuse the connection; that is not tested yet.

## Roadmap

- [x] Android Auto connection over USB, video and touch/knob/buttons
- [x] Widget home, music widget, day/night themes, settings
- [x] Car audio over USB (playback capture, uncompressed PCM)
- [ ] **Shizuku support** for car audio: audio moved like the official app, no prompt, no
      screen-sharing indicator, no muting; playback capture stays as the fallback
- [x] Full music screen: library, queue, up next
- [ ] Map widget with turn-by-turn directions from the phone's navigation app, also on the
      instrument cluster
- [ ] Notifications and messages widgets
- [ ] Calls: incoming call screen and controls (audio over Bluetooth)
- [ ] Voice commands through the car's microphone
- [ ] Wireless connection
- [ ] Steering wheel and knob shortcuts, driving restrictions

## How it works, in short

When the phone is plugged in, the car asks it to start Android Auto. BetterAuto answers instead,
speaks the Android Auto protocol, draws its interface on a virtual screen, encodes it as video and
streams it to the car, along with the audio. Touches, knob turns and button presses travel back
the other way. More in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## License and credits

The Android Auto protocol definitions come from
[opencardev/aasdk](https://github.com/opencardev/aasdk), licensed under GPLv3, so BetterAuto is
distributed under the GPLv3 as well.
