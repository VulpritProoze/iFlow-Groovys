## What to do? (As the human prompting the agent)
Provide the following:
1. Reference the file that creates the custom mapping e.g. W3PtoSAP_MapUOM.groovy, as well as the [custom mapping guideline file](/WEBPOStoSAP/MAP/agent_custom_mapping_guidelines).
2. Build the base mapping. For example, if you intend to map from WebPOS UoM → SAP UoMGroup, provide the base mappings first, then if a field requires complex calculation or API calls, then create a future reference (e.g. `see (#2)` where #2 is the second instruction in the order).
3. Encourage the agent to create reusable methods to lessen lines of code such as repeating `addUom()` method whose purpose is to create a new UoM in SAP.

Guidelines:
1. Use numbering to indicate order of instructions, e.g.
	```
	1. Mapping:
		Code → fuomId
		Name → fname
		BaseUom → see (#2)
		...etc.
	2. SAP Query... etc.
	```

Not to do:
1. Do not POST the resulting mapped record. That is the responsibility of the POSTER step.