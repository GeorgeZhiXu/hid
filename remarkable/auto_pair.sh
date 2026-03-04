#!/bin/sh
# Auto-accept pairing agent for BlueZ
# Runs bluetoothctl and auto-responds to all pairing prompts
exec script -qc '
bluetoothctl << EOF
agent NoInputNoOutput
default-agent
EOF
sleep 86400
' /dev/null <<< "$(while true; do echo yes; sleep 0.5; done)"
