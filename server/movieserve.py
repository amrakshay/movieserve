#!/usr/bin/env python3
"""movieserve — serve a folder of videos to an Android TV over the local network.

Point it at a directory and it exposes two things:

    GET /api/movies        the library as JSON, grouped by folder
    GET /stream/<path>     one video, with HTTP range support

Range support is the part that matters: without it a player cannot seek, and
dragging the scrub bar either fails or restarts the file from the beginning.

Standard library only, so it runs on any Python 3.9+ with no virtualenv and no
install step.

    python3 movieserve.py ~/Movies
    python3 movieserve.py ~/Movies --port 18080

There is no authentication. Anyone who can reach the port can list and download
the files, which is the intended trade-off for a tool on a home network. Do not
expose the port to the internet.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import sys
import urllib.parse
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

__version__ = "1.0.0"

DEFAULT_PORT = 18080

#: Extensions treated as playable. VLC handles far more, but these are the ones
#: worth listing; anything else in the folder is ignored rather than shown and
#: then failing to play.
VIDEO_SUFFIXES = frozenset({".mkv", ".mp4", ".avi", ".mov", ".m4v", ".webm", ".ts"})

#: Read size when streaming. Large enough to keep the pipe full on a busy
#: 2.4 GHz link, small enough that a seek abandons little work.
CHUNK = 256 * 1024

#: "bytes=0-", "bytes=500-999", "bytes=-500". Only a single range is supported:
#: players ask for one, and multipart/byteranges is not worth the complexity.
RANGE_RE = re.compile(r"^bytes=(\d*)-(\d*)$")


class Library:
    """What the server offers: the videos under a directory, and one optional remote URL.

    The tree is walked on every request rather than cached. A home library is
    small, the walk costs milliseconds, and it means a film copied in while the
    server runs shows up without a restart.

    Either source may be absent. Serving a directory alone is the usual case;
    a remote URL alone turns the server into a way of handing one link to the
    TV without typing it on a remote control.
    """

    def __init__(self, root: Path | None = None, video_url: str | None = None) -> None:
        if root is None and not video_url:
            raise ValueError("a directory, a video URL, or both")
        self.root = root.expanduser().resolve() if root is not None else None
        if self.root is not None and not self.root.is_dir():
            raise NotADirectoryError(self.root)
        self.video_url = video_url or None

    @property
    def name(self) -> str:
        """What to call this library, for the JSON and the startup banner."""
        return self.root.name if self.root is not None else "Link"

    def entries(self) -> list[dict]:
        """Every video on offer, sorted by folder then name, the remote URL last."""
        found = self._local_entries()
        if self.video_url:
            found.append(remote_entry(self.video_url))
        return found

    def _local_entries(self) -> list[dict]:
        found: list[dict] = []
        if self.root is None:
            return found
        for dirpath, dirnames, filenames in os.walk(self.root):
            # Skip hidden directories (.git, .DS_Store spam, Synology @eaDir…).
            dirnames[:] = sorted(d for d in dirnames if not d.startswith("."))
            for name in sorted(filenames):
                if name.startswith(".") or Path(name).suffix.lower() not in VIDEO_SUFFIXES:
                    continue
                path = Path(dirpath) / name
                relative = path.relative_to(self.root)
                folder = str(relative.parent) if relative.parent != Path(".") else ""
                try:
                    size = path.stat().st_size
                except OSError:
                    continue   # vanished between walk and stat
                found.append({
                    "name": name,
                    "folder": folder,
                    "path": str(relative),
                    "size": size,
                    "url": "/stream/" + urllib.parse.quote(str(relative)),
                })
        found.sort(key=lambda e: (e["folder"].lower(), e["name"].lower()))
        return found

    def resolve(self, relative: str) -> Path | None:
        """The file for a /stream/ path, or None if it escapes the root.

        The check is done on the resolved path, so symlinks and ".." both have
        to land inside the root to be served.
        """
        if self.root is None:
            return None
        candidate = (self.root / relative).resolve()
        if candidate != self.root and self.root not in candidate.parents:
            return None
        if not candidate.is_file() or candidate.suffix.lower() not in VIDEO_SUFFIXES:
            return None
        return candidate


class Handler(BaseHTTPRequestHandler):
    """One request. `library` is attached to the server, not to each handler."""

    server_version = f"movieserve/{__version__}"
    protocol_version = "HTTP/1.1"   # keep-alive, so a player can seek without reconnecting

    # ---- routing ---------------------------------------------------------------------------

    def do_GET(self) -> None:
        self._route(body=True)

    def do_HEAD(self) -> None:
        # Players ask HEAD first to learn the size and whether ranges are allowed.
        self._route(body=False)

    def _route(self, body: bool) -> None:
        path = urllib.parse.urlsplit(self.path).path
        if path == "/api/movies":
            self._send_library(body)
        elif path.startswith("/stream/"):
            self._send_video(urllib.parse.unquote(path[len("/stream/"):]), body)
        elif path in ("/", "/health"):
            self._send_bytes(b"movieserve is running\n", "text/plain; charset=utf-8", body)
        else:
            self._send_error(HTTPStatus.NOT_FOUND, "no such path")

    # ---- endpoints -------------------------------------------------------------------------

    def _send_library(self, body: bool) -> None:
        entries = self.library.entries()
        payload = json.dumps({
            "version": __version__,
            "root": self.library.name,
            "count": len(entries),
            "movies": entries,
        }, ensure_ascii=False, indent=2).encode()
        self._send_bytes(payload, "application/json; charset=utf-8", body)

    def _send_video(self, relative: str, body: bool) -> None:
        target = self.library.resolve(relative)
        if target is None:
            self._send_error(HTTPStatus.NOT_FOUND, "no such video")
            return

        total = target.stat().st_size
        span = parse_range(self.headers.get("Range"), total)
        if span is False:
            # Unsatisfiable: the spec wants the real length back so the player can retry.
            self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
            self.send_header("Content-Range", f"bytes */{total}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if span is None:
            start, end, status = 0, total - 1, HTTPStatus.OK
        else:
            start, end = span
            status = HTTPStatus.PARTIAL_CONTENT

        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", "video/" + target.suffix.lstrip(".").lower())
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if status == HTTPStatus.PARTIAL_CONTENT:
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
        self.end_headers()
        if body:
            self._pump(target, start, length)

    def _pump(self, target: Path, start: int, length: int) -> None:
        """Copy `length` bytes from `start` to the client.

        A player that seeks, pauses or stops closes the connection mid-write.
        That is normal, not an error, so the broken pipe is swallowed instead of
        dumping a traceback over the console on every seek.
        """
        try:
            with target.open("rb") as handle:
                handle.seek(start)
                remaining = length
                while remaining > 0:
                    block = handle.read(min(CHUNK, remaining))
                    if not block:
                        break
                    self.wfile.write(block)
                    remaining -= len(block)
        except (BrokenPipeError, ConnectionResetError):
            pass

    # ---- plumbing --------------------------------------------------------------------------

    def _send_bytes(self, payload: bytes, content_type: str, body: bool) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if body:
            self.wfile.write(payload)

    def _send_error(self, status: HTTPStatus, message: str) -> None:
        payload = json.dumps({"error": message}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt: str, *args) -> None:
        # One tidy line per request instead of the default's date banner.
        sys.stderr.write(f"{self.address_string()} {fmt % args}\n")


def remote_entry(url: str) -> dict:
    """The library entry for --video-url.

    The TV fetches this straight from the internet; the laptop only hands over the
    address. That keeps the laptop out of the data path, and means whether seeking
    works depends on the remote server honouring range requests, which is not ours
    to control.

    The size is left at 0 because finding it would mean a request to that server at
    list time, which would make the library slow to load and fail whenever the host
    is unreachable.
    """
    path = urllib.parse.urlsplit(url).path
    name = urllib.parse.unquote(path.rsplit("/", 1)[-1]) if path else ""
    if not name:
        name = urllib.parse.urlsplit(url).netloc or url
    return {
        "name": name,
        "folder": "Link",
        "path": url,
        "size": 0,
        "url": url,          # absolute, unlike a local entry's /stream/... path
    }


def is_playable_url(url: str) -> bool:
    """Whether --video-url is something VLC could be handed.

    Only the scheme and a host are checked. Whether the far end actually serves video
    cannot be known without fetching it, and a link to a web page rather than a file
    is the user's to get right -- see the README.
    """
    parts = urllib.parse.urlsplit(url)
    return parts.scheme in ("http", "https") and bool(parts.netloc)


def parse_range(header: str | None, total: int):
    """Interpret a Range header against a file of `total` bytes.

    Returns None when the whole file is wanted, ``(start, end)`` inclusive for a
    partial request, or ``False`` when the range cannot be satisfied.
    """
    if not header:
        return None
    match = RANGE_RE.match(header.strip())
    if not match:
        return None     # a malformed header is ignored, and the whole file sent
    first, last = match.group(1), match.group(2)

    if not first and not last:
        return None
    if not first:
        # "bytes=-500": the final 500 bytes.
        length = int(last)
        if length == 0:
            return False
        start = max(0, total - length)
        return (start, total - 1)

    start = int(first)
    if start >= total:
        return False
    end = int(last) if last else total - 1
    end = min(end, total - 1)
    if end < start:
        return False
    return (start, end)


def lan_address() -> str:
    """This machine's address on the LAN, for the URL printed at startup.

    No packet is actually sent; connecting a UDP socket just asks the routing
    table which interface would be used to reach the outside world.
    """
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()


def build_server(directory: Path | None, host: str, port: int,
                 video_url: str | None = None) -> ThreadingHTTPServer:
    """A server ready to `serve_forever`. Separated out so tests can bind port 0."""
    library = Library(directory, video_url)
    handler = type("BoundHandler", (Handler,), {"library": library})
    httpd = ThreadingHTTPServer((host, port), handler)
    httpd.daemon_threads = True     # a stuck stream must not keep the process alive
    return httpd


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="movieserve",
        description="Serve a folder of videos to an Android TV over the local network.",
    )
    parser.add_argument("directory", type=Path, nargs="?", default=None,
                        help="folder holding the videos (optional when --video-url is given)")
    parser.add_argument("--video-url", default=None, metavar="URL",
                        help="one direct video URL to offer alongside the folder, so it can be "
                             "picked on the TV instead of typed in with a remote. It must point "
                             "at a video file (.mp4, .mkv, .m3u8), not at a web page")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT,
                        help=f"port to listen on (default {DEFAULT_PORT})")
    parser.add_argument("--host", default="0.0.0.0",
                        help="address to bind (default 0.0.0.0, every interface)")
    parser.add_argument("--version", action="version", version=f"movieserve {__version__}")
    args = parser.parse_args(argv)

    if args.directory is None and not args.video_url:
        parser.error("give a directory, --video-url, or both")
    if args.video_url and not is_playable_url(args.video_url):
        parser.error(f"--video-url must be an http(s) URL, got {args.video_url!r}")

    try:
        httpd = build_server(args.directory, args.host, args.port, args.video_url)
    except NotADirectoryError as exc:
        print(f"movieserve: not a directory: {exc}", file=sys.stderr)
        return 2
    except OSError as exc:
        print(f"movieserve: cannot listen on {args.host}:{args.port}: {exc}", file=sys.stderr)
        return 1

    library: Library = httpd.RequestHandlerClass.library     # type: ignore[attr-defined]
    count = len(library.entries())
    print(f"movieserve {__version__}")
    if library.root is not None:
        print(f"  serving {count} video(s) from {library.root}")
    else:
        print(f"  serving {count} video(s)")
    if library.video_url:
        print(f"  plus the link {library.video_url}")
    print(f"  enter this on the TV:  http://{lan_address()}:{args.port}")
    print("  press Ctrl+C to stop")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
