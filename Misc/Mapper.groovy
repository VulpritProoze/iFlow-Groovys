import groovy.util.XmlSlurper;
import groovy.util.slurpersupport.GPathResult;
import groovy.json.JsonSlurper;

/**
 * Agnostic Payload Processor.
 * Dynamically handles both XML (SOAP) and JSON data formats.
 * 
 * @param payload Raw string payload (XML/SOAP or JSON)
 * @param mapping Definition of target fields to source fields. 
 *                Structure: [ "TargetField" : "SourceField" ]
 *                Example: [ "WarehouseCode" : "fsiteid", "Name" : "fname" ]
 * @param customRules Optional specific logic for target fields.
 *                    Structure: [ "TargetField" : { value -> logic } ]
 *                    Example: [ "Inactive" : { val -> val == "0" ? "tYES" : "tNO" } ]
 */
def extractMappedRecords(String payload, Map mapping, Map customRules = [:]) {
    def records = []
    
    // 1. Format Detection & Initial Parsing
    if (payload.trim().startsWith("<")) {
        def soapParser = new XmlSlurper()
        soapParser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        def envelope = soapParser.parseText(payload)
        
        // Extract inner XML from standard SAP SOAP result tags
        String innerXml = envelope.Body.callResponse.Result.text() ?: envelope.'**'.find { it.name() == 'Result' }?.text()
        
        if (!innerXml) return []

        def parser = new XmlSlurper()
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
        
        def root = parser.parseText(innerXml)
        records = root.data.record.collect { it }
    } else {
        def jsonSlurper = new JsonSlurper()
        def root = jsonSlurper.parseText(payload)
        
        if (root instanceof List) {
            records = root
        } else if (root.params?.data?.record) {
            records = root.params.data.record
        } else if (root.data?.record) {
            records = root.data.record
        } else if (root.record) {
            records = root.record
        } else {
            records = [root]
        }
    }

    // 2. Agnostic Mapping Implementation
    return records.collect { record ->
        def result = [:]
        mapping.each { target, source ->
            def val = (record instanceof GPathResult) ? 
                      (record."$source".text() ?: record."$target".text()) : 
                      (record[source] ?: record[target])
            
            // Apply custom rules if provided (e.g., specific flag handling)
            if (customRules.containsKey(target)) {
                result[target] = customRules[target](val)
            } else {
                result[target] = val ?: ""
            }
        }
        result
    }
}
