# Changelog

All notable changes to this project will be documented in this file.

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
