package ua.org.electros.app.ota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OtaUpdaterTest {

    @Test
    fun parseVersionNumbers_handlesVariousFormats() {
        assertThat(OtaUpdater.parseVersionNumbers("v1.0.0")).isEqualTo(listOf(1, 0, 0))
        assertThat(OtaUpdater.parseVersionNumbers("v2.1.0")).isEqualTo(listOf(2, 1, 0))
        assertThat(OtaUpdater.parseVersionNumbers("Release-v2.0.1-stable")).isEqualTo(listOf(2, 0, 1))
        assertThat(OtaUpdater.parseVersionNumbers("3.5")).isEqualTo(listOf(3, 5))
        assertThat(OtaUpdater.parseVersionNumbers("v0.9.8-beta2")).isEqualTo(listOf(0, 9, 8))
    }

    @Test
    fun isNewerVersion_correctlyComparesVersions() {
        // Newer version cases
        assertThat(OtaUpdater.isNewerVersion("1.0.0", "v2.0.0")).isTrue()
        assertThat(OtaUpdater.isNewerVersion("2.0.0", "v2.0.1")).isTrue()
        assertThat(OtaUpdater.isNewerVersion("2.0.1", "v2.1.0")).isTrue()
        assertThat(OtaUpdater.isNewerVersion("2.0", "2.0.1")).isTrue()

        // Same version cases
        assertThat(OtaUpdater.isNewerVersion("2.1.0", "v2.1.0")).isFalse()
        assertThat(OtaUpdater.isNewerVersion("2.0.0", "2.0.0")).isFalse()

        // Older version cases
        assertThat(OtaUpdater.isNewerVersion("2.1.0", "v2.0.1")).isFalse()
        assertThat(OtaUpdater.isNewerVersion("3.0.0", "v2.9.9")).isFalse()
    }

    @Test
    fun parseDigest_extractsValidSha256Hex() {
        val validSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertThat(OtaUpdater.parseDigest("sha256:$validSha256")).isEqualTo(validSha256)
        assertThat(OtaUpdater.parseDigest(validSha256)).isNull() // must have prefix
        assertThat(OtaUpdater.parseDigest("sha256:short")).isNull()
        assertThat(OtaUpdater.parseDigest(null)).isNull()
    }

    @Test
    fun isValidDownloadUrl_verifiesAllowedHosts() {
        assertThat(OtaUpdater.isValidDownloadUrl("https://github.com/aleksandrtk/electros-app/releases/download/v2.0.1/app.apk")).isTrue()
        assertThat(OtaUpdater.isValidDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset-2e65be/...")).isTrue()
        assertThat(OtaUpdater.isValidDownloadUrl("http://github.com/insecure.apk")).isFalse()
        assertThat(OtaUpdater.isValidDownloadUrl("https://malicious.com/app.apk")).isFalse()
        assertThat(OtaUpdater.isValidDownloadUrl("invalid-url")).isFalse()
    }
}
