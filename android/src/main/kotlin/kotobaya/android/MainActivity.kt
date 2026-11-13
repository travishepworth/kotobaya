package kotobaya.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotobaya.android.di.FeatureRegistry
import kotobaya.android.nav.AppNav
import kotobaya.android.struggle.StruggleDeepLink
import kotobaya.android.ui.KotobayaTheme
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val registry: FeatureRegistry = get()
        StruggleDeepLink.handle(intent)
        setContent { KotobayaTheme { AppNav(registry) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        StruggleDeepLink.handle(intent)
    }
}
