
# DictReleaseArtifactsInner

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **blobHash** | **kotlin.String** | Lowercase hex SHA-256 of a blob&#39;s bytes. |  |
| **fromRelease** | **kotlin.Int** | For &#x60;diff&#x60; - the release id it upgrades from; null for &#x60;full&#x60;. |  |
| **kind** | [**inline**](#Kind) | Full build or diff from an older release. |  |
| **scope** | [**inline**](#Scope) | Which entries the build holds. |  |
| **sizeBytes** | **kotlin.Long** | Size of the blob. |  |


<a id="Kind"></a>
## Enum: kind
| Name | Value |
| ---- | ----- |
| kind | full, diff |


<a id="Scope"></a>
## Enum: scope
| Name | Value |
| ---- | ----- |
| scope | top60k, all |



