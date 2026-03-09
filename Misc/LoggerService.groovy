/*
** This service handles dual-layered logging for SAP Cloud Integration (iFlows).
** logInternal: Adds an attachment to the SAP Message Processing Log (MPL) for debugging in the SAP Monitor.
** logExternal: Prints a JSON-structured log to STDOUT for external aggregation and analysis (e.g., Kibana).
** logBoth: Executes both internal and external logging simultaneously.
*/

import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput

/**
 * Data Transfer Object (DTO) for structured logging.
 * Consolidates step information, status, and payload.
 */
class LogRequest {
    /** The title of the log entry / attachment */
    String title
    /** The name of the process step being logged */
    String stepName
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
            this.messageLog.addAttachmentAsString(request.title ?: request.stepName, enrichedPayload, request.mediaType)
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
