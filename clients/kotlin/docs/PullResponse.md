
# PullResponse

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **durableSeq** | **kotlin.Int** | Highest seq s such that every change row with seq &lt;&#x3D; s is in a verified backup that any restore will start from (SYNC.md 6.2). &lt;&#x3D; high_water_seq; never decreases within one log_epoch. A device keeps each acked pending_op row until acked_seq &lt;&#x3D; durable_seq. |  |
| **hasMore** | **kotlin.Boolean** | true &#x3D; more ops exist after next_since; pull again immediately. |  |
| **highWaterSeq** | **kotlin.Int** | As in PushResponse. |  |
| **logEpoch** | **kotlin.String** | Identity of the server&#39;s log (SYNC.md 6.2). Changes only when the log is replaced (a restore from backup): seq values of different epochs are unrelated. A client that sees a value different from the one it stored starts a log reset. |  |
| **nextSince** | **kotlin.Int** | Cursor for the next pull: seq of the last op returned, or the request&#39;s since when ops is empty. |  |
| **ops** | [**kotlin.collections.List&lt;Opschema&gt;**](Opschema.md) | Log entries with seq &gt; since, in strictly ascending seq, from every origin including the requester itself. |  |
| **serverTime** | **kotlin.Int** | As in PushResponse. |  |



