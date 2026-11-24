package com.carlink.ocbm

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * OCBW v1, the host's side: OCBM over TCP on the phone's hotspot, the box's `crates/ocbm-link` byte for byte (the
 * Wi-Fi link plan of 23 Nov; the outside pull request enigy/carlink#2 was the starting point, ported by reading).
 *
 * WHY. Over USB the OCBM stream rides a cable only the head unit holds. Over Wi-Fi it rides a hotspot others can join,
 * and the stream carries a root console, file push and CarPlay's A/V keys. So nobody gets a byte through without the
 * 32-byte link key, and nobody on the hotspot reads one. The key is set once over a session Carlink already has
 * ([WifiLinkProvisioning]) and never crosses the network: both sides only prove they hold it.
 *
 * ```text
 *   box  -> host   "OCBW" ver=1 box_nonce[32]                                   (37 B)
 *   host -> box    "OCBW" ver=1 host_nonce[32] HMAC(K, "OCBW host" | bn | hn)   (69 B)
 *   box  -> host   HMAC(K, "OCBW box" | hn | bn)                                 (32 B)
 * ```
 *
 * The host proves first, so a stranger who connects learns only a nonce. Session keys: HKDF-SHA256 (salt = bn | hn,
 * ikm = K), info "OCBW v1 host->box" and "OCBW v1 box->host". Records: `[u32 BE ciphertext length][ciphertext | 16 B
 * tag]`, ChaCha20-Poly1305 with the nonce 4 zero bytes + a per-direction 64-bit little-endian counter from 0, so a
 * record dropped, replayed or reordered fails its tag and ends the link. The known-answer vectors in OcbwLinkTest
 * are the box crate's.
 *
 * An empty record (no plaintext, the tag alone) is the link's keepalive ([Sealer.keepalive]): each side sends one when
 * it has sent nothing for a second, so each can read a silent second side as gone within three ([WifiBulkTransport]).
 * Receivers drop it; it means nothing to OCBM.
 *
 * The record layer ([Sealer], [Opener], [Session]) takes its keys from anywhere, so a later set-up exchange that
 * agrees keys another way (the set-up network's design, notes/carlink.md) can reuse it.
 */
object OcbwLink {
    val MAGIC: ByteArray = byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), 'W'.code.toByte())
    const val VERSION: Byte = 1

    /** The TCP port the box listens on, on its address on the hotspot only. */
    const val PORT = 7020

    /** The DNS-SD service the box advertises on the hotspot, TXT `v=1` and `id=`[boxId]. */
    const val SERVICE_TYPE = "_ocbm._tcp"

    /** Largest record plaintext the box takes: an OCBM frame is at most 16 B + 64 KiB, and record boundaries mean nothing. */
    const val MAX_RECORD = 64 * 1024
    const val TAG_LEN = 16
    const val KEY_LEN = 32
    const val NONCE_LEN = 32
    const val PROOF_LEN = 32

    /** The box's first message: magic, version, its nonce. */
    const val BOX_HELLO_LEN = 4 + 1 + NONCE_LEN

    /** The host's answer: magic, version, its nonce, its proof. */
    const val HOST_REPLY_LEN = BOX_HELLO_LEN + PROOF_LEN

    /** The u32 in front of every record. */
    const val LENGTH_LEN = 4

    /** The box id's length in bytes; 16 hex digits on the air. */
    private const val BOX_ID_BYTES = 8

    private val HOST_LABEL = "OCBW host".toByteArray(Charsets.US_ASCII)
    private val BOX_LABEL = "OCBW box".toByteArray(Charsets.US_ASCII)
    private val ID_LABEL = "OCBW id".toByteArray(Charsets.US_ASCII)
    private val H2B = "OCBW v1 host->box".toByteArray(Charsets.US_ASCII)
    private val B2H = "OCBW v1 box->host".toByteArray(Charsets.US_ASCII)
    private val HKDF_BLOCK_1 = byteArrayOf(1)

    /**
     * Conscrypt (the head unit) names the AEAD "ChaCha20/Poly1305/NoPadding" (API 28+); the desktop JDK the unit tests run
     * on names it "ChaCha20-Poly1305". Either is RFC 8439, the box crate's.
     */
    private val AEAD_NAMES = listOf("ChaCha20/Poly1305/NoPadding", "ChaCha20-Poly1305")

    private val rng = SecureRandom()

    fun random32(): ByteArray = ByteArray(KEY_LEN).also { rng.nextBytes(it) }

    fun hmac(
        key: ByteArray,
        vararg parts: ByteArray,
    ): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { m.update(it) }
        return m.doFinal()
    }

    /** The box's public id for discovery: the first 8 bytes of HMAC(K, "OCBW id") as 16 lower-case hex digits. */
    fun boxId(key: ByteArray): String = hmac(key, ID_LABEL).copyOf(BOX_ID_BYTES).toHex()

    fun hostProof(
        key: ByteArray,
        bn: ByteArray,
        hn: ByteArray,
    ): ByteArray = hmac(key, HOST_LABEL, bn, hn)

    fun boxProof(
        key: ByteArray,
        hn: ByteArray,
        bn: ByteArray,
    ): ByteArray = hmac(key, BOX_LABEL, hn, bn)

    /** The two directions' 32-byte record keys. */
    class Keys(
        val hostToBox: ByteArray,
        val boxToHost: ByteArray,
    )

    /** HKDF-SHA256 (RFC 5869) for 32 bytes, one HMAC block: PRK = HMAC(salt, ikm), key = HMAC(PRK, info | 0x01). */
    fun hkdf32(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
    ): ByteArray = hmac(hmac(salt, ikm), info, HKDF_BLOCK_1)

    /** OCBW v1's two record keys: HKDF-SHA256 of K, salted with bn | hn. */
    fun sessionKeys(
        key: ByteArray,
        bn: ByteArray,
        hn: ByteArray,
    ): Keys = Keys(hostToBox = hkdf32(bn + hn, key, H2B), boxToHost = hkdf32(bn + hn, key, B2H))

    /**
     * The peer isn't an OCBW v1 peer, can't prove the key, or sent a record that fails: the link ends. [keyRefused]: the
     * box proved another key, or closed the connection just after this side's proof (a box refuses a wrong proof so).
     */
    open class LinkException(
        message: String,
        val keyRefused: Boolean = false,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    /** A finished handshake: [tx] seals what this side sends, [rx] opens what it receives. */
    class Session(
        val tx: Sealer,
        val rx: Opener,
    )

    /**
     * The host's side on a fresh connection. Blocking: give the socket a read timeout first. Throws [LinkException] when
     * the peer isn't an OCBW v1 box or can't prove it holds [key], and an IOException (EOF included) when the box closes
     * the connection, which is how a box refuses a host (notes/carlink.md, section 3).
     */
    fun hostHandshake(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        hostNonce: ByteArray = random32(),
    ): Session {
        require(key.size == KEY_LEN && hostNonce.size == NONCE_LEN) { "a 32-byte key and nonce" }
        val din = DataInputStream(input)
        val bn = readBoxHello(din)
        val reply = ByteArray(HOST_REPLY_LEN)
        MAGIC.copyInto(reply)
        reply[MAGIC.size] = VERSION
        hostNonce.copyInto(reply, MAGIC.size + 1)
        hostProof(key, bn, hostNonce).copyInto(reply, BOX_HELLO_LEN)
        output.write(reply)
        output.flush()
        val proof = readBoxProof(din)
        if (!MessageDigest.isEqual(proof, boxProof(key, hostNonce, bn))) throw LinkException("the box's proof failed: it holds another link key", keyRefused = true)
        val keys = sessionKeys(key, bn, hostNonce)
        return Session(Sealer(keys.hostToBox), Opener(keys.boxToHost))
    }

    /** The box's first message: its nonce, once the magic and version are OCBW v1's. */
    private fun readBoxHello(din: DataInputStream): ByteArray {
        val hello = ByteArray(BOX_HELLO_LEN)
        din.readFully(hello)
        if (!opensWithMagic(hello)) throw LinkException("the peer didn't open with OCBW v1")
        return hello.copyOfRange(MAGIC.size + 1, BOX_HELLO_LEN)
    }

    /** The box's proof; the connection closing instead is the box refusing this side's proof. */
    private fun readBoxProof(din: DataInputStream): ByteArray {
        val proof = ByteArray(PROOF_LEN)
        try {
            din.readFully(proof)
        } catch (_: EOFException) {
            throw LinkException("the box closed the connection after this head unit's proof", keyRefused = true)
        }
        return proof
    }

    /** The box's side, for tests: a fake box on loopback. Throws [LinkException] when the host's proof fails. */
    fun boxHandshake(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        boxNonce: ByteArray = random32(),
    ): Session {
        val hello = ByteArray(BOX_HELLO_LEN)
        MAGIC.copyInto(hello)
        hello[MAGIC.size] = VERSION
        boxNonce.copyInto(hello, MAGIC.size + 1)
        output.write(hello)
        output.flush()
        val reply = ByteArray(HOST_REPLY_LEN)
        DataInputStream(input).readFully(reply)
        if (!opensWithMagic(reply)) throw LinkException("the peer didn't open with OCBW v1")
        val hn = reply.copyOfRange(MAGIC.size + 1, BOX_HELLO_LEN)
        if (!MessageDigest.isEqual(reply.copyOfRange(BOX_HELLO_LEN, HOST_REPLY_LEN), hostProof(key, boxNonce, hn))) {
            throw LinkException("the host's proof failed")
        }
        output.write(boxProof(key, hn, boxNonce))
        output.flush()
        val keys = sessionKeys(key, boxNonce, hn)
        return Session(Sealer(keys.boxToHost), Opener(keys.hostToBox))
    }

    private fun opensWithMagic(b: ByteArray): Boolean = b.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) && b[MAGIC.size] == VERSION

    private fun aead(): Cipher {
        var last: GeneralSecurityException? = null
        for (name in AEAD_NAMES) {
            try {
                return Cipher.getInstance(name)
            } catch (e: GeneralSecurityException) {
                last = e
            }
        }
        throw IllegalStateException("this platform has no ChaCha20-Poly1305", last)
    }

    /** Seals one direction's records. Not thread-safe: the caller serialises sends (each record takes the next counter). */
    class Sealer(
        key: ByteArray,
    ) {
        private val key = SecretKeySpec(key.copyOf(), "ChaCha20")
        private val cipher = aead()
        private var counter = 0L

        /** One record for [len] bytes of [plain] from [off] (at most [MAX_RECORD]), ready to write. */
        fun seal(
            plain: ByteArray,
            off: Int = 0,
            len: Int = plain.size - off,
        ): ByteArray {
            require(len in 0..MAX_RECORD) { "record plaintext over MAX_RECORD" }
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(nonce(counter++)))
            val out = ByteArray(LENGTH_LEN + len + TAG_LEN)
            val n = cipher.doFinal(plain, off, len, out, LENGTH_LEN)
            check(n == len + TAG_LEN) { "the AEAD wrote $n B for $len" }
            for (i in 0 until LENGTH_LEN) out[i] = (n ushr (Byte.SIZE_BITS * (LENGTH_LEN - 1 - i))).toByte()
            return out
        }

        /** The keepalive: an empty record, which takes a counter like any other. */
        fun keepalive(): ByteArray = seal(EMPTY)
    }

    /** Opens the other direction's records, from a stream that may split them anywhere. Not thread-safe. */
    class Opener(
        key: ByteArray,
    ) {
        private val key = SecretKeySpec(key.copyOf(), "ChaCha20")
        private val cipher = aead()
        private var counter = 0L
        private var buf = ByteArray(0)

        /** Bytes as they came off the socket. */
        fun push(
            bytes: ByteArray,
            len: Int = bytes.size,
        ) {
            buf += bytes.copyOf(len)
        }

        /** The next whole record's plaintext (empty for a keepalive), null until one has arrived. Throws [LinkException]. */
        fun next(): ByteArray? {
            if (buf.size < LENGTH_LEN) return null
            val len = lengthOf(buf)
            checkLength(len)
            if (buf.size < LENGTH_LEN + len) return null
            val plain = open(buf, LENGTH_LEN, len)
            buf = buf.copyOfRange(LENGTH_LEN + len, buf.size)
            return plain
        }

        /**
         * Exactly one record from [input], blocking: its plaintext, empty for a keepalive. Null at a clean EOF between
         * records; an EOF inside one is an [EOFException].
         */
        fun read(input: DataInputStream): ByteArray? {
            val head = ByteArray(LENGTH_LEN)
            val first = input.read()
            if (first < 0) return null
            head[0] = first.toByte()
            input.readFully(head, 1, LENGTH_LEN - 1)
            val len = lengthOf(head)
            checkLength(len)
            val body = ByteArray(len)
            input.readFully(body)
            return open(body, 0, len)
        }

        private fun open(
            b: ByteArray,
            off: Int,
            len: Int,
        ): ByteArray {
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(nonce(counter)))
            val plain =
                try {
                    cipher.doFinal(b, off, len)
                } catch (_: AEADBadTagException) {
                    throw LinkException("a record failed authentication")
                }
            counter++
            return plain
        }
    }

    private val EMPTY = ByteArray(0)
}

private const val AEAD_NONCE_LEN = 12
private const val COUNTER_OFFSET = 4
private const val BYTE_MASK = 0xFF

/** A record's AEAD nonce: 4 zero bytes, then the direction's counter, little-endian. */
private fun nonce(counter: Long): ByteArray {
    val n = ByteArray(AEAD_NONCE_LEN)
    for (i in 0 until Long.SIZE_BYTES) n[COUNTER_OFFSET + i] = (counter ushr (Byte.SIZE_BITS * i)).toByte()
    return n
}

/** A record's u32 big-endian length at [off]. */
private fun lengthOf(
    b: ByteArray,
    off: Int = 0,
): Int =
    ((b[off].toInt() and BYTE_MASK) shl 24) or ((b[off + 1].toInt() and BYTE_MASK) shl 16) or
        ((b[off + 2].toInt() and BYTE_MASK) shl Byte.SIZE_BITS) or (b[off + 3].toInt() and BYTE_MASK)

private fun checkLength(len: Int) {
    if (len < OcbwLink.TAG_LEN || len > OcbwLink.MAX_RECORD + OcbwLink.TAG_LEN) throw OcbwLink.LinkException("a record of $len B is outside the limits")
}

/** Lower-case hex, two digits a byte. */
fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
    }
    return sb.toString()
}

/** The bytes [s] spells in hex (either case), or null when it doesn't. */
fun hexBytes(s: String): ByteArray? {
    val t = s.trim()
    if (t.length % 2 != 0) return null
    val out = ByteArray(t.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(t[2 * i], 16)
        val lo = Character.digit(t[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
