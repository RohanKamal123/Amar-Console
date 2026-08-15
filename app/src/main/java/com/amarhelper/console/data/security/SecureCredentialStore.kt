package com.amarhelper.console.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.amarhelper.console.core.log.AppLogger
import com.amarhelper.console.data.config.ServiceId
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.credentialDataStore: DataStore<Preferences> by preferencesDataStore(name = "console_credentials")

/** What Settings is allowed to know about a stored secret: that it exists, and when it was set. */
data class CredentialPresence(
    val isSet: Boolean,
    val updatedAtEpochMillis: Long?,
)

/**
 * Stores per-service bearer tokens / API keys.
 *
 * The plaintext never touches disk. A non-exportable AES-256 key lives in the Android
 * Keystore (hardware-backed where the device supports it) and encrypts each token with
 * AES/GCM; only the IV + ciphertext is persisted. There is no API that returns a token
 * to the UI layer — only [tokenFor], which the network layer calls to build a header.
 *
 * SharedPreferences is not used anywhere in this app, per SECURITY.md.
 */
@Singleton
class SecureCredentialStore @Inject constructor(
    private val context: Context,
) {
    private companion object {
        const val TAG = "CredentialStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "amar_console_credential_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_LENGTH = 12
    }

    private fun cipherTextKey(service: ServiceId) = stringPreferencesKey("token_${service.name}")
    private fun updatedAtKey(service: ServiceId) = longPreferencesKey("token_updated_${service.name}")

    private val prefs: Flow<Preferences> = context.credentialDataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }

    /** Observe only whether a credential exists — never its value. */
    fun presence(service: ServiceId): Flow<CredentialPresence> = prefs.map { p ->
        CredentialPresence(
            isSet = !p[cipherTextKey(service)].isNullOrBlank(),
            updatedAtEpochMillis = p[updatedAtKey(service)],
        )
    }

    suspend fun setToken(service: ServiceId, token: String) = withContext(Dispatchers.IO) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            clearToken(service)
            return@withContext
        }
        val encoded = encrypt(trimmed)
        context.credentialDataStore.edit {
            it[cipherTextKey(service)] = encoded
            it[updatedAtKey(service)] = System.currentTimeMillis()
        }
    }

    /**
     * Decrypts the stored token for use in an Authorization header.
     * Called only from the network layer.
     */
    suspend fun tokenFor(service: ServiceId): String? = withContext(Dispatchers.IO) {
        val stored = prefs.first()[cipherTextKey(service)] ?: return@withContext null
        try {
            decrypt(stored)
        } catch (e: Exception) {
            // Key invalidated (device credential removed, app restored to a new device):
            // drop the unusable ciphertext so the user is prompted to re-enter it.
            AppLogger.w(TAG, "Stored credential could not be decrypted; clearing it.", e)
            clearToken(service)
            null
        }
    }

    suspend fun clearToken(service: ServiceId) {
        context.credentialDataStore.edit {
            it.remove(cipherTextKey(service))
            it.remove(updatedAtKey(service))
        }
    }

    /** Full logout: forget every stored secret. */
    suspend fun clearAll() {
        context.credentialDataStore.edit { it.clear() }
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        iv.copyInto(combined)
        cipherText.copyInto(combined, iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > IV_LENGTH) { "ciphertext too short" }
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val cipherText = combined.copyOfRange(IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
