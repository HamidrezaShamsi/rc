#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo ./install.sh" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="/opt/rc-fpv"
BIN_PATH="/usr/local/bin/rc-fpv-stream"
SERVICE_PATH="/etc/systemd/system/rc-fpv-stream.service"
ENV_PATH="/etc/default/rc-fpv-stream"
CONFIG_CANDIDATES=("/boot/firmware/config.txt" "/boot/config.txt")

echo "[1/8] Installing packages..."
apt-get update
apt-get install -y --no-install-recommends rpicam-apps iw

echo "[2/8] Creating install directory..."
mkdir -p "${INSTALL_DIR}"
install -m 0755 "${SCRIPT_DIR}/fpv/stream.sh" "${INSTALL_DIR}/stream.sh"
ln -sf "${INSTALL_DIR}/stream.sh" "${BIN_PATH}"

echo "[3/8] Writing environment file..."
if [[ ! -f "${ENV_PATH}" ]]; then
  cat > "${ENV_PATH}" <<'EOF'
# Receiver side address (your controller laptop/ground station):
STREAM_HOST=192.168.4.2
STREAM_PORT=5600

# Tune these values for latency/quality:
STREAM_WIDTH=1280
STREAM_HEIGHT=720
STREAM_FPS=60
STREAM_BITRATE=5000000
STREAM_INTRA=10
CAMERA_ID=0
DENOISE=cdn_off
STREAM_FORMAT=mpegts
EOF
fi

echo "[4/8] Installing systemd service..."
install -m 0644 "${SCRIPT_DIR}/systemd/rc-fpv-stream.service" "${SERVICE_PATH}"

echo "[5/8] Applying camera boot settings..."
CONFIG_FILE=""
for candidate in "${CONFIG_CANDIDATES[@]}"; do
  if [[ -f "${candidate}" ]]; then
    CONFIG_FILE="${candidate}"
    break
  fi
done

if [[ -z "${CONFIG_FILE}" ]]; then
  echo "No boot config file found at /boot/firmware/config.txt or /boot/config.txt" >&2
  exit 1
fi

ensure_config_line() {
  local key="$1"
  local value="$2"
  local file="$3"

  if grep -Eq "^[# ]*${key}=" "${file}"; then
    sed -i "s|^[# ]*${key}=.*|${key}=${value}|g" "${file}"
  else
    printf "\n%s=%s\n" "${key}" "${value}" >> "${file}"
  fi
}

ensure_config_line "camera_auto_detect" "1" "${CONFIG_FILE}"
ensure_config_line "gpu_mem" "128" "${CONFIG_FILE}"

echo "[6/8] Enabling service..."
systemctl daemon-reload
systemctl enable rc-fpv-stream.service

echo "[7/8] Restarting service..."
systemctl restart rc-fpv-stream.service

echo "[8/8] Installation complete."
echo
echo "Receiver command example (on laptop/controller):"
echo "  ffplay -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 -sync ext udp://@:5600?fifo_size=1000000&overrun_nonfatal=1"
echo
echo "If this is first camera setup, reboot once:"
echo "  sudo reboot"
