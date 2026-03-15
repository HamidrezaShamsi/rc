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


## Update app (in-app)

- **Update app** button: downloads the latest APK from this repo’s GitHub Releases and opens the system installer.
- The workflow publishes each build as a release, so “Update app” fetches that release’s APK. Run the workflow at least once so a release exists.
- If your repo is not `rc/rc`, build with: `-PgithubRepo=YOUR_USERNAME/rc` so the app queries the correct repo.

## In-app controls

- **Fill/Fit toggle**: choose full-screen crop (Fill) or fit-with-bars (Fit).
- **Live telemetry**: state, FPS estimate, ping, lost pictures.
- **Stop button**: stop stream cleanly.
- **Settings panel**:
  - receiver cache slider (app-side)
  - sender sliders for FPS/bitrate/keyframe interval
  - apply-to-Pi button (requires Pi control API service on `10.42.0.1:8080`)
