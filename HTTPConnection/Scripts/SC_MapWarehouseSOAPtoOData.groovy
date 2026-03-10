import com.sap.gateway.ip.core.customdev.util.Message;
import groovy.util.XmlSlurper;
import groovy.json.JsonSlurper;
import groovy.json.JsonBuilder;
import groovy.json.JsonOutput

def Message processData(Message message) {
    def logger = new LoggerService(messageLogFactory, message)
    def payload = message.getBody(java.lang.String);
    def warehouseRecords = []

    try {
        if (payload.trim().startsWith("<")) {
            // 2a. Parse the outer SOAP Envelope
            def soapParser = new XmlSlurper()
            soapParser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            def envelope = soapParser.parseText(payload)
            
            // Extract the escaped XML string from the <Result> tag
            String innerXml = envelope.Body.callResponse.Result.text()
            
            if (!innerXml) {
                 innerXml = envelope.'**'.find { it.name() == 'Result' }?.text()
            }

            if (!innerXml) throw new RuntimeException("No <Result> tag found in SOAP body.")

            // 2b. Parse the inner XML securely
            def parser = new XmlSlurper()
            parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
            
            def root = parser.parseText(innerXml);
            
            // 3a. Extract records from inner XML
            warehouseRecords = root.data.record.collect { record ->
                [
                    WarehouseCode   : record.fsiteid.text(),
                    WarehouseName   : record.fname.text(),
                    Inactive        : (record.factive_flag.text() == "0") ? "tYES" : "tNO",
                    U_fupdated_date : record.fupdated_date.text(),
                    Street          : record.fmemo.text()
                ]
            }
        } else {
            // 2b. Parse JSON
            def jsonSlurper = new JsonSlurper()
            def root = jsonSlurper.parseText(payload)
            
            def records = []
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

            warehouseRecords = records.collect { record ->
                [
                    WarehouseCode   : record.fsiteid ?: record.WarehouseCode,
                    WarehouseName   : record.fname ?: record.WarehouseName,
                    Inactive        : (record.factive_flag == "0" || record.Inactive == "tYES") ? "tYES" : "tNO",
                    U_fupdated_date : record.fupdated_date ?: record.U_fupdated_date,
                    Street          : record.fmemo ?: record.Street
                ]
            }
        }
        
        // 4. Wrap it in a uniform JSON structure
        def jsonResult = JsonOutput.toJson(warehouseRecords)
        
        // Log Success
        logger.logBoth(new LogRequest(
            stepName: "MapWarehouseData",
            title: "Mapping Successful",
            status: "Success",
            payload: "Records Mapped: ${warehouseRecords.size()}\n\nResult:\n${JsonOutput.prettyPrint(jsonResult)}"
        ))
        
        // 5. Set the new JSON body back to the message
        message.setBody(jsonResult);
        
    } catch (Exception e) {
        // Log Error
        logger.logBoth(new LogRequest(
            stepName: "MapWarehouseData_Error",
            title: "Mapping Failed",
            status: "Error",
            payload: "Exception: ${e.message}\nStacktrace: ${e.stackTrace.take(10).join('\n')}\n\nOriginal Payload:\n${payload}"
        ))
        throw e
    }
    
    return message;
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
