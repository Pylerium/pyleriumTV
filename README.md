# pyleriumTV

pyleriumTV turns the media on your attached drives and local storage into personal TV channels, with a live
electronic programme guide. It is a separate app from [Pylerium Purge](https://github.com/Pylerium/PyleriumPurge)
and does not replace your launcher.

Part of the Pylerium TV suite: [Pylerium Purge](https://github.com/Pylerium/PyleriumPurge) (launcher) ·
[Pylerium Player](https://github.com/Pylerium/PyleriumPlayer) (movies and shows).

## Features

- Responsive remote-first programme guide with a live time marker, now/next details and live progress bars
- Channels built from whole folders or hand-picked files; mounted USB drives are detected automatically
- Linear playback: tuning joins a programme at its wall-clock position, never at the start
- **libVLC playback engine** — plays XviD/AVI, AC3, E-AC3 (DD+), DTS and 10-bit x265 that Android's own
  player cannot decode. Stalled or unplayable files are retried and then skipped
- Persistent Edit Mode (MENU): rename, create, clear and delete channels, assign folders or files
- Paired desktop studio sync: address-free LAN discovery, one-time code pairing, HMAC-signed requests and
  AES-GCM encrypted payloads. Uploads stream to the TV over an authenticated, encrypted channel, are written to a
  hidden `.part` file, verified (size + SHA-256 + MAC), fsynced and only then atomically renamed into the library.
  Free space is checked before a transfer starts, and orphaned partial files are cleaned up automatically
- Pylerium graphite, orange and cyan visual system

## Using it

1. Open pyleriumTV. On first use Android asks for full file access (needed to browse media in the built-in
   remote-friendly drive browser).
2. Press **MENU** for Edit Mode, select a channel, then add a folder or several video files.
3. Use the directional pad to browse the guide and **OK** to watch. In the player: **▲/▼** change channel,
   **OK** pauses (resuming rejoins live), **MENU** cycles audio tracks.

To send movies from a PC, open *Edit Mode → Pair desktop studio* on the TV and enter the six-digit code in the
Pylerium desktop studio.

## Build

```
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Requires the Android SDK (`local.properties` with `sdk.dir=...`), JDK 17+. The APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. Application id: `com.pylerium.tv`, minSdk 26.

## Credits

Launcher UI components derive from [CleanTV](https://github.com/JohnWayneeee/cleantv) by JohnWayneeee (MIT).
See [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE).
