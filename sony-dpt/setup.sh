#!/bin/bash
# Deploy TabletPen to Sony DPT-RP1
# Usage: ./setup.sh <device-ip>

DPT_IP="${1:?Usage: ./setup.sh <dpt-ip-address>}"

echo "=== TabletPen Setup for Sony DPT-RP1 ==="
echo "Target: root@$DPT_IP"
echo

echo "Testing SSH..."
ssh -o ConnectTimeout=5 root@$DPT_IP "echo OK" 2>/dev/null
if [ $? -ne 0 ]; then
    echo "ERROR: Cannot SSH to $DPT_IP"
    echo "Make sure:"
    echo "  1. DPT is rooted (dpt-rp1-py)"
    echo "  2. SSH is enabled: python3 -m dptrp1 enable-wifi-ssh"
    echo "  3. Connected to same WiFi"
    exit 1
fi

echo "Deploying tabletpen.py..."
scp tabletpen.py root@$DPT_IP:/data/local/tmp/tabletpen.py

# Check if Python exists
HAS_PYTHON=$(ssh root@$DPT_IP "which python3 2>/dev/null || ls /data/local/tmp/python3 2>/dev/null")
if [ -z "$HAS_PYTHON" ]; then
    echo
    echo "WARNING: Python 3 not found on device!"
    echo "You need to deploy a Python 3 binary for ARM."
    echo "See README.md for instructions."
fi

# Create launcher
ssh root@$DPT_IP 'cat > /data/local/tmp/start-tabletpen.sh << "SCRIPT"
#!/system/bin/sh
# Kill Sony BT services that might conflict
killall -9 com.sony.dp.hid 2>/dev/null

# Ensure Bluetooth is up
hciconfig hci0 up 2>/dev/null
hciconfig hci0 piscan 2>/dev/null

# Run TabletPen
PYTHON=$(which python3 2>/dev/null || echo /data/local/tmp/python3)
exec $PYTHON /data/local/tmp/tabletpen.py "$@"
SCRIPT
chmod +x /data/local/tmp/start-tabletpen.sh'

echo
echo "=== Deployed! ==="
echo "To run: ssh root@$DPT_IP /data/local/tmp/start-tabletpen.sh"
