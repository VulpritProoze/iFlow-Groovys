Agent-only concise guide for creating custom mappings.

General rules:
- Do not modify helper code (extractMappedRecords, extractRecordsFromPayload, isFdoneOne, LoggerService, SOAP/OData helpers).
- Do not modify the `Constants` class.
- Edit the `try` block ONLY inside `processData()`.
- Preserve the last two lines in the `try` block (they set the body and call `logger.logBoth`).
- Use `logger.logBoth(...)` on every API call success or error.
- When creating custom methods, always use Result pattern [status: status, message: message, payload: payload], and put them below processData.
- When using helper methods, expect that the result uses Result pattern. see [AGENTS.md](/AGENTS.md)

Specific rules:
- Use `extractRecordsFromPayload(payload)` to obtain `<record>` nodes (handles escaped inner `Result` XML).
- Example inner record (payload-agnostic):
	<record id="ID"><field1>v1</field1><field2>v2</field2></record>
- Never use for-each instead of for-loop, if we want to iterate over a record, or if we know that the loop code will span many lines of code. We want to ensure that we can early return anywhere in the processData.

SAP payload examples (agnostic):
- GET (OData list): ignore `odata.metadata`; items are under `value` as an array of objects.
	Example (fields omitted for brevity):
	{
		"value": [ { "ItemCode": "A1", "ItemName": "..." , /* other fields */ }, { "ItemCode": "A2", "ItemName": "..." } ]
	}
- POST (single entity): resulting object contains the entity fields at top-level (metadata may be present but can be ignored).
	Example:
	{
		"Code": "carton3",
		"Name": "carton",
		"Length1": 0.0,
		/* other entity fields */
	}

Prompt template for a human (what to write after agent reads this guide):
- If simple mapping: "Update `Constants.MAPPING` to map source tokens to target fields. Example: Constants.MAPPING = ["Code":"fuomid","Name":"fname"]"
- If custom logic: "Implement custom mapping inside `processData()` try block. Provide sample input record (XML), show desired output map/list, describe enrichment or API calls required. Leave the final two lines unchanged: `message.setBody(jsonResult)` and `logger.logBoth(...)`."

Keep changes minimal and use the helpers; the guide is intentionally terse for agent consumption.