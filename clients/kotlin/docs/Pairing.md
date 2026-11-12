
# Pairing

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **code** | **kotlin.String** | 8 characters of Crockford base32 (no I, L, O, U). Compared case-insensitively; hyphens and spaces typed by the user are ignored. |  |
| **expiresAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Creation time + 10 minutes. |  |
| **pairingUri** | **kotlin.String** | &#x60;kotobaya://pair?server&#x3D;&lt;percent-encoded public base URL including /api/v1&gt;&amp;code&#x3D;&lt;code&gt;&#x60;; the QR code content. |  |



