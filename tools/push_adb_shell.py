#!/usr/bin/env python3
import sys
from adb_shell.adb_device import AdbDeviceTcp
import base64

TV_IP = "192.168.100.36"
CHUNK_SIZE = 8192

def push_file_base64(local_path, remote_path):
    device = AdbDeviceTcp(TV_IP, 5555)
    device.connect()

    with open(local_path, 'rb') as f:
        data = f.read()

    encoded = base64.b64encode(data).decode('utf-8')
    chunks = [encoded[i:i+CHUNK_SIZE] for i in range(0, len(encoded), CHUNK_SIZE)]

    # Delete if exists
    device.shell(f"rm -f {remote_path}")

    # Create file with first chunk
    device.shell(f"echo '{chunks[0]}' | base64 -d > {remote_path}")

    # Append remaining chunks
    for i, chunk in enumerate(chunks[1:], 1):
        device.shell(f"echo '{chunk}' | base64 -d >> {remote_path}")

    print(f"Pushed {len(chunks)} chunks to {remote_path}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python push_adb_shell.py <local_file> <remote_path>")
        sys.exit(1)

    local_file = sys.argv[1]
    remote_path = sys.argv[2]
    push_file_base64(local_file, remote_path)
