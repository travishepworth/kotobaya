
# Grammarcreatedschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **id** | **kotlin.String** | Id of the new grammar point. Equals the envelope&#39;s entity_id. |  |
| **title** | **kotlin.String** | Register &#39;title&#39;. |  |
| **detectionRules** | [**kotlin.collections.List&lt;GrammarCreatedSchemaDetectionRulesInner&gt;**](GrammarCreatedSchemaDetectionRulesInner.md) | Register &#39;detection_rules&#39; (whole array). [] &#x3D; not auto-detected. |  [optional] |
| **explanation** | **kotlin.String** | Register &#39;explanation&#39; (Markdown). |  [optional] |
| **isStub** | **kotlin.Boolean** | Register &#39;is_stub&#39;: true for seeded title-and-pattern-only points. |  [optional] |
| **jlpt** | **kotlin.Int** | Register &#39;jlpt&#39;: N-level 1-5, or null. |  [optional] |
| **links** | [**kotlin.collections.List&lt;GrammarCreatedSchemaLinksInner&gt;**](GrammarCreatedSchemaLinksInner.md) | Register &#39;links&#39; (whole array). |  [optional] |
| **meaning** | **kotlin.String** | Register &#39;meaning&#39;. |  [optional] |
| **notes** | **kotlin.String** | Register &#39;notes&#39; (Markdown). |  [optional] |
| **pattern** | **kotlin.String** | Register &#39;pattern&#39;: display pattern, e.g. 〜ざるを得ない. |  [optional] |
| **relatedIds** | **kotlin.collections.Set&lt;kotlin.String&gt;** | Register &#39;related_ids&#39;: soft references to other grammar points. |  [optional] |



