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

Modules inherited from OCBM that this fork does **not** use and will remove: `:projection`
(Raspberry Pi target), `:car-system-stubs` (system APIs a third-party app cannot hold),
`:usbhandler` (OCBM's untested proof of the permission grant — superseded by `:bridge`).

## Status

**Seeded, not yet building as Carlink3.** The tree is OCBM's `host/CarlinkAndroid` plus Carlink2's bridge
modules, each imported with history. Next: rename the app, wire the bridge as a raw relay, port
Carlink2's cluster navigation. See `NOTICE.md` for provenance and the plan in Carlink2's
`documents/design/ocbm_migration_plan.md`.

## License

Public domain (Unlicense), like both parents.
