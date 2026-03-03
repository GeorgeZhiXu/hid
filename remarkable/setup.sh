#!/bin/bash
# Deploy TabletPen to reMarkable Paper Pro
# Run from your laptop (not on the reMarkable)
#
# Usage: ./setup.sh [IP]
#   Default IP: 10.11.99.1 (USB connection)

REMARKABLE_IP="${1:-10.11.99.1}"

echo "=== TabletPen Setup for reMarkable Paper Pro ==="
echo "Target: root@$REMARKABLE_IP"
echo

# Test SSH connection
echo "Testing SSH connection..."
ssh -o ConnectTimeout=5 root@$REMARKABLE_IP "echo 'SSH OK'" 2>/dev/null
if [ $? -ne 0 ]; then
    echo "ERROR: Cannot connect to reMarkable at $REMARKABLE_IP"
    echo "Make sure:"
    echo "  1. Developer Mode is enabled"
    echo "  2. USB-C cable is connected"
    echo "  3. Password is entered (found in Settings > Help > About)"
    exit 1
fi

# Deploy
echo "Deploying tabletpen.py..."
scp tabletpen.py root@$REMARKABLE_IP:/home/root/tabletpen.py
ssh root@$REMARKABLE_IP "chmod +x /home/root/tabletpen.py"

# Create launcher script on device
ssh root@$REMARKABLE_IP 'cat > /home/root/start-tabletpen.sh << "SCRIPT"
#!/bin/bash
# Load Bluetooth module
modprobe btnxpuart 2>/dev/null
sleep 1

# Start Bluetooth service
systemctl start bluetooth
sleep 2

# Make discoverable
bluetoothctl power on
bluetoothctl discoverable on
bluetoothctl pairable on

# Run TabletPen
python3 /home/root/tabletpen.py
SCRIPT
chmod +x /home/root/start-tabletpen.sh'

echo
echo "=== Deployed! ==="
echo
echo "To run on the reMarkable:"
echo "  ssh root@$REMARKABLE_IP"
echo "  ./start-tabletpen.sh"
echo
echo "Then pair from your laptop's Bluetooth settings."
