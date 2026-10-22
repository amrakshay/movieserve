<p align="center">
  <img src="tv-app/app/src/main/res/drawable-nodpi/banner.png" alt="movieserve" width="320">
</p>

<p align="center">
  Watch the films on your laptop or phone, on your TV. No pen drive, no cloud, no transcoding.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT licence"></a>
  <a href="../../actions/workflows/ci.yml"><img src="../../actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/server-Python%203.9%2B-blue" alt="Python 3.9+">
  <img src="https://img.shields.io/badge/apps-Android-blue" alt="Android">
</p>

---

## What it is

Three small pieces that remove one annoying chore — copying a film to a pen drive to watch it on
the TV.

| Piece | What it does |
|---|---|
| [`server/`](server/) | A Python server you point at a folder. **Standard library only** — no `pip install`, no virtualenv |
| [`phone-app/`](phone-app/) | The same server, from an **Android phone**, for when there is no laptop |
| [`tv-app/`](tv-app/) | An **Android TV app** that lists what it finds and hands your pick to **VLC** |

You need the TV app plus *either* the Python server or the phone app. They speak the same two
endpoints, so the TV cannot tell which is on the other end.

The TV app plays nothing itself. VLC already streams over HTTP, seeks, and handles every format
worth having, so it does the work and this stays small — the TV app is 145 KB, the phone app 31 KB.

```
laptop or phone                          TV
┌──────────────────────┐                ┌────────────────────────┐
│ share a folder       │  /api/movies   │ movieserve app         │
│                      │ ◄───────────── │   pick a film          │
│  GET /stream/…       │                │        │               │
│  with range support  │ ─────────────► │        ▼  VLC plays it │
└──────────────────────┘                └────────────────────────┘
```

## Quick start

**On the laptop**

```bash
git clone https://github.com/amrakshay/movieserve.git
cd movieserve
python3 server/movieserve.py ~/Movies
```

It prints the address to use:

```
movieserve 1.0.0
  serving 14 video(s) from /Users/you/Movies
  enter this on the TV:  http://192.168.0.116:18080
```

**On the TV**

1. Install the app — see [docs/USAGE.md](docs/USAGE.md); it is sideloaded with `adb`.
2. Open it, choose **Server address**, type the address above, save.
3. Pick a film. VLC opens and plays it.

That is the whole thing.

### Handing the TV a web link

Typing a URL with a remote is miserable, so pass it from the laptop instead:

```bash
python3 server/movieserve.py --video-url https://example.com/films/movie.mp4
```

It appears under a **Link** heading, and the TV streams it straight from the internet. It must be a
link to a video *file* — a YouTube or streaming-site page will not play, because VLC is handed the
URL as-is.

## Serving from an Android phone

Not everyone has a laptop to hand. [`phone-app/`](phone-app/) does the same job from a phone: pick
a folder, optionally add a web link, press start, and read the address out to whoever is holding
the remote.

It is byte-for-byte the same API, so **the TV app needs nothing changed but the address**. A
foreground service and a wake lock keep it serving once the screen goes off, and the app offers to
exempt itself from battery optimisation, which measurably matters — see
[docs/USAGE.md](docs/USAGE.md#serving-from-an-android-phone).

There is no iOS version, and there probably cannot be a useful one. Apple suspends an app roughly
30 seconds after it leaves the foreground, and its own engineers state there is no way to wake it
when a connection arrives. An iPhone could push a link to the TV; it cannot reliably serve a film.

## Why not just use a file share?

You can, and if SMB or DLNA already works for you, keep using it. Those are fiddly to set up,
awkward to browse with a remote, and often seek badly. `movieserve` is one command, one screen, and
correct HTTP range support so the scrub bar behaves.

## What it deliberately does not do

- **No transcoding.** Your laptop's fan stays off; VLC decodes what it is given.
- **No accounts, no authentication.** Anyone on your network can list and download the shared
  folder. That is the intended trade-off for a tool on a home LAN — see [SECURITY.md](SECURITY.md).
- **No posters or metadata.** Filenames and folders, nothing to configure or fetch.
- **No subtitle handling.** VLC reads subtitles embedded in the file by itself.

## Will it stream smoothly?

The limit is your TV's network, not this software.

| Content | Typical bitrate | Needs |
|---|---|---|
| 1080p web rip | 5–12 Mbps | Fine on decent Wi-Fi |
| 1080p Blu-ray remux | 20–40 Mbps | Strong 5 GHz, or Ethernet |
| 4K | 25–80 Mbps | Ethernet |

Many older Android TVs have **2.4 GHz-only Wi-Fi**, which realistically carries 25–35 Mbps and is
easily disrupted by neighbouring networks. If playback stutters or seeking stalls, a cable to the TV
fixes it more reliably than anything else. [docs/USAGE.md](docs/USAGE.md#troubleshooting) shows how
to check what your TV actually has.

## Tested on

Developed against real hardware rather than emulators, which is where every interesting bug came
from:

| | |
|---|---|
| TV | Xiaomi MiTV-AXSO0, Android TV 9, 1 GB RAM, 1280x720, 2.4 GHz-only Wi-Fi |
| Phone | OnePlus 7, OxygenOS 10.3.8 (Android 10) |
| Laptop | macOS, Python 3.11 |

## Documentation

- [docs/USAGE.md](docs/USAGE.md) — installing, running, the API, and troubleshooting
- [CONTRIBUTING.md](CONTRIBUTING.md) — how to build, test and propose changes
- [SECURITY.md](SECURITY.md) — the threat model, stated plainly

## Requirements

| | |
|---|---|
| Laptop | Python 3.9 or newer. Nothing else. |
| Or a phone | Android 7.0+, using `phone-app/` |
| TV | Android 5.0+ with VLC installed, on the same network |
| Building either app | JDK 17 and the Android SDK (`platforms;android-34`, `build-tools;34.x`) |

## Licence

MIT — see [LICENSE](LICENSE).
