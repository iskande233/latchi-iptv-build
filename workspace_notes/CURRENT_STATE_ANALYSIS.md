# Latchi IPTV Build - Current State Analysis (2026-06-14)

## Repository Structure
- Root: Build wrapper only (no full source tree at root).
- `IPTVmine-1.0.9.zip`: Contains the full Android project source (Kotlin + resources). This is what Codemagic unzips and builds.
- `codemagic.yaml`: Config to unzip the ZIP, run gradle assembleDebug.
- Other files: Docs (README, GOOGLE_SHEET_SETUP_AR.md, REVIEW_AND_FIXES_AR.md), patch, Google Apps Script, video assets, keystores.
- Git is clean, last commit: "Complete sync settings focus and Gemini home refinements" (e4826c4).

## Extracted Source
- Location: `extracted_project/`
- Full Android app under `extracted_project/app/`
- 55 Kotlin files.
- 36 layout XMLs.
- Package: com.latchi.iptv (customized from original IPTVmine).
- Core player: Stable ExoPlayer in `PlayerActivity.kt` (no FFmpeg, no experimental players - follows the "golden rule").

## Key Implemented Features (from instructions memo #2)
### Server Sync (High priority, mostly done)
- `ServerSyncManager.kt` fully implemented:
  - Background check using ActivationValidator + Google Script.
  - Compares playlist_url.
  - If changed: updates SourcePrefs, clears ChannelCache for profile, stores last applied.
  - Respects MIN_SYNC_INTERVAL (60s), skips MANUAL accounts, thread-safe.
- Integrated in:
  - `HomeFragment.kt`: `startSilentServerSync()` on view created + onResume path.
  - `ChannelListActivity.kt`: check on create.
  - `SettingsActivity.kt`: Manual "force" button + lastSyncAt display.
- `ServerUpdateOverlayHelper.kt`: Professional glassmorphism overlay with gold accents, Arabic text "✅ تم تحديث السيرفر...", fade+scale anim, 2.6s auto dismiss then callback.
- Drawable: `bg_success_dialog.xml` (gradient dark + gold stroke).
- Behavior:
  - Shows overlay only on actual change.
  - Returns to MainActivity + reload for Home/ChannelList.
  - Safe: No sync inside Player yet (intentional per memo).

### Gemini AI Button (done per latest guidance)
- Phone Home only: `cardAIVoice` as 5th card in `fragment_home.xml` (after Matches).
- Removed from general floating (FloatingBackHelper has no AI button now).
- Hidden on TV (no card in TV layouts, no add in TV Home).
- Voice handled via `GeminiVoiceController` + `VoiceHandler`.
- Order in phone Home: Live → Movies → Series → Matches → Gemini.

### TV Focus Separation (Categories vs Channels)
- In `ChannelListActivity.kt`:
  - `focusMode = "channels"` / `"categories"`.
  - On open categories: `recyclerView.descendantFocusability = FOCUS_BLOCK_DESCENDANTS`, focus catGridRecyclerView.
  - On close: restore to channels, requestFocus.
  - Uses `TvFocusHelper`.
- Good for D-Pad / remote: prevents overlap.

### Settings Feedback (partial, good start)
- Phone Settings (`setupPhoneSettings`): Checkmarks ✓ for current language and player mode. Gold highlight for active.
- TV Settings: Dynamic `TvCategory` / `TvOption` list (many options for stream, audio, screen, player, EPG, server_sync, etc.).
- Server Sync option in TV: "Server Sync" with action "server_sync".
- Manual force sync button calls `ServerSyncManager.checkForServerUpdate(..., force=true)`.
- Displays last sync time.
- Language change restarts activity (as expected).

### Other Notes from Code
- Player: Pure ExoPlayer, supports HLS/Progressive/Auto via PlayerPrefs. Controls for lock, fullscreen, etc. No changes to engine.
- No TvChannelPreviewActivity yet (future task).
- Channel loading: Xtream + M3U, local cache in filesDir, parallel fetches for speed.
- Multi-lang: ar/fr/en with LocaleHelper.
- TV vs Phone separation respected in many places (TvUtils.isTv, separate layouts like activity_tv.xml).
- Google Sheet / Activation: Uses `ActivationValidator`, `SourcePrefs`, `google_apps_script_activation.gs` (full v3 for both viewer + dashboard).

## Remaining Tasks (from تعليمات_التحديث_رقم_2_Latchi.txt - priorities)
**Priority 1 - Complete ServerSyncManager**
- Add Server Health Indicator (Online/Offline status, perhaps before accepting new URL).
- Safe sync inside PlayerActivity: If changed during watch, show overlay ~3s, then return to Home (don't break playback).

**Priority 2 - Complete Settings Feedback**
- Full visual polish for TV Settings (✓ marks on active options, gold for selected, like phone).
- Unified professional Overlay (instead of Toast) for all success messages (language, player mode, server sync, etc.).
- Mark ALL active options (not just lang/player).
- Add Server Status (Online/Offline) indicator in Settings.

**Priority 3 - Test & Improve Focus (Categories/Channels on TV)**
- Real testing on TV/emulator (D-Pad, Back closes only categories, OK selects, restore last focus/position).

**Priority 4 - New TV Live Preview Screen (important future)**
- When clicking Live channel on TV: Open dedicated `TvChannelPreviewActivity` (or TvLivePreviewActivity).
  - Preview player area (small Exo or thumbnail?).
  - List of channels in same category.
  - OK on preview or channel → full PlayerActivity.
  - Back → back to categories or previous.
  - Preserve focus/position.
- Do NOT replace existing ChannelList.
- Phone continues to open channels normally.
- Separate from current UI.

**Priority 5 - Additional Polish**
- Continue Watching row in TV Home.
- Favorites row in TV Home.
- Error overlays instead of plain Toasts.
- Live clock (seconds) in phone + TV UIs.
- Server health check before adopting new URL in sync.
- Internal changelog (Build ID, commit, date).
- Dashboard side: Inform users that viewer app will auto-sync via ServerSyncManager.

## Rules to Follow Strictly
- Golden rule: **Never touch core player engine**. Only wrappers, voice commands around current ExoPlayer, UI.
- Allowed voice commands (around player): pause/resume, next/prev, seek, volume, mute, fullscreen, lock/unlock.
- No new players or FFmpeg.
- Update this analysis + the two .txt instruction memos after every change set.
- Do NOT trigger new Codemagic build after every tiny edit. Group tasks, get explicit user OK for build.
- Phone = Touch UX. TV = D-Pad/Remote UX. Keep UIs as separate as possible.
- After changes to source: Update the ZIP (IPTVmine-1.0.9.zip) so builds pick it up. Commit ZIP + updated docs/memos.

## Next Steps Recommendation
1. User confirms direction or specifies starting task (e.g. "ابدأ بالـ Server Health + Player sync" or "كمل Settings Feedback أول").
2. Implement in `extracted_project/`.
3. When group ready: Re-zip the project root into IPTVmine-*.zip (or update version), commit, push (once keys provided).
4. Update memos.
5. Optional: Add internal CHANGELOG.md in app.

## Files to Maintain
- Memos: Keep copies in workspace or root (currently in /uploads/).
- Source edits: Only inside extracted_project/app/src/...
- To "release" changes: Zip the contents of extracted_project/ (matching original ZIP structure) back to IPTVmine-1.0.9.zip or new versioned zip.

## Dashboard Note
- Separate repo mentioned: https://github.com/iskande233/ltc-iptv-dashboard (not cloned here yet).
- After server change in dashboard, viewer should auto-detect via sync.

Analysis complete. Ready for next instructions or API keys.
