package kotobaya.auth

import kotobaya.jobs.EventBus
import kotobaya.jobs.EventHub
import org.jooq.DSLContext
import java.util.UUID

/**
 * What auth tells the rest of the server. Injected into [Auth]; the default does nothing (tests, or a server
 * without event streaming). Wire it with [AuthEvents.of] over the jobs [EventBus] and [EventHub].
 */
interface AuthEvents {
    /** `device.paired` / `device.revoked` (`EventDevice`), sent inside the transaction that made the change so it goes out on commit. */
    fun deviceEventInTx(dsl: DSLContext, type: String, deviceId: UUID, name: String)

    /** These tokens were revoked (committed): end their open `/events` streams. */
    fun tokensRevoked(tokenIds: Collection<UUID>)

    companion object {
        val None: AuthEvents = object : AuthEvents {
            override fun deviceEventInTx(dsl: DSLContext, type: String, deviceId: UUID, name: String) = Unit
            override fun tokensRevoked(tokenIds: Collection<UUID>) = Unit
        }

        /**
         * Publishes through [bus] (NOTIFY: every API process and the ntfy forwarder see it) and closes the revoked
         * tokens' streams on this process's [hub]. Either may be null.
         */
        fun of(bus: EventBus?, hub: EventHub?): AuthEvents = object : AuthEvents {
            override fun deviceEventInTx(dsl: DSLContext, type: String, deviceId: UUID, name: String) {
                bus?.publishInTx(dsl, type, mapOf("device_id" to deviceId.toString(), "name" to name))
            }

            override fun tokensRevoked(tokenIds: Collection<UUID>) {
                hub?.let { h -> tokenIds.forEach { h.closeToken(it.toString()) } }
            }
        }
    }
}

const val EVENT_DEVICE_PAIRED = "device.paired"
const val EVENT_DEVICE_REVOKED = "device.revoked"
