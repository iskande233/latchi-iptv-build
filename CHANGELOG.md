# Changelog

All notable changes to this project will be documented in this file.

## [2026-06-20 Per-Type Room Freshness] - 2026-06-20
### Added
- **Per-type freshness tracking** in `CatalogSyncStateEntity`:
  - `liveRevision`/`liveHash`/`liveUrl`/`liveLastSyncAt`
  - `beinRevision`/`beinHash`/`beinUrl`/`beinLastSyncAt`
  - `moviesRevision`/`moviesHash`/`moviesUrl`/`moviesLastSyncAt`
  - `seriesRevision`/`seriesHash`/`seriesUrl`/`seriesLastSyncAt`
- `CatalogType` enum unifying script ↔ internal type mapping (live, bein, movies, series).
- `isCatalogFresh()` API — checks per-type revision/hash against the server's `get_catalog_meta`.
- `getChannelsByTypeSmart()` API — Offline-First getter that returns Room data immediately
  then triggers a silent freshness check + re-sync if needed.
- `invalidateAllCatalogs()` / `invalidateCatalog()` — Room invalidation hooks.
- `FreshnessResult` diagnostic data class with reason, local/server revision/hash, network reachability.

### Fixed
- **Channels show quickly but don't play after server broadcast**.
  Root cause: `CatalogSyncStateEntity` only stored the global `serverRevision`.
  When admin uploaded a new prepared catalog, the app couldn't detect that
  a specific type (e.g. live) had changed because it was comparing the wrong
  field against `get_catalog_meta`.
- **"Server doesn't change" symptom**: when admin broadcasted, the old Room
  data persisted and was served without re-validation.
- Now each catalog type is checked independently with its own `revision` and
  `hash`, so updating live does not falsely invalidate movies and vice versa.
- `ServerSyncManager` now calls `CatalogRepository.invalidateAllCatalogs()`
  when the server revision changes — guaranteeing a clean re-sync.

### Changed
- `syncTypeFromApi()` uses the per-type local revision/hash instead of the
  global `serverRevision` for the `get_catalog_meta` `not_modified` check.
- `saveChannels()` now stores per-type `revision`/`hash`/`url` and `lastSyncAt`
  in addition to the global `serverRevision`.
- `HomeFragment`, `TvLivePreviewActivity`, and `ChannelListActivity` now use
  the smart getter so freshness is verified before showing Room data.

### Migration
- `CatalogDatabase` bumped to **version 2**.
- Uses `fallbackToDestructiveMigration()` (already enabled) — the cache will
  be rebuilt on first launch after upgrade. Data is recoverable via re-sync.

## [2026-06-19 Consolidated TV Phase] - 2026-06-19  
### Added  
- TV home **prayer summary widget** showing region, next prayer name, and next prayer time.  
- Support for **prepared catalogs** via remote config: `prepared_live_url`, `prepared_bein_url`, `prepared_movies_url`, `prepared_series_url`.  
- `PreparedCatalogHelper` to read lightweight JSON catalogs instead of relying only on raw sources.  
- Remote filter config storage for `hidden_categories`, `bein_keywords`, `bein_max_keywords`, and `alwan_keywords`.  

### Fixed  
- Fixed TV **fullscreen focus frame** so the yellow focus border no longer remains visible in full-screen playback.  
- Improved TV live refresh flow and unified profile refresh behavior after server sync.  
- Updated app endpoints to the latest **Google Script deployment**.  

### Changed  
- beIN TV flow now opens directly into a filtered TV preview layout.  
- TV Live and TV VOD/Series paths are now prepared to consume **fast prepared data** when available.  
- TV home top information area was compacted to better preserve visible icon space.  

## [1.0.8] - 2025-01-09  
### Added  
- Updated **UI design** for a modern and user-friendly experience.  
- **Fast Channel data handling** to improve Channel speed and reliability.  

### Fixed  
- Resolved minor bugs affecting channel loading and playback.  
- Fixed crashes occurring during user session management.  
- Improved overall stability and performance of the app.  

### Changed  
- Enhanced search and filtering to respond faster to user queries.  
- Redesigned **HomeFragment** for better usability and navigation flow.  
- Updated **PlayerFragment** to support smoother transitions between channels.  

## [1.0.0] - 2024-09-15  
### Added  
- Initial release of **IPTV Mine** with M3U playlist support.  
- **ChannelsProvider ViewModel** to fetch, parse, and filter channels based on user queries.  
- **Search Feature** with a debounce mechanism for better filtering.  
- Integrated **ExoPlayer** for seamless video streaming.  
- Support for **HLS (HTTP Live Streaming)** media sources.  
- Added **full-screen mode** and **orientation handling** in the video player.  
- Basic **UI layout** for channel listing, player controls, and home screen.  

### Changed  
- Updated the **UI layout** to improve user experience, including:  
  - Improved search functionality with better filtering options.  
  - Redesigned **HomeFragment** for more intuitive navigation.  
  - Updated **channel list display** to be more visually appealing.  

### Fixed  
- Resolved issues with video playback position handling.  
- Fixed **NullPointerException** when loading specific channels from the playlist.
