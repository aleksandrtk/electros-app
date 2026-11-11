package ua.org.electros.app.data.network

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

import ua.org.electros.app.data.local.EncryptedPrefsFactory

class CookieStorage(context: Context) : CookieJar {

    private val prefs: SharedPreferences = EncryptedPrefsFactory.create(
        context = context,
        fileName = "electros_cookies_encrypted",
        legacyPlaintextNames = listOf("electros_cookies")
    )
    private val memoryCookies = mutableMapOf<String, Cookie>()

    init {
        // Load stored cookies from SharedPreferences
        prefs.all.forEach { (key, value) ->
            if (value is String) {
                val url = HttpUrl.Builder().scheme("https").host("electros.org.ua").build()
                Cookie.parse(url, value)?.let { cookie ->
                    memoryCookies[key] = cookie
                }
            }
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val editor = prefs.edit()
        cookies.forEach { cookie ->
            val key = "${cookie.name};${cookie.domain}"
            memoryCookies[key] = cookie
            editor.putString(key, cookie.toString())
        }
        editor.apply()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val validCookies = mutableListOf<Cookie>()
        val expiredKeys = mutableListOf<String>()

        memoryCookies.forEach { (key, cookie) ->
            if (cookie.expiresAt < now) {
                expiredKeys.add(key)
            } else if (cookie.matches(url)) {
                validCookies.add(cookie)
            }
        }

        if (expiredKeys.isNotEmpty()) {
            val editor = prefs.edit()
            expiredKeys.forEach { key ->
                memoryCookies.remove(key)
                editor.remove(key)
            }
            editor.apply()
        }

        return validCookies
    }

    @Synchronized
    fun clear() {
        memoryCookies.clear()
        prefs.edit().clear().apply()
    }
}

