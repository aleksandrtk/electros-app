package ua.org.electros.app.data.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Thread-safe in-memory [CookieJar] for ephemeral PHP session cookies.
 * Avoids persisting session cookies on disk or sharing Keystore keys with [ua.org.electros.app.data.local.AuthPreferences].
 */
class CookieStorage : CookieJar {

    private val memoryCookies = mutableMapOf<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            val key = "${cookie.name};${cookie.domain};${cookie.path}"
            memoryCookies[key] = cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val validCookies = mutableListOf<Cookie>()
        val iterator = memoryCookies.entries.iterator()
        while (iterator.hasNext()) {
            val cookie = iterator.next().value
            if (cookie.expiresAt < now) {
                iterator.remove()
            } else if (cookie.matches(url)) {
                validCookies.add(cookie)
            }
        }
        return validCookies
    }

    @Synchronized
    fun clear() {
        memoryCookies.clear()
    }
}

