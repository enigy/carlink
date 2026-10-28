# Carlink

Wireless CarPlay on a GM AAOS head unit (2024 GMC Sierra, GM Info 3.7), using a Carlinkit CPC200-CCPA
adapter running **lvalen91's open OCBM stack** instead of the stock Carlinkit firmware. CarPlay's
turn-by-turn directions, lane guidance and a turn alert also reach the instrument cluster and the
head-up display.

Carlink is the successor to [Carlink2](https://github.com/enigy/Carlink2). Carlink2 stays on the
stock/v5 adapter firmware and is unchanged by this project.

<!-- ![CarPlay on the head unit, directions on the cluster](screenshots/carplay-and-cluster.png) -->
> **Screenshot to add:** `screenshots/carplay-and-cluster.png`: CarPlay on the head unit, with the
> turn and lanes on the cluster behind the wheel.

## What it does

- **CarPlay over OCBM.** Video, touch, audio and the call microphone run over the adapter's open
  stack, through a small sideloaded USB bridge that holds the adapter's USB permission.
- **Directions on the cluster and HUD.** CarPlay's next turn, distance and road go to GM's driver
  display, with turn icons drawn from the junction Apple describes for each turn.
- **Lane guidance.** The lanes for the next turn, drawn across GM's lane area, with the lanes to be
  in shown in a color you pick.
- **Turn alert.** The turn icon flashes as you approach the next turn or exit, starting earlier the
  faster you drive.
- **Media.** The AAOS media card with album art, and the steering-wheel Next and Back buttons.
- **Settings for each iPhone.** Each iPhone keeps its own directions, lane guidance, turn alert and
  CarPlay-while-driving settings, and they follow whichever iPhone connects.
- **Settings.** One screen in the head unit's light or dark theme, with phones, display, calls,
  cluster, troubleshooting and adapter health. Every fix that interrupts CarPlay asks first.

## Screenshots

To add a screenshot, save it under the path shown, uncomment the image line above its placeholder
and delete the placeholder.

### Settings

Categories on the left, each with a live summary; the selected category on the right. Settings
follows the head unit's theme.

<!-- ![Settings, dark theme](screenshots/settings-dark.png) -->
> **Screenshot to add:** `screenshots/settings-dark.png`: Settings in the dark theme.

<!-- ![Settings, light theme](screenshots/settings-light.png) -->
> **Screenshot to add:** `screenshots/settings-light.png`: the same screen in the light theme.

#### Phones

The connected phone, with **Settings** and **Forget**; the other known phones and the USB port; the
order the adapter calls paired phones in, and **Connect automatically**; and **Add phone**. Once a
phone is paired, a new phone can pair only while Add phone has the adapter's Bluetooth pairing open,
for 2 minutes. Forgetting a phone also deletes its settings.

<!-- ![Settings › Phones](screenshots/settings-phones.png) -->
> **Screenshot to add:** `screenshots/settings-phones.png`: Phones, with Add phone and the phone order.

#### Display

Display mode, the loading screen photo, the driver's seat, and **Limit CarPlay while driving**
(on: CarPlay hides its keyboard and shortens lists while the truck moves).

<!-- ![Settings › Display](screenshots/settings-display.png) -->
> **Screenshot to add:** `screenshots/settings-display.png`: Display.

#### Calls

The call microphone, the call audio buffer, and echo and voice tests.

<!-- ![Settings › Calls](screenshots/settings-calls.png) -->
> **Screenshot to add:** `screenshots/settings-calls.png`: Calls.

#### Cluster

**Directions on cluster**, **Reconnect cluster**, the **Turn alert** with a live preview of the
flash, and **Lane guidance**.

<!-- ![Settings › Cluster › Turn alert](screenshots/settings-turn-alert.png) -->
> **Screenshot to add:** `screenshots/settings-turn-alert.png`: Turn alert, with its preview
> flashing.

<!-- ![Settings › Cluster › Lane guidance](screenshots/settings-lanes.png) -->
> **Screenshot to add:** `screenshots/settings-lanes.png`: Lane guidance, with the Lanes to be in
> colors.

#### Settings for each iPhone

Each iPhone keeps its own **Directions on cluster**, **Lane guidance**, **Turn alert** and **Limit
CarPlay while driving**. Settings shows the iPhone connected now, or the last one that connected.
The display mode, driver's seat, loading screen, calls, phones and logs stay shared by everyone.

A new iPhone starts from the factory settings: directions on, lane guidance on, turn alert off,
CarPlay limited while driving. While parked, the bar at the top of Cluster can switch to another
iPhone's settings to change them, copy one iPhone's settings to another, or reset an iPhone to the
factory settings. While driving, Settings shows only the iPhone in use.

<!-- ![Settings for each iPhone](screenshots/settings-profiles.png) -->
> **Screenshot to add:** `screenshots/settings-profiles.png`: Cluster with the bar for picking
> whose settings these are.

#### Troubleshoot

Fixes from least to most disruptive: Reset video, Restart wireless, Reset connection, Reboot adapter
and Disconnect adapter. Each one that interrupts CarPlay asks first. Below them are the logs:
Detailed logging; Record drive logs, which writes the app's and the adapter's logs to
`Downloads/Carlink` as you drive and keeps them 7 days; Copy logs to USB (parked only); and Save
logs. Saved logs hide phone names and Bluetooth addresses.

<!-- ![Settings › Troubleshoot](screenshots/settings-troubleshoot.png) -->
> **Screenshot to add:** `screenshots/settings-troubleshoot.png`: Troubleshoot.

<!-- ![A confirmation](screenshots/dialog-confirm.png) -->
> **Screenshot to add:** `screenshots/dialog-confirm.png`: a confirmation, with Cancel and the fix
> as two halves along the bottom.

#### Adapter and about

The app version, the adapter's name and mode, and its health: Bluetooth, Wi-Fi, CarPlay and firmware.

<!-- ![Settings › Adapter and about](screenshots/settings-adapter.png) -->
> **Screenshot to add:** `screenshots/settings-adapter.png`: Adapter and about.

### Cluster and HUD

<!-- ![HUD turn icon](screenshots/hud-turn-icon.png) -->
> **Screenshot to add:** `screenshots/hud-turn-icon.png`: the HUD showing a turn icon and distance.

<!-- ![Cluster lane strip](screenshots/cluster-lanes.png) -->
> **Screenshot to add:** `screenshots/cluster-lanes.png`: the cluster turn card with the lane strip,
> lanes to be in colored.

<!-- ![Turn alert on the HUD](screenshots/hud-turn-alert.png) -->
> **Screenshot to add:** `screenshots/hud-turn-alert.png`: the turn alert's colored frame on the HUD
> (Inlay and Outline styles side by side).

## Cluster and HUD

Turn on **Settings › Cluster › Directions on cluster**. GM's Templates Host binds Carlink's cluster
service, and Carlink sends each step of the route as a navigation trip; GM draws the turn card, the
distance, the road and the lanes. In release builds only car hosts on the Car App Library's
allowlist may bind that service.
GM drops trips that arrive while it is still building its card, so Carlink resends the current one
each second for 10 seconds after a route starts. **Reconnect cluster** restarts the binding if
directions stop updating.

### Turn icons

Each icon is drawn from the junction Apple sends with the maneuver: the real exit angle, so sharp and
slight turns look sharp and slight. Only the turn is drawn, without the side roads, so the arrow fills
the icon. Roundabouts keep their ring and exit. Without junction data, each maneuver type has its own
fixed icon.

### Lane guidance

**Settings › Cluster › Lane guidance**, kept for each iPhone:

| Setting | Factory setting | What it does |
|---|---|---|
| Ask iPhone for lanes | On | Asks the iPhone for lane guidance from the next connection (Reset connection applies it now). Turns itself off if the iPhone refuses. Needs an adapter built with the lane guidance change. |
| Lanes in the turn icon | Off | Off; Arrow over lanes (the turn arrow on top, the lanes beneath); or Lanes only. |
| Separate lane strip for GM | On | Sends the lanes to GM's own lane area under the turn. |
| Lanes to be in | White | White, red, amber, green, cyan or magenta. |

Settings also shows the last lanes the iPhone sent, or why none have arrived.

The lane strip is always 6:1. GM fits it into a lane area at most 74 dp tall (the Car App Library's
500 × 74 dp box), and a calibration card in the lane area showed 6:1 as the widest shape that keeps
that height. The lanes share the full width; each arrow is centered in its lane and widened to fill
it, so turns read wider than they are tall.

### Turn alert

**Settings › Cluster › Turn alert** (off by factory setting, kept for each iPhone) flashes the turn
icon between its normal frame and a colored one as you approach the next turn or exit.

| Start alerting | Up to 35 mph | 36–59 mph | 60–69 mph | 70 mph and up |
|---|---|---|---|---|
| Earlier | 1,000 ft | 2,000 ft | 3,000 ft | 5,200 ft |
| Standard | 500 ft | 1,000 ft | 1,500 ft | 2,600 ft |
| Later | 300 ft | 600 ft | 900 ft | 1,560 ft |

Carlink can't read the truck's speed, so the speed is how fast the iPhone's distance to the turn
counts down. **Flash speed** is one flash every 1.5, 1 or 0.5 seconds. **Inlay color** is red, amber,
green, cyan or magenta; **Style** colors the inside of the arrow (Inlay) or adds a colored edge too
(Outline). **Solid for the last 3 seconds** holds the colored frame just before the turn. Nothing
flashes when stopped, on straight-ahead or follow-the-road steps, or on arrival.

## Adapter features

Add phone and the phone order appear only when the adapter reports that it supports them. Lane
guidance from the iPhone needs an adapter built with the lane guidance change. The adapter runs
lvalen91's OCBM userspace, installed and recovered per his documentation (`ocbm/docs/ops/`).

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

## Structure

| Module | Role |
|---|---|
| `:app` | Display app (`com.enigy.carlink3`). Decodes HEVC CarPlay over OCBM, renders, handles touch/audio/mic, drives the AAOS media card and the cluster/HUD turn-by-turn via the Templates Host. Play-shippable. |
| `:bridge` | Sideloaded USB bridge, package `android.car.usb.handler`. Holds the adapter's USB permission silently on every attach, relays the raw OCBM bulk stream to the app over a socket, and hosts the cluster icon `ContentProvider`. |
| `:bridge-api` | AIDL contract between the two. |

Modules inherited from OCBM and removed here: `:projection` (Raspberry Pi target), `:car-system-stubs`
(system APIs a third-party app cannot hold), `:usbhandler` (OCBM's untested proof of the permission
grant — superseded by `:bridge`).

## Status

Version 1.00, released through Google Play internal testing and tested in the truck with each
release. `:app`, `:bridge` and `:bridge-api` build; ktlint, detekt, Android lint and 413 unit tests
pass.

- **Transport:** the bridge relays the adapter's bulk stream raw (API 3) and the app reaches it through
  `BridgeBulkTransport`, an implementation of OCBM's `RawBulkTransport`; `OcbmClient` is unchanged. The
  bridge is required: the app has no USB access of its own (no USB permission, no USB dialog, no claim on
  the device), like Carlink2 since its [204]. The bridge gets USB permission silently on every attach and
  shows a one-tap prompt for an adapter that attached during boot.
- **Cluster/HUD navigation:** Carlink2's Templates Host pipeline (`cluster/`, `navigation/`, the compose
  icon store) is the base. `navigation/OcbmNavigationFeed` turns the box's `routeGuidance` /
  `maneuver` JSON (`CH_METADATA`) into the `NavigationState` it renders, including the exit label
  ("Exit 123 · John Smith Rd") and the lanes. Junction spoke angles (`junctionAngles` /
  `junctionExitAngle`) are consumed when the box emits them — that is the `patches/ocbm/` change, on the
  fork's `route-guidance-exitinfo-angles` branch; against an unpatched box, roundabout icons fall back
  to the static glyph per maneuver type.
- **CI and protocol drift:** `.github/workflows/gate.yml` runs the same gate as local on every push and
  PR, and first runs `tools/proto_check.py`, which compares the Kotlin `OcbmProto.kt` against the
  reference copy of OCBM's canonical table in `proto/` (OCBM's own checker only sees clients inside its
  repo). Refresh `proto/ocbm-proto.lib.rs` whenever the box is rebuilt from a newer OCBM.
- **Style gates:** files ported byte-identical from Carlink2 are excluded from ktlint in `.editorconfig`
  (`ktlint = disabled` per file) and their detekt findings are baselined — Carlink2 ran ktlint with
  `ignoreFailures=true`, so it never shaped them. Everything written for Carlink is gated.

See `NOTICE.md` for provenance.

## Releasing

`.github/workflows/release.yml` is Carlink2's release pipeline with the names changed: it signs the
`sideloadRelease` AAB and the bridge APK with the upload key from the `CARLINK_*` secrets, uploads
the AAB to Google Play (`com.enigy.carlink3`) and attaches both to a GitHub Release.

- **Version:** `appVersionCode` in `app/build.gradle.kts` is the only number to bump; `versionName`
  derives from it (100 is 1.00, 101 is 1.01) and the release tag must be `v<appVersionCode>`.
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
