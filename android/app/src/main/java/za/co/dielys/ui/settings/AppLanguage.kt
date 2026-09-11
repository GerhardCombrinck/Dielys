package za.co.dielys.ui.settings

/**
 * South Africa's eleven official languages (#42), tagged with the BCP-47 code
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
    NDEBELE("nr", "isiNdebele"),
    XHOSA("xh", "isiXhosa"),
    ZULU("zu", "isiZulu"),
    SEPEDI("nso", "Sepedi"),
    SESOTHO("st", "Sesotho"),
    SISWATI("ss", "siSwati"),
    SETSWANA("tn", "Setswana"),
    TSHIVENDA("ve", "Tshivenda"),
    XITSONGA("ts", "Xitsonga"),
}
