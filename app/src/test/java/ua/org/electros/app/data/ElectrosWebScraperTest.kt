package ua.org.electros.app.data

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.network.CookieStorage
import ua.org.electros.app.data.network.ElectrosWebScraper
import java.io.File

class ElectrosWebScraperTest {

    private val sampleHtml = """
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
                Струм, виміряний: 16.2 A
            </div>
            <table>
                <tr><td>Спожита енергія</td><td><span>2.2 кВт*год</span></td></tr>
                <tr><td>Потужність</td><td><span>3.7 кВт</span></td></tr>
            </table>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun parseTelemetryDocument_correctlyExtractsAllFields() {
        // We test parseTelemetryDocument directly on Jsoup parsed DOM
        val doc = Jsoup.parse(sampleHtml)
        
        // Mock-less scraper testing parser method
        val scraper = ElectrosWebScraper()

        val telemetry = scraper.parseTelemetryDocument(doc)

        assertThat(telemetry.isOnline).isFalse()
        assertThat(telemetry.statusText).isEqualTo("Станція OFFLINE")
        assertThat(telemetry.voltageMin).isEqualTo(184)
        assertThat(telemetry.voltageAvg).isEqualTo(202)
        assertThat(telemetry.voltageCurrent).isEqualTo(224)
        assertThat(telemetry.voltageMax).isEqualTo(224)
        assertThat(telemetry.neutralCurrent).isEqualTo(2)
        assertThat(telemetry.selectedCurrentAmperes).isEqualTo(10)
        assertThat(telemetry.measuredCurrentAmperes).isEqualTo(16.2)
        assertThat(telemetry.consumedEnergyKwh).isEqualTo(2.2)
        assertThat(telemetry.powerKw).isEqualTo(3.7)
        assertThat(telemetry.isChargingActive).isTrue()
    }
}
