# Android FPV Viewer

This folder contains an Android app that opens a full-screen FPV stream on your phone.

## Default stream URL

- `udp://@:5600`

On app launch, tap to show controls, edit URL if needed, then press **Connect**.

## Build APK with GitHub Actions (recommended)

```bash
gh workflow run android-apk.yml
gh run watch
gh run download --name rc-fpv-viewer-debug-apk --dir android-fpv-viewer/build-artifacts
```

APK output path:

- `android-fpv-viewer/build-artifacts/app-debug.apk`

## Install to phone

```bash
adb install -r android-fpv-viewer/build-artifacts/app-debug.apk
```
