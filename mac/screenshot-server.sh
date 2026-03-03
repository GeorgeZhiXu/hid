#!/bin/bash
# Screenshot server for TabletPen
# Serves screenshots over HTTP, tunneled via ADB reverse
# Usage: ./screenshot-server.sh

# Find ADB
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
if [ ! -x "$ADB" ]; then
    ADB=$(which adb 2>/dev/null)
fi

# Set up ADB reverse tunnel (keeps retrying)
setup_tunnel() {
    while true; do
        $ADB reverse tcp:9877 tcp:9877 2>/dev/null && break
        echo "Waiting for device..."
        sleep 2
    done
    echo "ADB reverse tunnel ready"
}

# Keep tunnel alive in background
keep_tunnel() {
    while true; do
        sleep 10
        $ADB reverse tcp:9877 tcp:9877 2>/dev/null || echo "Tunnel lost, retrying..."
    done
}

setup_tunnel
keep_tunnel &

echo "Screenshot server on port 9877"
python3 -c "
from http.server import HTTPServer, BaseHTTPRequestHandler
import subprocess

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        subprocess.run(['screencapture','-x','-t','jpg','/tmp/tabletpen-ss.jpg'])
        try:
            with open('/tmp/tabletpen-ss.jpg','rb') as f:
                d = f.read()
        except:
            self.send_error(500)
            return
        self.send_response(200)
        self.send_header('Content-Type','image/jpeg')
        self.send_header('Content-Length',len(d))
        self.end_headers()
        self.wfile.write(d)
        print(f'Sent {len(d)} bytes', flush=True)
    def log_message(self, fmt, *args): pass

HTTPServer(('127.0.0.1',9877),H).serve_forever()
"
