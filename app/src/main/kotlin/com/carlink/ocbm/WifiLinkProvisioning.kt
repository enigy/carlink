package com.carlink.ocbm

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What MGMT_WIFI_LINK carries ([Ocbm.MGMT_WIFI_LINK]), and the checks before Carlink sends it. The phone hotspot's
 * password is hashed here, on the head unit, into the WPA key ([wpaPsk]), and that hash is all the adapter gets: the
 * password itself is sent nowhere and kept nowhere. Nothing here knows how the session reached the adapter.
 *
 * `[MGMT_WIFI_LINK][ver 1][link key 32][ssid_len u8][ssid][psk_len u8][psk]`, psk = the WPA key as 64 lower-case hex
 * digits. Carlink always names a hotspot (the box's empty-SSID form is not used) and always sends the hash. The verb
 * with no body clears the adapter's link.
 */
object WifiLinkProvisioning {
    const val BODY_VERSION: Byte = 1

    /** IEEE 802.11i: a WPA passphrase is 8 to 63 characters; an SSID at most 32 bytes. */
    const val PASSPHRASE_MIN = 8
    const val PASSPHRASE_MAX = 63
    const val SSID_MAX_BYTES = 32

    /**
     * The hotspot's name as an iPhone writes it: an iPhone's name carries the curly apostrophe (U+2019), which a head
     * unit's keyboard can't type, and the adapter matches the name byte for byte (bench of 24 Nov 2026: a plain one
     * never joined). So a typed plain apostrophe becomes the curly one, in the field, where the driver sees it.
     */
    fun hotspotName(typed: String): String = typed.replace('\'', '\u2019')

    /** PBKDF2's iterations and output for the WPA key (IEEE 802.11i, H.4). */
    private const val PBKDF2_ITERATIONS = 4096
    private const val PSK_BYTES = 32
    private const val SHA1_BYTES = 20

    /** Why a hotspot can't be sent, in the words Settings shows; null when it can. */
    fun problem(
        ssid: String,
        passphrase: String,
    ): String? {
        val nameBytes = ssid.toByteArray(Charsets.UTF_8).size
        val passBytes = passphrase.toByteArray(Charsets.UTF_8).size
        return when {
            ssid.isEmpty() -> "Enter the hotspot's name."
            nameBytes > SSID_MAX_BYTES -> "That name is too long for a Wi-Fi network."
            ssid.any { it == '\n' || it == '\r' } -> "The name can't contain a line break."
            passBytes !in PASSPHRASE_MIN..PASSPHRASE_MAX -> "Hotspot passwords are 8 to 63 characters."
            passphrase.any { it !in ' '..'~' } ->
                "The password has a character a Wi-Fi password can't contain (a long dash or a curly quote?). " +
                    "Tap the eye to check it."
            else -> null
        }
    }

    /**
     * The hotspot's WPA key as 64 hex digits: PBKDF2-HMAC-SHA1(passphrase, ssid, 4096, 32 B), IEEE 802.11i. Written
     * out over the UTF-8 bytes rather than through `PBKDF2WithHmacSHA1`, whose providers turn a password's chars into
     * bytes differently.
     */
    fun wpaPsk(
        passphrase: String,
        ssid: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(passphrase.toByteArray(Charsets.UTF_8), "HmacSHA1")) }
        val salt = ssid.toByteArray(Charsets.UTF_8)
        val out = ByteArray(PSK_BYTES)
        var block = 1
        var filled = 0
        while (filled < PSK_BYTES) {
            mac.update(salt)
            var u = mac.doFinal(byteArrayOf(0, 0, 0, block.toByte()))
            val t = u.copyOf()
            repeat(PBKDF2_ITERATIONS - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(SHA1_BYTES, PSK_BYTES - filled)
            t.copyInto(out, filled, 0, n)
            filled += n
            block++
        }
        return out.toHex()
    }

    /** The request's argument (after the verb), the WPA key already hashed. Throws when [problem] would refuse it. */
    fun body(
        key: ByteArray,
        ssid: String,
        passphrase: String,
    ): ByteArray {
        require(key.size == OcbwLink.KEY_LEN) { "a 32-byte link key" }
        problem(ssid, passphrase)?.let { throw IllegalArgumentException(it) }
        val name = ssid.toByteArray(Charsets.UTF_8)
        val psk = wpaPsk(passphrase, ssid).toByteArray(Charsets.US_ASCII)
        return byteArrayOf(BODY_VERSION) + key + byteArrayOf(name.size.toByte()) + name + byteArrayOf(psk.size.toByte()) + psk
    }
}
