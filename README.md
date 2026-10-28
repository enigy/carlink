# Carlink

Wireless CarPlay on a GM AAOS head unit (2024 Silverado, GM Info 3.7), using a Carlinkit CPC200-CCPA
adapter running **lvalen91's open OCBM stack** instead of the stock Carlinkit firmware.

Carlink is the successor to [Carlink2](https://github.com/enigy/Carlink2). Carlink2 stays on the
stock/v5 adapter firmware and is unchanged by this project.

## Structure (target)

| Module | Role |
|---|---|
| `:app` | Display app (`com.enigy.carlink3`). Decodes HEVC CarPlay over OCBM, renders, handles touch/audio/mic, drives the AAOS media card and the cluster/HUD turn-by-turn via the Templates Host. Play-shippable. |
| `:bridge` | Sideloaded USB bridge, package `android.car.usb.handler`. Holds the adapter's USB permission silently on every attach, relays the raw OCBM bulk stream to the app over a socket, and hosts the cluster icon `ContentProvider`. |
| `:bridge-api` | AIDL contract between the two. |

Modules inherited from OCBM and removed here: `:projection` (Raspberry Pi target), `:car-system-stubs`
(system APIs a third-party app cannot hold), `:usbhandler` (OCBM's untested proof of the permission
grant — superseded by `:bridge`).

## Install

1. Install Carlink from Google Play. <!-- TODO: Play link -->
2. Copy [`app/carlink_bridge.apk`](app/carlink_bridge.apk) (Carlink USB Bridge 1.0.5) to a USB drive.
   SHA-256: `d5a4f48cd2dc668caa1e97cee0fe63334a574c6c7e3b0e11695aa45e930f1e79`
3. While parked, open Carlink. It shows **USB bridge not installed** with an **Open Files** button
   that opens the head unit's hidden Files app. Insert the USB drive, open the drive and tap the APK.
   If asked, allow Files to install apps.
4. When the install finishes, tap the Carlink notification (or press Home and open Carlink).

The bridge uses the same package name as Carlink2's (`android.car.usb.handler`, the name GM's head
unit hands USB devices to) and answers only Carlink, so installing it replaces Carlink2's bridge.
From 1.0.4 it answers only a Carlink signed by this project (the Play app-signing or upload
certificate, pinned in `bridge/build.gradle.kts`), or one installed by the Play Store.
To check the download: `shasum -a 256 carlink_bridge.apk` on macOS, `sha256sum carlink_bridge.apk`
on Linux, or `Get-FileHash carlink_bridge.apk -Algorithm SHA256` in Windows PowerShell.

Each [GitHub Release](https://github.com/enigy/carlink/releases) also attaches the bridge as
`carlink_bridge-<version>.apk`. The release workflow rebuilds it only when `bridge/` or
`bridge-api/` changed since this file was last committed, and otherwise attaches this file, so the
hash above holds until the next bridge change (v111 built 1.0.5; earlier releases' attachments have
their own hashes). When the bridge changes, replace `app/carlink_bridge.apk` with the release's
signed APK and update the version and SHA-256 above.

## Status

**Milestones 1, 2 and 4 are in; nothing has run on a box yet.** `:app` (`com.enigy.carlink3`),
`:bridge` and `:bridge-api` build; ktlint, detekt and 277 unit tests pass.

- **Transport:** the bridge relays the adapter's bulk stream raw (API 3) and the app reaches it through
  `BridgeBulkTransport`, an implementation of OCBM's `RawBulkTransport`; `OcbmClient` is unchanged. The
  bridge is required: the app has no USB access of its own (no USB permission, no USB dialog, no claim on
  the device), like Carlink2 since its [204]. The bridge gets USB permission silently on every attach and
  shows a one-tap prompt for an adapter that attached during boot.
- **Cluster/HUD navigation:** Carlink2's Templates Host pipeline (`cluster/`, `navigation/`, the compose
  icon store) is ported verbatim. `navigation/OcbmNavigationFeed` turns the box's `routeGuidance` /
  `maneuver` JSON (`CH_METADATA`) into the `NavigationState` it renders, including the exit label
  ("Exit 123 · John Smith Rd"). Off by default — Settings → Control → **Cluster Navigation**; **Reset
  Cluster** restarts the host binding. Junction spoke angles (`junctionAngles` / `junctionExitAngle`)
  are consumed when the box emits them — that is the `patches/ocbm/` change, on the fork's
  `route-guidance-exitinfo-angles` branch; against an unpatched box, roundabout icons fall back to the
  static glyph per maneuver type.
- **CI and protocol drift:** `.github/workflows/gate.yml` runs the same gate as local on every push and
  PR, and first runs `tools/proto_check.py`, which compares the Kotlin `OcbmProto.kt` against the
  reference copy of OCBM's canonical table in `proto/` (OCBM's own checker only sees clients inside its
  repo). Refresh `proto/ocbm-proto.lib.rs` whenever the box is rebuilt from a newer OCBM.
- **Style gates:** files ported byte-identical from Carlink2 are excluded from ktlint in `.editorconfig`
  (`ktlint = disabled` per file) and their detekt findings are baselined — Carlink2 ran ktlint with
  `ignoreFailures=true`, so it never shaped them. Everything written for Carlink is gated.

Next: the first bench session — OCBM hello/heartbeat over the bridge, then a CarPlay session, then a
route on the cluster. See `NOTICE.md` for provenance and the plan in Carlink2's
`documents/design/ocbm_migration_plan.md`.

## Releasing

`.github/workflows/release.yml` is Carlink2's release pipeline with the names changed: it signs the
`sideloadRelease` AAB and the bridge APK with the upload key from the `CARLINK_*` secrets, uploads
the AAB to Google Play (`com.enigy.carlink3`) and attaches both to a GitHub Release.

- **Version:** `appVersionCode` in `app/build.gradle.kts` is the only number to bump; `versionName`
  derives from it and the release tag must be `v<appVersionCode>`.
- **Tag push `vNNN`:** build, upload to Internal testing, GitHub Release. **Manual run** from main:
  pick the track; untick *Upload* to build only. Runs from other branches never upload.
- **Naming a release:** the bump commit carries the summary (`[7] Connecting screen, call-audio
  choices`, not `Bump appVersionCode`), since it is the workflow run's title; the annotated tag's
  message becomes the GitHub Release title and intro (`git tag -a v7 -m "Carlink v7: …"`).
- **Play Console, once:** create the app (`com.enigy.carlink3`), grant the existing service account
  release rights to it, and upload the first AAB from the workflow artifact by hand — the API only
  accepts drafts until an app has been rolled out once (the step then fails with
  `status=completed`; rerun after the first manual rollout). The bridge's caller pins are committed in
  `bridge/build.gradle.kts`; a `CARLINK3_CALLER_CERTS` repository variable replaces the release list.
- **Local release builds** need a `keystore.properties` next to `settings.gradle.kts` (gitignored,
  same keys the workflow writes). Without it, release variants build unsigned and debug is unaffected.

## License

Public domain (Unlicense), like both parents.
