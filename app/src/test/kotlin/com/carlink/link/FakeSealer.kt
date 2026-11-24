package com.carlink.link

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A [LinkKeySealer] for tests: AES-GCM under a fixed key in memory, in place of the Android Keystore's (Robolectric has no
 * AndroidKeyStore). [broken] stands for a Keystore whose key is gone: nothing opens.
 */
internal class FakeSealer(
    private val broken: Boolean = false,
    /** The Keystore failing for now: opens are [LinkKeySealer.Opened.Unavailable], seals throw. */
    @Volatile var unavailable: Boolean = false,
) : LinkKeySealer {
    private val key = SecretKeySpec(ByteArray(32) { 3 }, "AES")

    override fun seal(plain: ByteArray): String {
        if (unavailable) throw java.security.ProviderException("keystore busy")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return Base64.getEncoder().encodeToString(c.iv + c.doFinal(plain))
    }

    override fun open(sealed: String): LinkKeySealer.Opened {
        if (unavailable) return LinkKeySealer.Opened.Unavailable
        if (broken) return LinkKeySealer.Opened.Gone
        return runCatching {
            val b = Base64.getDecoder().decode(sealed)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b, 0, 12))
            LinkKeySealer.Opened.Key(c.doFinal(b, 12, b.size - 12))
        }.getOrDefault(LinkKeySealer.Opened.Gone)
    }
}
