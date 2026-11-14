package kotobaya.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import kotobaya.android.di.FeatureRegistry
import kotobaya.android.nav.AppRoot
import kotobaya.android.struggle.StruggleDeepLink
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge on every API level (Android 15 forces it); the app shell applies the insets (AppShell).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION") // Below API 30 the IME insets only reach Compose with adjustResize.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val registry: FeatureRegistry = get()
        StruggleDeepLink.handle(intent)
        setContent { AppRoot(registry) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        StruggleDeepLink.handle(intent)
    }
}
