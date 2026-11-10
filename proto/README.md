# OCBM protocol table — reference copy

`ocbm-proto.lib.rs` is a byte-identical copy of `crates/ocbm-proto/src/lib.rs` from
enigy/ocbm at commit `5ffd015` (branch `livi-research`, 2026-11-10: OCBM main `3cf6934` plus `INPUT_LOCATION` 0x09 for the car GPS experiment; the retired numbers are a comment table in it). It is **not compiled** here; it is the
canonical constant table that `tools/proto_check.py` compares the Kotlin client
(`app/src/main/kotlin/com/carlink/ocbm/OcbmProto.kt`) against. OCBM's own checker only looks at
clients inside its repo, so a separate repo needs its own copy of both.

When the box firmware is rebuilt from a newer OCBM, refresh this file from the same commit and run
the check; a value that disagrees is a wire bug, a missing core constant (channels, `CT_*`, frame
flags) fails the build, other missing constants are reported as gaps.
