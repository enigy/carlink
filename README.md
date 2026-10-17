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

**Scaffold builds.** `:app` (`com.enigy.carlink3`), `:bridge` and `:bridge-api` all compile; the
three OCBM-only modules are removed; the inherited unit tests pass (271). The app is still OCBM's client claiming USB itself — the bridge is
not yet wired as its transport. Next: make the bridge a raw OCBM bulk relay and give `:app` a
socket-backed `RawBulkTransport`; then port Carlink2's cluster navigation. See `NOTICE.md` for provenance and the plan in Carlink2's
`documents/design/ocbm_migration_plan.md`.

## License

Public domain (Unlicense), like both parents.
