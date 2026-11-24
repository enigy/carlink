package com.carlink.ocbm

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * OCBS v1, the host's side: the first set-up of an adapter that has no Wi-Fi link and no host, over the set-up network
 * it offers from its own AP for 10 minutes after power-up (the Wi-Fi link plan's M3, approved 24 Nov; the exchange is
 * the adapter side's proposal in the handoff notes, its P-256 vectors reproduced with the platform's own EC classes in
 * OcbsVectorsTest). The network's passphrase is fixed and published, so the secrecy is this exchange's:
 *
 * ```text
 *   host -> box   "OCBS" 0x01 host_pub[65]        (P-256, uncompressed 0x04|X|Y)   70 B
 *   box  -> host  "OCBS" 0x01 box_pub[65]                                          70 B
 *   Z = the ECDH shared secret's X (32 B); keys HKDF-SHA256(salt = host_pub | box_pub, ikm = Z),
 *   info "OCBS v1 host->box" / "OCBS v1 box->host"
 * ```
 *
 * Then OCBW v1's record layer exactly ([OcbwLink.Sealer], [OcbwLink.Opener], keepalives), with CT_HELLO, CT_SETTIME,
 * MGMT_GET_INFO and MGMT_WIFI_LINK the only frames the box takes. Trust is first-come within the window: the adapter has
 * no screen to show a code on. P-256 is the platform's (Conscrypt on the head unit): no new library.
 */
object OcbsSetup {
    val MAGIC: ByteArray = byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte())
    const val VERSION: Byte = 1

    /** The box's set-up listener, on its AP address, only while the set-up network is up. */
    const val PORT = 7021
    const val BOX_ADDRESS = "192.168.43.1"

    /** The set-up network: `CarLink-Setup-xxxx`, xxxx the box's 4 hex digits, with this fixed, published passphrase. */
    const val SSID_PREFIX = "CarLink-Setup-"
    const val PASSPHRASE = "carlinksetup"

    const val POINT_LEN = 65
    const val HELLO_LEN = 4 + 1 + POINT_LEN
    private const val COORD_LEN = 32
    private const val UNCOMPRESSED: Byte = 4

    private val H2B = "OCBS v1 host->box".toByteArray(Charsets.US_ASCII)
    private val B2H = "OCBS v1 box->host".toByteArray(Charsets.US_ASCII)

    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    /** A fresh P-256 key pair for one exchange. */
    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** [pub] as 65 bytes, 0x04 | X | Y. */
    fun encode(pub: ECPublicKey): ByteArray = byteArrayOf(UNCOMPRESSED) + fixed(pub.w.affineX) + fixed(pub.w.affineY)

    /** A 65-byte uncompressed point as a key; throws [OcbwLink.LinkException] when it isn't one. ECDH checks the curve. */
    fun decode(b: ByteArray): ECPublicKey {
        if (b.size != POINT_LEN || b[0] != UNCOMPRESSED) throw OcbwLink.LinkException("not an uncompressed P-256 point")
        val point = ECPoint(BigInteger(1, b.copyOfRange(1, 1 + COORD_LEN)), BigInteger(1, b.copyOfRange(1 + COORD_LEN, POINT_LEN)))
        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
        } catch (e: GeneralSecurityException) {
            throw OcbwLink.LinkException("the peer's point was refused: ${e.message}", cause = e)
        }
    }

    /** Z: the X coordinate of [own] times [peer], 32 bytes. Throws [OcbwLink.LinkException] for a point off the curve. */
    fun sharedX(
        own: PrivateKey,
        peer: ECPublicKey,
    ): ByteArray =
        try {
            KeyAgreement.getInstance("ECDH").run {
                init(own)
                doPhase(peer, true)
                generateSecret()
            }
        } catch (e: GeneralSecurityException) {
            throw OcbwLink.LinkException("the key agreement failed: ${e.message}", cause = e)
        }

    /** The two record keys: HKDF-SHA256 of [z], salted with both points (130 bytes). */
    fun sessionKeys(
        z: ByteArray,
        hostPub: ByteArray,
        boxPub: ByteArray,
    ): OcbwLink.Keys = OcbwLink.Keys(OcbwLink.hkdf32(hostPub + boxPub, z, H2B), OcbwLink.hkdf32(hostPub + boxPub, z, B2H))

    /** The host's side on a fresh connection to the set-up port. Blocking: give the socket a read timeout first. */
    fun hostHandshake(
        input: InputStream,
        output: OutputStream,
        own: KeyPair = newKeyPair(),
    ): OcbwLink.Session {
        val hostPub = encode(own.public as ECPublicKey)
        output.write(hello(hostPub))
        output.flush()
        val boxPub = readHello(DataInputStream(input))
        val keys = sessionKeys(sharedX(own.private, decode(boxPub)), hostPub, boxPub)
        return OcbwLink.Session(OcbwLink.Sealer(keys.hostToBox), OcbwLink.Opener(keys.boxToHost), transcript = hostPub + boxPub)
    }

    /** The box's side, for tests: a fake adapter's set-up listener. */
    fun boxHandshake(
        input: InputStream,
        output: OutputStream,
        own: KeyPair = newKeyPair(),
    ): OcbwLink.Session {
        val hostPub = readHello(DataInputStream(input))
        val boxPub = encode(own.public as ECPublicKey)
        output.write(hello(boxPub))
        output.flush()
        val keys = sessionKeys(sharedX(own.private, decode(hostPub)), hostPub, boxPub)
        return OcbwLink.Session(OcbwLink.Sealer(keys.boxToHost), OcbwLink.Opener(keys.hostToBox))
    }

    private fun hello(pub: ByteArray): ByteArray = MAGIC + byteArrayOf(VERSION) + pub

    /** The peer's 70 bytes: its point, once the magic and version are OCBS v1's. */
    private fun readHello(din: DataInputStream): ByteArray {
        val b = ByteArray(HELLO_LEN)
        din.readFully(b)
        if (!b.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) || b[MAGIC.size] != VERSION) throw OcbwLink.LinkException("the peer didn't open with OCBS v1")
        return b.copyOfRange(MAGIC.size + 1, HELLO_LEN)
    }

    private fun fixed(v: BigInteger): ByteArray {
        val b = v.toByteArray().takeLast(COORD_LEN).toByteArray()
        return ByteArray(COORD_LEN - b.size) + b
    }
}
