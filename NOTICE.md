# Provenance

Carlink3 is assembled from two public-domain (Unlicense) parents, each imported **with its git
history** via `git subtree split`, so every line can be traced to where it came from.

| Path in this repo | Origin | Imported from |
|---|---|---|
| repo root (`app/`, `build.gradle.kts`, `settings.gradle.kts`, `projection/`, `car-system-stubs/`, `usbhandler/`, `OCBMANDROID.md`, `KNOWN_DEVICES_PLAN.md`, …) | **lvalen91/ocbm**, directory `host/CarlinkAndroid` — the AAOS head-unit host app for the OCBM open adapter stack | `https://github.com/lvalen91/ocbm`, split at commit `c9a718d` (2026-10-17) |
| `bridge/` | **enigy/Carlink2** `bridge/` — the sideloaded USB bridge (`android.car.usb.handler`) that holds the adapter's USB permission and hosts the cluster icon `ContentProvider` | `https://github.com/enigy/Carlink2` at `v237` |
| `bridge-api/` | **enigy/Carlink2** `bridge-api/` — the AIDL contract between the display app and the bridge | same |

## What this repo is

A fork of OCBM's `CarlinkAndroid` app, restructured the way Carlink2 is: a Play-shippable **display
app** plus a separate **USB bridge** that carries the adapter's USB permission silently and renders the
HUD/cluster maneuver icons. The adapter itself runs lvalen91's OCBM userspace, installed and recovered
per his documentation (`ocbm/docs/ops/`), which this repo does not duplicate.

The protocol constants in `app/.../ocbm/OcbmProto.kt` are a hand-maintained restatement of the canonical
table in `ocbm/crates/ocbm-proto/src/lib.rs`. They must track OCBM box releases.

## Reference material consulted, not incorporated

- Apple CarPlay SDK material via the CarPlay Simulator, under an Apple Developer Program membership —
  protocol truth for iAP2/AirPlay. Nothing from it is copied here.
- `CPC200-CCPA_resources` (lvalen91) — community documentation of the stock Carlinkit firmware.

Upstream remotes used for tracking: `upstream-ocbm` → `lvalen91/ocbm`, `carlink2` → `enigy/Carlink2`.
