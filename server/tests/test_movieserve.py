"""Tests for movieserve.

Standard library only, to match the server. Run from the repository root:

    python3 -m unittest discover -s server/tests -v

The HTTP tests start a real server on an ephemeral port and talk to it over a
real socket. Range handling is the thing worth testing hardest: a player that
cannot seek is the main way this project can fail in practice.
"""
from __future__ import annotations

import json
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import movieserve  # noqa: E402


class VersionTests(unittest.TestCase):
    """The repository's VERSION file is the single source of truth.

    The Android projects read it at build time, so they cannot drift. The Python server carries a
    literal instead, to stay a single file with no imports beyond the standard library -- which
    means it can drift, so this test is what stops it.
    """

    def test_server_version_matches_the_version_file(self):
        version_file = Path(__file__).resolve().parents[2] / "VERSION"
        declared = version_file.read_text().strip()
        self.assertEqual(
            movieserve.__version__, declared,
            f"server/movieserve.py says {movieserve.__version__!r} but VERSION says {declared!r}. "
            f"Update __version__ to match, or the release will ship disagreeing numbers.")

    def test_version_file_is_semantic(self):
        version_file = Path(__file__).resolve().parents[2] / "VERSION"
        self.assertRegex(version_file.read_text().strip(), r"^\d+\.\d+\.\d+$")

    def test_the_server_header_carries_that_version(self):
        # What a client actually sees, rather than the constant on its own.
        self.assertEqual(movieserve.Handler.server_version,
                         f"movieserve/{movieserve.__version__}")


class ParseRangeTests(unittest.TestCase):
    """parse_range maps a Range header onto a file of a known size."""

    TOTAL = 1000

    def test_no_header_means_whole_file(self):
        self.assertIsNone(movieserve.parse_range(None, self.TOTAL))
        self.assertIsNone(movieserve.parse_range("", self.TOTAL))

    def test_open_ended_range(self):
        self.assertEqual(movieserve.parse_range("bytes=200-", self.TOTAL), (200, 999))

    def test_closed_range(self):
        self.assertEqual(movieserve.parse_range("bytes=200-499", self.TOTAL), (200, 499))

    def test_suffix_range_returns_the_tail(self):
        self.assertEqual(movieserve.parse_range("bytes=-300", self.TOTAL), (700, 999))

    def test_suffix_longer_than_file_is_clamped_to_the_whole_file(self):
        self.assertEqual(movieserve.parse_range("bytes=-5000", self.TOTAL), (0, 999))

    def test_end_beyond_the_file_is_clamped(self):
        self.assertEqual(movieserve.parse_range("bytes=900-5000", self.TOTAL), (900, 999))

    def test_start_beyond_the_file_is_unsatisfiable(self):
        self.assertIs(movieserve.parse_range("bytes=1000-", self.TOTAL), False)
        self.assertIs(movieserve.parse_range("bytes=4000-5000", self.TOTAL), False)

    def test_backwards_range_is_unsatisfiable(self):
        self.assertIs(movieserve.parse_range("bytes=500-200", self.TOTAL), False)

    def test_zero_length_suffix_is_unsatisfiable(self):
        self.assertIs(movieserve.parse_range("bytes=-0", self.TOTAL), False)

    def test_malformed_header_falls_back_to_the_whole_file(self):
        # Ignoring nonsense is friendlier than refusing: the player still gets its video.
        self.assertIsNone(movieserve.parse_range("bytes=abc", self.TOTAL))
        self.assertIsNone(movieserve.parse_range("seconds=1-2", self.TOTAL))
        self.assertIsNone(movieserve.parse_range("bytes=0-1,5-6", self.TOTAL))

    def test_single_byte(self):
        self.assertEqual(movieserve.parse_range("bytes=0-0", self.TOTAL), (0, 0))


class LibraryTests(unittest.TestCase):
    """Walking the directory: what is listed, what is ignored, what is refused."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        (self.root / "Action").mkdir()
        (self.root / ".hidden").mkdir()
        (self.root / "Top.mkv").write_bytes(b"a" * 10)
        (self.root / "Action" / "Chase.mp4").write_bytes(b"b" * 20)
        (self.root / "notes.txt").write_text("not a video")
        (self.root / ".secret.mkv").write_bytes(b"c" * 5)
        (self.root / ".hidden" / "Hidden.mkv").write_bytes(b"d" * 5)
        self.library = movieserve.Library(self.root)

    def test_lists_videos_recursively(self):
        paths = {e["path"] for e in self.library.entries()}
        self.assertEqual(paths, {"Top.mkv", "Action/Chase.mp4"})

    def test_ignores_non_video_and_hidden_files_and_folders(self):
        names = {e["name"] for e in self.library.entries()}
        self.assertNotIn("notes.txt", names)
        self.assertNotIn(".secret.mkv", names)
        self.assertNotIn("Hidden.mkv", names)

    def test_entry_shape(self):
        entry = next(e for e in self.library.entries() if e["name"] == "Chase.mp4")
        self.assertEqual(entry["folder"], "Action")
        self.assertEqual(entry["size"], 20)
        self.assertEqual(entry["url"], "/stream/Action/Chase.mp4")

    def test_top_level_files_have_an_empty_folder(self):
        entry = next(e for e in self.library.entries() if e["name"] == "Top.mkv")
        self.assertEqual(entry["folder"], "")

    def test_sorted_by_folder_then_name(self):
        # Top-level ("") sorts before "Action".
        order = [e["path"] for e in self.library.entries()]
        self.assertEqual(order, ["Top.mkv", "Action/Chase.mp4"])

    def test_url_is_percent_encoded(self):
        (self.root / "A Film & Co.mkv").write_bytes(b"x" * 3)
        entry = next(e for e in self.library.entries() if e["name"] == "A Film & Co.mkv")
        self.assertEqual(entry["url"], "/stream/A%20Film%20%26%20Co.mkv")

    def test_resolve_accepts_a_listed_file(self):
        self.assertIsNotNone(self.library.resolve("Action/Chase.mp4"))

    def test_resolve_refuses_traversal(self):
        self.assertIsNone(self.library.resolve("../outside.mkv"))
        self.assertIsNone(self.library.resolve("Action/../../outside.mkv"))

    def test_resolve_refuses_a_non_video(self):
        self.assertIsNone(self.library.resolve("notes.txt"))

    def test_resolve_refuses_a_missing_file(self):
        self.assertIsNone(self.library.resolve("Nope.mkv"))

    def test_rejects_a_root_that_is_not_a_directory(self):
        with self.assertRaises(NotADirectoryError):
            movieserve.Library(self.root / "Top.mkv")


class VideoUrlTests(unittest.TestCase):
    """--video-url: one remote link offered alongside (or instead of) a folder."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        (self.root / "Local.mkv").write_bytes(b"x" * 8)

    def test_url_appears_alongside_local_files(self):
        library = movieserve.Library(self.root, "https://example.com/films/Remote.mkv")
        entries = library.entries()
        self.assertEqual([e["name"] for e in entries], ["Local.mkv", "Remote.mkv"])

    def test_url_entry_is_absolute_and_grouped_under_link(self):
        library = movieserve.Library(self.root, "https://example.com/films/Remote.mkv")
        entry = library.entries()[-1]
        self.assertEqual(entry["url"], "https://example.com/films/Remote.mkv")
        self.assertEqual(entry["folder"], "Link")
        self.assertEqual(entry["size"], 0)

    def test_url_alone_needs_no_directory(self):
        library = movieserve.Library(None, "https://example.com/a.mp4")
        self.assertEqual(len(library.entries()), 1)
        self.assertEqual(library.name, "Link")

    def test_neither_source_is_refused(self):
        with self.assertRaises(ValueError):
            movieserve.Library(None, None)

    def test_resolve_never_serves_local_files_without_a_root(self):
        library = movieserve.Library(None, "https://example.com/a.mp4")
        self.assertIsNone(library.resolve("anything.mkv"))

    def test_name_comes_from_the_last_path_segment(self):
        self.assertEqual(
            movieserve.remote_entry("https://example.com/a/b/My%20Film.mkv")["name"],
            "My Film.mkv")

    def test_name_falls_back_to_the_host_when_the_path_has_none(self):
        self.assertEqual(movieserve.remote_entry("https://example.com/")["name"], "example.com")
        self.assertEqual(movieserve.remote_entry("https://example.com")["name"], "example.com")

    def test_accepts_http_and_https(self):
        self.assertTrue(movieserve.is_playable_url("http://example.com/a.mp4"))
        self.assertTrue(movieserve.is_playable_url("https://example.com/a.mp4"))

    def test_rejects_anything_vlc_could_not_be_handed(self):
        for bad in ("", "example.com/a.mp4", "ftp://example.com/a.mp4",
                    "file:///etc/passwd", "javascript:alert(1)", "http://"):
            self.assertFalse(movieserve.is_playable_url(bad), bad)


class HttpTests(unittest.TestCase):
    """A real server on a real socket."""

    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        cls.root = Path(cls._tmp.name)
        cls.payload = bytes(range(256)) * 8      # 2048 bytes, every value, easy to slice
        (cls.root / "Films").mkdir()
        (cls.root / "Films" / "Demo.mkv").write_bytes(cls.payload)
        cls.httpd = movieserve.build_server(cls.root, "127.0.0.1", 0)
        cls.base = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=5)
        cls._tmp.cleanup()

    def get(self, path, headers=None, method="GET"):
        request = urllib.request.Request(self.base + path, headers=headers or {}, method=method)
        return urllib.request.urlopen(request, timeout=10)

    # ---- library ---------------------------------------------------------------------------

    def test_api_lists_the_library(self):
        with self.get("/api/movies") as response:
            self.assertEqual(response.status, 200)
            body = json.loads(response.read())
        self.assertEqual(body["count"], 1)
        self.assertEqual(body["movies"][0]["path"], "Films/Demo.mkv")
        self.assertEqual(body["movies"][0]["size"], len(self.payload))

    def test_health_endpoint(self):
        with self.get("/") as response:
            self.assertEqual(response.status, 200)

    # ---- streaming -------------------------------------------------------------------------

    def test_whole_file(self):
        with self.get("/stream/Films/Demo.mkv") as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.headers["Accept-Ranges"], "bytes")
            self.assertEqual(response.read(), self.payload)

    def test_head_reports_size_without_a_body(self):
        with self.get("/stream/Films/Demo.mkv", method="HEAD") as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(int(response.headers["Content-Length"]), len(self.payload))
            self.assertEqual(response.read(), b"")

    def test_range_returns_206_and_the_right_slice(self):
        with self.get("/stream/Films/Demo.mkv", {"Range": "bytes=100-199"}) as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(response.headers["Content-Range"],
                             f"bytes 100-199/{len(self.payload)}")
            self.assertEqual(int(response.headers["Content-Length"]), 100)
            self.assertEqual(response.read(), self.payload[100:200])

    def test_open_ended_range_runs_to_the_end(self):
        start = len(self.payload) - 10
        with self.get("/stream/Films/Demo.mkv", {"Range": f"bytes={start}-"}) as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(response.read(), self.payload[start:])

    def test_suffix_range(self):
        with self.get("/stream/Films/Demo.mkv", {"Range": "bytes=-16"}) as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(response.read(), self.payload[-16:])

    def test_unsatisfiable_range_returns_416_with_the_real_length(self):
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.get("/stream/Films/Demo.mkv", {"Range": "bytes=99999-"})
        self.assertEqual(caught.exception.code, 416)
        self.assertEqual(caught.exception.headers["Content-Range"], f"bytes */{len(self.payload)}")

    def test_percent_encoded_path(self):
        (self.root / "Films" / "A Film.mkv").write_bytes(b"hello")
        with self.get("/stream/Films/A%20Film.mkv") as response:
            self.assertEqual(response.read(), b"hello")

    # ---- refusals --------------------------------------------------------------------------

    def test_missing_video_is_404(self):
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.get("/stream/Nope.mkv")
        self.assertEqual(caught.exception.code, 404)

    def test_unknown_path_is_404(self):
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.get("/nope")
        self.assertEqual(caught.exception.code, 404)

    def test_a_player_hanging_up_mid_stream_is_not_an_error(self):
        """A seek closes the connection while the server is still writing.

        Observed on a real TV: VLC drops the socket on every seek, which used to print a
        ConnectionResetError traceback per seek. The server must carry on serving others.
        """
        import socket
        sock = socket.create_connection(("127.0.0.1", self.httpd.server_address[1]), timeout=10)
        sock.sendall(b"GET /stream/Films/Demo.mkv HTTP/1.1\r\nHost: x\r\n\r\n")
        sock.recv(64)          # take only the first bytes, then walk away mid-body
        sock.close()
        # The next request must still be answered normally.
        with self.get("/api/movies") as response:
            self.assertEqual(response.status, 200)

    def test_traversal_is_refused(self):
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.get("/stream/..%2F..%2Fetc%2Fpasswd")
        self.assertEqual(caught.exception.code, 404)


if __name__ == "__main__":
    unittest.main()
