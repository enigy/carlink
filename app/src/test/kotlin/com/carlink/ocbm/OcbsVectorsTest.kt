package com.carlink.ocbm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import kotlin.concurrent.thread

/**
 * The adapter side's M3 proposal (handoff notes, "OCBS v1"), whose P-256 vectors were computed with a hand-written Python
 * curve: reproduced here with the platform's own EC classes only (the JDK's here; Conscrypt on the head unit), no curve
 * arithmetic of Carlink's. Each public point is checked against its private scalar by ECDH with the generator (its X) and
 * by an ECDSA signature that verifies under it (its Y, sign included); Z by ECDH both ways; then the HKDF keys.
 */
class OcbsVectorsTest {
    private val params: ECParameterSpec =
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    private val ec = KeyFactory.getInstance("EC")

    private val hostPriv = BigInteger(1, ByteArray(32) { 0x11 })
    private val boxPriv = BigInteger(1, ByteArray(32) { 0x22 })
    private val hostPub = hexBytes(HOST_PUB)!!
    private val boxPub = hexBytes(BOX_PUB)!!

    private fun priv(d: BigInteger) = ec.generatePrivate(ECPrivateKeySpec(d, params))

    private fun pub(uncompressed: ByteArray): ECPublicKey {
        require(uncompressed.size == 65 && uncompressed[0] == 4.toByte())
        val x = BigInteger(1, uncompressed.copyOfRange(1, 33))
        val y = BigInteger(1, uncompressed.copyOfRange(33, 65))
        return ec.generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    private fun ecdh(
        d: BigInteger,
        q: ECPublicKey,
    ): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(priv(d))
            doPhase(q, true)
            generateSecret()
        }

    private fun signsFor(
        d: BigInteger,
        q: ECPublicKey,
    ): Boolean {
        val msg = "OCBS v1 vector check".toByteArray()
        val sig =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(priv(d))
                update(msg)
                sign()
            }
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(q)
            update(msg)
            verify(sig)
        }
    }

    @Test
    fun `each public point is its private scalar times the generator, X and Y`() {
        val g = ec.generatePublic(ECPublicKeySpec(params.generator, params)) as ECPublicKey
        assertEquals(HOST_PUB.substring(2, 66), ecdh(hostPriv, g).toHex())
        assertEquals(BOX_PUB.substring(2, 66), ecdh(boxPriv, g).toHex())
        assertTrue(signsFor(hostPriv, pub(hostPub)))
        assertTrue(signsFor(boxPriv, pub(boxPub)))
        // The check can fail: the other point, and the point with Y negated, don't verify.
        assertFalse(signsFor(hostPriv, pub(boxPub)))
        val p = (params.curve.field as ECFieldFp).p
        val negY = hostPub.copyOf()
        fixed32(p.subtract(BigInteger(1, hostPub.copyOfRange(33, 65)))).copyInto(negY, 33)
        assertFalse(signsFor(hostPriv, pub(negY)))
    }

    @Test
    fun `Z is the shared X coordinate both ways`() {
        assertEquals(Z, ecdh(hostPriv, pub(boxPub)).toHex())
        assertEquals(Z, ecdh(boxPriv, pub(hostPub)).toHex())
    }

    @Test
    fun `the session keys are HKDF-SHA256 of Z, salted with both points`() {
        val prk = OcbwLink.hmac(hostPub + boxPub, hexBytes(Z)!!)
        assertEquals(H2B, OcbwLink.hmac(prk, "OCBS v1 host->box".toByteArray(), byteArrayOf(1)).toHex())
        assertEquals(B2H, OcbwLink.hmac(prk, "OCBS v1 box->host".toByteArray(), byteArrayOf(1)).toHex())
        assertArrayEquals(hexBytes(H2B), OcbwLink.hkdf32(hostPub + boxPub, hexBytes(Z)!!, "OCBS v1 host->box".toByteArray()))
    }

    @Test
    fun `the host and a fake box on loopback reach the vectors' keys with the vectors' points`() {
        val host = KeyPair(pub(hostPub), priv(hostPriv))
        val box = KeyPair(pub(boxPub), priv(boxPriv))
        ServerSocket(0).use { server ->
            var boxSession: OcbwLink.Session? = null
            val t = thread { server.accept().use { s -> boxSession = OcbsSetup.boxHandshake(s.getInputStream(), s.getOutputStream(), box) } }
            Socket("127.0.0.1", server.localPort).use { s ->
                s.soTimeout = 5_000
                val session = OcbsSetup.hostHandshake(s.getInputStream(), s.getOutputStream(), host)
                t.join(5_000)
                assertEquals(OcbwLink.Sealer(hexBytes(H2B)!!).seal("OCBM".toByteArray()).toHex(), session.tx.seal("OCBM".toByteArray()).toHex())
                assertEquals(OcbwLink.Sealer(hexBytes(B2H)!!).seal("OCBM".toByteArray()).toHex(), boxSession!!.tx.seal("OCBM".toByteArray()).toHex())
                assertArrayEquals(hostPub, OcbsSetup.encode(host.public as ECPublicKey))
                // The order trap on the live path: the host keeps host_pub | box_pub, so both sides show the vector's code.
                assertEquals("2092", OcbsSetup.code(session.transcript))
                assertEquals("2092", OcbsSetup.code(boxSession!!.transcript))
            }
        }
    }

    @Test
    fun `the set-up code is the architect's vector, 2092, and 3978 with the points swapped`() {
        val digest = MessageDigest.getInstance("SHA-256").digest("OCBS v1 code".toByteArray() + hostPub + boxPub)
        assertEquals(CODE_DIGEST, digest.toHex())
        assertEquals("2092", OcbsSetup.code(hostPub + boxPub))
        // box_pub | host_pub: one side would show another code than the other, and the driver could never match them.
        assertEquals("3978", OcbsSetup.code(boxPub + hostPub))
    }

    @Test
    fun `the code is four digits, zero-padded, and only for two whole points`() {
        // The code reads the bytes alone; these two pairs (0x04 then 64 equal bytes each, found by a search) end in 0000 and
        // 0007 (digests 63ed8330... and ac6748d7...).
        assertEquals("0000", OcbsSetup.code(point(0x13) + point(0x48)))
        assertEquals("0007", OcbsSetup.code(point(0x00) + point(0xae)))
        assertNull("one point is no transcript", OcbsSetup.code(hostPub))
        assertNull(OcbsSetup.code(ByteArray(0)))
        assertNull(OcbsSetup.code(hostPub + boxPub + byteArrayOf(0)))
    }

    /** 0x04 and 64 bytes of [fill]: the shape of a point, for the code's padding (no curve involved). */
    private fun point(fill: Int): ByteArray = byteArrayOf(4) + ByteArray(64) { fill.toByte() }

    @Test
    fun `a point off the curve, a short point and the wrong magic are refused`() {
        val off = hostPub.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertRefused { OcbsSetup.sharedX(priv(boxPriv), OcbsSetup.decode(off)) }
        assertRefused { OcbsSetup.decode(hostPub.copyOf(64)) }
        val wrongMagic = "OCBW".toByteArray() + byteArrayOf(1) + boxPub
        assertRefused { OcbsSetup.hostHandshake(ByteArrayInputStream(wrongMagic), ByteArrayOutputStream()) }
    }

    private fun assertRefused(block: () -> Unit) {
        try {
            block()
            fail("expected a LinkException")
        } catch (_: OcbwLink.LinkException) {
        }
    }

    /** [v] as 32 big-endian bytes. */
    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray().takeLast(32).toByteArray()
        return ByteArray(32 - b.size) + b
    }

    private companion object {
        const val HOST_PUB =
            "040217e617f0b6443928278f96999e69a23a4f2c152bdf6d6cdf66e5b80282d4ed194a7debcb97712d2dda3ca85aa8765a56f45fc758599652f2897c65306e5794"
        const val BOX_PUB =
            "04d65a93977caa3d1b081852ff57a79e465f1660577304baead505dd3a48589cf350185e895372df6221ea3a137557e473fddb6755f05bd507c3c533fce9c91285"
        const val Z = "ccfc261f58193c98ca4ad4a53bbac6f0ee29bc4d48438090446908622ca79af6"
        const val H2B = "263e0d8efff4f691378e9b34501d35b52dc05692b96021e40cc2e2505abcd4e4"
        const val B2H = "e414baa6ea6e0160cc2e31bfce7645e7b6c5db9657c5cb3af58e02e6fa3fada4"

        /** SHA-256("OCBS v1 code" | host_pub | box_pub), the plan's ADDENDUM 5 (computed by the architect). */
        const val CODE_DIGEST = "66ce8b3c25c5f65e6e40bed0735ce316977299b0ebb2c5031842d943933a6460"
    }
}
