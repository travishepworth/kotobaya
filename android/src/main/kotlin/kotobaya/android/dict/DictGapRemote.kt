package kotobaya.android.dict

import java.net.URLEncoder
import kotobaya.android.net.ApiClient
import kotobaya.android.net.ApiError

/** A server answer for one reference. */
sealed interface RemoteAnswer {
    /** `200`: the body as the server sent it (overlay included; the cache strips it). */
    data class Found(val body: String) : RemoteAnswer

    /** `404`: the server's current release has no such entry / kanji. */
    data object NotFound : RemoteAnswer
}

/** The server reads gap fill and step 3 of L-7 use. Every failure other than `404` is thrown (an [ApiError]). */
interface DictGapRemote {
    /** `getDictRelease`: the server's current release id, or null on `404` (no release loaded yet). */
    suspend fun serverRelease(): Long?

    /** `getDictEntry`. */
    suspend fun entry(seq: Long): RemoteAnswer

    /** `getKanji`. */
    suspend fun kanji(literal: String): RemoteAnswer
}

/** [DictGapRemote] over the public API. */
class HttpDictGapRemote(private val api: ApiClient) : DictGapRemote {
    override suspend fun serverRelease(): Long? = try {
        api.get("/dict/release").body.getLong("id")
    } catch (e: ApiError.Other) {
        if (e.status == 404) null else throw e
    } catch (e: org.json.JSONException) {
        throw ApiError.BadBody(e.message ?: "release")
    }

    override suspend fun entry(seq: Long): RemoteAnswer = answer { api.get("/dict/entries/$seq").body.toString() }

    override suspend fun kanji(literal: String): RemoteAnswer =
        answer { api.get("/kanji/" + URLEncoder.encode(literal, "UTF-8").replace("+", "%20")).body.toString() }

    private suspend fun answer(call: suspend () -> String): RemoteAnswer = try {
        RemoteAnswer.Found(call())
    } catch (e: ApiError.Other) {
        if (e.status == 404) RemoteAnswer.NotFound else throw e
    } catch (e: ApiError.Validation) {
        // getKanji: a literal of more than one code point is 422; it can never be found
        RemoteAnswer.NotFound
    }
}
