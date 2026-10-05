# APT extras installed into the Noxs rootfs (kept minimal; canonical 70noxs
# conf lives in linux-runtime/rootfs/overlay/etc/apt/apt.conf.d/70noxs).
# This directory documents additional optional snippets.

# 80noxs-no-recommends-extra:
#   APT::Install-Suggests "false";

# 85noxs-archive-limits:
#   Acquire::Max-FutureTime "3600";
#   Acquire::Retries "3";
