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
| `--version` | | Print the version and exit |

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

**VLC does not open**
VLC must be installed on the TV. The app targets `org.videolan.vlc` specifically, so a film never
opens in something unexpected.

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
