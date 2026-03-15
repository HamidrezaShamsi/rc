# Android FPV Viewer

This folder contains an Android app that opens a full-screen FPV stream on your phone.

## Default stream URL

- `udp://@:5600`

On app launch, tap to show controls, keep default `udp://@:5600`, then press **Start**.

## Build APK with GitHub Actions (recommended)

```bash
gh workflow run android-apk.yml
gh run watch
gh run download --name rc-fpv-viewer-debug-apk --dir android-fpv-viewer/build-artifacts
```

APK output path:

- `android-fpv-viewer/build-artifacts/app-debug.apk`

## Install to phone

From the repo root (after downloading the artifact zip from Actions, unzip it first):

```bash
adb install -r android-fpv-viewer/build-artifacts/app-debug.apk
```

Or unzip the artifact anywhere and run: `adb install -r /path/to/app-debug.apk`
