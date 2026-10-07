# Sticky

**English** · [Türkçe](README.tr.md)

A small, account-free-feeling to-do app for **Windows** and **Android**. Your tasks live in a local database on each device and sync through a **hidden app folder in your own Google Drive**. No notifications, no analytics, no server other than Google Drive.

- **Windows:** a small, sticky-note style window that stays open (Tauri 2 + Rust). 2.4 MB installer.
- **Android:** a Jetpack Compose app plus a **home-screen widget** that works like the app (check off, delete, add). 2.3 MB APK.

> The UI is in Turkish. The code and this README are open for anyone to read; see [License](#license).

## Features

- **Quick add:** one line, press Enter.
- **Tasks:** done / delete, optional notes, colour, free-form tags with tag filter.
- **Deadline:** a plain text box. Write `DD.MM.YYYY HH:MM` (time optional, defaults to 23:59) and the task is sorted by it and highlighted when overdue; any other text is shown as-is and sorted last. No date picker, no reminders.
- **Recurring tasks:** daily or weekly, pinned in their own section at the top. The "done" mark resets itself when the day (or week, starting Monday) ends.
- **Offline first:** everything is written to the local SQLite database first. Add, edit and delete work without internet; the number of pending changes is shown.
- **Sync (Google Drive, `drive.appdata` scope only):** one JSON file in the app's hidden folder. The app cannot see any other file in your Drive.
  - Manual **Save** button (uploads and downloads), plus automatic sync on app start, about 5 seconds after a change, when the network comes back, and periodically.
  - Merge is per task: the latest `updatedAt` wins. Deleted tasks are kept as tombstones so they do not come back from another device.
- **Windows:** remembers position and size, optional start with Windows (toggle in the title bar), stays on one virtual desktop.
- **Android widget:** large, resizable, scrollable. Check, delete and add (`+` opens a tiny dialog) straight from the home screen.

## How sync works

```
device A (SQLite) ──┐                    ┌── device B (SQLite)
                    ├── sticky.json ─────┤
        Google Drive (hidden appDataFolder)
```

Each sync downloads the file, merges it with local changes, and uploads the result if anything changed. If the file changed on Drive in the meantime, the merge is repeated, so another device's upload is never overwritten. The merge rules are written down as scenarios in [`shared/sync-cases.json`](shared/sync-cases.json), and **both the Rust and the Kotlin test suites run the same file**, so the two platforms cannot drift apart.

## Repository layout

| Path | What |
| --- | --- |
| `src/` | Windows UI (TypeScript + Vite, no framework) |
| `src-tauri/` | Windows app (Rust, Tauri 2): SQLite store, Google sign-in (PKCE), Drive client, sync, autostart, NSIS installer |
| `android/` | Android app (Kotlin, Compose, Glance widget, WorkManager) |
| `shared/` | Merge scenarios shared by both test suites |
| `REQUIREMENTS.md` | Requirements, decisions and measurements (Turkish) |

## Build it yourself

You need your **own Google Cloud project**: this repository contains no client IDs or secrets.

### 1. Google Cloud (one time, free)

1. Create a project and enable the **Google Drive API**.
2. OAuth consent screen: user type **External**, add only the scope `.../auth/drive.appdata` (non-sensitive), then **publish to production** (in "Testing" the refresh token expires after 7 days). Production needs a home page and a privacy policy URL; a simple public page is enough.
3. Create two OAuth clients:
   - **Desktop app** (for Windows),
   - **Android** with package name `com.sticky.reminder` and the SHA-1 fingerprint of the key you sign the APK with.

### 2. Windows

Requirements: Rust (stable), Node.js 20.19+ or 22, Visual Studio Build Tools (C++ workload), WebView2 (included in Windows 11).

```powershell
npm install
npm run tauri dev        # development
npm run tauri build      # installer: src-tauri/target/release/bundle/nsis/
cd src-tauri; cargo test # 62 tests
```

Put your Desktop client in `%APPDATA%\com.sticky.reminder\oauth.json`:

```json
{ "clientId": "xxxx.apps.googleusercontent.com", "clientSecret": "GOCSPX-xxxx" }
```

Then press **Google ile giriş** in the window's footer. (For desktop apps Google does not treat the client secret as confidential.)

### 3. Android

Requirements: JDK 17+, Android SDK (platform 37, build-tools 37; set `sdk.dir` in `android/local.properties` or `ANDROID_HOME`).

```powershell
cd android
.\gradlew.bat testDebugUnitTest     # 43 tests
.\gradlew.bat assembleRelease       # app/build/outputs/apk/release/
adb install -r app\build\outputs\apk\release\app-release.apk
```

Signing: create `android/keystore.properties` (git-ignored) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Without it the build falls back to the debug key. **Whatever key you use, its SHA-1 must be the one registered in the Android OAuth client**, otherwise sign-in fails. Keep a backup of that key.

To add the widget: long-press the home screen → Widgets → Sticky, or use "Ana ekrana ekle" inside the app.

## Data usage (measured)

Mostly TLS handshakes, not task data:

| Sync | Windows | Android |
| --- | --- | --- |
| nothing changed | ~13 KB | ~4.5 KB (~9.5 KB right after app start) |
| upload (add / delete / done) | ~20 KB | ~12.5 KB |

Roughly **85-165 MB per month** for typical use, depending on how long the Windows window stays open. Details and ways to reduce it are in [`REQUIREMENTS.md`](REQUIREMENTS.md).

## Privacy

- Talks only to Google (sign-in and Drive). No ads, no analytics, no other server.
- Windows keeps the refresh token in **Windows Credential Manager**; Android does not store a refresh token (Play Services issues short-lived access tokens).
- Sign-out is per device and does not delete tasks. To remove the app's access entirely: Google Account → Security → Third-party access.

## Known limitations

- The Windows installer is **not code-signed**, so SmartScreen may warn on other machines.
- Deleted-task tombstones are never purged yet (tiny; the file stays small for normal use).
- Not tested on other devices or Android launchers than a Samsung Galaxy A24 (Android 16) and Windows 11.

## License

No license has been chosen yet, which means all rights are reserved. If you would like to use or contribute to the project, please open an issue.
