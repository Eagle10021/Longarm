# Longarm

Control your Linux computers and servers from your Android phone, over Tailscale or your home network.

Each machine runs a small agent, and the app shows only what that machine can do.

- **Controls:** screen brightness for each monitor, volume and mute.
- **Device:** lock, sleep, restart and shut down, each with its own safeguards.
- **System:** CPU, temperature, memory, disks and uptime.
- **Containers:** see Docker containers and restart them, with a confirmation first.
- **Specs:** model, processor, memory, graphics, storage and network addresses.
- **AI:** switch between local AI engines, with one running at a time.
- **Custom cards:** read-only information from your own script.
- **[Legion Control](https://github.com/Eagle10021/Legion-Controller):** on Lenovo Legion laptops, also keyboard lighting, battery, power profile and charging mode.

- **Settings:** light or dark mode, Material You, colour presets, custom themes with your own background picture, refresh speed, and which actions ask first.

## Requirements

- Android 12 or newer.
- Linux with Python 3.10 or newer, and `openssl`.
- For volume, `pactl`. For brightness, KDE Plasma 6. For Docker features, the `docker` command.
- Tailscale, your home network, or both.

## Install

Get the APK from [Releases](https://github.com/Eagle10021/Longarm/releases).

On each computer:

```bash
git clone https://github.com/Eagle10021/Longarm.git
mkdir -p ~/longarm-agent ~/.config/systemd/user
cp -r Longarm/agent/longarm_agent ~/longarm-agent/
cp Longarm/agent/longarm-agent.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now longarm-agent
```

To keep the agent running while logged out, run `loginctl enable-linger $USER` once.

## Adding a device

Open the menu and choose **Add a device**. Devices are labelled **Tailscale**, **Local network**, or both, and the app uses whichever one works.

Tap a device and the app shows a six-digit code. If the device shows the same code, approve it:

- **Legion Control** asks in a window.
- **The agent** logs the code. Run `python3 -m longarm_agent --approve CODE` from `~/longarm-agent`, or `--deny CODE` to refuse.

Android can't list Tailscale devices by itself, so these appear once one device has been added. For the first one, type its Tailscale address under **Connect by hand**, or paste the code from `python3 -m longarm_agent --pair`.

## Settings

The agent reads `~/.config/longarm/agent.json`, which is created with a new token on first run. Restart the agent after changing it.

| Key | Meaning |
| :--- | :--- |
| `listen` | Addresses to listen on. Empty means Tailscale and the home network. |
| `port` | HTTPS port, 47126 by default. |
| `disks` | Mount points to report. Leave out any drive that should be able to sleep, since the app reads these every few seconds. |
| `containers` | `true` to list Docker containers and allow restarting them. |
| `cards_command` | A command, as a list, that prints custom cards. |
| `ai_engines` | Local AI engines the app can switch between. |
| `role` | `computer` or `server`. Servers have no lock or sleep, start with shutdown turned off, and need their name typed to shut down. |
| `allow_restart`, `allow_shutdown` | Whether the app may restart or power off this machine. |

The last two rows can also be changed in the app, under **Edit** for that device. They're stored and enforced on the device, so they apply to every paired phone.

An AI engine is a Docker Compose stack:

```json
{"ai_engines": [{"id": "ollama", "label": "Ollama", "container": "ollama", "compose_dir": "~/ollama"}]}
```

A cards command prints JSON, and is run at most once a minute:

```json
[{"title": "Backups", "rows": [{"label": "Photos", "value": "success", "warning": false}]}]
```

## Restart and shutdown

If the app says a device needs permission, the machine is asking for a password, which is common on servers and over SSH. Run this once on that machine to allow your user to restart and shut it down:

```bash
sudo bash agent/allow-power-actions.sh
```

## Firewall

Tailscale usually works as it is. On a home network with a firewall, allow TCP 47126 and, so devices can be found, UDP 47127, from your local network only. With `ufw`:

```bash
sudo ufw allow from 192.168.0.0/16 to any port 47126 proto tcp
sudo ufw allow from 192.168.0.0/16 to any port 47127 proto udp
```

## Security

- All traffic is HTTPS with a self-signed certificate. The phone remembers each device's certificate and refuses any other.
- A new phone gets the token only after the code is approved on the device. The code is derived from the certificate the phone received, so a machine impersonating the device would show a different code.
- Anyone with the token can control that device, including restarting its containers. **New token** in Legion Control, or removing `token` from `agent.json` and restarting the agent, signs every phone out.
- Finding a device reveals only its name, port and certificate fingerprint.
- Don't expose the port to the internet.

## Building

The app is Kotlin with Jetpack Compose, and needs Android Studio's SDK.

```bash
cd android
./gradlew assembleDebug
```

Release builds are signed when `android/signing.properties` exists, with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. That file and the key must never be committed.

## License

MIT
