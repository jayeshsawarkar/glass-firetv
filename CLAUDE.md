# Glass for Fire TV: notes for Claude Code

The TV: a Fire TV Stick 4K Max (1st gen), Fire OS 7.7.1.3, at <tv-ip> on the same Wi-Fi (ask the user for its address).
The user chose: debloat (ads, tracking, telemetry), privacy settings, 0.5x animations, the Glass home
screen, a backup first. Keep Alexa, Prime Video, Live TV and system updates. **Leave ADB debugging on.**

Do it in this order, showing the user what each step will change before running it:

1. `./glass.sh status <tv-ip>` (connects; the user accepts the prompt on the TV)
2. `./glass.sh all <tv-ip> --dry-run`, show the user the list
3. `./glass.sh all <tv-ip>`
4. Ask before `adb -s <tv-ip>:5555 reboot`
5. `./glass.sh status <tv-ip>`, then ask the user to press Home and check Glass comes up

Rules: disable, never uninstall, Amazon packages. Never disable the Amazon launcher, Settings, the keyboard,
Bluetooth/remote, the Appstore or WebView. Install Glass before anything takes Home away from Amazon.
If something goes wrong: `./glass.sh undo <tv-ip>`.

## Glass 2.0 (added 2026-11-07)

- Build: `./build.sh` (JDK 17 at /opt/homebrew/opt/openjdk@17, SDK at /opt/homebrew/share/android-commandlinetools).
  Install updates with `./glass.sh install <tv-ip>`: it re-toggles the Home service (so Android re-reads its
  key-filtering capability) and grants WRITE_SECURE_SETTINGS, READ_TV_LISTINGS and usage access. The user approved
  these grants. `./glass.sh undo` revokes them.
- Fire OS 7.7 refuses ADB connections that start on the TV itself (127.0.0.1 or its own IP), so apps can't use the
  TV's own ADB. Turning apps on/off stays on the computer.
- City for weather and day/night: New York by default, changeable in Glass settings → City.
- Strings go through `Tr.t()`; translations in `app/assets/i18n/<lang>.tsv` (es, fr, de, pt, hi).
