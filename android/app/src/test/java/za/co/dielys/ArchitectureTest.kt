package za.co.dielys

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The layer rules from E1, enforced by a test rather than by good intentions.
 *
 * Every one of these has already been broken once in some other codebase by a
 * single convenient import, so they are checked the same way the sync invariants
 * are: automatically, on every build.
 */
class ArchitectureTest {
    /**
     * E1.1. `domain/` holds the ordering and identity rules the server also
     * implements (F4). An `android.*` import there would mean the only way to test
     * them was an emulator, which is how a rule stops being tested at all (H1).
     */
    @Test
    fun `domain does not import the Android framework`() {
        val domain = filesUnder("domain")
        assertTrue(domain.isNotEmpty(), "no domain files found; the scope is wrong")

        val offenders =
            domain.flatMap { file ->
                file.imports
                    .map { it.name }
                    .filter { it.startsWith("android.") || it.startsWith("androidx.") }
                    .map { "${file.name}: $it" }
            }

        assertEquals(emptyList<String>(), offenders)
    }

    /**
     * E1.2. A screen that calls something that calls the network is the exact bug
     * this architecture exists to prevent: it works on wifi and lies in the shop.
     * The UI reads Room, and Room is fed by the sync engine.
     */
    @Test
    fun `the ui layer never reaches the network`() {
        val offenders =
            filesUnder("ui").flatMap { file ->
                file.imports
                    .map { it.name }
                    .filter { it.startsWith(REMOTE) || it.startsWith("okhttp3") }
                    .map { "${file.name}: $it" }
            }

        assertEquals(emptyList<String>(), offenders)
    }

    /** E1. A `ViewModel` holding a `Context` outlives it and leaks the Activity. */
    @Test
    fun `no view model holds a context`() {
        val offenders =
            Konsist
                .scopeFromProject()
                .classes()
                .filter { it.name.endsWith("ViewModel") }
                .flatMap { viewModel ->
                    viewModel
                        .properties()
                        .filter { it.type?.name?.endsWith("Context") == true }
                        .map { "${viewModel.name}.${it.name}" }
                }

        assertEquals(emptyList<String>(), offenders)
    }

    private fun filesUnder(layer: String): List<KoFileDeclaration> =
        Konsist.scopeFromProject().files.filter {
            it.path.replace('\\', '/').contains("/za/co/dielys/$layer/")
        }

    private companion object {
        const val REMOTE = "za.co.dielys.data.remote"
    }
}
