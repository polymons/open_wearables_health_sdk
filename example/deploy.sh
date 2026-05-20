#!/usr/bin/env bash
# Bash port of deploy.ps1 for macOS/Linux terminals.
set -euo pipefail

DEVICE=''
MODE='release'
SKIP_CLEAN=0
KEEP_APP_DATA=0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ID='com.openwearables.health.sdk.example'

usage() {
    cat <<'EOF'
Usage: ./deploy.sh [options]
  -d, --device <serial>   Target adb device (auto-detected if omitted)
  -m, --mode <mode>       Build mode: release | debug | profile  (default: release)
      --skip-clean        Skip "flutter clean"
      --keep-app-data     Skip uninstalling the app before install
  -h, --help              Show this help
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -d|--device)        DEVICE="$2"; shift 2 ;;
        -m|--mode)          MODE="$2"; shift 2 ;;
        --skip-clean)       SKIP_CLEAN=1; shift ;;
        --keep-app-data)    KEEP_APP_DATA=1; shift ;;
        -h|--help)          usage; exit 0 ;;
        *)                  echo "Unknown option: $1" >&2; usage; exit 1 ;;
    esac
done

case "$MODE" in
    release|debug|profile) ;;
    *) echo "ERROR: -m/--mode must be one of: release, debug, profile" >&2; exit 1 ;;
esac

step() { printf '\n==> %s\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

cd "$SCRIPT_DIR"

if [[ -z "$DEVICE" ]]; then
    mapfile -t devices < <(adb devices | tail -n +2 | awk '$2 == "device" { print $1 }')
    if [[ ${#devices[@]} -eq 0 ]]; then
        fail "No authorized adb devices. Connect a phone and accept the USB debugging prompt."
    fi
    if [[ ${#devices[@]} -gt 1 ]]; then
        fail "Multiple devices found: ${devices[*]}. Re-run with --device <serial>."
    fi
    DEVICE="${devices[0]}"
fi
echo "Target device: $DEVICE"

step "Stopping Gradle daemons (prevents stale file-locks on build/)"
"$SCRIPT_DIR/android/gradlew" --stop

if [[ "$KEEP_APP_DATA" -eq 0 ]]; then
    step "Uninstalling $APP_ID from $DEVICE (use --keep-app-data to skip)"
    adb -s "$DEVICE" uninstall "$APP_ID" 2>&1 || true
fi

if [[ "$SKIP_CLEAN" -eq 0 ]]; then
    step "flutter clean"
    flutter clean
fi

step "flutter pub get"
flutter pub get || fail "flutter pub get failed"

step "flutter build apk --$MODE"
flutter build apk "--$MODE" || fail "flutter build apk failed"

step "flutter install -d $DEVICE --$MODE"
flutter install -d "$DEVICE" "--$MODE" || fail "flutter install failed"

printf '\nDone. %s installed on %s.\n' "$APP_ID" "$DEVICE"
