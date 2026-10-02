#!/bin/sh
# shellcheck disable=SC1090,SC1091,SC2012,SC2034,SC2086,SC2164,SC2295
# noxs-launch.sh — reference launch command used for debugging/CI.
# The Android app builds the same argv in ProotLauncher (unit tested).
# This script documents exactly how a Noxs Debian session is created.
#
# NOXS_BASE = app-private base dir, e.g.:
#   /data/data/com.noxs.linux/files/noxs
# Run from an adb shell on a DEBUG build for diagnostics.

NOXS_BASE="${1:-/data/data/com.noxs.linux/files/noxs}"
ROOTFS="$NOXS_BASE/rootfs"
PROOT="$NOXS_BASE/bin/proot"

exec "$PROOT" \
    --kill-on-exit \
    -0 \
    -r "$ROOTFS" \
    -b "$NOXS_BASE/run:/var/run/noxs/host" \
    -b /dev \
    -b /proc \
    -b /sys \
    -b "$NOXS_BASE/etc-noxs-resources.conf:/etc/noxs/resources.conf" \
    -w /home/noxs \
    /bin/su -s /bin/bash -l noxs
