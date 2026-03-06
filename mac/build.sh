#!/bin/bash
cd "$(dirname "$0")"
echo "Compiling screenshot-server..."
swiftc \
    -framework IOBluetooth \
    -framework Foundation \
    -framework ImageIO \
    -framework CoreGraphics \
    -framework ScreenCaptureKit \
    -framework CoreMedia \
    -framework VideoToolbox \
    -framework CoreVideo \
    screenshot-server.swift -o screenshot-server
echo "Done. Run with: ./screenshot-server"
