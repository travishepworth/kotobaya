
# Token

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **active** | **kotlin.Boolean** | Derived - valid right now (not revoked, not expired, device not revoked). |  |
| **createdAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Creation time. |  |
| **deviceId** | **kotlin.String** | Bound device, or null. |  |
| **expiresAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Expiry, or null for never. |  |
| **id** | **kotlin.String** | Token id. |  |
| **kind** | [**TokenKind**](TokenKind.md) |  |  |
| **lastUsedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Last authenticated request (updated at most once a minute). |  |
| **name** | **kotlin.String** | Audit name. |  |
| **revokedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Set when revoked. |  |
| **scopes** | [**kotlin.collections.List&lt;Scope&gt;**](Scope.md) | Scopes held. |  |
| **tokenPrefix** | **kotlin.String** | First 8 characters of the token string. |  |



