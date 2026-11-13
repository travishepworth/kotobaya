package kotobaya.android.home

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotobaya.android.vm.StateViewModel

data class HomeUiState(
    /** This device has no token/server: Home shows the route to pairing. */
    val unpaired: Boolean = false,
)

/** The Home tab (the sync chip has its own ViewModel). [isPaired] reads the token store, so it runs on IO. */
class HomeViewModel(
    isPaired: () -> Boolean,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<HomeUiState>(HomeUiState(), io) {
    init {
        launchIo {
            val unpaired = !isPaired()
            update { it.copy(unpaired = unpaired) }
        }
    }
}
