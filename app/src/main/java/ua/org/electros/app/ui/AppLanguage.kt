package ua.org.electros.app.ui

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * In-app language choice backed by Android's per-app language setting, so it is saved by the
 * system, shown in the system app-language settings, and applied to notifications as well.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    UKRAINIAN("uk"),
    ENGLISH("en");

    companion object {
        fun current(context: Context): AppLanguage {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) return SYSTEM
            return when (locales[0].language) {
                "uk" -> UKRAINIAN
                "en" -> ENGLISH
                else -> SYSTEM
            }
        }

        fun apply(context: Context, language: AppLanguage) {
            val manager = context.getSystemService(LocaleManager::class.java)
            manager.applicationLocales = language.tag?.let { LocaleList.forLanguageTags(it) }
                ?: LocaleList.getEmptyLocaleList()
        }
    }
}
