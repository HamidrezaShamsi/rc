# RC FPV Raspberry Pi (Low-Latency)

This project sets up a Raspberry Pi camera stream for RC FPV use with an emphasis on low latency.

## What gets installed

- `fpv/stream.sh`: tuned `rpicam-vid` UDP H.264 stream command
- `systemd/rc-fpv-stream.service`: boot-time service
- `install.sh`: one-shot installer that also writes system files
- `receiver/view.sh`: low-latency `ffplay` example receiver

## Install on a Raspberry Pi (Lite OS)

From this folder:

```bash
chmod +x install.sh fpv/stream.sh receiver/view.sh
sudo ./install.sh
```

Installer actions:

- installs required packages (`rpicam-apps`, `iw`)
- copies stream script into `/opt/rc-fpv`
- creates `/usr/local/bin/rc-fpv-stream`
- creates `/etc/default/rc-fpv-stream` (settings)
- installs `/etc/systemd/system/rc-fpv-stream.service`
- updates camera config in `/boot/firmware/config.txt` or `/boot/config.txt`
- enables and starts the service

## Configure destination IP (ground station)

Edit:

```bash
sudo nano /etc/default/rc-fpv-stream
```

Set `STREAM_HOST` to the receiver device IP, then restart:

```bash
sudo systemctl restart rc-fpv-stream
```

## View stream on controller laptop

Install ffmpeg/ffplay and run:

```bash
./receiver/view.sh 5600
```

Or directly:

```bash
ffplay -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 -sync ext udp://@:5600?fifo_size=1000000\&overrun_nonfatal=1
```

## Useful service commands

```bash
sudo systemctl status rc-fpv-stream
sudo journalctl -u rc-fpv-stream -f
sudo systemctl restart rc-fpv-stream
```

## Latency tuning tips

- Lower resolution first (`960x540` or `640x480`) for worst-case Wi-Fi stability.
- Keep GOP short: `STREAM_INTRA=15` can reduce decode wait, at cost of bitrate.
- Use 5 GHz Wi-Fi if possible.
- Keep bitrate realistic for your link (`4-8 Mbps` is a typical FPV range).
- Keep receiver and TX/RX close to reduce packet loss bursts.
