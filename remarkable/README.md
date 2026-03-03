# TabletPen for reMarkable Paper Pro

Turn a reMarkable Paper Pro into a wireless Bluetooth HID drawing tablet.

Same concept as the Android version but built for reMarkable's Linux OS. Reads Wacom pen input via evdev, sends HID digitizer reports over Bluetooth.

## Requirements

- reMarkable Paper Pro with **Developer Mode** enabled
- SSH access (USB: `ssh root@10.11.99.1`)
- Python 3 on the device (must be deployed — not pre-installed)

## Quick Start

### 1. Enable Developer Mode

On the reMarkable: Settings → General → Paper Tablet → Software → Advanced → Developer Mode

**Warning**: This triggers a factory reset.

### 2. SSH In

```bash
# Connect via USB-C
ssh root@10.11.99.1
# Password: found in Settings → Help → About → Copyrights and Licenses
```

### 3. Enable Bluetooth

```bash
modprobe btnxpuart
systemctl start bluetooth
```

### 4. Deploy and Run

From your laptop:
```bash
scp remarkable/tabletpen.py root@10.11.99.1:/home/root/
ssh root@10.11.99.1
python3 /home/root/tabletpen.py
```

### 5. Pair

On your laptop: Bluetooth settings → pair with the reMarkable.

## How It Works

```
reMarkable Paper Pro
┌─────────────────────────────────────────────┐
│  Wacom digitizer (/dev/input/eventX)         │
│       ↓ evdev                                │
│  tabletpen.py                                │
│       ↓ HID reports                          │
│  BlueZ → L2CAP PSM 17/19 → Bluetooth HID    │
└──────────────────────┬──────────────────────┘
                       │
                  Bluetooth
                       │
                  ┌────▼─────┐
                  │  Laptop   │
                  │  (HID)    │
                  └──────────┘
```

- Reads pen events (X, Y, pressure, tip, barrel, in-range) from the Wacom digitizer via Linux evdev
- Normalizes coordinates to HID range (0–32767 for X/Y, 0–4095 for pressure)
- Sends HID digitizer reports over Bluetooth L2CAP (standard HID protocol)
- Laptop sees it as a native pen tablet — no drivers needed

## HID Reports

Same format as the Android version — fully compatible. A laptop paired with either the Android tablet or the reMarkable will see identical HID reports.

## Technical Details

- **OS**: Codex (Yocto Linux), kernel 6.1.55+
- **CPU**: NXP i.MX 8M Mini (ARM Cortex-A53, AArch64)
- **Screen**: 2160x1620, 11.8" color e-ink
- **Digitizer**: Wacom EMR
- **Bluetooth**: NXP UART chipset (`btnxpuart` module)
- **Bluetooth stack**: BlueZ 5.x
- **HID protocol**: L2CAP PSM 17 (control) + PSM 19 (interrupt)

## Caveats

- **Bluetooth power management**: The reMarkable aggressively powers off Bluetooth on battery. You may need a wake lock or keep it plugged in.
- **Python**: Not pre-installed. Deploy Python 3 binary or use a statically-compiled version.
- **Root required**: L2CAP sockets need root (default SSH is root).
- **Developer Mode**: Required for SSH access; shows a warning on every boot.

## Alternative: C Version

For environments without Python, a C version can be cross-compiled using the reMarkable SDK:

```bash
# Install SDK
chmod +x meta-toolchain-remarkable-*.sh && ./meta-toolchain-remarkable-*.sh -d sdk
source sdk/environment-setup-*

# Cross-compile
$CC -o tabletpen tabletpen.c -levdev -lbluetooth
scp tabletpen root@10.11.99.1:/home/root/
```
