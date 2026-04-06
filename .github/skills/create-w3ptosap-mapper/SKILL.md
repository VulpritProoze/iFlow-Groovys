---
name: create-w3ptosap-mapper
description: "Scaffold and guidance for creating W3P → SAP mapping Groovy files from the `W3PtoSAPMapper_v2` boilerplate."
argument-hint: "entity -> e.g. Account, Product, UoM; mappings -> mapping spec block"
---

# W3P → SAP Mapping Skill (w3ptosap_mapping)

**Summary**
- Short, repeatable workflow to scaffold `W3PtoSAP_Map[Entity].groovy` files using the `Misc/v2/W3PtoSAPMapper_v2.groovy` boilerplate and to document mapping decisions.

**When to Use**
- Implementing a new entity mapping from W3P payloads into SAP (Account, Product, UoM, etc.).

**Process (step-by-step)**
1. Create a new file named `W3PtoSAP_Map[Entity].groovy` under `WEBPOStoSAP/MAP`.
2. Copy the boilerplate from [Misc/v2/W3PtoSAPMapper_v2.groovy](../../../Misc/v2/W3PtoSAPMapper_v2.groovy) into the new file.
3. Implement the entity-specific mapping inside the `try { ... }` block within `processData(Message message)` — this is where the script assembles the output list and sets `message.body`.
4. If you need SAP-side lookups, call the repo's OData helper (see [Misc/ODataConnection.groovy](../../../Misc/ODataConnection.groovy)) and log results with a `${Constants.STEP_NAME}_[ENTITY]_RESOLVE` step before using responses.
5. Add any helper methods needed below `processData` in the "Add other methods here for customization" area. Do NOT modify the protected helper methods (the XML/record helpers) unless explicitly requested.
6. Follow logging conventions (see Logging section) and return/output JSON as the script currently does.

**Decision Points**
- If a destination field requires values from multiple source fields, implement a resolver method below `processData` and log a `_RESOLVE` step for traceability.
- If the mapping requires additional API calls to enrich data, log both the API request and response with detailed payloads (use pretty JSON when appropriate).

**Quality Criteria / Checks**
- Add a top-of-file docstring listing dependencies and a short example usage.
- Preserve the existing helper methods; place custom helpers only in the free customization area.
- Use the repository result pattern for helper methods (`[status:, message:, payload:]`) for consistency.
- Ensure `message.setBody(...)` outputs JSON (use `JsonOutput.toJson(...)`) and that logs include sufficient context for replay/debug.

**Logging**
- Use the `LoggerService` and log types: `${Constants.STEP_NAME}_ERR`, `${Constants.STEP_NAME}_OK`, and `${Constants.STEP_NAME}_[ENTITY]_RESOLVE` for lookup results.
- Make input payloads as detailed as practical (e.g., `JsonOutput.prettyPrint(JsonOutput.toJson(payloadMap))`) and include response payloads as the `outputPayload`.

**Example mapping spec (user-provided format)**
```
1. Mapping:
    Code → fuomId
    Name → fname
    BaseUom → see (#2)
    ...etc.
2. SAP Query: use OData GET /Items?$filter=Code eq '{Code}' to fetch base UoM
```

**Example prompt to run this skill**
- "Scaffold `W3PtoSAP_MapAccount.groovy` for Account with mappings: Code→fuomId, Name→fname, BaseUom→(ODATA lookup)."

**Notes**
- Keep this skill short: the file produced should be a boilerplate copy with clear TODOs inside the `processData` `try` block for the implementer.
- If you want, the agent can auto-generate the new mapper file for a specific `entity` when provided with the `mappings` block.
