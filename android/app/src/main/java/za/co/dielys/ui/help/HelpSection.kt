package za.co.dielys.ui.help

import androidx.annotation.StringRes
import za.co.dielys.R

/** One card on the help screen. Same sections, same order, as web's HelpPage.tsx. */
enum class HelpSection(
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
) {
    Lists(R.string.help_lists_title, R.string.help_lists_body),
    Items(R.string.help_items_title, R.string.help_items_body),
    Sharing(R.string.help_sharing_title, R.string.help_sharing_body),
    Together(R.string.help_together_title, R.string.help_together_body),
    Leaving(R.string.help_leaving_title, R.string.help_leaving_body),
    Offline(R.string.help_offline_title, R.string.help_offline_body),
    Account(R.string.help_account_title, R.string.help_account_body),
}
