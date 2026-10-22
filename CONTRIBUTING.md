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

CI checks that the generator still runs, but not that its output matches the committed PNG: the
wordmark uses whichever system font is present, so the bytes differ between machines. Regenerating
on a machine without Helvetica Neue will produce a visibly different banner, so check what you are
committing.

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

## Versioning and releases

The version lives in one place: the [`VERSION`](VERSION) file at the repository root.

- Both Android projects read it at build time through `tools/version.gradle`, which also derives
  `versionCode` from it (1.2.3 becomes 10203).
- The Python server carries a literal, so it can stay a single file with no imports beyond the
  standard library. A test asserts it matches `VERSION`, and fails with the line to edit if not.

The project follows [semantic versioning](https://semver.org/). The HTTP API is the public
interface: changing the shape of `/api/movies`, or how ranges are answered, is a breaking change,
because a TV app in the wild may be older than the server it is pointed at.

### The signing key

Releases are signed with a real key so an installed app can be upgraded in place. The key is **not**
in this repository. It lives on the maintainer's machine at `~/.movieserve/` and in three repository
secrets that the release workflow decodes:

| Secret | What it is |
|---|---|
| `MOVIESERVE_KEYSTORE_BASE64` | The PKCS12 keystore, base64 encoded |
| `MOVIESERVE_KEYSTORE_PASSWORD` | Its password |
| `MOVIESERVE_KEY_PASSWORD` | The key's password. **The same value**: PKCS12 keeps one password for both, and a different key password is silently ignored and then fails at signing time |

The alias is `movieserve` and is set in the workflow, not a secret.

**Losing that keystore means never being able to publish an upgrade** to an already installed app,
because Android refuses an update signed with a different key. Back up `~/.movieserve/` somewhere
durable.

Pull-request builds deliberately do **not** get this key. They sign with a throwaway debug key, so
a pull request from a fork cannot be used to obtain it, and the release workflow refuses to run at
all if the secret is missing rather than quietly publishing unsigned APKs.

To build a signed release locally:

```bash
set -a; source ~/.movieserve/credentials.env; set +a   # note: exported, or Gradle will not see them
export MOVIESERVE_KEYSTORE=~/.movieserve/release.keystore
cd tv-app && ./gradlew assembleRelease
```

### Cutting a release

1. Update `VERSION`.
2. Update `server/movieserve.py`'s `__version__` to match — the test will tell you if you forget.
3. Move the `Unreleased` entries in [`CHANGELOG.md`](CHANGELOG.md) under the new version with
   today's date.
4. Merge to `main`.
5. Tag and push: `git tag v1.2.3 && git push origin v1.2.3`.

Pushing the tag builds both apps, names the APKs after the version, pulls that version's section
out of the changelog for the notes, and publishes a GitHub release. It refuses to run if the tag
does not match `VERSION`, so a mistyped tag fails loudly instead of shipping artefacts labelled
with the wrong number.

A tag containing a hyphen (`v1.3.0-beta1`) is published as a pre-release.

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
