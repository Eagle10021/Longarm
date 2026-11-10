#!/bin/bash
# Allows the user running sudo to restart and power off this machine without a password, which the agent needs.
set -eu
if [ "$(id -u)" != 0 ] || [ -z "${SUDO_USER:-}" ]; then
    echo "Run with sudo from the account the agent runs as." >&2
    exit 1
fi
cat > /etc/polkit-1/rules.d/49-longarm-power.rules <<RULE
polkit.addRule(function (action, subject) {
    var power = /^org\.freedesktop\.login1\.(power-off|reboot)(-multiple-sessions|-ignore-inhibit)?$/;
    if (subject.user == "$SUDO_USER" && power.test(action.id)) {
        return polkit.Result.YES;
    }
});
RULE
chmod 644 /etc/polkit-1/rules.d/49-longarm-power.rules
echo "Done. $SUDO_USER can now restart and shut down this machine."
