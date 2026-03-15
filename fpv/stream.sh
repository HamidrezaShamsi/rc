#!/usr/bin/env bash
set -euo pipefail

# Loads optional overrides from /etc/default/rc-fpv-stream when installed as a service.
if [[ -f /etc/default/rc-fpv-stream ]]; then
  # shellcheck disable=SC1091
  source /etc/default/rc-fpv-stream
fi

: "${STREAM_HOST:=192.168.4.2}"
: "${STREAM_PORT:=5600}"
# mpegts = H.264 in MPEG-TS over UDP (timestamped; best for Android playback).
# raw = raw H.264 over UDP (legacy).
: "${STREAM_FORMAT:=mpegts}"
: "${STREAM_WIDTH:=1920}"
: "${STREAM_HEIGHT:=1080}"
: "${STREAM_FPS:=60}"
: "${STREAM_BITRATE:=8000000}"
: "${STREAM_INTRA:=30}"
: "${CAMERA_ID:=0}"
: "${DENOISE:=cdn_off}"

# Pi 5 can do higher FPS; Pi 3/4 typically best at <= 60
is_pi5() {
  [[ -f /proc/device-tree/model ]] && tr -d '\0' < /proc/device-tree/model 2>/dev/null | grep -q "Raspberry Pi 5"
}
MAX_FPS=60
if is_pi5; then
  MAX_FPS=120
fi
if [[ "${STREAM_FPS}" -gt "${MAX_FPS}" ]]; then
  echo "STREAM_FPS must be <= ${MAX_FPS} for this Pi model." >&2
  exit 1
fi

# Pi 5 benefits from main profile (better compression); baseline for wider compatibility on older Pi
H264_PROFILE="baseline"
if is_pi5; then
  H264_PROFILE="${H264_PROFILE_OVERRIDE:-main}"
fi

# Reduces jitter spikes on Wi-Fi links.
if command -v iw >/dev/null 2>&1; then
  iw dev wlan0 set power_save off || true
fi

rpicam_args=(
  --camera "${CAMERA_ID}"
  --timeout 0
  --nopreview
  --codec h264
  --profile "${H264_PROFILE}"
  --level 4.2
  --inline
  --flush
  --width "${STREAM_WIDTH}"
  --height "${STREAM_HEIGHT}"
  --framerate "${STREAM_FPS}"
  --bitrate "${STREAM_BITRATE}"
  --intra "${STREAM_INTRA}"
  --denoise "${DENOISE}"
)

if [[ "${STREAM_FORMAT}" == rtp ]]; then
  rpicam-vid "${rpicam_args[@]}" --output - 2>/dev/null | \
    ffmpeg -loglevel error -analyzeduration 2M -probesize 2M -f h264 -i pipe:0 -c copy -f rtp "rtp://${STREAM_HOST}:${STREAM_PORT}" 2>/tmp/rc-fpv.sdp
  exit
fi

if [[ "${STREAM_FORMAT}" == mpegts ]]; then
  # Use rpicam-vid/libav muxing directly (more stable than pipe+ffmpeg here).
  exec rpicam-vid "${rpicam_args[@]}" \
    --libav-format mpegts \
    --output "udp://${STREAM_HOST}:${STREAM_PORT}"
fi

exec rpicam-vid "${rpicam_args[@]}" --output "udp://${STREAM_HOST}:${STREAM_PORT}"
