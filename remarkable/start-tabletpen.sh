#!/bin/sh
# Start TabletPen on reMarkable Paper Pro
# Usage: ./start-tabletpen.sh [options]
#   Options are passed to tabletpen (e.g. --ratio 16:9 --floor 80)

echo "=== TabletPen for reMarkable Paper Pro ==="

# Kill previous instances
killall -9 tabletpen bluetoothd 2>/dev/null
sleep 1

# Full Bluetooth module reset (frees PSM 17/19)
echo "Resetting Bluetooth..."
rmmod bnep 2>/dev/null
rmmod btnxpuart 2>/dev/null
sleep 1
rmmod bluetooth 2>/dev/null
sleep 2
modprobe bluetooth
modprobe btnxpuart
sleep 3

# HCI setup
hciconfig hci0 up
sleep 1
hciconfig hci0 class 0x002540  # Peripheral, Tablet
hciconfig hci0 name TabletPen
hciconfig hci0 piscan          # Discoverable + connectable
hciconfig hci0 sspmode 1       # Secure Simple Pairing

# Start bluetoothd (SDP server + no input plugin so PSM 17 is free)
echo "Starting Bluetooth..."
/usr/libexec/bluetooth/bluetoothd --compat --noplugin=input &
sleep 3

# Register HID SDP record
sdptool add KEYB 2>/dev/null

# Make discoverable
bluetoothctl power on 2>/dev/null
bluetoothctl discoverable on 2>/dev/null
bluetoothctl pairable on 2>/dev/null

# Auto-accept pairing agent (sends "yes" to all confirmations)
(while true; do echo yes; sleep 0.3; done) | bluetoothctl > /dev/null 2>&1 &
AGENT_PID=$!

# Start TabletPen
echo "Starting pen input..."
/home/root/tabletpen --device /dev/input/event2 "$@"

# Cleanup
kill $AGENT_PID 2>/dev/null
