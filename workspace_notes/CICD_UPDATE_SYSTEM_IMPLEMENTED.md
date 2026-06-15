# Automated CI/CD Update System - Fully Implemented (2026-06-14)

## Client Request Summary
"منظومة تحديث أوتوماتيكية متكاملة" that connects Codemagic + GitHub + App so that every successful build automatically notifies users inside the app and allows one-tap install.

## Complete Pipeline Implemented

### 1. Codemagic Automation (codemagic.yaml - UPDATED)
- On successful build:
  - Renames APK to fixed name: `latchi-vip-latest.apk`
  - Automatically uploads to GitHub Releases under tag `latest`
  - Uses `GITHUB_TOKEN` environment variable (user must add classic token with `repo` scope in Codemagic UI)
- Result: Always the same direct download link:
  `https://github.com/iskande233/latchi-iptv-build/releases/latest/download/latchi-vip-latest.apk`

### 2. Remote Version Control (update.json)
- Clean `update.json` created in `assets/` and ready to host on GitHub (raw URL).
- Fields:
  - `versionCode`
  - `versionName`
  - `apkUrl` → points to the fixed `latchi-vip-latest.apk`
  - `releaseNotes` → multi-language object (ar / fr / en)

### 3. In-App Update Engine (UpdateManager.kt - NEW)
- Called automatically from `SplashActivity` after permissions.
- Fetches the remote `update.json` (raw GitHub).
- Compares `versionCode` with `BuildConfig.VERSION_CODE`.
- If newer → shows professional VVIP dialog (blur/gold style matching the app).

### 4. Download + Install
- Uses Android `DownloadManager` (shows progress in notifications).
- After download completes → uses `FileProvider` + `Intent.ACTION_VIEW` to open the system installer.
- Safe on Android 7 → 14 thanks to FileProvider + `REQUEST_INSTALL_PACKAGES`.

### 5. Permissions & Security (AndroidManifest + file_paths.xml)
- Added `REQUEST_INSTALL_PACKAGES`
- Added `FileProvider` configuration pointing to `res/xml/file_paths.xml`
- `file_paths.xml` created (covers Download/ and external paths).

### 6. UI/UX
- New `dialog_update.xml` (gold glassmorphism VVIP style).
- Full multi-language support (strings added to values/, values-en/, values-fr/).
- Dialog is non-cancelable during download for better UX.

## Files Created / Modified for this System
- New: `UpdateManager.kt`
- New: `dialog_update.xml`
- New: `file_paths.xml`
- New: `assets/update.json` (clean, ready for GitHub)
- Modified: `AndroidManifest.xml` (permissions + provider)
- Modified: `SplashActivity.kt` (triggers update check)
- Modified: `strings.xml` (ar/en/fr)
- Modified: `codemagic.yaml` (full GitHub Releases automation with fixed filename)

## How to Activate the Full System (for the user)

1. **In Codemagic**:
   - Go to your app settings → Environment variables
   - Add `GITHUB_TOKEN` = classic GitHub token with **repo** scope (generate at https://github.com/settings/tokens)

2. **Host the control file**:
   - Put the `update.json` from `app/src/main/assets/update.json` in the root of your GitHub repo (or any raw URL).
   - Update the `UPDATE_JSON_URL` constant in `UpdateManager.kt` if you change the location.

3. **Workflow**:
   Push code → Codemagic builds → uploads `latchi-vip-latest.apk` to Releases "latest" → users open app → Splash checks → shows beautiful dialog → one tap download + install.

## Integration Status
This system is now fully merged with all previous work (ServerSyncManager + Health + TvLivePreview + Overlays + Live Clock + Settings improvements + Gemini placement + Focus separation, etc.).

All changes are in `extracted_project/`.
ZIP is updated and ready.

