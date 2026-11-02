#!/usr/bin/env python3
import sys
import subprocess
import os

ADB_PATH = r"C:\Users\Yul\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
TV_IP = "192.168.100.70"

def run_adb_command(args):
    cmd = [ADB_PATH, "-s", f"{TV_IP}:5555"] + args
    result = subprocess.run(cmd, capture_output=True, text=True)
    return result.stdout, result.stderr, result.returncode

def shell_command(cmd):
    stdout, stderr, code = run_adb_command(["shell", cmd])
    if code != 0:
        print(f"Error: {stderr}", file=sys.stderr)
        sys.exit(1)
    return stdout.strip()

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python tv.py <command> [args]")
        print("Commands:")
        print("  shell <cmd>        Run shell command on TV")
        print("  screenshot <file>  Take screenshot and save to file")
        sys.exit(1)

    command = sys.argv[1]

    if command == "shell":
        if len(sys.argv) < 3:
            print("Error: shell command requires argument", file=sys.stderr)
            sys.exit(1)
        cmd = " ".join(sys.argv[2:])
        print(shell_command(cmd))

    elif command == "screenshot":
        if len(sys.argv) < 3:
            print("Error: screenshot requires output file path", file=sys.stderr)
            sys.exit(1)
        output_file = sys.argv[2]
        # Take screenshot on device
        stdout, stderr, code = run_adb_command(["shell", "screencap", "-p", "/sdcard/screen.png"])
        if code != 0:
            print(f"Error taking screenshot: {stderr}", file=sys.stderr)
            sys.exit(1)
        # Pull to local
        stdout, stderr, code = run_adb_command(["pull", "/sdcard/screen.png", output_file])
        if code != 0:
            print(f"Error pulling screenshot: {stderr}", file=sys.stderr)
            sys.exit(1)
        # Clean up on device
        run_adb_command(["shell", "rm", "/sdcard/screen.png"])
        print(f"Screenshot saved to {output_file}")

    else:
        print(f"Unknown command: {command}", file=sys.stderr)
        sys.exit(1)
