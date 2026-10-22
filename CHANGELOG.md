# Changelog

All notable changes are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[semantic versioning](https://semver.org/).

The version lives in one place, the [`VERSION`](VERSION) file. The Android projects read it at
build time and the Python server's is checked against it by a test, so the three cannot ship
disagreeing numbers.

## [Unreleased]

Nothing yet.

## [1.0.0] — 2026-10-22

First release. Three pieces that let you watch films from a laptop or phone on an Android TV,
without copying anything to a pen drive.

### Added

- **`server/`** — a Python HTTP server for a folder of videos. Standard library only, so it runs
  with `python3 server/movieserve.py ~/Movies` and no install step. Two endpoints: `/api/movies`
  lists the library as JSON, `/stream/<path>` serves a video.
- **Full HTTP range support**, which is what lets a player seek. Open-ended, closed, suffix,
  clamped and unsatisfiable ranges are all handled, with `416` carrying the real length so a player
  can recover. Python's own `SimpleHTTPRequestHandler` does not do this, which is why the request
  handling is written out rather than subclassed.
- **`--video-url`** — offer one direct web link alongside the folder, or instead of it, so a URL
  can be chosen on the TV rather than typed in with a remote. The TV fetches it straight from the
  internet.
- **`tv-app/`** — an Android TV app that lists what the server has and hands the chosen file to
  VLC. It does no playback itself, so every format VLC supports works. 145 KB, one dependency.
- **`phone-app/`** — the same server from an Android phone, for when there is no laptop. Shares a
  folder through the Storage Access Framework, offers the same web link, and keeps serving while
  the screen is off via a foreground service and a wake lock. 31 KB.
- An offer to exempt the phone app from battery optimisation. Measured on a OnePlus 7 under forced
  deep Doze: without it the ninetieth percentile response was 6.2 s and the worst 8.1 s; with it
  nothing exceeded 3 s.
- Path traversal and non-video requests are refused, on both servers, with tests.

### Notes

Developed against real hardware — a Xiaomi MiTV-AXSO0 (Android TV 9, 1 GB RAM, 720p, 2.4 GHz-only
Wi-Fi) and a OnePlus 7 (OxygenOS 10.3.8). Every interesting bug came from devices rather than
emulators: Android silently blocking cleartext HTTP, a list that loaded but could not be focused,
and a server printing a traceback on every seek.

There is no authentication, by design. See [SECURITY.md](SECURITY.md).

[Unreleased]: https://github.com/amrakshay/movieserve/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/amrakshay/movieserve/releases/tag/v1.0.0
