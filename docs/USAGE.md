# Usage

Everything you need after cloning the repository.

## 1. Run the server

```bash
python3 server/movieserve.py ~/Movies
```

Options:

| Flag | Default | Meaning |
|---|---|---|
| `--port` | `18080` | Port to listen on |
| `--host` | `0.0.0.0` | Interface to bind. `127.0.0.1` makes it local-only |
| `--video-url` | | One direct video URL to offer alongside the folder |
| `--version` | | Print the version and exit |

### Offering a single web link

Typing a long URL with a TV remote is miserable. Pass it on the laptop instead:

```bash
python3 server/movieserve.py --video-url https://example.com/films/movie.mp4
```

It appears in the app under a **Link** heading. The folder is optional here, so the URL can be
offered on its own, or alongside a folder:

```bash
python3 server/movieserve.py ~/Movies --video-url https://example.com/films/movie.mp4
```

The TV fetches it **straight from the internet** — your laptop only supplies the address and is not
in the data path. One consequence: whether seeking works depends on the remote server supporting
range requests, which is out of our hands.

**It has to be a link to a video file, not to a web page.** VLC is handed the URL directly, so:

| Link | Works |
|---|---|
| `https://example.com/movie.mp4` | yes |
| `https://example.com/stream.m3u8` | yes |
| `https://youtube.com/watch?v=…` | **no** — that is a page, not a file |

A link that is not `http://` or `https://` is refused at startup rather than failing later on the TV.

It walks the folder recursively and lists `.mkv .mp4 .avi .mov .m4v .webm .ts`. Hidden files and
hidden folders are skipped. The folder is re-read on every request, so a film copied in while the
server runs appears without a restart.

Stop it with `Ctrl+C`.

### Keep the laptop awake

A sleeping laptop stops the film. Either keep the lid open, or on macOS:

```bash
caffeinate -i python3 server/movieserve.py ~/Movies
```

## 2. Build and install the TV app

You need JDK 17 and the Android SDK with `platforms;android-34` and `build-tools;34.x`.

```bash
cd tv-app
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
```

The APK lands at `tv-app/app/build/outputs/apk/release/app-release.apk`.

It is signed with the Android SDK's **debug keystore** so it installs without any setup. That is
fine for your own TV. To sign a build for anyone else, set `MOVIESERVE_KEYSTORE`,
`MOVIESERVE_KEYSTORE_PASSWORD`, `MOVIESERVE_KEY_ALIAS` and `MOVIESERVE_KEY_PASSWORD`.

### Put it on the TV

Enable developer options and USB/network debugging on the TV, then:

```bash
adb connect <tv-ip>:5555
adb install -r tv-app/app/build/outputs/apk/release/app-release.apk
```

Turn debugging back off when you are done — it is a shell into your TV for anyone on the network.

## 3. Point the app at the laptop

Open **movieserve** on the TV, choose **Server address**, and type what the server printed, for
example `http://192.168.0.116:18080`. The scheme can be left out; `http://` is assumed.

The address is remembered. If your laptop's IP changes, come back and edit it.

## Serving from an Android phone instead

Build and install `phone-app/` the same way as the TV app:

```bash
cd phone-app
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Then on the phone: **Choose folder**, optionally paste a web link, and **Start server**. The
address to type into the TV is shown on screen.

The folder is picked through Android's Storage Access Framework, so the app can read that folder
and nothing else. The permission is kept across restarts, so you only pick it once.

### Allow background use

**Tap "Allow background use" in the app and grant it.** The button is shown only while the
exemption is missing, and it is the single most useful thing you can do for smooth playback.

This was measured, not guessed. On a OnePlus 7 with Doze forced on deliberately
(`adb shell dumpsys deviceidle force-idle`), requesting a chunk of a film every five seconds:

| | Without the exemption | With it |
|---|---|---|
| Median response | 1,506 ms | ~1,100 ms |
| 90th percentile | **6,233 ms** | ~1,600 ms |
| Worst | **8,141 ms** | ~1,700 ms |
| Over 3 seconds | 7 of 26 | **none** |

Nothing ever *failed* — 86 requests across foreground, background, locked, light Doze and deep
Doze, with zero errors. The foreground service and wake lock hold up. What suffers without the
exemption is speed, and an eight-second stall is a frozen picture.

One caveat on those numbers: the test fired one small request every five seconds, which lets the
radio sleep in between. A film holds a continuous connection and should keep it awake, so this is
closer to a worst case than to normal playback.

### Other things to know

- A **foreground service** keeps it serving when the screen goes off, with an ongoing notification
  that also stops it. A **wake lock** stops the CPU dozing mid-film; the service alone is not
  enough for that.
- **OnePlus, Xiaomi and Samsung add their own killing on top of Android's.** If playback dies
  partway through a film even with the exemption granted, look for a vendor setting too: on
  OxygenOS, Settings, Battery, Battery optimisation, and any "advanced" or "sleep standby"
  optimisation.
- Phones sleep their Wi-Fi radio hard when idle. A phone left untouched can show hundreds of
  milliseconds of latency before the server's wake lock takes effect.
- The phone is doing the serving, so expect battery drain and some warmth over a long film. Charge
  it if you can.
- The folder is read through the Storage Access Framework, so the app can read **that folder and
  nothing else**. Android has no way to grant that without the picker, which is why choosing the
  folder cannot be automated.

## The API

Two endpoints, if you want to build something else against it.

### `GET /api/movies`

```json
{
  "version": "1.0.0",
  "root": "Movies",
  "count": 2,
  "movies": [
    {
      "name": "Top.mkv",
      "folder": "",
      "path": "Top.mkv",
      "size": 1610612736,
      "url": "/stream/Top.mkv"
    },
    {
      "name": "Chase.mp4",
      "folder": "Action",
      "path": "Action/Chase.mp4",
      "size": 524288000,
      "url": "/stream/Action/Chase.mp4"
    }
  ]
}
```

Sorted by folder, then name. `folder` is `""` for a file at the top level.

### `GET /stream/<path>`

The video, with `Accept-Ranges: bytes`. A `Range` header returns `206 Partial Content` with
`Content-Range`; an impossible range returns `416` with the real length. `HEAD` works and reports
the size without a body.

Range support is what lets a player seek. Without it, dragging the scrub bar either fails or
restarts the file.

## Troubleshooting

**"Could not reach …"**
The laptop is asleep, the server is not running, or the address is wrong. Check the server is still
printing to its terminal, and that the IP has not changed — `ipconfig getifaddr en0` on macOS,
`hostname -I` on Linux.

**The app opens but the list is empty**
The folder has no files with a recognised extension, or they are all hidden. Check the server's
startup line: it prints how many videos it found.

**Nothing opens when a film is picked**
A video player must be installed on the TV — VLC and MX Player both work, and many sets ship their
own. The app lists whatever can open a video URL and asks which to use the first time, then
remembers. Change it under **Server address → Default player**, or turn on **Ask every time** to be
asked before each film.

Android's own placeholder (`com.google.android.tv.frameworkpackagestubs`) claims to handle videos
and then reports that nothing can. It is filtered out of the list on purpose, so if you expected to
see it, that is why.

**Playback stutters, or seeking takes many seconds**
Almost always the TV's network rather than the server. Check what the TV's Wi-Fi actually is:

```bash
adb shell getprop ro.wifi.channels     # 11 means 2.4 GHz only
adb shell dumpsys wifi | grep -i "Link speed"
```

A 2.4 GHz-only TV on a crowded channel realistically carries 25–35 Mbps, which is fine for a 1080p
web rip and not enough for a remux. An Ethernet cable solves it outright. Failing that, move the
router's 2.4 GHz band to channel 1 or 11 — the channels that do not overlap their neighbours.

**Filenames with accents or symbols**
Handled: paths are percent-encoded in the API and decoded by the server.

**The TV app shows the list, but pressing OK opens the settings screen**
Fixed in the TV app: the list is focused once its rows exist. If you see it on an older build,
press Down once to move focus into the list, then OK.

**"Cleartext HTTP traffic to ... not permitted"**
Fixed. Android blocks plain HTTP by default for apps targeting SDK 28 and above; both apps ship a
network security config that permits it, because the whole point is talking to a server on your own
network at an address you type in.

**Playback from a phone stutters where the laptop was fine**
Two likely reasons. The phone may not be exempt from battery optimisation — see above. Or the
phone's Wi-Fi is simply weaker than the laptop's; check what each is connected to. A phone on
2.4 GHz serving a TV also on 2.4 GHz is the worst case, because both ends share the same congested
band.
