
# DictReleaseArtifactsInner

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **blobHash** | **kotlin.String** | Lowercase hex SHA-256 of a blob&#39;s bytes. |  |
| **formatVersion** | **kotlin.Int** | For &#x60;full&#x60; - the package&#39;s &#x60;PRAGMA user_version&#x60;; for &#x60;diff&#x60; - the &#x60;kotobaya-dict-diff&#x60; &#x60;format_version&#x60;. Currently 1. Devices skip artifacts whose version they do not support (&#x60;contracts/db/DICT_PACKAGE.md&#x60; 5-6). |  |
| **fromRelease** | **kotlin.Int** | For &#x60;diff&#x60; - the release id it upgrades from; null for &#x60;full&#x60;. |  |
| **kind** | [**inline**](#Kind) | Full build or diff from an older release. |  |
| **scope** | [**inline**](#Scope) | Which entries the build holds. |  |
| **sizeBytes** | **kotlin.Long** | Size of the blob (compressed bytes, as served by &#x60;GET /blobs/{hash}&#x60;). |  |
| **uncompressedSizeBytes** | **kotlin.Long** | Size after zstd decompression, for the device&#39;s free-space check. |  |


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



