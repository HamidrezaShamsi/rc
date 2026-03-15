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

echo "[1/11] Installing packages..."
apt-get update
apt-get install -y --no-install-recommends rpicam-apps iw dnsmasq python3

echo "[2/11] Creating install directory..."
mkdir -p "${INSTALL_DIR}"
install -m 0755 "${SCRIPT_DIR}/fpv/stream.sh" "${INSTALL_DIR}/stream.sh"
install -m 0755 "${SCRIPT_DIR}/fpv/hotspot.sh" "${INSTALL_DIR}/hotspot.sh"
install -m 0755 "${SCRIPT_DIR}/fpv/control_server.py" "${INSTALL_DIR}/control_server.py"
ln -sf "${INSTALL_DIR}/stream.sh" "${BIN_PATH}"

echo "[3/11] Writing environment file..."
if [[ ! -f "${ENV_PATH}" ]]; then
  cat > "${ENV_PATH}" <<'EOF'
# Receiver side address (your controller laptop/ground station):
STREAM_HOST=192.168.4.2
STREAM_PORT=5600

# Pi 5–optimized defaults (Pi 3/4: use 1280x720, 6M bitrate if needed):
STREAM_WIDTH=1920
STREAM_HEIGHT=1080
STREAM_FPS=60
STREAM_BITRATE=8000000
STREAM_INTRA=30
CAMERA_ID=0
DENOISE=cdn_off
STREAM_FORMAT=mpegts
EOF
fi

echo "[4/11] Installing systemd services..."
install -m 0644 "${SCRIPT_DIR}/systemd/rc-fpv-stream.service" "${SERVICE_PATH}"
install -m 0644 "${SCRIPT_DIR}/systemd/rc-fpv-hotspot.service" "/etc/systemd/system/rc-fpv-hotspot.service"
install -m 0644 "${SCRIPT_DIR}/systemd/rc-fpv-control.service" "/etc/systemd/system/rc-fpv-control.service"

echo "[5/11] Installing dnsmasq config for hotspot DHCP..."
install -m 0644 "${SCRIPT_DIR}/etc/dnsmasq.d/rc-fpv-ap.conf" "/etc/dnsmasq.d/rc-fpv-ap.conf"

if [[ ! -f /etc/default/rc-fpv-hotspot ]]; then
  cat > /etc/default/rc-fpv-hotspot <<'HOTSPOT_EOF'
# Optional overrides for the Pi hotspot (AP). Uncomment and set as needed.
# HOTSPOT_SSID=RC-FPV
# HOTSPOT_PASSWORD=fpvstream
# HOTSPOT_CHANNEL=6
HOTSPOT_EOF
fi

echo "[6/11] Applying camera boot settings..."
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

# Pi 5 ignores gpu_mem (dynamic allocation); only set for Pi 3/4
is_pi5() {
  [[ -f /proc/device-tree/model ]] && tr -d '\0' < /proc/device-tree/model 2>/dev/null | grep -q "Raspberry Pi 5"
}

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
if ! is_pi5; then
  ensure_config_line "gpu_mem" "128" "${CONFIG_FILE}"
fi

echo "[7/11] Enabling services..."
systemctl daemon-reload
systemctl enable rc-fpv-stream.service
systemctl enable rc-fpv-hotspot.service
systemctl enable rc-fpv-control.service
systemctl enable dnsmasq.service

echo "[8/11] Starting hotspot (Pi becomes AP; WiFi client will disconnect)..."
systemctl start rc-fpv-hotspot.service || true

echo "[9/11] Restarting stream service..."
systemctl restart rc-fpv-stream.service
systemctl restart rc-fpv-control.service

echo "[10/11] Control API enabled on http://10.42.0.1:8080/api/stream-config"
echo
echo "[11/11] Installation complete."
echo
echo "Hotspot: Pi is now the WiFi AP. Connect your phone/laptop to SSID 'RC-FPV'"
echo "  (password: fpvstream). You'll get an IP in 192.168.4.x; stream goes to .2."
echo "  To change SSID/password: sudo nano /etc/default/rc-fpv-hotspot then"
echo "  sudo systemctl start rc-fpv-hotspot"
echo
echo "Receiver command example (on laptop/controller):"
echo "  ffplay -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 -sync ext udp://@:5600?fifo_size=1000000&overrun_nonfatal=1"
echo
echo "If this is first camera setup, reboot once:"
echo "  sudo reboot"
