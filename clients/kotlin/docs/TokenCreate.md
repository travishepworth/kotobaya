
# TokenCreate

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **name** | **kotlin.String** | Audit name shown in logs and lists. |  |
| **scopes** | [**kotlin.collections.Set&lt;Scope&gt;**](Scope.md) | Scopes to grant. |  |
| **expiresAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Optional expiry (must be in the future). Absent or null &#x3D; never. |  [optional] |
| **withDevice** | **kotlin.Boolean** | Also create a &#x60;script&#x60; device bound to the token, so it can push ops. |  [optional] |



