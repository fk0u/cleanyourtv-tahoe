#!/usr/bin/env python3
"""adb-closed.py <ip>: exits 0 only if port 5555 actively refuses (debugging really off)."""
import socket, sys
try:
    socket.create_connection((sys.argv[1], 5555), 5).close(); sys.exit("OPEN: network ADB is still on")
except ConnectionRefusedError: print("closed (connection refused)")
except OSError as e: sys.exit(f"inconclusive: {e} (only 'refused' proves it is closed)")
