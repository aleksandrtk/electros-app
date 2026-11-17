package ua.org.electros.app

import android.app.Application
import android.os.Build
import ua.org.electros.app.diag.DiagLog
import ua.org.electros.app.diag.ExitReasonLogger
import ua.org.electros.app.ui.AppLanguage
import ua.org.electros.app.data.local.AppDatabase
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.local.SettingsStore
import ua.org.electros.app.data.network.CookieStorage
import ua.org.electros.app.data.network.ElectrosWebScraper
import ua.org.electros.app.data.repository.ChargeRepository

class ElectrosApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var authPreferences: AuthPreferences
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var cookieStorage: CookieStorage
        private set

    lateinit var scraper: ElectrosWebScraper
        private set

    lateinit var repository: ChargeRepository
        private set

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        AppLanguage.ensureChosen(this)
        database = AppDatabase.getInstance(this)
        authPreferences = AuthPreferences(this)
        settingsStore = SettingsStore.getInstance(this)
        cookieStorage = CookieStorage()
        scraper = ElectrosWebScraper(cookieStorage, authPreferences)
        repository = ChargeRepository(scraper, database.chargeDao(), authPreferences, settingsStore)
        Thread({
            // Credentials are decrypted off the main thread; until then nothing sensitive is logged yet.
            runCatching { DiagLog.setSecrets(authPreferences.deviceSerial, authPreferences.internetPassword) }
            DiagLog.i(
                "App",
                "Process started: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"
            )
            ExitReasonLogger.recordPreviousExits(this)
        }, "electros-diag-start").start()
    }
}

