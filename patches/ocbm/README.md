# OCBM box-side patches

Changes Carlink3 needs in the adapter firmware ([lvalen91/ocbm](https://github.com/lvalen91/ocbm))
that are not yet upstream. Each file is a `git format-patch` against OCBM `main`
at the base commit named below.

| Patch | Base | What it does | Status |
|---|---|---|---|
| `0001-iap2-core-exitinfo-selector-and-junction-angles.patch` | c9a718d | `start_route_guidance()` adds selector 3 (ExitInfo) to the 0x5200 subscribe; `maneuver()` emits `junctionAngles`, `junctionExitAngle`, `linkedLaneGuidance` from 0x5202 params 10/11/12. Adds two tests. | Tests green on fork CI ([enigy/ocbm](https://github.com/enigy/ocbm) branch `route-guidance-exitinfo-angles`); upstream PR pending |

Apply:

```sh
git clone https://github.com/lvalen91/ocbm && cd ocbm
git checkout -b route-guidance-exitinfo-angles c9a718d
git am ../Carlink3/patches/ocbm/0001-*.patch
cargo test --locked --manifest-path crates/vendor/iap2-core/Cargo.toml   # crate is excluded from the workspace
```

Without `0001`, the box still sends `routeGuidance`/`maneuver` JSON and the cluster
works; exit numbers and junction geometry are simply absent and Carlink3 falls back
to the maneuver type alone.
