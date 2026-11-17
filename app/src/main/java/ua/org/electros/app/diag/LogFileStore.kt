package ua.org.electros.app.diag

import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Rotating journal in the logrotate style:
 *
 * - `electros.log` is the live, plain-text file;
 * - it is rotated when it reaches [maxFileBytes] or when a new day starts;
 * - rotated files are gzip-compressed into `electros.1.log.gz` (newest) … `electros.N.log.gz` (oldest);
 * - at most [maxArchives] archives are kept, and archives older than [maxAgeDays] are deleted.
 *
 * Compression goes through a temporary file that is renamed only when complete, so a process killed
 * mid-rotation never leaves a broken archive. Not thread-safe: [DiagLog] calls it from one thread.
 */
class LogFileStore(
    private val dir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxArchives: Int = DEFAULT_MAX_ARCHIVES,
    private val maxAgeDays: Long = DEFAULT_MAX_AGE_DAYS,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private val current get() = File(dir, CURRENT_NAME)

    fun append(text: String) {
        dir.mkdirs()
        rotateIfNeeded()
        current.appendText(text, Charsets.UTF_8)
    }

    /** Space the journal takes on disk, archives included. */
    fun sizeBytes(): Long = current.length() + archives().sumOf { it.length() }

    fun isEmpty(): Boolean = current.length() == 0L && archives().isEmpty()

    /** Rotated archives, newest first. */
    fun archives(): List<File> = (1..maxArchives + EXTRA_SCAN)
        .map { archive(it) }
        .filter { it.exists() }

    /**
     * Writes a zip with a single `[entryName]` text entry: [header], then every archive and the live
     * file, oldest lines first. Archives are decompressed while streaming, so nothing large is held
     * in memory.
     */
    fun exportZip(target: File, entryName: String, header: String) {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(header.toByteArray(Charsets.UTF_8))
            writeAllOldestFirst(zip)
            zip.closeEntry()
        }
    }

    /** Streams the whole journal as plain text, oldest first. */
    fun writeAllOldestFirst(out: OutputStream) {
        for (archive in archives().reversed()) {
            runCatching { GZIPInputStream(archive.inputStream().buffered()).use { it.copyTo(out) } }
        }
        if (current.exists()) current.inputStream().use { it.copyTo(out) }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun rotateIfNeeded() {
        val file = current
        val length = file.length()
        if (length == 0L) return
        val startedOnAnotherDay = localDate(file.lastModified()) != localDate(clockMs())
        if (length >= maxFileBytes || startedOnAnotherDay) rotate()
        pruneOldArchives()
    }

    private fun rotate() {
        // Compress first: if this fails or the process dies, the live file and old archives are untouched.
        val tmp = File(dir, "$CURRENT_NAME.gz.tmp")
        GZIPOutputStream(tmp.outputStream().buffered()).use { gz ->
            current.inputStream().use { it.copyTo(gz) }
        }
        tmp.setLastModified(current.lastModified())

        archive(maxArchives).delete()
        for (index in maxArchives - 1 downTo 1) {
            val from = archive(index)
            if (from.exists()) from.renameTo(archive(index + 1))
        }
        tmp.renameTo(archive(1))
        current.delete()
    }

    private fun pruneOldArchives() {
        val oldestAllowed = localDate(clockMs()).minusDays(maxAgeDays)
        (1..maxArchives + EXTRA_SCAN).map { archive(it) }.forEachIndexed { i, file ->
            if (!file.exists()) return@forEachIndexed
            if (i + 1 > maxArchives || localDate(file.lastModified()).isBefore(oldestAllowed)) file.delete()
        }
    }

    private fun archive(index: Int) = File(dir, "electros.$index.log.gz")

    private fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    companion object {
        const val DEFAULT_MAX_FILE_BYTES = 1L * 1024 * 1024
        const val DEFAULT_MAX_ARCHIVES = 14
        const val DEFAULT_MAX_AGE_DAYS = 14L
        const val CURRENT_NAME = "electros.log"
        /** Also looks a little past the limit, so archives left by a larger limit are cleaned up. */
        private const val EXTRA_SCAN = 6
    }
}
