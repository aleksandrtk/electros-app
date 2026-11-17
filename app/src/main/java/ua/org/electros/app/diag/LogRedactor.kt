package ua.org.electros.app.diag

/**
 * Removes the station's device number and password from log lines before they reach the file.
 * Log calls never pass them on purpose; this is the safety net for exception messages and the like.
 */
class LogRedactor {
    @Volatile private var secrets: List<String> = emptyList()

    fun setSecrets(values: Collection<String>) {
        secrets = values.map { it.trim() }
            .filter { it.length >= MIN_SECRET_LENGTH }
            .distinct()
            .sortedByDescending { it.length }
    }

    fun redact(text: String): String {
        var result = text
        for (secret in secrets) {
            if (result.contains(secret)) result = result.replace(secret, MASK)
        }
        return result
    }

    companion object {
        const val MASK = "•••"
        private const val MIN_SECRET_LENGTH = 3
    }
}
