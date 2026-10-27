"""Local deterministic API, slow range transfer, and HLS fixtures; no remote media."""
import http.server
import json
import os
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[1] / ".deps"

class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_GET(self):
        base = f"http://127.0.0.1:{self.server.server_port}"
        if self.path.startswith("/api"):
            body = json.dumps({"pagecount":1, "class":[{"type_id":1,"type_name":"测试视频"}], "list":[{
                "vod_id": "test", "vod_name": "本地播放测试", "vod_play_from":"fixture",
                "vod_play_url":f"直链${base}/playback-test.mp4#HLS${base}/hls/index.m3u8"
            }]}).encode()
            self.send_response(200); self.send_header("Content-Type","application/json")
            self.send_header("Content-Length",str(len(body))); self.end_headers(); self.wfile.write(body)
        elif self.path == "/playback-test.mp4":
            data = (ROOT / "playback-test.mp4").read_bytes()
            offset = int(self.headers.get("Range", "bytes=0-").split("=")[1].split("-")[0])
            partial = "Range" in self.headers and self.headers.get("If-Range") == '"fixture-v1"'
            if not partial: offset = 0
            self.send_response(206 if partial else 200)
            self.send_header("ETag", '"fixture-v1"')
            self.send_header("Content-Length", str(len(data)-offset))
            if partial: self.send_header("Content-Range", f"bytes {offset}-{len(data)-1}/{len(data)}")
            self.end_headers()
            try:
                for i in range(offset, len(data), 65536):
                    self.wfile.write(data[i:i+65536]); self.wfile.flush(); time.sleep(.025)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError): pass
        else:
            super().do_GET()

if __name__ == "__main__":
    http.server.ThreadingHTTPServer(("127.0.0.1", 18764), Handler).serve_forever()
