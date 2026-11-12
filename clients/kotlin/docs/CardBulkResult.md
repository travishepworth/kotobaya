
# CardBulkResult

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **changed** | **kotlin.Int** | Cards (or, for tag and move actions, notes) for which an op was emitted. |  |
| **dryRun** | **kotlin.Boolean** | Echo of the request flag. |  |
| **matched** | **kotlin.Int** | Live cards selected. |  |
| **opsEmitted** | **kotlin.Int** | Number of ops emitted (0 for a dry run). |  |
| **skipped** | **kotlin.Int** | Selected ids or cards for which nothing was done (unknown, deleted, merged away, already in a no-op state). |  |



