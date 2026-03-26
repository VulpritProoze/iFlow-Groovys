1. Do not use closures for for-each. Method processData() must be early-returnable
2. Preferably use result pattern for method return statements `[status: status, message: message, payload: payload]` where
- **status**: -1 represents system/server error, 0 represents client error, 1 represents success
- **message**: must be of type _String_
- **payload** (Optional): must preferably be an API response payload from an API call.
3. Always append jdoc-style strings above a method when creating where
- **summary**: represents short definition of the method
- **params**: parameters of the method
- **description**: extended description
- **example**: use case scenario
4. Preferably code logger calls, and api calls in one line.