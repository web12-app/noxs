#!/bin/sh
# shellcheck disable=SC1090,SC1091,SC2012,SC2034,SC2086,SC2164,SC2295
# Recreate FHS compatibility symlinks inside the Noxs sandbox (canonical copy).
# App storage cannot always create symlinks during extraction; this runs on
# first in-sandbox login via /etc/profile.d/noxs.sh.
[ -e /bin ] || ln -s usr/bin /bin
[ -e /sbin ] || ln -s usr/sbin /sbin
[ -e /lib ] || ln -s usr/lib /lib
[ -e /lib64 ] || ln -s usr/lib64 /lib64 2>/dev/null
[ -e /var/run ] || ln -s /run /var/run
exit 0
