package za.co.dielys

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import za.co.dielys.data.PendingInvite
import za.co.dielys.data.PendingMagicLink
import za.co.dielys.ui.DielysApp
import za.co.dielys.ui.theme.DielysTheme
import javax.inject.Inject

/**
 * The only Activity. Everything it shows reads from Room and nothing on it
 * touches the network (E1) — the sync engine runs in `WorkManager`, so closing
 * this screen mid-drain loses nothing.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * A tapped invite link (L3). Parked rather than acted on: it can arrive with
     * nobody signed in, and then it has to outlive a sign-in before any screen
     * can use it.
     */
    @Inject
    lateinit var invites: PendingInvite

    /** A tapped magic-link sign-in link (ADR 0005). Parked the same way, for
     * the opposite reason an invite is: this one arrives with nobody signed in
     * as a rule, not as an edge case. */
    @Inject
    lateinit var magicLinks: PendingMagicLink

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a genuinely new launch. A rotation runs `onCreate` again with
        // the same intent, and re-offering it would ask about an invite (or
        // redeem a magic link) that has already been handled.
        if (savedInstanceState == null) {
            invites.offer(intent?.dataString)
            magicLinks.offer(intent?.dataString)
        }
        setContent {
            DielysTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DielysApp()
                }
            }
        }
    }

    /** `singleTask`: a second link while the app is already up arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        invites.offer(intent.dataString)
        magicLinks.offer(intent.dataString)
    }
}
