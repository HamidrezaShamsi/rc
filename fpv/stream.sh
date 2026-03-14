#!/usr/bin/env bash
set -euo pipefail

# Loads optional overrides from /etc/default/rc-fpv-stream when installed as a service.
if [[ -f /etc/default/rc-fpv-stream ]]; then
  # shellcheck disable=SC1091
  source /etc/default/rc-fpv-stream
fi

: "${STREAM_HOST:=192.168.4.2}"
: "${STREAM_PORT:=5600}"
: "${STREAM_WIDTH:=1280}"
: "${STREAM_HEIGHT:=720}"
: "${STREAM_FPS:=60}"
: "${STREAM_BITRATE:=6000000}"
: "${STREAM_INTRA:=30}"
: "${CAMERA_ID:=0}"
: "${DENOISE:=cdn_off}"

if [[ "${STREAM_FPS}" -gt 60 ]]; then
  echo "STREAM_FPS must be <= 60 for stability on most Pi camera pipelines." >&2
  exit 1
fi

# Reduces jitter spikes on Wi-Fi links.
if command -v iw >/dev/null 2>&1; then
  iw dev wlan0 set power_save off || true
fi

exec rpicam-vid \
  --camera "${CAMERA_ID}" \
  --timeout 0 \
  --nopreview \
  --codec h264 \
  --profile baseline \
  --level 4.2 \
  --inline \
  --flush \
  --width "${STREAM_WIDTH}" \
  --height "${STREAM_HEIGHT}" \
  --framerate "${STREAM_FPS}" \
  --bitrate "${STREAM_BITRATE}" \
  --intra "${STREAM_INTRA}" \
  --denoise "${DENOISE}" \
  --output "udp://${STREAM_HOST}:${STREAM_PORT}"
