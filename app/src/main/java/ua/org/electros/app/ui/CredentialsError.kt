package ua.org.electros.app.ui

import androidx.annotation.StringRes
import ua.org.electros.app.R

/** Turns a login/credential check failure into a translated message instead of raw exception text. */
fun credentialsErrorText(error: Throwable, @StringRes fallback: Int): UiText {
    val raw = error.message.orEmpty()
    return when {
        raw.contains("Невірний", ignoreCase = true) || raw.contains("Invalid", ignoreCase = true) ->
            UiText.Res(R.string.err_credentials)
        raw.contains("temporarily blocked", ignoreCase = true) -> UiText.Res(R.string.err_credentials)
        else -> UiText.Res(fallback)
    }
}
