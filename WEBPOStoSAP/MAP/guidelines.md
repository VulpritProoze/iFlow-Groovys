Agent-only concise guide for creating custom mappings.

General rules:
- Do not modify helper code (extractMappedRecords, extractRecordsFromPayload, isFdoneOne, LoggerService, SOAP/OData helpers).
- Do not modify the `Constants` class.
- For custom logic: only edit the `try` block inside `processData()`.
- For simple one-to-one mappings: only update `Constants.MAPPING`.
- Preserve the last two lines in the `try` block (they set the body and call `logger.logBoth`).
- Use `logger.logBoth(...)` on every API call success or error.

Specific rules:
- Use `extractRecordsFromPayload(payload)` to obtain `<record>` nodes (handles escaped inner `Result` XML).
- Example inner record (payload-agnostic):
	<record id="ID"><field1>v1</field1><field2>v2</field2></record>
- `extractMappedRecords(...)` returns a result map: `[status: number, message: String, payload: List<Map>]`.
	The working mapped collection is `mappedRecords` (a List of Maps) before `JsonOutput.toJson(...)`.

Prompt template for a human (what to write after agent reads this guide):
- If simple mapping: "Update `Constants.MAPPING` to map source tokens to target fields. Example: Constants.MAPPING = ["Code":"fuomid","Name":"fname"]"
- If custom logic: "Implement custom mapping inside `processData()` try block. Provide sample input record (XML), show desired output map/list, describe enrichment or API calls required. Leave the final two lines unchanged: `message.setBody(jsonResult)` and `logger.logBoth(...)`."

Keep changes minimal and use the helpers; the guide is intentionally terse for agent consumption.