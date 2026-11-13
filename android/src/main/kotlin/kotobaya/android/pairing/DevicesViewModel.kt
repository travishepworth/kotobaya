package kotobaya.android.pairing

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotobaya.android.R
import kotobaya.android.net.ApiError
import kotobaya.android.vm.StateViewModel
import kotobaya.android.vm.UiText

/** What Settings > Devices reads and does (blocking reads run on IO). */
interface DevicesPort {
    fun isPaired(): Boolean

    fun deviceId(): String?

    fun serverBaseUrl(): String?

    suspend fun pair(target: PairingTarget): PairResult

    fun unpair()
}

/** [DevicesPort] over [PairingRepository]. */
class AppDevicesPort(private val repo: PairingRepository) : DevicesPort {
    override fun isPaired() = repo.isPaired

    override fun deviceId() = repo.deviceId

    override fun serverBaseUrl() = repo.serverBaseUrl

    override suspend fun pair(target: PairingTarget) = repo.pair(target)

    override fun unpair() = repo.unpair()
}

/** User-facing message for any [ApiError]. */
@StringRes
fun errorMessageRes(e: ApiError): Int = when (e) {
    is ApiError.Offline -> R.string.err_offline
    is ApiError.Unreachable -> R.string.err_unreachable
    is ApiError.Unauthorized -> R.string.err_unauthorized
    is ApiError.Forbidden -> R.string.err_forbidden
    is ApiError.InvalidPairingCode -> R.string.pair_err_code_expired
    is ApiError.Validation -> if (e.isKindConflict) R.string.pair_err_kind_conflict else R.string.pair_err_validation
    is ApiError.RateLimited -> R.string.pair_err_rate_limited
    is ApiError.Server -> R.string.err_server
    is ApiError.Other -> R.string.err_other
    is ApiError.BadBody -> R.string.err_bad_body
}

private fun errorText(e: ApiError): UiText = when (e) {
    is ApiError.Server -> UiText(errorMessageRes(e), e.status)
    is ApiError.Other -> UiText(errorMessageRes(e), e.status)
    is ApiError.Validation -> UiText(errorMessageRes(e), e.code ?: e.pointer ?: "")
    else -> UiText(errorMessageRes(e))
}

data class DevicesUiState(
    val paired: Boolean = false,
    val deviceId: String? = null,
    val serverUrl: String? = null,
    val url: String = "",
    val code: String = "",
    val error: UiText? = null,
    @StringRes val message: Int? = null,
    val busy: Boolean = false,
)

interface DevicesIntents {
    fun setUrl(url: String) {}
    fun setCode(code: String) {}
    /** The QR scanner returned [raw] (null: nothing readable). */
    fun scanned(raw: String?) {}
    fun submit() {}
    fun unpair() {}
}

/** Settings > Devices: this device only (pair by QR or URL + code, unpair). */
class DevicesViewModel(
    private val port: DevicesPort,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<DevicesUiState>(DevicesUiState(), io), DevicesIntents {
    init {
        reload()
    }

    /** Re-reads paired state, server and device id (the device id is read again on every pairing change). */
    private fun reload() {
        launchIo {
            val paired = port.isPaired()
            val server = port.serverBaseUrl()
            val id = port.deviceId()
            update { it.copy(paired = paired, serverUrl = server, deviceId = id) }
        }
    }

    override fun setUrl(url: String) = update { it.copy(url = url) }

    override fun setCode(code: String) = update { it.copy(code = code) }

    override fun scanned(raw: String?) {
        val target = raw?.let(PairingUri::parse)
        if (target == null) update { it.copy(error = UiText(R.string.pair_err_invalid_uri)) } else pair(target)
    }

    override fun submit() {
        val s = current
        val server = PairingUri.normalizeServerUrl(s.url)
        val c = PairingUri.normalizeCode(s.code)
        if (server == null || c.isEmpty()) update { it.copy(error = UiText(R.string.pair_err_bad_url)) }
        else pair(PairingTarget(server, c))
    }

    private fun pair(target: PairingTarget) {
        update { it.copy(busy = true, error = null) }
        launchIo {
            when (val r = port.pair(target)) {
                is PairResult.Paired -> {
                    update { it.copy(paired = true, message = R.string.pair_success) }
                    reload()
                }
                is PairResult.Failed -> update { it.copy(error = errorText(r.error)) }
            }
            update { it.copy(busy = false) }
        }
    }

    /** BUG-8 review: unpair finishes on IO before the screen shows "not paired". */
    override fun unpair() {
        launchIo {
            port.unpair()
            update { it.copy(paired = false, message = null) }
            reload()
        }
    }
}
