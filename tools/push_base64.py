#!/usr/bin/env python3
import sys
import subprocess
import base64
import os

ADB_PATH = r"C:\Users\Yul\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
TV_IP = "192.168.100.36"
CHUNK_SIZE = 4096

def run_adb_shell(cmd):
    adb_cmd = [ADB_PATH, "-s", f"{TV_IP}:5555", "shell", " ".join(cmd)]
    result = subprocess.run(adb_cmd, capture_output=True, text=True)
    return result.stdout, result.stderr, result.returncode

def push_file_base64(local_path, remote_path):
    with open(local_path, 'rb') as f:
        data = f.read()

    encoded = base64.b64encode(data).decode('utf-8')
    chunks = [encoded[i:i+CHUNK_SIZE] for i in range(0, len(encoded), CHUNK_SIZE)]

    # Delete if exists
    run_adb_shell(["rm", "-f", remote_path])

    # Create file with first chunk
    stdout, stderr, code = run_adb_shell(["echo", f"'{chunks[0]}'", "|", "base64", "-d", ">", remote_path])
    if code != 0:
        print(f"Error creating file: {stderr}", file=sys.stderr)
        sys.exit(1)

    # Append remaining chunks
    for i, chunk in enumerate(chunks[1:], 1):
        stdout, stderr, code = run_adb_shell(["echo", f"'{chunk}'", "|", "base64", "-d", ">>", remote_path])
        if code != 0:
            print(f"Error appending chunk {i}: {stderr}", file=sys.stderr)
            sys.exit(1)

    # Verify with md5
    print(f"Pushed {len(chunks)} chunks")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python push_base64.py <local_file> <remote_path>")
        sys.exit(1)

    local_file = sys.argv[1]
    remote_path = sys.argv[2]
    push_file_base64(local_file, remote_path)
