# OCBM protocol table — reference copy

`ocbm-proto.lib.rs` is a byte-identical copy of `crates/ocbm-proto/src/lib.rs` from
enigy/ocbm at commit `968778c` (branch `wifi-link`, 2026-11-24, not yet on main: OCBM 2.0's table plus the Wi-Fi link's
MGMT_WIFI_LINK and CAP_WIFI_LINK; the retired numbers are a comment table in it). Refresh it from main's commit once
that branch is merged. It is **not compiled** here; it is the
canonical constant table that `tools/proto_check.py` compares the Kotlin client
(`app/src/main/kotlin/com/carlink/ocbm/OcbmProto.kt`) against. OCBM's own checker only looks at
clients inside its repo, so a separate repo needs its own copy of both.

When the box firmware is rebuilt from a newer OCBM, refresh this file from the same commit and run
the check; a value that disagrees is a wire bug, a missing core constant (channels, `CT_*`, frame
flags) fails the build, other missing constants are reported as gaps.
