package ua.org.electros.app.ui.settings

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import ua.org.electros.app.R
import ua.org.electros.app.diag.DiagLog

/**
 * Asks for the fingerprint/face, or the screen lock PIN, pattern or password as a fallback, before
 * the saved station password is shown. Uses the platform prompt, so no extra library is needed.
 */
internal object DeviceAuth {
    private const val TAG = "DeviceAuth"
    private const val AUTHENTICATORS =
        Authenticators.BIOMETRIC_STRONG or Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL

    /**
     * Calls [onSuccess] after a successful check. With no screen lock set up at all there is nothing
     * to check against, so the password is shown straight away (the phone itself is unprotected).
     */
    fun authenticate(context: Context, onSuccess: () -> Unit, onError: (CharSequence) -> Unit) {
        val manager = context.getSystemService(BiometricManager::class.java)
        val availability = manager?.canAuthenticate(AUTHENTICATORS) ?: BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        if (availability == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
            availability == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        ) {
            DiagLog.i(TAG, "No screen lock to check against ($availability), showing the password")
            onSuccess()
            return
        }
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(context.getString(R.string.auth_show_password_title))
            .setSubtitle(context.getString(R.string.auth_show_password_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    DiagLog.i(TAG, "Prompt closed: code $errorCode")
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    ) {
                        onError(errString)
                    }
                }
            }
        )
    }
}
