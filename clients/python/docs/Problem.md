# Problem

RFC 9457 problem details with Kotobaya extension members.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**code** | **str** | Stable machine-readable error code (error table in the API description). | 
**detail** | **str** | Human-readable explanation of this occurrence. English; not for parsing. | [optional] 
**errors** | [**List[FieldError]**](FieldError.md) | Present for &#x60;validation_failed&#x60;, &#x60;invalid_query&#x60;, &#x60;bulk_too_large&#x60; and (optionally) &#x60;invalid_request&#x60; - one element per problem. | [optional] 
**existing_id** | **str** | Present for &#x60;already_exists&#x60; - id of the row (or job) holding the key. | [optional] 
**instance** | **str** | Request path the problem occurred on. | [optional] 
**request_id** | **str** | Id of this request in the server log (also sent as the &#x60;X-Request-Id&#x60; response header). | 
**required_scope** | [**Scope**](Scope.md) | Present for &#x60;insufficient_scope&#x60;. | [optional] 
**status** | **int** | The HTTP status code. | 
**title** | **str** | Short, stable, human-readable summary of the code. | 
**type** | **str** | &#x60;urn:kotobaya:problem:&lt;code&gt;&#x60;. | 

## Example

```python
from kotobaya_client.models.problem import Problem

# TODO update the JSON string below
json = "{}"
# create an instance of Problem from a JSON string
problem_instance = Problem.from_json(json)
# print the JSON string representation of the object
print(Problem.to_json())

# convert the object into a dict
problem_dict = problem_instance.to_dict()
# create an instance of Problem from a dict
problem_from_dict = Problem.from_dict(problem_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


