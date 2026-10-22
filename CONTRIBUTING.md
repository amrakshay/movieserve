# Contributing

Thanks for looking. This is a small project with a deliberately small scope, so the most useful
thing you can do before writing code is open an issue and check the change fits.

## The scope, so you do not waste your time

`movieserve` streams files from a folder to VLC on a TV. It stays small on purpose.

**In scope:** correctness and reliability of the servers, better handling of awkward filenames and
network conditions, TV-app usability with a remote, phone-app behaviour under Android's power
management, documentation, tests.

**Out of scope** unless something changes our minds: transcoding, user accounts, a web UI, posters
and metadata scraping, running as a system service, anything that adds a runtime dependency to the
server. If you want those, Jellyfin and Plex do them well.

## Getting set up

**Server** — nothing to install.

```bash
python3 server/movieserve.py ~/Movies
python3 -m unittest discover -s server/tests -v
```

**TV app and phone app** — JDK 17 and the Android SDK (`platforms;android-34`,
`build-tools;34.x`). Two independent Gradle projects:

```bash
cd tv-app     # or phone-app
./gradlew test            # JVM unit tests, no device needed
./gradlew assembleRelease
```

Both are signed with the SDK's debug keystore so a build installs with no setup. Override with
`MOVIESERVE_KEYSTORE` and friends to sign for distribution.

### The range code exists three times, on purpose

Range handling lives in the Python server, the phone app and their tests, with the **same cases on
both sides**. They must behave identically: a TV that seeks correctly against a laptop and badly
against a phone is worse than one that fails outright, because nobody knows which half to blame.

If you change one, change the other, and the test that fails is the point.

**The banner** is generated, not hand-drawn. If you change it, regenerate rather than editing the
PNG:

```bash
java tools/BannerGenerator.java
```

## House style

- **The server stays standard library only.** This is the feature that makes it a one-command tool;
  a dependency, however small, breaks that.
- **The TV app adds no dependency it can avoid.** It currently has one: `recyclerview`.
- Comments explain *why*, not *what*. If a line needs explaining, it is usually the reasoning
  behind it that is missing, not a restatement of the code.
- Match the surrounding code rather than introducing a new style.

## Tests

Anything touching range handling, path resolution or JSON parsing needs a test. Those three are
where the real bugs live: a broken range handler means a player cannot seek, a loose path resolver
serves files outside the folder, and a brittle parser crashes the app when the server changes.

Both suites run in CI on every pull request and must pass.

## Pull requests

1. Branch from `main`.
2. Keep the change focused — one idea per pull request.
3. Run both test suites.
4. Describe what changes for a user, not only what changed in the code.
5. Say what you tested it on. "Built and installed on a Mi TV 4A Pro, Android 9" is worth more than
   a green tick, because this software's problems are mostly about real devices and real networks.

## Reporting bugs

Use the issue templates. For playback problems, the single most useful detail is what the TV's
network actually is — `adb shell getprop ro.wifi.channels` and the link speed. Most "it stutters"
reports turn out to be a 2.4 GHz TV on a crowded channel rather than a bug.

If the server is a phone, also say whether it is exempt from battery optimisation
(`adb shell dumpsys deviceidle whitelist | grep movieserve`). Without that exemption a request
under deep Doze was measured at up to eight seconds, which looks exactly like a stuttering bug and
is not one.

### Test on real devices

Every interesting bug in this project came from hardware, not an emulator: Android silently
blocking plain HTTP, a list that loaded but could not be focused, a server printing a traceback on
every seek. If a change could affect playback, say what you ran it on.
