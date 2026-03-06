import IOBluetooth
let count = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice])?.count ?? 0
print(count)
