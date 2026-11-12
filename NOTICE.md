# Provenance

Carlink is assembled from two public-domain (Unlicense) parents, each imported **with its git
history** via `git subtree split`, so every line can be traced to where it came from.

| Path in this repo | Origin | Imported from |
|---|---|---|
| repo root (`app/`, `build.gradle.kts`, `settings.gradle.kts`, `projection/`, `car-system-stubs/`, `usbhandler/`, `OCBMANDROID.md`, `KNOWN_DEVICES_PLAN.md`, …) | **lvalen91/ocbm**, directory `host/CarlinkAndroid` — the AAOS head-unit host app for the OCBM open adapter stack | `https://github.com/lvalen91/ocbm`, split at commit `c9a718d` (2026-10-17) |
| `bridge/` | **enigy/Carlink-legacy (formerly Carlink2)** `bridge/` — the sideloaded USB bridge (`android.car.usb.handler`) that holds the adapter's USB permission and hosts the cluster icon `ContentProvider` | `https://github.com/enigy/Carlink-legacy` at `v237` |
| `bridge-api/` | **enigy/Carlink-legacy (formerly Carlink2)** `bridge-api/` — the AIDL contract between the display app and the bridge | same |

## What this repo is

A fork of OCBM's `CarlinkAndroid` app, restructured the way Carlink-legacy (formerly Carlink2) is: a Play-shippable **display
app** plus a separate **USB bridge** that carries the adapter's USB permission silently and renders the
HUD/cluster maneuver icons. The adapter itself runs lvalen91's OCBM userspace, installed and recovered
per his documentation (`ocbm/docs/ops/`), which this repo does not duplicate.

The protocol constants in `app/.../ocbm/OcbmProto.kt` are a hand-maintained restatement of the canonical
table in `ocbm/crates/ocbm-proto/src/lib.rs`. They must track OCBM box releases.

## Reference material consulted, not incorporated

- Apple CarPlay SDK material via the CarPlay Simulator, under an Apple Developer Program membership —
  protocol truth for iAP2/AirPlay. Nothing from it is copied here.
- `CPC200-CCPA_resources` (lvalen91) — community documentation of the stock Carlinkit firmware.

Upstream remotes used for tracking: `upstream-ocbm` → `lvalen91/ocbm`, `carlink2` → `enigy/Carlink-legacy` (formerly `enigy/Carlink2`).

## Loading screen photos

Four Loading screen scenes are photographs dedicated to the public domain under CC0 1.0 (published on
Unsplash before its 5 June 2017 licence change, mirrored on Wikimedia Commons). No attribution is
required; credited here anyway.

| File (`app/src/main/res/drawable-nodpi/`) | Photo | Photographer | Source |
|---|---|---|---|
| `loading_bg_mountain_road.jpg` | Mountain road in the evening | Linhao Zhang | https://commons.wikimedia.org/wiki/File:Mountain_road_in_the_evening_(Unsplash).jpg |
| `loading_bg_boathouse.jpg` | Boathouse on a mountain lake (Lago di Braies) | Luca Bravo | https://commons.wikimedia.org/wiki/File:Boathouse_on_a_mountain_lake_(Unsplash).jpg |
| `loading_bg_lakeside_cabin.jpg` | Lakeside cabin (Piburger See) | Paul Gilmore | https://commons.wikimedia.org/wiki/File:Lakeside_cabin_(Unsplash).jpg |
| `loading_bg_workshop.jpg` | Woodworking workshop | Unsplash contributor "s w" (2015) | https://commons.wikimedia.org/wiki/File:Woodworking_workshop_(Unsplash).jpg |

## Third-party libraries

Carlink's video player talks to your iPhone's proxy over TLS 1.3 with a pre-shared key, which Android's
own TLS doesn't offer, using the Bouncy Castle Crypto APIs (`org.bouncycastle:bcprov-jdk18on`,
`bctls-jdk18on` and `bcutil-jdk18on`), under the Bouncy Castle licence, an MIT licence:

> Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
> NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
> OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
> CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

Its full text is at https://www.bouncycastle.org/about/license/.
