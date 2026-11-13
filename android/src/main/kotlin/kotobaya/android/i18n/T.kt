package kotobaya.android.i18n

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** The single way Compose code obtains user-visible text: always from res/values/strings.xml. */
@Composable
fun T(@StringRes id: Int, vararg args: Any): String = stringResource(id, *args)

/** [text] resolved through the i18n layer. */
@Composable
fun T(text: kotobaya.android.vm.UiText): String = stringResource(text.res, *text.args.toTypedArray())
