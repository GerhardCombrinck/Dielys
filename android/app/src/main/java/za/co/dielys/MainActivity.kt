package za.co.dielys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import za.co.dielys.ui.DielysApp
import za.co.dielys.ui.theme.DielysTheme

/**
 * The only Activity. Everything it shows reads from Room and nothing on it
 * touches the network (E1) — the sync engine runs in `WorkManager`, so closing
 * this screen mid-drain loses nothing.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DielysTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DielysApp()
                }
            }
        }
    }
}
