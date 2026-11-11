#!/bin/sh
# Glass: a cleaner, faster Fire TV with a glassy big-screen home screen. Runs over Wi-Fi with adb.
#
#   ./glass.sh all     <tv-ip> [--dry-run]   everything below, in the safe order
#   ./glass.sh backup  <tv-ip>               save the app list and settings (done by all of the others too)
#   ./glass.sh install <tv-ip>               build, install Glass, take over the Home button
#   ./glass.sh debloat <tv-ip> [--dry-run]   turn off Amazon ads, tracking and junk (debloat.txt)
#   ./glass.sh tune    <tv-ip> [--dry-run]   privacy settings and faster animations (settings.txt)
#   ./glass.sh status  <tv-ip>               free memory, what's off, animation speed
#   ./glass.sh wallpaper <tv-ip> photo.jpg   use your own photo
#   ./glass.sh portfolio <tv-ip> holdings.txt   your stocks for the header (SYMBOL SHARES COST per line)
#   ./glass.sh privacy <tv-ip>               what still runs for tracking, ads, logs; who may use the mic
#   ./glass.sh undo    <tv-ip> [--uninstall] put everything back (and remove Glass)
#
# Nothing is deleted from the TV: apps are disabled and undo turns them back on. ADB debugging is left on.
set -e
cd "$(dirname "$0")"

CMD=$1; IP=$2
[ -n "$CMD" ] && [ -n "$IP" ] || { sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
case "$IP" in *:*|emulator-*) ;; *) IP=$IP:5555 ;; esac
shift 2
DRY=""; for a in "$@"; do [ "$a" = --dry-run ] && DRY=1; done

PKG=tv.glass.home
SVC=$PKG/$PKG.HomeService
TVDIS=/data/local/tmp/glass-disabled.txt   # what debloat turned off (kept on the TV, so undo works from any computer)
TVBG=/data/local/tmp/glass-background.txt  # protected apps whose background running was stopped
TVSET=/data/local/tmp/glass-settings.txt   # settings as they were before tune

ADB=$(command -v adb || true)
for a in /opt/homebrew/share/android-commandlinetools/platform-tools/adb "$HOME/Library/Android/sdk/platform-tools/adb" "${ANDROID_HOME:-/nonexistent}/platform-tools/adb"; do
  [ -n "$ADB" ] || { [ -x "$a" ] && ADB=$a; }
done
[ -n "$ADB" ] || { echo "adb not found. macOS: brew install --cask android-platform-tools  ·  Windows: winget install Google.PlatformTools" >&2; exit 1; }
tv() { "$ADB" -s "$IP" shell "$@" </dev/null | tr -d '\r'; } # </dev/null: adb would eat read loops

connect() {
  [ -n "$CONNECTED" ] && return
  case "$IP" in emulator-*) ;; *) "$ADB" connect "$IP" >/dev/null 2>&1 || true ;; esac
  state=$("$ADB" -s "$IP" get-state 2>/dev/null || true)
  if [ "$state" != device ]; then
    echo "Look at the TV: choose \"Always allow from this computer\" and press OK."
    i=0
    while [ "$state" != device ] && [ $i -lt 45 ]; do
      sleep 2; i=$((i + 1))
      "$ADB" connect "$IP" >/dev/null 2>&1 || true
      state=$("$ADB" -s "$IP" get-state 2>/dev/null || true)
    done
  fi
  [ "$state" = device ] || { echo "Couldn't connect to $IP. Is ADB debugging on? (Settings > My Fire TV > Developer Options)" >&2; exit 1; }
  OSNAME=$(tv getprop ro.build.version.name)
  SDK=$(tv getprop ro.build.version.sdk)
  case "$OSNAME" in "Fire OS"*) ;; *) OSNAME="Android $(tv getprop ro.build.version.release)" ;; esac
  echo "Connected to $(tv getprop ro.product.model), $OSNAME"
  CONNECTED=1
}

memory() {
  tv cat /proc/meminfo | awk '/MemTotal/{t=$2} /MemAvailable/{a=$2} END{printf "Free memory: %d MB of %d MB\n", a/1024, t/1024}'
}

backup() {
  connect
  dir=backups/$(date +%Y%m%d-%H%M%S)
  mkdir -p "$dir"
  tv pm list packages -e > "$dir/enabled.txt"
  tv pm list packages -d > "$dir/disabled.txt"
  for ns in global secure system; do tv settings list $ns > "$dir/settings-$ns.txt"; done
  echo "Backup saved in $dir ($(wc -l < "$dir/enabled.txt" | tr -d ' ') apps on)."
}

home_on() {
  cur=$(tv settings get secure enabled_accessibility_services)
  case "$cur" in
    *"$SVC"*) ;;
    ""|null) tv settings put secure enabled_accessibility_services "$SVC" ;;
    *) tv settings put secure enabled_accessibility_services "$cur:$SVC" ;;
  esac
  tv settings put secure accessibility_enabled 1
}

home_off() {
  cur=$(tv settings get secure enabled_accessibility_services)
  rest=$(echo "$cur" | tr ':' '\n' | grep -v -x "$SVC" | grep -v -x null | paste -sd: - || true)
  if [ -n "$rest" ]; then tv settings put secure enabled_accessibility_services "$rest"
  else tv settings delete secure enabled_accessibility_services >/dev/null; fi
}

install() {
  connect
  if [ -n "$DRY" ]; then echo "would install Glass and send the Home button to it"; return; fi
  [ -f out/glass.apk ] || ./build.sh
  "$ADB" -s "$IP" install -r out/glass.apk
  # Off and on again: Android re-reads what the Home service may do after an update.
  home_off
  sleep 5 # long enough for Android to drop its cached copy of the service's capabilities
  home_on
  # Fire OS won't let apps use the TV's own ADB, so Glass gets these once from here instead:
  # Home / screensaver / Now Playing switches, Recent apps, Continue Watching. ./glass.sh undo takes them back.
  tv pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
  tv pm grant $PKG android.permission.READ_TV_LISTINGS
  tv appops set $PKG GET_USAGE_STATS allow
  tv am start -n $PKG/.MainActivity >/dev/null 2>&1
  echo "Glass is on the TV. Press Home on the remote: it should come back to Glass."
}

# Never turned off, whatever debloat.txt says: the home screen, settings, keyboard, remote and Bluetooth,
# the Appstore, WebView, Alexa, Prime Video, Live TV, system updates and Android's own pieces.
protected() {
  case "$1" in
    android|com.android.systemui|com.android.settings*|com.android.providers.*|com.android.shell|$PKG) return 0 ;;
    com.amazon.tv.launcher|com.amazon.tv.settings*|com.amazon.device.settings*|com.amazon.dummy.settings) return 0 ;;
    com.amazon.tv.ime*|*bluetooth*|com.amazon.fireinputdevices|com.amazon.tv.inputpreference*|*tvinput*) return 0 ;;
    com.amazon.venezia|*webview*|com.amazon.vizzini*|*alexa*|com.amazon.firebat|com.amazon.tv.livetv) return 0 ;;
    com.amazon.device.software.ota*|com.amazon.tv.forcedotaupdater*|com.amazon.dcp*|com.amazon.connectivitycontroller) return 0 ;;
    com.amazon.whasettings|com.amazon.uxcontrollerservice|com.amazon.tv.devicecontrolsettings) return 0 ;;
  esac
  return 1
}

# Apps that saved a Wi-Fi network. Turning one off makes Android forget the networks it saved.
wifi_savers() {
  tv dumpsys wifi | grep -o -E '(cname|lname)=[^ ]+' | cut -d= -f2 | sort -u
}

# Apps Glass turned off that are on again (a Fire OS update can do that).
back_on() {
  bg=$(tv "cat $TVBG 2>/dev/null" | sort -u)
  for pkg in $(tv "cat $TVDIS 2>/dev/null" | sort -u); do
    echo "$bg" | grep -q -x "$pkg" && continue # Amazon never let it be turned off; kept from the background instead
    tv pm list packages -e "$pkg" | grep -q -x "package:$pkg" && echo "$pkg"
  done
  return 0
}

debloat() {
  connect
  memory
  savers=$(wifi_savers)
  limited=$(tv "cat $TVBG 2>/dev/null" | sort -u)
  grep -v '^[[:space:]]*#' debloat.txt | while read -r pkg note; do
    [ -n "$pkg" ] || continue
    tv pm list packages -e "$pkg" | grep -q -x "package:$pkg" || continue # missing or already off
    echo "$limited" | grep -q -x "$pkg" && continue # protected by Amazon; already kept from the background
    if protected "$pkg"; then echo "kept on     $pkg  (protected: Glass never turns this off)"; continue; fi
    if echo "$savers" | grep -q -x "$pkg"; then
      echo "kept on     $pkg  (it saved a Wi-Fi network; turning it off would make the TV forget it)"; continue
    fi
    note=$(echo "$note" | sed 's/^# *//')
    if [ -n "$DRY" ]; then echo "would turn off  $pkg  ($note)"; continue; fi
    tv "echo $pkg >> $TVDIS"
    out=$(tv pm disable-user --user 0 "$pkg" 2>&1 || true)
    case "$out" in
      *"new state: disabled"*) echo "turned off  $pkg  ($note)" ;;
      *)
        # Amazon protects some packages. Stop them running in the background instead.
        tv appops set "$pkg" RUN_IN_BACKGROUND ignore >/dev/null 2>&1 || true
        [ "${SDK:-0}" -ge 28 ] && tv appops set "$pkg" RUN_ANY_IN_BACKGROUND ignore >/dev/null 2>&1 || true
        tv am force-stop "$pkg" >/dev/null 2>&1 || true
        tv "echo $pkg >> $TVBG"
        echo "protected   $pkg  ($note): kept from running in the background instead"
        ;;
    esac
  done
  [ -n "$DRY" ] || sleep 10 # Android saves package changes a few seconds later; a restart before that can undo them
}

tune() {
  connect
  fireos7=""; case "$OSNAME" in "Fire OS 7"*|"Fire OS 6"*) fireos7=1 ;; esac
  # Saved once, before the first tune, so undo gets the original values even if tune runs twice.
  have_saved=$(tv "[ -f $TVSET ] && echo yes" || true)
  { grep -v '^[[:space:]]*#' settings.txt; [ -n "$fireos7" ] && echo "global animator_duration_scale 0.5"; } |
  while read -r ns key val; do
    [ -n "$key" ] || continue
    old=$(tv settings get "$ns" "$key")
    if [ -n "$DRY" ]; then echo "would set  $ns $key: $old -> $val"; continue; fi
    [ "$have_saved" = yes ] || tv "echo '$ns $key $old' >> $TVSET"
    tv settings put "$ns" "$key" "$val"
    echo "set  $ns $key = $val"
  done
  [ -n "$fireos7" ] || echo "animator_duration_scale left at 1.0: at 0.5 it breaks Fire OS 8's slide-out menu."
}

status() {
  connect
  memory
  echo "Animations: window $(tv settings get global window_animation_scale), transition $(tv settings get global transition_animation_scale), animator $(tv settings get global animator_duration_scale)"
  echo "Turned off by Glass: $(tv "cat $TVDIS 2>/dev/null" | sort -u | grep -c . || true) apps"
  again=$(back_on)
  if [ -n "$again" ]; then
    echo "Back on since then (a Fire OS update?): $(echo "$again" | tr '\n' ' ')"
    echo "  Turn them off again: ./glass.sh debloat ${IP%:5555}   (Glass also warns about this on Home)"
  fi
  case "$(tv settings get secure enabled_accessibility_services)" in
    *"$SVC"*) echo "Home button: Glass" ;; *) echo "Home button: Amazon" ;; esac
  echo "ADB debugging: on (as you asked). Anyone on your Wi-Fi who you approve on the TV can connect."
}

case "$CMD" in
all)
  connect
  [ -n "$DRY" ] || backup
  install
  debloat
  tune
  if [ -z "$DRY" ]; then
    memory
    echo
    echo "Done. Restart the TV to free the memory those apps held:"
    echo "  $ADB -s $IP reboot     (or Settings > My Fire TV > Restart)"
  fi
  ;;
backup) backup ;;
install) [ -n "$DRY" ] || backup; install ;;
debloat) [ -n "$DRY" ] || backup; debloat; [ -n "$DRY" ] || { memory; echo "Restart the TV to free the memory those apps held."; } ;;
tune) [ -n "$DRY" ] || backup; tune ;;
status) status ;;

privacy)
  # What's still on that looks like telemetry, ads, logging or content recognition, which apps may use the
  # microphone, and whether each one is running now. Read-only: turning things off stays with debloat.txt.
  connect
  echo "Amazon packages still on whose names point at tracking, ads, logs or metrics:"
  limited=$(tv "cat $TVBG 2>/dev/null" | sort -u)
  tv pm list packages -e | sed 's/^package://' | grep -i -E 'metric|minerva|usagestats|telemetr|logger|logman|logan|crash|analytic|acr$|adid|ftvads|advert|sneakpeek|ceviche|perfc|tracking|dataservice|recommend|shoptv|blackbird' | sort |
  while read -r p; do
    run=$(tv "pidof $p >/dev/null && echo running || echo idle")
    if protected "$p"; then note="kept: on Glass's never-touch list"
    elif echo "$limited" | grep -q -x -F "$p"; then note="Amazon won't let it be turned off; kept from the background"
    else note="could be turned off"; fi
    printf '  %-48s %-8s %s\n' "$p" "$run" "$note"
  done
  echo
  # Most of Fire OS's own apps share the system's identity, so they all "have" the microphone; that says nothing.
  # The apps you installed, each judged on its own, tell you more, plus Alexa, which listens only while you hold the button.
  echo "Apps you installed that may use the microphone:"
  for p in $(tv pm list packages -3 | sed 's/^package://'); do
    [ "$(tv "dumpsys package $p | sed -n '/runtime permissions:/,/disabledComponents/p' | grep -c 'RECORD_AUDIO: granted=true'")" -gt 0 ] && echo "  $p"
  done
  echo "  (and Alexa: com.amazon.vizzini, only while the voice button is held)"
  echo
  echo "Ad-blocking DNS: $(tv settings get global private_dns_mode) $(tv settings get global private_dns_specifier)"
  echo "Ad tracking limited: $(tv settings get secure limit_ad_tracking)   Usage data upload: $(tv settings get secure USAGE_METRICS_UPLOAD_ENABLED)"
  ;;

portfolio)
  file=$1
  [ -f "$file" ] || { echo "usage: ./glass.sh portfolio <tv-ip> holdings.txt   (lines: SYMBOL SHARES COST, and CASH 12.34)" >&2; exit 1; }
  connect
  TVTMP=/data/local/tmp/glass-holdings.txt
  "$ADB" -s "$IP" push "$file" $TVTMP >/dev/null
  tv chmod 644 $TVTMP
  tv am start -W -n $PKG/.MainActivity --es holdings $TVTMP >/dev/null 2>&1
  sleep 2
  tv rm -f $TVTMP
  echo "Holdings sent. Glass keeps them in its own storage; the file on the TV is gone."
  ;;

wallpaper)
  photo=$1
  [ -f "$photo" ] || { echo "usage: ./glass.sh wallpaper <tv-ip> photo.jpg" >&2; exit 1; }
  connect
  tmp=$(mktemp -d)/wallpaper.jpg
  # The TV draws at 1920x1080: big photos are scaled down so they load fast and use little memory.
  if command -v magick >/dev/null; then magick "$photo" -resize '1920x1080^' -quality 85 "$tmp"
  elif command -v sips >/dev/null; then cp "$photo" "$tmp"; sips -Z 1920 "$tmp" >/dev/null
  else cp "$photo" "$tmp"; fi
  TVTMP=/data/local/tmp/glass-wallpaper.jpg
  "$ADB" -s "$IP" push "$tmp" $TVTMP >/dev/null
  tv chmod 644 $TVTMP
  tv am start -W -n $PKG/.MainActivity --es from $TVTMP >/dev/null 2>&1
  sleep 1
  tv rm -f $TVTMP
  echo "Wallpaper set. \"Next wallpaper\" in the ☰ panel switches between it and the built-in ones."
  ;;

undo)
  connect
  for pkg in $(tv "cat $TVDIS 2>/dev/null" | sort -u); do tv pm enable "$pkg" >/dev/null && echo "turned on  $pkg"; done
  for pkg in $(tv "cat $TVBG 2>/dev/null" | sort -u); do
    tv appops set "$pkg" RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
    tv appops set "$pkg" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
    echo "background allowed again  $pkg"
  done
  tv "cat $TVSET 2>/dev/null" | while read -r ns key old; do
    [ -n "$key" ] || continue
    if [ "$old" = null ] || [ -z "$old" ]; then tv settings delete "$ns" "$key" >/dev/null 2>&1 || true
    else tv settings put "$ns" "$key" "$old"; fi
    echo "restored  $ns $key = $old"
  done
  tv rm -f $TVDIS $TVBG $TVSET
  # What Glass switched itself (Now Playing, its screensaver), and the permissions install gave it.
  tv cmd notification disallow_listener $PKG/$PKG.NowPlaying >/dev/null 2>&1 || true
  case "$(tv settings get secure screensaver_components)" in
    *"$PKG"*) tv settings put secure screensaver_components com.amazon.ftv.screensaver/.app.services.ScreensaverService
              echo "screensaver: Amazon's again" ;;
  esac
  tv pm revoke $PKG android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true
  tv pm revoke $PKG android.permission.READ_TV_LISTINGS >/dev/null 2>&1 || true
  tv appops set $PKG GET_USAGE_STATS default >/dev/null 2>&1 || true
  home_off
  echo "Home button goes to Amazon's home screen again."
  for a in "$@"; do [ "$a" = --uninstall ] && "$ADB" -s "$IP" uninstall $PKG >/dev/null && echo "Glass removed."; done
  echo "ADB debugging is still on. Turn it off in Settings > My Fire TV > Developer Options if you want."
  ;;

*) sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
