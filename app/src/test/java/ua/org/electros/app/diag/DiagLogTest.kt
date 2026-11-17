package ua.org.electros.app.diag

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ua.org.electros.app.data.model.TelemetryData
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

class DiagLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun resetSecrets() = DiagLog.setSecrets()

    private val zone = ZoneId.of("Europe/Kyiv")
    private var nowMs = Instant.parse("2026-11-17T10:00:00Z").toEpochMilli()
    private val dayMs = 24 * 3_600_000L

    private fun store(dir: File, maxFileBytes: Long = 1_000, maxArchives: Int = 3, maxAgeDays: Long = 14) =
        LogFileStore(dir, maxFileBytes, maxArchives, maxAgeDays, clockMs = { nowMs }, zone = zone)

    /** Appends, then stamps the live file with the fake clock, as the real file system would. */
    private fun LogFileStore.write(dir: File, text: String) {
        append(text)
        File(dir, LogFileStore.CURRENT_NAME).setLastModified(nowMs)
    }

    private fun gunzip(file: File) = GZIPInputStream(file.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun sizeRotation_compressesIntoNumberedArchivesNewestFirst() {
        val dir = tmp.newFolder("diag")
        val s = store(dir, maxFileBytes = 20)

        s.write(dir, "line A 0123456789ab\n")  // 20 bytes: full
        s.write(dir, "line B 0123456789ab\n")  // rotates A into .1
        s.write(dir, "line C 0123456789ab\n")  // rotates B into .1, A moves to .2

        assertThat(gunzip(File(dir, "electros.1.log.gz"))).isEqualTo("line B 0123456789ab\n")
        assertThat(gunzip(File(dir, "electros.2.log.gz"))).isEqualTo("line A 0123456789ab\n")
        assertThat(File(dir, LogFileStore.CURRENT_NAME).readText()).isEqualTo("line C 0123456789ab\n")
        assertThat(dir.list()!!.filter { it.endsWith(".tmp") }).isEmpty()
    }

    @Test
    fun archivesAreGzipCompressed() {
        val dir = tmp.newFolder("diag")
        val s = store(dir, maxFileBytes = 50_000)
        val line = "2026-11-17 12:00:00.000 D Supervisor: Tick abcd1234 [CHARGING] online charging U=228V\n"
        repeat(600) { s.write(dir, line) }   // ~52 kB of typical log text, then one rotation

        val archive = File(dir, "electros.1.log.gz")
        assertThat(archive.length()).isLessThan(52_000L / 10)
        assertThat(gunzip(archive).lines().first()).isEqualTo(line.trim())
    }

    @Test
    fun onlyMaxArchivesAreKept() {
        val dir = tmp.newFolder("diag")
        val s = store(dir, maxFileBytes = 10, maxArchives = 3)

        repeat(20) { s.write(dir, "entry %02d xx\n".format(it)) }

        assertThat(dir.list()!!.sorted()).containsExactly(
            "electros.1.log.gz", "electros.2.log.gz", "electros.3.log.gz", LogFileStore.CURRENT_NAME
        )
        assertThat(gunzip(File(dir, "electros.3.log.gz"))).isEqualTo("entry 16 xx\n")
        assertThat(File(dir, LogFileStore.CURRENT_NAME).readText()).isEqualTo("entry 19 xx\n")
    }

    @Test
    fun newDay_rotatesEvenWhenTheFileIsSmall() {
        val dir = tmp.newFolder("diag")
        val s = store(dir)

        s.write(dir, "monday\n")
        nowMs += dayMs
        s.write(dir, "tuesday\n")

        assertThat(gunzip(File(dir, "electros.1.log.gz"))).isEqualTo("monday\n")
        assertThat(File(dir, LogFileStore.CURRENT_NAME).readText()).isEqualTo("tuesday\n")
    }

    @Test
    fun archivesOlderThanMaxAgeAreDeleted() {
        val dir = tmp.newFolder("diag")
        val s = store(dir, maxArchives = 10, maxAgeDays = 2)

        s.write(dir, "day 0\n")
        nowMs += dayMs; s.write(dir, "day 1\n")
        nowMs += dayMs; s.write(dir, "day 2\n")
        nowMs += dayMs; s.write(dir, "day 3\n")

        val kept = s.archives().map { gunzip(it) }
        assertThat(kept).containsExactly("day 2\n", "day 1\n").inOrder()
    }

    @Test
    fun exportZip_containsOneTextFileWithHeaderAndAllLinesOldestFirst() {
        val dir = tmp.newFolder("diag")
        val s = store(dir, maxFileBytes = 10)
        listOf("one 123456\n", "two 123456\n", "three 1234\n").forEach { s.write(dir, it) }

        val zipFile = File(tmp.root, "out/log.zip")
        s.exportZip(zipFile, "electros-log.txt", "HEADER\n")

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList()
            assertThat(entries.map { it.name }).containsExactly("electros-log.txt")
            val text = zip.getInputStream(entries.single()).readBytes().toString(Charsets.UTF_8)
            assertThat(text).isEqualTo("HEADER\none 123456\ntwo 123456\nthree 1234\n")
        }
    }

    @Test
    fun clearRemovesLiveFileAndArchives() {
        val dir = tmp.newFolder("diag")
        val s = store(dir, maxFileBytes = 10)
        repeat(5) { s.write(dir, "entry $it 1234\n") }

        s.clear()

        assertThat(s.isEmpty()).isTrue()
        assertThat(dir.list()!!.toList()).isEmpty()
    }

    @Test
    fun redactor_masksDeviceNumberAndPasswordEverywhere() {
        val redactor = LogRedactor()
        redactor.setSecrets(listOf("AB12CD34", " s3cret ", "", "x"))

        val text = redactor.redact("site AB12CD34 failed, password=s3cret; again AB12CD34 x")

        assertThat(text).isEqualTo("site ••• failed, password=•••; again ••• x")
    }

    @Test
    fun formatLine_hasTimeLevelTagAndStackTraceWithSecretsMasked() {
        DiagLog.setSecrets("AB12CD34")
        val nowMs = Instant.parse("2026-11-17T10:15:30.250Z").toEpochMilli()

        val line = DiagLog.formatLine(
            DiagLog.Level.WARN,
            "Network",
            "WS failed for site AB12CD34",
            IOException("closed for AB12CD34"),
            nowMs,
            ZoneId.of("Europe/Kyiv")
        )

        assertThat(line).startsWith("2026-11-17 12:15:30.250 W Network: WS failed for site •••\n")
        assertThat(line).contains("java.io.IOException: closed for •••")
        assertThat(line).contains("at ua.org.electros.app.diag.DiagLogTest")
        assertThat(line).doesNotContain("AB12CD34")
        assertThat(line).endsWith("\n")
    }

    @Test
    fun callsBeforeInit_doNothing() {
        // Unit tests and early startup run without init; logging must never throw there.
        assertThat(DiagLog.isEnabled).isFalse()
        DiagLog.i("Test", "ignored")
        DiagLog.d("Test") { error("message must not be built while the journal is off") }
        assertThat(DiagLog.sizeBytes()).isEqualTo(0L)
    }

    @Test
    fun telemetryLogString_showsReadingWithoutStationIdentity() {
        val t = TelemetryData(
            statusText = "Заряджання",
            isOnline = true,
            voltageCurrent = 228,
            selectedCurrentAmperes = 16,
            measuredCurrentAmperes = 15.8,
            consumedEnergyKwh = 123.4567,
            powerKw = 3.61,
            isChargingActive = true,
            isPreciseCounter = false
        )

        assertThat(t.toLogString()).isEqualTo(
            "online charging 'Заряджання' U=228V set=16A I=15.8A P=3.61kW counter=123.457kWh (http, rounded)"
        )
    }
}
