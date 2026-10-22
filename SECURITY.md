# Security

## The threat model, stated plainly

**`movieserve` has no authentication**, in either the Python server or the phone app. Anyone who
can reach the port can list every video in the folder you shared and download any of them. This is
a deliberate design choice for a tool that runs for an hour on a home network, not an oversight.

Traffic is plain HTTP, so it is also unencrypted on your local network. Both apps ship a network
security config permitting cleartext, because the alternative would be a certificate for a private
IP address, which is a worse trade for traffic that never leaves the building.

What that means in practice:

- **Share only a folder you would not mind everyone on your network seeing.** It serves the folder
  you point it at, recursively.
- **Do not port-forward it, and do not expose it to the internet.** It is built for a LAN. There is
  no rate limiting, no access log worth the name, and no password.
- On a shared or public network — an office, a hotel, student accommodation — bind it to localhost
  (`--host 127.0.0.1`) or do not run it at all.

## What it does protect against

- **Path traversal.** Requests are resolved and checked to land inside the shared folder, so `..`
  and symlinks pointing outside are refused. This is covered by tests.
- **Serving arbitrary files.** Only known video extensions are listed and streamed; a request for
  anything else is a 404, even inside the shared folder.
- **Reaching outside the shared folder on a phone.** The phone app serves only content URIs that
  appear in the current listing, so a guessed or stale URI cannot be used to read something else the
  app happens to hold permission for. Android's Storage Access Framework limits it to the chosen
  folder in the first place.

## Reporting a vulnerability

Open a GitHub issue. Given the above, "there is no password" is not a vulnerability — it is
documented behaviour. A path traversal, a crash that can be triggered remotely, or anything that
reaches outside the shared folder is, and is worth reporting.

If you would rather not discuss it publicly, say so in the issue without the details and we will
find another way.
