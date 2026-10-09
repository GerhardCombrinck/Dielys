package za.co.dielys.ui.settings

/**
 * The languages the app is translated into (#42), tagged with the BCP-47 code
 * `LocalePrefs` and `values-<tag>/` resource folders both key on. Listed in
 * alphabetical order by each language's own name — no language reads above
 * another in its own picker.
 */
enum class AppLanguage(
    val tag: String,
    val nativeName: String,
) {
    AFRIKAANS("af", "Afrikaans"),
    ENGLISH("en", "English"),
}
