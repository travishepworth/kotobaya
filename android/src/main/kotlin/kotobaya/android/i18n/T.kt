package kotobaya.android.i18n

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** The single way Compose code obtains user-visible text: always from res/values/strings.xml. */
@Composable
fun T(@StringRes id: Int, vararg args: Any): String = stringResource(id, *args)
