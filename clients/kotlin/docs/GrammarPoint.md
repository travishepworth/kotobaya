
# GrammarPoint

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **createdAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Creation time. |  |
| **deletedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Tombstone. |  |
| **detectionRules** | [**kotlin.collections.List&lt;DetectionRule&gt;**](DetectionRule.md) | Detection rules; empty &#x3D; not auto-detected. |  |
| **exampleCount** | **kotlin.Int** | Derived - number of live spans (detected and manual) whose sentence exists. |  |
| **explanation** | **kotlin.String** | Explanation (Markdown). |  |
| **id** | **kotlin.String** | Grammar point id. |  |
| **isStub** | **kotlin.Boolean** | true for seeded pattern-and-title-only points not yet filled in. |  |
| **jlpt** | **kotlin.Int** | N-level; null if unclassified. |  |
| **language** | **kotlin.String** | BCP 47; always &#x60;ja&#x60; in v1. |  |
| **links** | [**kotlin.collections.List&lt;GrammarLink&gt;**](GrammarLink.md) | Reference links. |  |
| **meaning** | **kotlin.String** | Short meaning. |  |
| **noteId** | **kotlin.String** | Derived - your live grammar note for this point, or null. |  |
| **notes** | **kotlin.String** | Register and nuance notes (Markdown). |  |
| **pattern** | **kotlin.String** | Display pattern. |  |
| **relatedIds** | **kotlin.collections.List&lt;kotlin.String&gt;** | Related grammar points (soft references; may be deleted). |  |
| **title** | **kotlin.String** | Title. |  |
| **updatedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Last change. |  |



