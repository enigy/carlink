package com.carlink.fuel

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.time.YearMonth
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Fuel card's Google settings (stage C): the driver's API key, and this month's lookups against the cap
 * ([MonthlyBudget]).
 *
 * The key is encrypted with an AES key that lives in the head unit's Android Keystore and never leaves it, and the
 * ciphertext sits in Carlink's private preferences. A key that can't be decrypted (a backup restored on another head
 * unit) reads as no key. The key is never logged and never in Save Logs; Settings shows its last four characters.
 */
class GooglePlacesStore private constructor(
    context: Context,
) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val hasKeyState = MutableStateFlow(prefs.contains(KEY_BLOB))
    private val keyVersionState = MutableStateFlow(0)
    private val budgetState =
        MutableStateFlow(
            MonthlyBudget(
                prefs.getString(KEY_MONTH, null) ?: thisMonth(),
                prefs.getInt(KEY_COUNT, 0),
                prefs.getInt(KEY_CAP, MonthlyBudget.DEFAULT_CAP),
            ),
        )

    /** Whether a key is set. */
    val hasKey: StateFlow<Boolean> = hasKeyState.asStateFlow()

    /** Bumped at each key entered or removed: what Google said about the old key no longer holds. */
    val keyVersion: StateFlow<Int> = keyVersionState.asStateFlow()

    /** This month's lookups and the cap. */
    val budget: StateFlow<MonthlyBudget> = budgetState.asStateFlow()

    /** The key's last four characters, for Settings, or null. */
    val keyTail: String? get() = prefs.getString(KEY_TAIL, null)

    /** The key, decrypted; null when none is set or it can't be read. */
    fun key(): String? = prefs.getString(KEY_BLOB, null)?.let(::decrypt)

    fun setKey(key: String) {
        prefs
            .edit()
            .putString(KEY_BLOB, encrypt(key))
            .putString(KEY_TAIL, key.takeLast(TAIL))
            .apply()
        hasKeyState.value = true
        keyVersionState.value += 1
    }

    fun clearKey() {
        prefs
            .edit()
            .remove(KEY_BLOB)
            .remove(KEY_TAIL)
            .apply()
        hasKeyState.value = false
        keyVersionState.value += 1
    }

    /** Room for one more lookup this month. */
    fun allowsLookup(): Boolean = budgetState.value.allows(thisMonth())

    /** One lookup made this month. */
    fun spendLookup() = saveBudget(budgetState.value.spend(thisMonth()))

    fun setCap(cap: Int) = saveBudget(budgetState.value.copy(cap = cap))

    private fun saveBudget(b: MonthlyBudget) {
        prefs
            .edit()
            .putString(KEY_MONTH, b.month)
            .putInt(KEY_COUNT, b.count)
            .putInt(KEY_CAP, b.cap)
            .apply()
        budgetState.value = b
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? =
        runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()

    /** The Keystore's AES key for this, made on first use. */
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec =
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these loses the key or the count.
        private const val PREFS_NAME = "carlink_fuel_google"
        private const val KEY_BLOB = "api_key_v1"
        private const val KEY_TAIL = "api_key_tail"
        private const val KEY_MONTH = "lookups_month"
        private const val KEY_COUNT = "lookups_count"
        private const val KEY_CAP = "lookups_cap"
        private const val ALIAS = "carlink_places_key"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val KEY_BITS = 256
        private const val TAIL = 4

        fun thisMonth(): String = YearMonth.now().toString()

        @Volatile private var instance: GooglePlacesStore? = null

        fun get(context: Context): GooglePlacesStore =
            instance ?: synchronized(this) {
                instance ?: GooglePlacesStore(context).also { instance = it }
            }
    }
}
