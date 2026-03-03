#!/bin/bash
cd "$(dirname "$0")"
echo "Compiling screenshot-server..."
swiftc -framework IOBluetooth -framework Foundation screenshot-server.swift -o screenshot-server
echo "Done. Run with: ./screenshot-server"
