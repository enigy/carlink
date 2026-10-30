# Security policy

Please report security problems in Carlink privately through GitHub, never in a public issue. You will hear back within 7 days.

For the privacy policy, please see: [PRIVACY.md](https://github.com/enigy/carlink/blob/main/PRIVACY.md)

## Supported versions

Only the newest release gets security fixes. Update to it before reporting, if you can.

| Component | Supported | Where it comes from |
| --- | --- | --- |
| Carlink app (`com.enigy.carlink3`) | The newest release (2.04 today) | Google Play, or the APK on the GitHub release |
| Carlink USB Bridge | The version the newest release ships (1.0.6 today) | `app/carlink_bridge.apk` in this repo, and the GitHub release |
| OCBM adapter software | Reported to its own repo, [enigy/ocbm](https://github.com/enigy/ocbm) | The adapter's one-file install |
| Older app and bridge versions | No |  |

## Reporting a vulnerability

Use GitHub's private reporting: open this repo's **Security** tab and choose **Report a vulnerability**. Only the maintainer sees the report. Please don't open a public issue, pull request or discussion about it.

Include what you can of:

- The Carlink version (Settings › Adapter and about › App version) and the USB Bridge version.
- The head unit, and the adapter's firmware (OCBM version or Carlinkit stock).
- What an attacker can do, and what they need first: physical access, a paired phone, being on the adapter's Wi-Fi, or nothing.
- Steps to reproduce, or a proof of concept.
- Whether you plan to publish, and when.

Not sure whether something is a security problem? Report it privately anyway.

## What happens next

Carlink has one maintainer, so these are targets, not guarantees. You will hear back within **7 days**, with a first assessment within **14 days**. Fixes ship as a normal Carlink release on Google Play and GitHub.

| Severity | Example in Carlink | Fix target |
| --- | --- | --- |
| Critical | Code runs in Carlink or the USB Bridge without physical access; driver-safety limits switched off without the driver | 14 days |
| High | Another app on the head unit uses the USB Bridge; the adapter's Wi-Fi password, Bluetooth keys, contacts or location leak | 30 days |
| Medium | Needs a paired phone, the adapter's Wi-Fi or a modified adapter; a crash an attacker can trigger at will | 90 days |
| Low | Hardening, or needs an unlocked or rooted head unit | A later release |

Please keep the report private until a fix is released, or for 90 days, whichever comes first. If you need longer or shorter, say so and we'll agree to a date.

## Scope

In scope:

- **The Carlink app**: how it handles what the adapter and the iPhone send it, its settings and saved data, and what it sends over the network (lyrics lookups to LRCLIB, release downloads from GitHub).
- **The Carlink USB Bridge**: it should answer only Carlink builds signed with the pinned keys, and its icon provider only the car's system apps.
- **Cluster and HUD**: the cluster service should bind only to the car's own templates host.
- **Logs**: Save Logs should hide phone names, Bluetooth addresses, callers and places. A personal detail that gets through is a bug to report here.
- **This repo's build and release pipeline**: the GitHub Actions workflows, signing, and the Google Play upload.
- **Adapter conversion and updates in the app**: checking an installer or update package before anything reaches the adapter.

Out of scope here (please report to the owner):

- **OCBM, the adapter's own software**, to [enigy/ocbm](https://github.com/enigy/ocbm). If you can't tell which side a problem is on, report it here and we'll route it.
- **Apple** (iOS, CarPlay), **GM** (the head unit, Android Automotive), **Carlinkit** (the adapter's stock firmware), **LRCLIB** and **Google Play**.
- Problems that need someone to already control the head unit (root, adb, a debug build installed by hand), or that only affect debug builds.
- Missing hardening with no attack behind it, such as a dependency's CVE that Carlink's code never reaches.

## Driver safety

Carlink runs on a car's screen while it is driven, so anything that could distract the driver is treated as a security problem too. It is the user's responsibility and acceptance of installing Carlink that they use its features and capabilities in a safe and legal manner. However, if a bug is found that the user believes to cause unnecessary distraction, then we strongly encourage the user to report it here.

## Keep secrets out of reports

Some files hold keys that would let someone into your car's adapter. Never attach these, privately or publicly:

- The adapter's flash backup and anything else on the conversion stick: `OCBM_BACKUP`, `identity.tgz`, `mtd*.bin`. They hold its Wi-Fi password and Bluetooth keys.
- Signing keys or keystores, other than the public `keystores/debug.p12`.

Saved logs are redacted, but read them before attaching one, and cut anything personal that got through.

## Good-faith research and credit

Test only on a head unit, adapter, phone and accounts that are yours. Never test while driving, and never on someone else's car. Research done that way and reported here privately is welcome; it won't lead to complaints from this project.

Each fix's release notes credit the reporter, by the name you choose, unless you ask to stay anonymous. Carlink is a free, unpaid project and offers no bounty.
