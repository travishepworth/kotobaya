
# Principal

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **deviceId** | **kotlin.String** | Device the token is bound to, or null. |  |
| **expiresAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Expiry, or null for never. |  |
| **kind** | [**TokenKind**](TokenKind.md) |  |  |
| **name** | **kotlin.String** | Audit name of the token. |  |
| **scopes** | [**kotlin.collections.List&lt;Scope&gt;**](Scope.md) | Scopes held, in the order read, write, review, import, admin. |  |
| **tokenId** | **kotlin.String** | &#x60;api_token.id&#x60;. |  |
| **totpEnabled** | **kotlin.Boolean** | Whether the server has TOTP configured. |  |



