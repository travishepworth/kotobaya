package kotobaya.android.push

import org.koin.core.context.GlobalContext
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Entry point for the UnifiedPush distributor (the ntfy app). Thin: everything real is in [PushHandler] and [PushRegistrar]. */
class KotobayaPushService : PushService() {
    private fun <T : Any> get(clazz: kotlin.reflect.KClass<T>): T? = runCatching { GlobalContext.get().get<T>(clazz) }.getOrNull()

    override fun onMessage(message: PushMessage, instance: String) {
        get(PushHandler::class)?.onMessage(message.content)
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        get(PushRegistrar::class)?.onEndpoint(endpoint.url)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        get(PushRegistrar::class)?.onUnregistered()
    }

    override fun onUnregistered(instance: String) {
        get(PushRegistrar::class)?.onUnregistered()
    }
}
