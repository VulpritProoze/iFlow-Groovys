import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput

class LoggerService {
    def messageLog
    def correlationId

    // Constructor: Initialize with the global factory and the current message
    LoggerService(def messageLogFactory, Message message) {
        if (messageLogFactory != null) {
            this.messageLog = messageLogFactory.getMessageLog(message)
        }
        // Capture the standard SAP correlation ID to link internal and external logs
        this.correlationId = message.getHeaders().get("SAP_MessageProcessingLogID") ?: "N/A"
    }

    // 1. Internal Logging: Saves as an attachment in the SAP Monitor
    def logInternal(String title, Object payload, String mediaType = "text/plain") {
        if (this.messageLog != null && payload != null) {
            this.messageLog.addAttachmentAsString(title, payload.toString(), mediaType)
        }
    }

    // 2. External Logging: Prints to STDOUT for BTP Application Logging (Kibana)
    def logExternal(String stepName, String status, Object payload) {
        // Structuring as JSON allows Kibana to automatically index the fields
        def logEntry = [
            type         : "IFLOW_EXTERNAL_LOG",
            correlationId: this.correlationId,
            step         : stepName,
            status       : status,
            payload      : payload != null ? payload.toString() : "null"
        ]
        println(JsonOutput.toJson(logEntry))
    }

    // 3. Combined Logging: Triggers both methods at once
    def logBoth(String title, String status, Object payload) {
        logInternal(title, payload)
        logExternal(title, status, payload)
    }
}