# Carlink3

Wireless CarPlay on a GM AAOS head unit (2024 Silverado, GM Info 3.7), using a Carlinkit CPC200-CCPA
adapter running **lvalen91's open OCBM stack** instead of the stock Carlinkit firmware.

Carlink3 is the successor to [Carlink2](https://github.com/enigy/Carlink2). Carlink2 stays on the
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

## Status

**Milestones 1, 2 and 4 are in; nothing has run on a box yet.** `:app` (`com.enigy.carlink3`),
`:bridge` and `:bridge-api` build; ktlint, detekt and 276 unit tests pass.

- **Transport:** the bridge relays the adapter's bulk stream raw (API 3) and the app reaches it through
  `BridgeBulkTransport`, an implementation of OCBM's `RawBulkTransport`; `OcbmClient` is unchanged. The
  app tries the bridge first (silent USB permission; one-tap prompt for a boot-time attach) and only claims
  USB in-process when no bridge is installed.
- **Cluster/HUD navigation:** Carlink2's Templates Host pipeline (`cluster/`, `navigation/`, the compose
  icon store) is ported verbatim. `navigation/OcbmNavigationFeed` turns the box's `routeGuidance` /
  `maneuver` JSON (`CH_METADATA`) into the `NavigationState` it renders, including the exit label
  ("Exit 273 · Wade Green Rd"). Off by default — Settings → Control → **Cluster Navigation**; **Reset
  Cluster** restarts the host binding. Junction spoke angles are not in OCBM's JSON yet, so roundabout
  icons use the static glyph per maneuver type until the Rust side emits params 10/11.
- **Style gates:** files ported byte-identical from Carlink2 are excluded from ktlint in `.editorconfig`
  (`ktlint = disabled` per file) and their detekt findings are baselined — Carlink2 ran ktlint with
  `ignoreFailures=true`, so it never shaped them. Everything written for Carlink3 is gated.

Next: the first bench session — OCBM hello/heartbeat over the bridge, then a CarPlay session, then a
route on the cluster. See `NOTICE.md` for provenance and the plan in Carlink2's
`documents/design/ocbm_migration_plan.md`.

## License

Public domain (Unlicense), like both parents.
