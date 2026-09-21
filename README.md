# Apple Maps for Android

Native Android app that renders Apple Maps tiles, search, and GPS — without any Apple developer account or Google Maps SDK.

## How it works

```
DuckDuckGo → JWT → Apple MapKit JS Bootstrap → accessKey (tiles) + access_token (search)
```

The app piggybacks on DuckDuckGo's publicly available MapKit JS token to authenticate with Apple's tile and search APIs. No Apple developer credentials needed.

## Features

- **Apple Maps tiles** — standard, satellite, and hybrid overlay
- **Live search** with autocomplete via Apple MapKit JS REST API (`/v1/searchAutocomplete`)
- **GPS location** with blue dot and accuracy circle
- **Red pin markers** on search results
- **Pan & pinch-to-zoom** — custom Compose Canvas slippy map
- **Zoom +/− controls** and map style toggle
- **Two-level tile cache** — memory LRU + disk persistence
- **Auto-refreshing auth** — tokens refresh every 25 minutes

## Architecture

5 Kotlin files, ~1,100 lines total:

| File | Lines | Purpose |
|------|-------|---------|
| `AppleMapAuth.kt` | 115 | DDG JWT → Apple bootstrap → accessKey + searchToken |
| `AppleMapSearch.kt` | 140 | Search + autocomplete via `api.apple-mapkit.com/v1/` |
| `AppleMapView.kt` | 230 | Custom Compose Canvas tile renderer, blue dot, red pin |
| `TileCache.kt` | 100 | Memory LRU (100 tiles) + disk cache |
| `MainActivity.kt` | 430 | Compose UI, GPS permissions, search flow |

**Zero Google Maps dependency.** Renders Apple's raster tiles directly on a Compose Canvas.

## Build

```bash
# Requirements: JDK 21, Android SDK 35
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

cd apple-maps-android
./gradlew assembleDebug

# APK at: app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## How the RE was done

1. Downloaded iPhone 16 Pro IPSW (iOS 26 / build 24A437)
2. Extracted system cryptex from IPSW, decrypted with fcs-key
3. Pulled GeoServices.framework (72MB) from dyld shared cache
4. Dumped 5,675 Objective-C headers (3,552 from GeoServices alone)
5. Identified 1,927 protobuf message classes for search/directions/tiles
6. Verified auth flow and tile endpoints from headers + traffic analysis
7. Built native Android client speaking the same protocol

## Auth flow detail

```
1. GET https://duckduckgo.com/local.js?get_mk_token=1
   → JWT (MapKit JS token embedded in DDG)

2. GET https://cdn.apple-mapkit.com/ma/bootstrap?apiVersion=2&mkjsVersion=5.79.95&poi=1
   Authorization: Bearer <JWT>
   → { accessKey, authInfo.access_token, tileSources[] }

3. Tiles: https://cdn.apple-mapkit.com/...  (using accessKey in URL path)
4. Search: https://api.apple-mapkit.com/v1/searchAutocomplete?q=...
   Authorization: Bearer <authInfo.access_token>
```

## Not yet implemented

- Directions / routing (Apple's `directions.arpc` endpoint uses protobuf)
- Vector tile rendering (Apple serves protobuf vector tiles at `gspe19-ssl.ls.apple.com`)
- Turn-by-turn navigation
- Street-level imagery (Look Around)
- Proper app icon

## License

MIT

## Disclaimer

This is a research/educational project. Apple Maps data and tiles are property of Apple Inc. Use at your own risk.
