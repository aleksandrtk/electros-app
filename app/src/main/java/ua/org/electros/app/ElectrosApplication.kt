package ua.org.electros.app

import android.app.Application
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
        database = AppDatabase.getInstance(this)
        authPreferences = AuthPreferences(this)
        settingsStore = SettingsStore.getInstance(this, authPreferences)
        cookieStorage = CookieStorage(this)
        scraper = ElectrosWebScraper(cookieStorage, authPreferences)
        repository = ChargeRepository(scraper, database.chargeDao(), authPreferences, settingsStore)
    }
}

