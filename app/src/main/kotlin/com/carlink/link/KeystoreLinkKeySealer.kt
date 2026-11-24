package com.carlink.link

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the Wi-Fi link key with an AES-256-GCM key that lives in the head unit's Android Keystore and never leaves it, as
 * [com.carlink.fuel.GooglePlacesStore] keeps the driver's API key. The sealed text (IV, ciphertext and tag, Base64) sits in
 * Carlink's private preferences, which are never backed up (allowBackup is off). Text this Keystore can never open (its
 * key gone, a bad tag: made on another head unit) is [LinkKeySealer.Opened.Gone], and Settings asks for the hotspot
 * again; any other KeyStore failure is [LinkKeySealer.Opened.Unavailable] and tried again later.
 */
class KeystoreLinkKeySealer : LinkKeySealer {
    override fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    override fun open(sealed: String): LinkKeySealer.Opened {
        val bytes = runCatching { Base64.decode(sealed, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size > IV_BYTES } ?: return LinkKeySealer.Opened.Gone
        return try {
            val key = existingKey() ?: return LinkKeySealer.Opened.Gone // the Keystore has no key: for good
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            LinkKeySealer.Opened.Key(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        } catch (_: AEADBadTagException) {
            LinkKeySealer.Opened.Gone // not sealed by this key: for good
        } catch (_: GeneralSecurityException) {
            LinkKeySealer.Opened.Unavailable // KeyStoreException and the like: may pass
        } catch (_: ProviderException) {
            LinkKeySealer.Opened.Unavailable // the Keystore daemon's own failure: may pass
        } catch (_: java.io.IOException) {
            LinkKeySealer.Opened.Unavailable // loading the KeyStore
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(ALIAS, null) as? SecretKey

    /** The Keystore's AES key for the link key, made on first use. */
    private fun secretKey(): SecretKey {
        existingKey()?.let { return it }
        val spec =
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        // PERSISTENCE CONTRACT: a new alias can't open the link keys sealed under the old one.
        const val ALIAS = "carlink_wifi_link_key"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
        const val KEY_BITS = 256
    }
}
