package za.co.dielys

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads a file from `protocol/fixtures/`, which `app/build.gradle.kts` puts on the
 * unit-test resource path directly rather than copying. Both suites read the same
 * bytes, so a fixture change that breaks one build breaks the other (F4).
 */
object Fixtures {
    /**
     * Deliberately not [za.co.dielys.data.remote.DielysJson.outbound]: several
     * fixtures carry an explicit `"deletedAt": null`, and dropping nulls here would
     * make a round trip look clean while quietly losing a field.
     */
    val json =
        Json {
            ignoreUnknownKeys = true
        }

    fun read(path: String): String =
        checkNotNull(Fixtures::class.java.classLoader?.getResourceAsStream(path)) {
            "fixture not on the test classpath: $path"
        }.use { it.readBytes().decodeToString() }

    /**
     * Every fixture in a directory, found rather than listed. A fixture added for
     * the server suite is then covered here on the next run without anyone
     * remembering to add it.
     */
    fun list(directory: String): List<String> {
        val url =
            checkNotNull(Fixtures::class.java.classLoader?.getResource(directory)) {
                "fixture directory not on the test classpath: $directory"
            }
        val files = File(url.toURI()).listFiles().orEmpty()
        check(files.isNotEmpty()) { "no fixtures in $directory" }
        return files.filter { it.extension == "json" }.map { "$directory/${it.name}" }.sorted()
    }

    inline fun <reified T> load(path: String): T = json.decodeFromString(read(path))
}
