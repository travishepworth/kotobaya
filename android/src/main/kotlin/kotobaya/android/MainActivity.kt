package kotobaya.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotobaya.android.di.FeatureRegistry
import kotobaya.android.nav.AppNav
import kotobaya.android.ui.KotobayaTheme
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val registry: FeatureRegistry = get()
        setContent { KotobayaTheme { AppNav(registry) } }
    }
}
