
# PushResponse

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **durableSeq** | **kotlin.Int** | Highest seq s such that every change row with seq &lt;&#x3D; s is in a verified backup that any restore will start from (SYNC.md 6.2). &lt;&#x3D; high_water_seq; never decreases within one log_epoch. A device keeps each acked pending_op row until acked_seq &lt;&#x3D; durable_seq. |  |
| **highWaterSeq** | **kotlin.Int** | Highest change.seq committed when the response was built; &gt;&#x3D; every seq in results. A client whose pull_seq is greater starts a log reset (SYNC.md 6.2); otherwise informational (pending pull count). |  |
| **logEpoch** | **kotlin.String** | Identity of the server&#39;s log (SYNC.md 6.2). Changes only when the log is replaced (a restore from backup): seq values of different epochs are unrelated. A client that sees a value different from the one it stored starts a log reset. |  |
| **results** | [**kotlin.collections.List&lt;PushResult&gt;**](PushResult.md) | Exactly one result per pushed op, in request order. |  |
| **serverTime** | **kotlin.Int** | Server wall clock when the response was built; clients compare it with their own clock to warn about skew and to block minting when it exceeds 24 h (SYNC.md 3.4). |  |



