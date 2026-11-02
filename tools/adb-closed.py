#!/usr/bin/env python3
import sys
import socket

def check_adb_closed(ip, port=5555):
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(5)
        result = sock.connect_ex((ip, port))
        sock.close()

        if result == 0:
            print(f"FAIL: Port {port} is OPEN on {ip}")
            print("ADB is still accessible. Please disable USB debugging on the TV.")
            return False
        else:
            print(f"SUCCESS: Port {port} is CLOSED on {ip}")
            print("ADB connection is properly secured.")
            return True
    except Exception as e:
        print(f"Error checking port: {e}")
        return False

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python adb-closed.py <tv_ip>")
        sys.exit(1)

    ip = sys.argv[1]
    check_adb_closed(ip)
