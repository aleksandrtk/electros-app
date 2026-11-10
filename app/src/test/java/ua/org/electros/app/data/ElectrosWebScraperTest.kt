package ua.org.electros.app.data

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test
import ua.org.electros.app.data.network.ElectrosWebScraper

class ElectrosWebScraperTest {

    private val offlineHtml = """
        <!DOCTYPE html>
        <html>
        <body>
            <div class="status-container">
                Стан: <span style="background: red;">Станція OFFLINE</span>
            </div>
            <table>
                <caption>Напруга Мережі, В:</caption>
                <tr><th>Min</th><th>Середня</th><th>Поточна</th><th>Max</th></tr>
                <tr><td>184</td><td>202</td><td>224</td><td>224</td></tr>
            </table>
            <table>
                <caption>Напруга нейтралі, В:</caption>
                <tr><th>Min</th><th>Середня</th><th>Поточна</th><th>Max</th></tr>
                <tr><td>2</td><td>6</td><td>2</td><td>7</td></tr>
            </table>
            <div>
                Оберіть струм заряджання:
                <select name="current">
                    <option value="10" selected>10 A</option>
                    <option value="16">16 A</option>
                </select>
            </div>
            <div>
                Струм, виміряний: 0.0 A
            </div>
            <table>
                <tr><td>Спожита енергія</td><td><span>2.2 кВт*год</span></td></tr>
                <tr><td>Потужність</td><td><span>0.0 кВт</span></td></tr>
            </table>
        </body>
        </html>
    """.trimIndent()

    private val connectedIdleHtml = """
        <!DOCTYPE html>
        <html>
        <body>
            <div>Стан: <span>Підключено. Немає заряду</span></div>
            <table>
                <caption>Напруга Мережі, В:</caption>
                <tr><th>Min</th><th>Середня</th><th>Поточна</th><th>Max</th></tr>
                <tr><td>210</td><td>220</td><td>228</td><td>232</td></tr>
            </table>
            <table>
                <caption>Напруга нейтралі, В:</caption>
                <tr><th>Min</th><th>Середня</th><th>Поточна</th><th>Max</th></tr>
                <tr><td>1</td><td>2</td><td>2</td><td>3</td></tr>
            </table>
            <div>
                Оберіть струм заряджання:
                <select name="current">
                    <option value="16" selected>16 A</option>
                </select>
            </div>
            <div>
                Струм, виміряний: 0.0 A
            </div>
            <table>
                <tr><td>Спожита енергія</td><td><span>5.4 кВт*год</span></td></tr>
                <tr><td>Потужність</td><td><span>0.0 кВт</span></td></tr>
            </table>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun parseTelemetryDocument_whenOffline_isOnlineFalse() {
        val doc = Jsoup.parse(offlineHtml)
        val scraper = ElectrosWebScraper()
        val telemetry = scraper.parseTelemetryDocument(doc)

        assertThat(telemetry.isOnline).isFalse()
        assertThat(telemetry.statusText).isEqualTo("Станція OFFLINE")
        assertThat(telemetry.voltageCurrent).isEqualTo(224)
        assertThat(telemetry.isChargingActive).isFalse()
    }

    @Test
    fun parseTelemetryDocument_whenConnectedIdle_isOnlineTrueAndNotCharging() {
        val doc = Jsoup.parse(connectedIdleHtml)
        val scraper = ElectrosWebScraper()
        val telemetry = scraper.parseTelemetryDocument(doc)

        assertThat(telemetry.isOnline).isTrue()
        assertThat(telemetry.statusText).isEqualTo("Підключено. Немає заряду")
        assertThat(telemetry.voltageCurrent).isEqualTo(228)
        assertThat(telemetry.selectedCurrentAmperes).isEqualTo(16)
        assertThat(telemetry.isChargingActive).isFalse()
    }

    @Test
    fun parseTelemetryDocument_whenUnpluggedFromOutlet_isOnlineFalse() {
        val unpluggedHtml = """
            <!DOCTYPE html>
            <html>
            <body>
                <div>Стан: <span>Немає зв'язку зі станцією</span></div>
                <table>
                    <caption>Напруга Мережі, В:</caption>
                    <tr><th>Min</th><th>Середня</th><th>Поточна</th><th>Max</th></tr>
                    <tr><td>0</td><td>0</td><td>0</td><td>0</td></tr>
                </table>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(unpluggedHtml)
        val scraper = ElectrosWebScraper()
        val telemetry = scraper.parseTelemetryDocument(doc)

        assertThat(telemetry.isOnline).isFalse()
        assertThat(telemetry.statusText).contains("Немає зв'язку")
        assertThat(telemetry.voltageCurrent).isEqualTo(0)
        assertThat(telemetry.isChargingActive).isFalse()
    }

    @Test
    fun parseTelemetryDocument_whenConnectedWithOfflineInScript_isOnlineTrue() {
        val htmlWithScript = """
            <!DOCTYPE html>
            <html>
            <head><script>var states = ["OFFLINE", "ONLINE"];</script></head>
            <body>
                <div class="offline-badge-container">
                    Стан: <span>Підключено. Немає заряду</span>
                </div>
                <table>
                    <caption>Напруга мережі, В:</caption>
                    <tr><th>Min</th><th>Середня</th><th>Поточна</th><th>Max</th></tr>
                    <tr><td>215</td><td>222</td><td>226</td><td>230</td></tr>
                </table>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(htmlWithScript)
        val scraper = ElectrosWebScraper()
        val telemetry = scraper.parseTelemetryDocument(doc)

        assertThat(telemetry.isOnline).isTrue()
        assertThat(telemetry.statusText).isEqualTo("Підключено. Немає заряду")
        assertThat(telemetry.voltageCurrent).isEqualTo(226)
    }
}
