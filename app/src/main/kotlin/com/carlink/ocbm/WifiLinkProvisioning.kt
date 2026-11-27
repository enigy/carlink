package com.carlink.ocbm

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What MGMT_WIFI_LINK carries ([Ocbm.MGMT_WIFI_LINK]), the checks before Carlink sends it, and the adapter's answer.
 * Nothing here knows how the session reached the adapter. Two versions of the body (notes/carlink.md, "ADDENDUM 24 wire
 * proposal"):
 *
 * ```text
 * v2, the AP link (the head unit joins the adapter's own Wi-Fi; the only one Carlink's Settings send)
 *   request [MGMT_WIFI_LINK][ver 2][link key 32][band u8]                       band 0x02 = 2.4 GHz, 0x05 = 5 GHz
 *   answer  [MGMT_ACK][MGMT_WIFI_LINK][0][ssid_len u8][ssid][pass_len u8][pass] the adapter's own network ([network])
 *           [MGMT_ACK][MGMT_WIFI_LINK][1]                                       refused, nothing changed
 * v1, the hotspot link (both join the phone's hotspot; kept, no longer offered)
 *   request [MGMT_WIFI_LINK][ver 1][link key 32][ssid_len u8][ssid][psk_len u8][psk]
 *   answer  [MGMT_ACK][MGMT_WIFI_LINK][status]
 * ```
 *
 * v1: the phone hotspot's password is hashed here, on the head unit, into the WPA key ([wpaPsk]), and that hash is all
 * the adapter gets: the password itself is sent nowhere and kept nowhere. psk = the WPA key as 64 lower-case hex digits;
 * Carlink always names a hotspot (the box's empty-SSID form is not used). v2's answer carries the adapter's own Wi-Fi
 * password, which only ever goes to the head unit: never logged ([ApNetwork] hides it), kept only sealed. The verb with
 * no body clears the adapter's link.
 */
object WifiLinkProvisioning {
    const val BODY_VERSION: Byte = 1

    /** v2: the AP link. An adapter that predates it refuses the version (status 1) and changes nothing. */
    const val AP_BODY_VERSION: Byte = 2

    /** v2's band byte: the adapter's own Wi-Fi on 2.4 GHz (the AP link's default, ADDENDUM 24 item 4) or on 5 GHz. */
    const val BAND_2_4: Byte = 0x02
    const val BAND_5: Byte = 0x05

    /** v2's body after the verb: version, key, band. */
    const val AP_BODY_LEN = 1 + OcbwLink.KEY_LEN + 1

    /** An answer's status byte: 0 = taken. */
    const val STATUS_OK = 0

    /** A raw WPA key in place of a passphrase: 64 hex digits. */
    private const val RAW_PSK_LEN = 64

    /** `[MGMT_ACK][verb][status]`, before v2's network. */
    private const val ACK_HEAD = 3

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

    /** v2's argument (after the verb): the link key and the band of the adapter's own Wi-Fi ([BAND_2_4] or [BAND_5]). */
    fun apBody(
        key: ByteArray,
        band: Byte,
    ): ByteArray {
        require(key.size == OcbwLink.KEY_LEN) { "a 32-byte link key" }
        require(band == BAND_2_4 || band == BAND_5) { "a band of 2.4 or 5 GHz" }
        return byteArrayOf(AP_BODY_VERSION) + key + byteArrayOf(band)
    }

    /** The adapter's answer to MGMT_WIFI_LINK: its [status], and on a v2 it took, its own Wi-Fi ([network], null without). */
    class Answer(
        val status: Int,
        val network: ApNetwork?,
    )

    /**
     * [reply] (MGMT_ACK's payload, from its first byte) as the answer to MGMT_WIFI_LINK; null for any other reply. The
     * network is read only from a status-0 answer, and only when it is whole: a name of 1 to 32 bytes of UTF-8 with no
     * line break or NUL, and a password of 0 (an open network), 8 to 63 printable ASCII, or 64 hex digits, ending the reply.
     */
    fun answer(reply: ByteArray): Answer? {
        if (reply.size < ACK_HEAD || reply[0] != Ocbm.MGMT_ACK || reply[1] != Ocbm.MGMT_WIFI_LINK) return null
        val status = reply[2].toInt() and BYTE_MASK
        return Answer(status, if (status == STATUS_OK) network(reply, ACK_HEAD) else null)
    }

    /** `[ssid_len][ssid][pass_len][pass]` from [at] to the end of [b]; null when absent or not whole. */
    private fun network(
        b: ByteArray,
        at: Int,
    ): ApNetwork? {
        val nameLen = b.getOrNull(at)?.toInt()?.and(BYTE_MASK) ?: return null
        val nameEnd = at + 1 + nameLen
        val passLen = b.getOrNull(nameEnd)?.toInt()?.and(BYTE_MASK) ?: return null
        if (nameLen !in 1..SSID_MAX_BYTES || nameEnd + 1 + passLen != b.size) return null
        val name = strictUtf8(b.copyOfRange(at + 1, nameEnd))?.takeIf { n -> n.none { it == '\n' || it == '\r' || it == '\u0000' } } ?: return null
        val pass = b.copyOfRange(nameEnd + 1, b.size)
        return if (passwordFits(pass)) ApNetwork(name, String(pass, Charsets.US_ASCII)) else null
    }

    /** An access point's WPA2 password as hostapd holds one: none (open), a passphrase, or a raw key in hex. */
    private fun passwordFits(p: ByteArray): Boolean =
        when (p.size) {
            0 -> true
            in PASSPHRASE_MIN..PASSPHRASE_MAX -> p.all { it in PRINTABLE_FIRST..PRINTABLE_LAST }
            RAW_PSK_LEN -> p.all { it.toInt().toChar().let { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
            else -> false
        }

    private fun strictUtf8(b: ByteArray): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    private const val BYTE_MASK = 0xFF
    private const val PRINTABLE_FIRST: Byte = 0x20
    private const val PRINTABLE_LAST: Byte = 0x7E
}

/**
 * The adapter's own Wi-Fi network as its answer to MGMT_WIFI_LINK v2 names it: what the driver joins in the car's Wi-Fi
 * settings. [password] is a secret (empty for an open network): [toString] shows neither, so neither reaches a log by
 * accident, and it is kept only sealed ([com.carlink.link.WifiLinkStore]).
 */
class ApNetwork(
    val ssid: String,
    val password: String,
) {
    override fun equals(other: Any?): Boolean = other is ApNetwork && other.ssid == ssid && other.password == password

    override fun hashCode(): Int = 31 * ssid.hashCode() + password.hashCode()

    override fun toString(): String = "ApNetwork(name and password not shown)"
}
