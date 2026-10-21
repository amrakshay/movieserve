# Security

## The threat model, stated plainly

**`movieserve` has no authentication.** Anyone who can reach the port can list every video in the
folder you shared and download any of them. This is a deliberate design choice for a tool that runs
for an hour on a home network, not an oversight.

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

## Reporting a vulnerability

Open a GitHub issue. Given the above, "there is no password" is not a vulnerability — it is
documented behaviour. A path traversal, a crash that can be triggered remotely, or anything that
reaches outside the shared folder is, and is worth reporting.

If you would rather not discuss it publicly, say so in the issue without the details and we will
find another way.
