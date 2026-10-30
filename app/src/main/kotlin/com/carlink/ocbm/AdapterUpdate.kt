package com.carlink.ocbm

// Updating an adapter that already runs OCBM (remote updates plan, U2; enigy/ocbm f8f18c8): the package
// goes over the OCBM link to /tmp/ocbm-update.tgz, MGMT_UPDATE starts ocbm_update.sh, and the adapter
// installs it as a trial that keeps the update only once its start-up self-test passes.

/**
 * OCBM versions in the adapter's own order (ocbm_update.sh `newer`): X.Y.Z compared as numbers, and a
 * pre-release ("1.1.0-beta.1") below its release but above the version before it.
 */
object OcbmVersion {
    /** Whether [a] is newer than [b]. */
    fun newer(
        a: String,
        b: String,
    ): Boolean = key(a) > key(b)

    /** The adapter's sort key: three six-digit parts, then `~` for a release or `-` and the pre-release tag. */
    internal fun key(version: String): String {
        val release = version.substringBefore('-')
        val pre = if ('-' in version) version.substringAfter('-') else null
        val parts = release.split('.')
        val numbers =
            (0 until PARTS).joinToString("") { i ->
                (parts.getOrNull(i)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0).toString().padStart(PART_DIGITS, '0')
            }
        return numbers + (pre?.let { "-$it" } ?: "~")
    }

    private const val PARTS = 3
    private const val PART_DIGITS = 6
}

/** What an update package says of itself (its VERSION, FROM_MIN and ROOM files), or what a pin says of the package it names. */
data class UpdatePackage(
    /** "1.1.0". */
    val version: String,
    /** The oldest OCBM it updates; an adapter on an older one refuses it. */
    val fromMin: String,
    /** About how much flash it needs on the adapter, in KB, the adapter's 384 KB spare included. */
    val needKb: Int,
    /** One or two lines for the driver, shown before the update is confirmed; empty when there are none. */
    val notes: String = "",
)

/** What Settings offers the adapter in use, given the newest package this build knows. */
sealed interface UpdateOffer {
    /** The package offered; null for [None]. */
    val pkg: UpdatePackage?

    /** Nothing to offer: no package, nothing newer, an adapter without CAP_UPDATE, or a build with updates off. */
    data object None : UpdateOffer {
        override val pkg: UpdatePackage? get() = null
    }

    /** The package fits: it is installed in place, as a trial that falls back to this version. */
    data class InPlace(
        override val pkg: UpdatePackage,
    ) : UpdateOffer

    /**
     * A trial adapter the package can't go onto in place ([why]): it goes back to stock CarPlay, then converts
     * again by USB stick with this build's installer.
     */
    data class RoundTrip(
        override val pkg: UpdatePackage,
        val why: String,
    ) : UpdateOffer

    /** The install is `confirmed`, and finishes when this drive ends: the update waits until then. */
    data class AfterThisDrive(
        override val pkg: UpdatePackage,
    ) : UpdateOffer

    /** A `final` adapter that can't take the package ([why]); with no stock left, there is no round trip. */
    data class CannotTake(
        override val pkg: UpdatePackage,
        val why: String,
    ) : UpdateOffer
}

/**
 * The offer for an adapter whose one-file [install] is known, which takes MGMT_UPDATE ([takesUpdates]) and has
 * [freeKb] of flash free (MGMT_INFO's `rootfs_free_kb`; null when it didn't say, and the adapter checks for
 * itself). Trial adapters update "in place, else round trip" (Scott, 29 Oct); the round trip only from a build
 * that converts stock adapters ([canConvert]).
 */
fun updateOffer(
    install: OneFileInstall?,
    takesUpdates: Boolean,
    pkg: UpdatePackage?,
    freeKb: Int?,
    canConvert: Boolean = true,
): UpdateOffer {
    val have = install?.version
    if (pkg == null || have == null) return UpdateOffer.None
    if (!takesUpdates || !OcbmVersion.newer(pkg.version, have)) return UpdateOffer.None
    val tooOld = OcbmVersion.newer(pkg.fromMin, have)
    val noRoom = freeKb != null && pkg.needKb > freeKb
    val why =
        when {
            tooOld -> "It updates OCBM ${pkg.fromMin} or newer, and this adapter runs $have."
            noRoom -> "It needs about ${pkg.needKb} KB of flash, and this adapter has $freeKb KB free."
            else -> null
        }
    return when {
        install.mode == InstallMode.CONFIRMED -> UpdateOffer.AfterThisDrive(pkg)
        why == null -> UpdateOffer.InPlace(pkg)
        install.mode == InstallMode.TRIAL && canConvert -> UpdateOffer.RoundTrip(pkg, why)
        else -> UpdateOffer.CannotTake(pkg, why)
    }
}

/** MGMT_INFO's `"update"`: where the adapter's update stands. */
enum class UpdateState(
    val wire: String,
) {
    NONE("none"),

    /** ocbm_update.sh is checking or placing the package. */
    INSTALLING("installing"),

    /** Placed and restarted into: its start-up self-test decides. */
    TRIAL("trial"),
    COMMITTED("committed"),
    ROLLED_BACK("rolled_back"),
    REFUSED("refused"),
    ;

    companion object {
        fun of(wire: String?): UpdateState = entries.firstOrNull { it.wire == wire } ?: NONE
    }
}

/**
 * MGMT_INFO's `"update_result"`, the adapter's one line on its last update (/etc/ocbm/update_result):
 * `committed 1.1.0 ocbm abc (from 1.0.0 ocbm def)`, `rolled_back 1.1.0 ocbm abc: why (kept 1.0.0 ocbm def)`,
 * `refused 1.1.0 ocbm abc: why`.
 */
data class UpdateResult(
    val state: UpdateState,
    /** The version the update was to ("1.1.0"); null when the line doesn't say. */
    val version: String?,
    /** Why it was refused or undone; empty for a commit. */
    val reason: String,
    /** The version put back after a roll-back ("1.0.0"); null otherwise. */
    val kept: String?,
) {
    companion object {
        private val KEPT = Regex("""\s*\(kept (\S+)[^()]*\)$""")

        /** The line [line], or null when it is empty or not one of the adapter's. */
        fun parse(line: String): UpdateResult? {
            val text = line.trim()
            val state = UpdateState.of(text.substringBefore(' '))
            if (state == UpdateState.NONE) return null
            val rest = text.substringAfter(' ', "")
            val version = rest.substringBefore(' ').substringBefore(':').takeIf { it.isNotEmpty() && it != "?" }
            return when (state) {
                UpdateState.ROLLED_BACK -> {
                    val kept = KEPT.find(rest)
                    UpdateResult(state, version, rest.substringAfter(": ", "").replace(KEPT, ""), kept?.groupValues?.get(1))
                }
                UpdateState.REFUSED -> UpdateResult(state, version, rest.substringAfter(": ", ""), null)
                else -> UpdateResult(state, version, "", null)
            }
        }
    }
}
