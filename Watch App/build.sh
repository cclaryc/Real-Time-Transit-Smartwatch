#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
DEFAULT_BOARD="esp32s3_devkitc/esp32s3/procpu"

if [ -d "$PROJECT_ROOT/../.venv/bin" ]; then
    export PATH="$PROJECT_ROOT/../.venv/bin:$PATH"
fi

if [ -z "${ZEPHYR_BASE:-}" ]; then
    if [ -d "$PROJECT_ROOT/../zephyr" ]; then
        export ZEPHYR_BASE="$PROJECT_ROOT/../zephyr"
    else
        echo "ZEPHYR_BASE is not set and ../zephyr was not found." >&2
        exit 1
    fi
fi

BOARD="${BOARD:-$DEFAULT_BOARD}"
BUILD_DIR="${BUILD_DIR:-$PROJECT_ROOT/build}"
FLASH_PORT="${FLASH_PORT:-${ESPTOOL_PORT:-}}"
FLASH_BAUD="${FLASH_BAUD:-}"

PRISTINE=0
CLEAN_ONLY=0
RUN_FLASH=0
ERASE_FLASH=0

while [ $# -gt 0 ]; do
    case "$1" in
        -c|--clean)
            CLEAN_ONLY=1
            ;;
        -p|--pristine)
            PRISTINE=1
            ;;
        -f|--flash|--upload)
            RUN_FLASH=1
            ;;
        --erase)
            ERASE_FLASH=1
            ;;
        --port)
            shift
            if [ $# -eq 0 ]; then
                echo "--port requires a value." >&2
                exit 1
            fi
            FLASH_PORT="$1"
            ;;
        --baud)
            shift
            if [ $# -eq 0 ]; then
                echo "--baud requires a value." >&2
                exit 1
            fi
            FLASH_BAUD="$1"
            ;;
        -h|--help)
            cat <<EOF
Usage: ./build.sh [--clean] [--pristine] [--flash] [--port <device>] [--baud <rate>] [--erase]

Environment overrides:
  BOARD=<board target>       Default: $DEFAULT_BOARD
  BUILD_DIR=<build dir>      Default: $PROJECT_ROOT/build
  ZEPHYR_BASE=<zephyr path>  Auto-detected from ../zephyr if unset
  FLASH_PORT=<device>        Serial port for flashing
  FLASH_BAUD=<rate>          Serial baud rate for flashing

Options:
  --clean                    Remove build directory and exit
  --pristine                 Remove build directory, then configure and build
  --flash, --upload          Flash after build
EOF
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
    shift
done

if [ "$CLEAN_ONLY" -eq 1 ]; then
    rm -rf "$BUILD_DIR"
    echo "Removed build directory: $BUILD_DIR"
    exit 0
fi

if [ "$PRISTINE" -eq 1 ]; then
    rm -rf "$BUILD_DIR"
fi

cmake -GNinja -B "$BUILD_DIR" -S "$PROJECT_ROOT" -DBOARD="$BOARD"
cmake --build "$BUILD_DIR"

if [ "$RUN_FLASH" -eq 1 ]; then
    flash_cmd=(west flash --no-rebuild -d "$BUILD_DIR" -r esp32)
    runner_args=()

    if [ -n "$FLASH_PORT" ]; then
        runner_args+=(--esp-device "$FLASH_PORT")
    fi

    if [ -n "$FLASH_BAUD" ]; then
        runner_args+=(--esp-baud-rate "$FLASH_BAUD")
    fi

    if [ "$ERASE_FLASH" -eq 1 ]; then
        runner_args+=(--erase)
    fi

    if [ ${#runner_args[@]} -gt 0 ]; then
        flash_cmd+=(-- "${runner_args[@]}")
    fi

    "${flash_cmd[@]}"
fi

echo
echo "Build complete:"
echo "  Board: $BOARD"
echo "  Build dir: $BUILD_DIR"
echo "  ELF: $BUILD_DIR/zephyr/zephyr.elf"

if [ "$RUN_FLASH" -eq 1 ]; then
    echo "  Flash: completed"
    echo "Opening serial terminal on ${FLASH_PORT} (115200)..."
    exec screen "${FLASH_PORT}" 115200
fi
