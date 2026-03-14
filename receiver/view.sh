#!/usr/bin/env bash
set -euo pipefail

PORT="${1:-5600}"

exec ffplay \
  -fflags nobuffer \
  -flags low_delay \
  -framedrop \
  -probesize 32 \
  -analyzeduration 0 \
  -sync ext \
  "udp://@:${PORT}?fifo_size=1000000&overrun_nonfatal=1"
