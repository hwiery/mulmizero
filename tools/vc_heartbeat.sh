#!/bin/bash
# Pleos Connect Virtual Console keeps Emulation Controls active only while a browser session
# sends heartbeats. This replaces the browser tab when the console is driven from scripts.
#   adb forward tcp:18088 tcp:8088   (the console listens on 8088 inside the emulator)
PORT=${VC_PORT:-18088}
while true; do curl -s -X POST "http://127.0.0.1:$PORT/browser/session?client=mulmizero-cli" >/dev/null; sleep 1; done
