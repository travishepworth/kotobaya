package kotobaya.android.shell.api.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.ShellGate
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.identity.IdentityTestSupport.call
import kotobaya.android.shell.api.identity.IdentityTestSupport.json
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonString

/** SHELL-L1 acceptance: `getPrincipal` is the pairing, offline, with exactly the device scopes. */
class PrincipalTest {
    @Test
    fun unpairedIs401() {
        val api = LocalApi(
            routes = routes,
            gate = object : ShellGate {
                override fun isPaired() = false
                override fun isRebuildPending() = false
                override suspend fun awaitReady() = Unit
            },
            newRequestId = { "r" },
        )
        val res = runBlocking { api.handle(ShellRequest("GET", "/api/v1/auth/session")) }
        assertEquals(401, res.status)
        assertEquals("unauthenticated", ParityJson.str(ParityJson.obj(res.json()).members["code"]))
    }

    @Test
    fun pairedHasExactlyReadWriteReview() {
        val d = IdentityTestSupport.device()
        val res = d.call("GET", "/api/v1/auth/session")
        assertEquals(200, res.status)
        val p = ParityJson.obj(res.json()).members
        assertEquals(JsonArray(listOf("read", "write", "review").map(::JsonString)), p["scopes"])
        assertEquals(JsonString("device"), p["kind"])
        assertEquals(JsonString(IdentityTestSupport.DEVICE), p["device_id"])
        assertEquals(JsonNull, p["expires_at"])
        assertEquals(JsonBool(false), p["totp_enabled"])
        assertEquals(JsonString("Parity phone"), p["name"])
        assertEquals(JsonString("01931f3f-0000-7000-8000-0000000000b2"), p["token_id"])
    }
}
