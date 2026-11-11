package ua.org.electros.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Creates [EncryptedSharedPreferences] with corruption/keystore-loss recovery and
 * cleans up legacy plaintext SharedPreferences files without falling back to plaintext storage.
 */
object EncryptedPrefsFactory {

    fun create(
        context: Context,
        fileName: String,
        legacyPlaintextNames: List<String> = emptyList()
    ): SharedPreferences {
        return openOrRecover(
            fileName = fileName,
            legacyPlaintextNames = legacyPlaintextNames,
            deletePrefs = { name -> context.deleteSharedPreferences(name) },
            openEncrypted = { name -> buildEncryptedPrefs(context, name) }
        )
    }

    internal fun openOrRecover(
        fileName: String,
        legacyPlaintextNames: List<String>,
        deletePrefs: (String) -> Boolean,
        openEncrypted: (String) -> SharedPreferences
    ): SharedPreferences {
        legacyPlaintextNames.forEach { deletePrefs(it) }
        return try {
            openEncrypted(fileName)
        } catch (e: GeneralSecurityException) {
            deletePrefs(fileName)
            openEncrypted(fileName)
        } catch (e: IOException) {
            deletePrefs(fileName)
            openEncrypted(fileName)
        }
    }

    private fun buildEncryptedPrefs(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
