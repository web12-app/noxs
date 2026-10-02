# /etc/profile.d/noxs.sh — Noxs environment integration (canonical copy)
# CI diff-checks this against the asset embedded in the app.
export NOXS=1
export NOXS_USER=noxs
export NOXS_RUN_DIR=/var/run/noxs
export NOXS_HOME=/home/noxs
export PATH="/usr/local/bin:$PATH"
export PS1='noxs@android:\w\$ '
# recreate FHS links once (app storage cannot create symlinks directly)
if [ -x /usr/local/lib/noxs-links.sh ]; then
    /usr/local/lib/noxs-links.sh >/dev/null 2>&1 || true
fi
# apply resource quotas (children of this shell only)
if [ -r /etc/noxs/resources.conf ]; then
    . /etc/noxs/resources.conf 2>/dev/null || true
    [ -n "$MAX_PROCESSES" ] && ulimit -u "$MAX_PROCESSES" 2>/dev/null || true
    [ -n "$MAX_OPEN_FILES" ] && ulimit -n "$MAX_OPEN_FILES" 2>/dev/null || true
fi
