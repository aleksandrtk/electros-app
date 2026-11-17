package ua.org.electros.app.ui

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * The app speaks only Ukrainian and English, so the language is always one of the two: there is
 * no "system" option that could resolve to a language the app does not have. The choice is kept in
 * Android's per-app language setting, which also applies it to notifications.
 */
enum class AppLanguage(val tag: String) {
    UKRAINIAN("uk"),
    ENGLISH("en");

    companion object {
        fun current(context: Context): AppLanguage {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) defaultFor(systemLanguage(context)) else fromLanguage(locales[0].language)
        }

        fun apply(context: Context, language: AppLanguage) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(language.tag)
        }

        /** On first launch picks the language from the phone's language and stores it. */
        fun ensureChosen(context: Context) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val chosen = manager.applicationLocales
            if (chosen.isEmpty || fromLanguageOrNull(chosen[0].language) == null) {
                apply(context, defaultFor(systemLanguage(context)))
            }
        }

        /** Ukrainian for phones set to Ukrainian or Russian, English for any other language. */
        internal fun defaultFor(systemLanguage: String): AppLanguage =
            if (systemLanguage == "uk" || systemLanguage == "ru") UKRAINIAN else ENGLISH

        private fun fromLanguage(language: String): AppLanguage = fromLanguageOrNull(language) ?: ENGLISH

        private fun fromLanguageOrNull(language: String): AppLanguage? = entries.firstOrNull { it.tag == language }

        private fun systemLanguage(context: Context): String =
            context.getSystemService(LocaleManager::class.java).systemLocales[0]?.language.orEmpty()
    }
}
