package kotobaya.android.net

sealed class Reachability {
    /** The server answered. [authenticated] is false when it answered 401/403 to our token. */
    data class Reachable(val authenticated: Boolean) : Reachability()

    data class Down(val error: ApiError) : Reachability()
}

/** One probe (`GET /meta`) shared by sync and the UI. */
class ServerReachability(private val client: ApiClient, private val limits: MetaLimitsStore? = null) {
    suspend fun probe(): Reachability = try {
        // Also keeps the persisted limits fresh when the probe succeeds.
        if (limits != null) limits.refresh(client) else client.get("/meta")
        Reachability.Reachable(authenticated = true)
    } catch (e: ApiError.Unauthorized) {
        Reachability.Reachable(authenticated = false)
    } catch (e: ApiError.Forbidden) {
        Reachability.Reachable(authenticated = false)
    } catch (e: ApiError) {
        Reachability.Down(e)
    }
}
