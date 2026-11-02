from adb_shell.adb_device import AdbDeviceTcp

# Try to connect without authentication first
device = AdbDeviceTcp('192.168.100.36', 5555)
try:
    device.connect()
    print("Connected successfully!")
    # Test with a simple command
    result = device.shell("echo 'test'")
    print(f"Test command result: {result}")
except Exception as e:
    print(f"Connection failed: {e}")
