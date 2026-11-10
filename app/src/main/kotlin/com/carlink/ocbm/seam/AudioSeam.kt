package com.carlink.ocbm.seam

import com.carlink.logging.ProbeLog
import java.util.concurrent.atomic.AtomicLong

/**
 * `CH_MEDIA_AUDIO` + `CH_ALT_AUDIO` → `MediaAudioPlayer` and `VoiceRouter`.
 *
 * Like [VideoSeam] this is a transcoder from the forward-encrypted v2 seam into the framing the
 * players speak. Both lanes get the same 12-byte [VoiceTag] per access unit —
 * `[u32 BE rate][u16 BE ch][u8 atype][u8 codec][u32 BE len][AU]`, the box's legacy 11-byte tag
 * (`forward.rs:101-109`) plus the codec — so each player dispatches on the codec THE BOX NEGOTIATED
 * instead of assuming one: PCM on wired CarPlay, AAC-LC media / AAC-ELD voice on wireless.
 *
 * `VoiceRouter` even diagnoses the mismatch this class exists to remove — it rejects an implausible
 * rate with *"the seam is probably speaking the forward-encrypted v2 framing"*. It now never sees it.
 *
 * ## Both channels, one instance
 *
 * The two seams are physically separate but the `scid` key and format tables are shared, exactly as in
 * the macOS host: a stream's SEAM_KEY and SEAM_FORMAT may arrive on either, and every message is
 * scid-tagged precisely so concurrent streams (telephony and alert share the voice sink) can never
 * clobber each other. Route on the format's `audioType`, never on which channel a packet arrived on.
 *
 * ## Wire
 *
 * `[u32 BE len][SEAM_MAGIC "SEAV"][marker][...]` — since 2026-09-03 the same self-synchronizing
 * envelope [VideoSeam] uses, `len` counting the magic:
 *  - `0x00 SEAM_KEY`    `[key 32][scid 8 LE]`      (len 45)
 *  - `0x01 SEAM_PKT`    `[scid 8 LE][raw encrypted RTP packet]`
 *  - `0x02 SEAM_FORMAT` `[scid 8 LE][codec][rate u32 LE][ch][bits][atype]`  (len 21)
 *
 * (`0x03`, btd's unencrypted Bluetooth call audio, is retired in OCBM 1.0.5 and is skipped like any
 * unknown marker.)
 *
 * The magic exists because ocbmd replaces a seam producer on a re-SETUP **without draining the old
 * one**, so this buffer can be holding half a message when the new producer's first bytes arrive. Before
 * the magic the new `SEAM_KEY` landed mid-message and the lane desynced for the rest of the session.
 * `OcbmProto.F_NEW_SOURCE` on the first frame of a new producer is the primary fix; the magic is the
 * recovery when that flag is absent. A box build older than that date sends no magic at all, so the
 * legacy `[u32 BE len][marker]` framing is detected once per seam and parsed as before.
 *
 * Any other marker is SKIPPED by its length prefix (the proto's rule for a host meeting a marker it
 * does not know) and is never treated as a desync.
 *
 * PCM in the tag is ALWAYS little-endian, on both lanes. The AirPlay PCM downlink (wired CarPlay,
 * `SEAM_PKT`, codec 0 — media AND voice) is big-endian on the wire and is byte-swapped here before
 * tagging.
 */
class AudioSeam(
    private val mediaPipe: SeamPipe,
    private val voicePipe: SeamPipe,
    private val log: ProbeLog.Logger,
) {
    companion object {
        /** Generous vs any real RTP packet; a larger declared length is a desync, not a jumbo packet. */
        private const val MAX_MESSAGE = 1 shl 20

        /**
         * Cap for a message whose marker this host does not know. The proto says skip it by its
         * length, and that is honoured — but a false magic in ciphertext after a resync would
         * otherwise let a random length swallow up to [MAX_MESSAGE]. No real audio message is
         * anywhere near 64 KiB (an RTP packet is ~1.5 KB), so anything larger under
         * an unknown marker is junk and byte-resyncs instead.
         */
        private const val MAX_UNKNOWN_MESSAGE = 1 shl 16
    }

    class Format(
        val codec: Int,
        val rate: Int,
        val channels: Int,
        val bits: Int,
        val atype: Int,
    )

    /** One decrypted access unit's bytes. */
    private class Au(
        val buf: ByteArray,
        val off: Int,
        val len: Int,
    ) {
        fun copy(): ByteArray = buf.copyOfRange(off, off + len)
    }

    val decryptOk = AtomicLong(0)
    val decryptFail = AtomicLong(0)
    val unkeyed = AtomicLong(0)

    /** Messages with a marker this host does not know, skipped by their length prefix. */
    val skippedUnknown = AtomicLong(0)

    private val keys = HashMap<Long, ByteArray>()
    private val formats = HashMap<Long, Format>()

    private val media = Buf()
    private val voice = Buf()

    /** Which framing a seam speaks; latched from its first message and kept for the connection. */
    private enum class Framing { UNKNOWN, MAGIC, LEGACY }

    private class Buf {
        var b = ByteArray(1 shl 16)
        var end = 0
        var start = 0
        var framing = Framing.UNKNOWN
    }

    private var loggedLegacy = false

    fun feedMedia(payload: ByteArray) = feed(media, payload)

    fun feedVoice(payload: ByteArray) = feed(voice, payload)

    /**
     * `OcbmProto.F_NEW_SOURCE`: the box accepted a new producer on this seam and dropped the previous
     * one without draining it. Whatever partial message is buffered belongs to a producer that will
     * never finish it — discard it BEFORE the new bytes are appended, or the new `SEAM_KEY` is parsed
     * as the tail of a dead message. The latched framing is kept: it is a property of the box build.
     */
    @Synchronized
    fun resetMedia() = reset(media)

    @Synchronized
    fun resetVoice() = reset(voice)

    private fun reset(s: Buf) {
        val held = s.end - s.start
        s.start = 0
        s.end = 0
        if (held > 0) log.i("new seam producer — dropped $held B of the previous producer's partial message")
    }

    @Synchronized
    private fun feed(
        s: Buf,
        payload: ByteArray,
    ) {
        compact(s)
        if (s.end + payload.size > s.b.size) {
            if (s.start > 0) {
                System.arraycopy(s.b, s.start, s.b, 0, s.end - s.start)
                s.end -= s.start
                s.start = 0
            }
            var cap = s.b.size
            while (cap < s.end + payload.size) cap = cap shl 1
            if (cap != s.b.size) s.b = s.b.copyOf(cap)
        }
        System.arraycopy(payload, 0, s.b, s.end, payload.size)
        s.end += payload.size
        drain(s)
    }

    private fun compact(s: Buf) {
        if (s.start >= s.end) {
            s.start = 0
            s.end = 0
            return
        }
        if (s.start > (1 shl 16)) {
            System.arraycopy(s.b, s.start, s.b, 0, s.end - s.start)
            s.end -= s.start
            s.start = 0
        }
    }

    private fun be32(
        b: ByteArray,
        off: Int,
    ): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun le32(
        b: ByteArray,
        off: Int,
    ): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun le64(
        b: ByteArray,
        off: Int,
    ): Long {
        var v = 0L
        for (k in 0 until 8) v = v or ((b[off + k].toLong() and 0xFF) shl (8 * k))
        return v
    }

    private fun magicAt(
        s: Buf,
        off: Int,
    ): Boolean =
        off + 4 <= s.end &&
            s.b[off] == SeamCrypto.SEAM_MAGIC[0] &&
            s.b[off + 1] == SeamCrypto.SEAM_MAGIC[1] &&
            s.b[off + 2] == SeamCrypto.SEAM_MAGIC[2] &&
            s.b[off + 3] == SeamCrypto.SEAM_MAGIC[3]

    /**
     * Magic-framed message: `[len 4][magic 4][marker][…]`. Two of the three shapes are FIXED length, so
     * "magic verified ⇒ mlen trustworthy" is structural, not probabilistic — after a resync the 4 bytes
     * in front of the magic are whatever preceded it, and a false magic inside RTP ciphertext would
     * otherwise declare a length that swallows every message after it. An unknown marker is skipped
     * by its length as the proto requires, under a tighter cap for the same reason.
     */
    private fun lengthPlausible(
        s: Buf,
        mlen: Int,
    ): Boolean {
        if (mlen < 5 || mlen > MAX_MESSAGE) return false
        if (s.end - s.start < 9) return true // marker not buffered yet — can't judge; wait
        return when (s.b[s.start + 8].toInt() and 0xFF) {
            SeamCrypto.MARK_KEY -> mlen == 45
            SeamCrypto.MARK_FORMAT -> mlen == 21
            SeamCrypto.MARK_PKT -> mlen >= 4 + 1 + 8 + 36 // RTP hdr 12 + tag 16 + nonce 8 floor
            // Unknown marker: SKIP by length (proto rule), bounded — see MAX_UNKNOWN_MESSAGE.
            else -> mlen <= MAX_UNKNOWN_MESSAGE
        }
    }

    /** Legacy (pre-magic) shapes, used ONLY to decide once that a seam speaks the old framing. */
    private fun legacyLengthPlausible(
        s: Buf,
        mlen: Int,
    ): Boolean {
        if (s.end - s.start < 5 || mlen < 1 || mlen > MAX_MESSAGE) return false
        return when (s.b[s.start + 4].toInt() and 0xFF) {
            SeamCrypto.MARK_KEY -> mlen == 41
            SeamCrypto.MARK_FORMAT -> mlen == 17
            SeamCrypto.MARK_PKT -> mlen >= 1 + 8 + 36
            else -> false
        }
    }

    /** Re-align on the next `SEAM_MAGIC`, leaving the cursor on its 4-byte length prefix. */
    private fun resyncToMagic(s: Buf): Boolean {
        var i = s.start + 5 // past the current (bad) position; i − 4 > start guarantees progress
        while (i + 4 <= s.end) {
            if (magicAt(s, i)) {
                s.start = i - 4
                return true
            }
            i++
        }
        if (s.end - s.start > 7) s.start = s.end - 7 // a magic could straddle the next feed
        return false
    }

    /**
     * Decide, once per seam, which framing it speaks. A magic-framed message can never be mistaken for
     * a legacy one (its byte 4 is `'S'`, not a marker) and vice versa, so junk cannot latch the wrong
     * framing — it byte-resyncs instead. Leaves `framing` UNKNOWN and `start` unmoved when there are not
     * yet enough bytes to tell them apart.
     */
    private fun latchFraming(
        s: Buf,
        mlen: Int,
    ) {
        if (magicAt(s, s.start + 4)) {
            s.framing = Framing.MAGIC
            return
        }
        if (legacyLengthPlausible(s, mlen)) {
            s.framing = Framing.LEGACY
            if (!loggedLegacy) {
                loggedLegacy = true
                log.w(
                    "legacy audio framing detected (no SEAM_MAGIC) — this box predates the " +
                        "self-syncing audio seam; a re-SETUP on it can still desync the lane",
                )
            }
            return
        }
        if (s.end - s.start >= 8) s.start += 1 // neither framing fits — byte-resync
    }

    /** One legacy `[len][marker][…]` message. False = need more bytes. */
    private fun takeLegacy(
        s: Buf,
        mlen: Int,
    ): Boolean {
        if (mlen <= 0 || mlen > MAX_MESSAGE) {
            s.start += 1
            return true
        }
        if (s.end - s.start < 4 + mlen) return false
        handle(s.b, s.start + 4, mlen)
        s.start += 4 + mlen
        compact(s)
        return true
    }

    /** One `[len][SEAV][marker][…]` message, resyncing on the magic when the framing is torn. */
    private fun takeMagic(
        s: Buf,
        mlen: Int,
    ): Boolean {
        if (s.end - s.start < 8) return false // need [len 4][magic 4]
        if (!magicAt(s, s.start + 4) || !lengthPlausible(s, mlen)) return resyncToMagic(s)
        if (s.end - s.start < 4 + mlen) return false // magic verified, so mlen is trustworthy
        handle(s.b, s.start + 8, 4 + mlen - 8) // strip [len][magic] → [marker][payload]
        s.start += 4 + mlen
        compact(s)
        return true
    }

    /**
     * Three-way: `true` = framing known, take a message; `false` = byte-resynced, re-read the length;
     * `null` = undecidable until more bytes arrive, stop draining.
     */
    private fun framingReady(
        s: Buf,
        mlen: Int,
    ): Boolean? {
        if (s.framing != Framing.UNKNOWN) return true
        val before = s.start
        latchFraming(s, mlen)
        if (s.framing != Framing.UNKNOWN) return true
        return if (s.start == before) null else false
    }

    private fun drain(s: Buf) {
        while (s.end - s.start >= 5) { // [len 4][marker] is the smallest either framing can start with
            val mlen = be32(s.b, s.start)
            val ready = framingReady(s, mlen) ?: return
            if (!ready) continue
            val progressed = if (s.framing == Framing.LEGACY) takeLegacy(s, mlen) else takeMagic(s, mlen)
            if (!progressed) return
        }
    }

    private fun handle(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        if (len < 1) return
        when (b[off].toInt() and 0xFF) {
            SeamCrypto.MARK_KEY -> {
                if (len < 41) return
                val scid = le64(b, off + 33)
                keys[scid] = b.copyOfRange(off + 1, off + 33)
                log.i("received audio key (scid=$scid)")
            }
            SeamCrypto.MARK_FORMAT -> if (len >= 17) onFormat(b, off)
            SeamCrypto.MARK_PKT -> if (len >= 9) onPacket(b, off, len)
            else -> {
                skippedUnknown.incrementAndGet()
                val m = b[off].toInt() and 0xFF
                warnOnce("marker-$m", "unknown audio seam marker 0x%02x — skipping by length".format(m))
            }
        }
    }

    private fun onFormat(
        b: ByteArray,
        off: Int,
    ) {
        val scid = le64(b, off + 1)
        val f =
            Format(
                codec = b[off + 9].toInt() and 0xFF,
                rate = le32(b, off + 10),
                channels = b[off + 14].toInt() and 0xFF,
                bits = b[off + 15].toInt() and 0xFF,
                atype = b[off + 16].toInt() and 0xFF,
            )
        val prev = formats[scid]
        formats[scid] = f
        val changed = prev == null || prev.codec != f.codec || prev.rate != f.rate || prev.channels != f.channels || prev.atype != f.atype
        if (changed) {
            log.i(
                "audio format scid=$scid: codec=${codecName(f.codec)} ${f.rate}Hz " +
                    "${f.channels}ch atype=${f.atype} -> ${if (SeamCrypto.isMediaAudioType(f.atype)) "media" else "voice"}",
            )
        }
    }

    private fun onPacket(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        val scid = le64(b, off + 1)
        val key = keys[scid]
        if (key == null) {
            unkeyed.incrementAndGet()
            return
        }
        val pkt = b.copyOfRange(off + 9, off + len)
        val au = SeamCrypto.openAudio(key, pkt)
        if (au == null) {
            val n = decryptFail.incrementAndGet()
            if (n == 1L || n % 500 == 0L) log.e("audio decrypt FAILED (ok=${decryptOk.get()} fail=$n)")
            return
        }
        decryptOk.incrementAndGet()
        route(scid, Au(au, 0, au.size))
    }

    private fun codecName(c: Int) =
        when (c) {
            SeamCrypto.CODEC_PCM -> "PCM"
            SeamCrypto.CODEC_AAC_LC -> "AAC-LC"
            SeamCrypto.CODEC_AAC_ELD -> "AAC-ELD"
            SeamCrypto.CODEC_OPUS -> "OPUS"
            else -> "codec$c"
        }

    /**
     * Route one access unit by its stream's `audioType`, then by its codec.
     *
     * atype 5 (`compatibility`) goes to **media**, not voice. It is a media-carrying PCM fallback
     * (`session.rs:889-895`); the macOS host's `isVoice { audioType != 0 }` shortcut sends it to the
     * voice player, which on AAOS would put music on the assistant volume group and permanently duck it.
     */
    private fun route(
        scid: Long,
        au: Au,
    ) {
        val f = formats[scid]
        if (f == null) {
            // Without SEAM_FORMAT there is no rate/channel/atype to route or configure with, and
            // guessing is how streams end up on the wrong AAOS volume group.
            warnOnce("nofmt-$scid", "audio AU for scid=$scid with no SEAM_FORMAT yet — dropped")
            return
        }
        if (SeamCrypto.isMediaAudioType(f.atype)) {
            routeMedia(scid, f, au)
        } else {
            routeVoice(scid, f, au)
        }
    }

    /**
     * Media → the same 12-byte [VoiceTag] framing the voice lane uses, so `MediaAudioPlayer` dispatches
     * on the tag's codec: wired CarPlay's PCM (big-endian on the wire, byte-swapped here to S16LE) and
     * wireless CarPlay's AAC-LC (raw AU; the player configures MediaCodec from the tag's rate/channels).
     */
    private fun routeMedia(
        scid: Long,
        f: Format,
        au: Au,
    ) {
        when {
            f.codec == SeamCrypto.CODEC_AAC_LC ->
                mediaPipe.write(VoiceTag.wrap(au.buf, au.off, au.len, VoiceTag.Fmt(f.rate, f.channels, f.atype, SeamCrypto.CODEC_AAC_LC)))
            f.codec == SeamCrypto.CODEC_PCM -> {
                if (f.bits != 16) {
                    warnOnce("media-pcm-bits-$scid", "PCM media stream scid=$scid is ${f.bits}-bit; only S16 is supported — dropping")
                    return
                }
                val pcm = au.copy()
                swap16(pcm)
                mediaPipe.write(VoiceTag.wrap(pcm, VoiceTag.Fmt(f.rate, f.channels, f.atype, SeamCrypto.CODEC_PCM)))
            }
            else ->
                warnOnce(
                    "media-${f.codec}",
                    "media stream scid=$scid is ${codecName(f.codec)} ${f.rate}Hz ${f.channels}ch " +
                        "${f.bits}-bit; the media player decodes PCM and AAC-LC — dropping. " +
                        "Wireless CarPlay negotiates AAC-LC and wired negotiates PCM, so the pushed audio config selected something else.",
                )
        }
    }

    private fun routeVoice(
        scid: Long,
        f: Format,
        au: Au,
    ) {
        when (f.codec) {
            SeamCrypto.CODEC_AAC_ELD ->
                voicePipe.write(VoiceTag.wrap(au.buf, au.off, au.len, VoiceTag.Fmt(f.rate, f.channels, f.atype, SeamCrypto.CODEC_AAC_ELD)))
            SeamCrypto.CODEC_PCM -> voicePcm(scid, f, au)
            else ->
                warnOnce(
                    "voice-${f.codec}",
                    "voice stream scid=$scid is ${codecName(f.codec)} ${f.rate}Hz ${f.channels}ch atype=${f.atype}; " +
                        "this client decodes AAC-ELD and PCM on the voice lane — dropping rather than " +
                        "feeding it to the wrong decoder",
                )
        }
    }

    /**
     * PCM voice. AirPlay RTP PCM is big-endian on the wire (macOS `OCBMAVBridge`: "everything else came out of the CarPlay
     * RTP and is BIG-endian"); VoiceRouter takes S16LE, so that one is swapped here.
     */
    private fun voicePcm(
        scid: Long,
        f: Format,
        au: Au,
    ) {
        if (f.bits != 16) {
            warnOnce("voice-pcm-bits-$scid", "PCM voice stream scid=$scid is ${f.bits}-bit; only S16 is supported — dropping")
            return
        }
        val pcm = au.copy()
        swap16(pcm)
        voicePipe.write(VoiceTag.wrap(pcm, VoiceTag.Fmt(f.rate, f.channels, f.atype, SeamCrypto.CODEC_PCM)))
    }

    /** In-place S16 byte swap (big-endian wire -> little-endian host). An odd trailing byte is left alone. */
    private fun swap16(b: ByteArray) {
        var i = 0
        while (i + 1 < b.size) {
            val t = b[i]
            b[i] = b[i + 1]
            b[i + 1] = t
            i += 2
        }
    }

    private val warned = HashSet<String>()

    private fun warnOnce(
        key: String,
        msg: String,
    ) {
        if (warned.add(key)) log.w(msg)
    }
}
