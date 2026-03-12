/**
 * W3PtoSAP_MapWarehouse.groovy
 * 
 * Dependencies:
 * - Misc/Mapper.groovy (Logic refactored and appended below)
 * - Misc/LoggerService.groovy (Logic refactored and appended below)
 */
import com.sap.gateway.ip.core.customdev.util.Message;
import groovy.util.XmlSlurper;
import groovy.util.slurpersupport.GPathResult;
import groovy.json.JsonSlurper;
import groovy.json.JsonBuilder;
import groovy.json.JsonOutput

class Constants {
    static final String STEP_NAME = "W3PtoSAP_MapWarehouses"
    static final Map WAREHOUSE_MAPPING = [
        "WarehouseCode"   : "fsiteid",
        "WarehouseName"   : "fname",
        "Inactive"        : "factive_flag",
        "U_fupdated_date" : "fupdated_date",
        "Street"          : "fmemo"
    ]
    static final Map CUSTOM_RULES = [
        "Inactive": { val -> (val == "0" || val == "tYES") ? "tYES" : "tNO" }
    ]
}

/**
 * Mapping configuration for Warehouse records.
 * Defines how source fields from various platforms (SOAP, JSON, W3P) 
 * map to the target SAP Warehouse structure.
 * 
 * {@code
 * // Example usage in processData:
 * def warehouseRecords = extractMappedRecords(payload, Constants.WAREHOUSE_MAPPING, Constants.CUSTOM_RULES)
 * }
 */
def Message processData(Message message) {
    def logger = new LoggerService(messageLogFactory, message)
    def payload = message.getBody(java.lang.String);

    try {
        // Use the extracted mapping utility
        def warehouseRecords = extractMappedRecords(
            payload, 
            Constants.WAREHOUSE_MAPPING, 
            Constants.CUSTOM_RULES
        )
        
        if (warehouseRecords.isEmpty() && payload.trim().startsWith("<")) {
            throw new RuntimeException("No records found or failed to parse XML <Result>.")
        }

        // 4. Wrap it in a uniform JSON structure
        def jsonResult = JsonOutput.toJson(warehouseRecords)
        
        // Log Success
        logger.logInternal(new LogRequest(
            stepName: Constants.STEP_NAME,
            title: "Mapping Successful",
            status: "Success",
            payload: "Records Mapped: ${warehouseRecords.size()}\n\nResult:\n${JsonOutput.prettyPrint(jsonResult)}"
        ))
        
        // 5. Set the new JSON body back to the message
        message.setBody(jsonResult);
        
    } catch (Exception e) {
        // Log Error
        logger.logInternal(new LogRequest(
            stepName: Constants.STEP_NAME,
            title: "Mapping Failed",
            status: "Error",
            payload: "Exception: ${e.message}\nStacktrace: ${e.stackTrace.take(10).join('\n')}\n\nOriginal Payload:\n${payload}"
        ))
        throw e
    }
    
    return message;
}



/**
 * Agnostic Payload Processor (Refactored from Mapper.groovy)
 * Dynamically handles both XML (SOAP) and JSON data formats.
 */
def extractMappedRecords(String payload, Map mapping, Map customRules = [:]) {
    def records = []
    
    if (payload.trim().startsWith("<")) {
        def soapParser = new XmlSlurper()
        soapParser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        def envelope = soapParser.parseText(payload)
        
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

    return records.collect { record ->
        def result = [:]
        mapping.each { target, source ->
            def val = (record instanceof GPathResult) ? 
                      (record."$source".text() ?: record."$target".text()) : 
                      (record[source] ?: record[target])
            
            if (customRules.containsKey(target)) {
                result[target] = customRules[target](val)
            } else {
                result[target] = val ?: ""
            }
        }
        result
    }
}



/*
** This service handles dual-layered logging for SAP Cloud Integration (iFlows).
** logInternal: Adds an attachment to the SAP Message Processing Log (MPL) for debugging in the SAP Monitor.
** logExternal: Prints a JSON-structured log to STDOUT for external aggregation and analysis (e.g., Kibana).
** logBoth: Executes both internal and external logging simultaneously.
*/


/**
 * Data Transfer Object (DTO) for structured logging.
 * Consolidates step information, status, and payload.
 */
class LogRequest {
    /** The name of the process step being logged */
    String stepName
    /** The title of the log entry / attachment */
    String title
    /** The status of the step (e.g., Success, Error, Info) */
    String status
    /** The content or object to be logged */
    Object payload
    /** Optional media type for the internal attachment (default: text/plain) */
    String mediaType = "text/plain"
}

/**
 * Handles internal and external logging for SAP Cloud Integration.
 * 
 * Example usage:
 * LoggerService logger = new LoggerService(messageLogFactory, message)
 * logger.logInternal("Payload Received", body)
 * logger.logExternal("RequestStep", "Success", [id: 123])
 * logger.logBoth("Final Status", "Completed", responseBody)
 */
class LoggerService {
    def messageLog
    def correlationId

    /**
     * Initializes the logger service.
     * @param messageLogFactory The global message log factory provided by the iFlow engine.
     * @param message The current iFlow Message object.
     */
    LoggerService(def messageLogFactory, Message message) {
        if (messageLogFactory != null) {
            this.messageLog = messageLogFactory.getMessageLog(message)
        }
        this.correlationId = message.getHeaders().get("SAP_MessageProcessingLogID") ?: "N/A"
    }

    /**
     * Adds an attachment to the SAP Message Processing Log (MPL).
     * Appends the Step Name to the payload content for better visibility.
     * @param request The LogRequest object containing all logging details.
     */
    def logInternal(LogRequest request) {
        if (this.messageLog != null && request.payload != null) {
            String enrichedPayload = "Step: ${request.stepName}\nTitle: ${request.title ?: 'N/A'}\nStatus: ${request.status}\n\n${request.payload.toString()}"
            this.messageLog.addAttachmentAsString(request.stepName ?: request.title, enrichedPayload, request.mediaType)
        }
    }

    /**
     * Prints a JSON-structured log to STDOUT for external aggregation (Kibana).
     * @param request The LogRequest object containing all logging details.
     */
    def logExternal(LogRequest request) {
        def logEntry = [
            type         : "IFLOW_EXTERNAL_LOG",
            title        : request.title,
            correlationId: this.correlationId,
            step         : request.stepName,
            status       : request.status,
            payload      : request.payload != null ? request.payload.toString() : "null"
        ]
        println(JsonOutput.toJson(logEntry))
    }

    /**
     * Triggers both internal and external logging using a LogRequest object.
     * @param request The LogRequest object containing all logging details.
     */
    def logBoth(LogRequest request) {
        logInternal(request)
        logExternal(request)
    }

    /**
     * Overloaded method for quick logging without creating a LogRequest object.
     * @param title The title/step name for the log.
     * @param status The status of the operation.
     * @param payload The data to be logged.
     */
    def logBoth(String title, String status, Object payload) {
        def req = new LogRequest(stepName: title, status: status, payload: payload)
        logInternal(req)
        logExternal(req)
    }
}
