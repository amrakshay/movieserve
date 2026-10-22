## What does this change?

<!-- What is different for someone using movieserve, not only what changed in the code. -->

## Why?

<!-- Link an issue if there is one. -->

## How was it tested?

<!--
Real devices and real networks are where this project's problems live, so this matters more
than usual. For example: "Built and installed on a Mi TV 4A Pro (Android 9), played a 2 GB
1080p MKV over 2.4 GHz Wi-Fi, seeked repeatedly."
-->

- [ ] `python3 -m unittest discover -s server/tests` passes
- [ ] `cd tv-app && ./gradlew test` passes
- [ ] `cd phone-app && ./gradlew test` passes
- [ ] Tried on a real device, if the change could affect playback

## Checklist

- [ ] The Python server still has no third-party dependencies
- [ ] New behaviour around ranges, path resolution or JSON parsing has a test
- [ ] If range handling changed, it changed in **both** the Python server and the phone app
- [ ] Docs updated if behaviour changed
- [ ] The banner was regenerated rather than edited, if it changed
