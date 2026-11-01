# pyleriumTV 0.1

Built and installed on the connected AI PONT Android TV on 30 October 2026.

The app builds personal TV channels entirely from user-selected folders and files on attached drives or local storage.

Validation completed:

- `assembleDebug`
- `testDebugUnitTest` including channel creation and current-program selection tests
- `lintDebug` with 0 errors
- Cold launch on the connected 1280×720 TV
- Four-row remote navigation and off-screen row scrolling
- Local channel Edit Mode via the remote MENU key
- Custom remote-first drive browser, including attached USB volume detection
- Address-free LAN discovery, one-time code pairing and HMAC-signed desktop synchronization
- AndroidRuntime crash-log check
- Leanback launcher registration
- Home-button regression check: fasterTV remains the device launcher

New channels show assignment prompts until a folder or one or more video files are added in Edit Mode.
