import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput

/**
 * SAP BTP iFlow Script function to log the message body.
 *
 * @param message The SAP Cloud Integration Message object.
 * @return The unmodified Message object.
 */
def Message processData(Message message) {
    def body = message.getBody(java.lang.String)
    
    // Initialize the logger service
    def logger = new LoggerService(messageLogFactory, message)
    
    // Log the message body as an attachment
    logger.logInternal(new LogRequest(
        title: "Message Body Log",
        stepName: "Body_Logger_Step",
        status: "INFO",
        payload: body ?: "Empty Body"
    ))
    
    return message
}

/*
** =====================================================================================
** LOGGER SERVICE CODE (Inline for standalone use)
** =====================================================================================
*/

/**
 * Data Transfer Object (DTO) for structured logging.
 */
class LogRequest {
    String title
    String stepName
    String status
    Object payload
    String mediaType = "text/plain"
}

/**
 * Handles internal and external logging for SAP Cloud Integration.
 */
class LoggerService {
    def messageLog
    def correlationId

    LoggerService(def messageLogFactory, Message message) {
        if (messageLogFactory != null) {
            this.messageLog = messageLogFactory.getMessageLog(message)
        }
        this.correlationId = message.getHeaders().get("SAP_MessageProcessingLogID") ?: "N/A"
    }

    def logInternal(LogRequest request) {
        if (this.messageLog != null && request.payload != null) {
            String enrichedPayload = "Step: ${request.stepName}\nTitle: ${request.title ?: 'N/A'}\nStatus: ${request.status}\n\n${request.payload.toString()}"
            this.messageLog.addAttachmentAsString(request.title ?: request.stepName, enrichedPayload, request.mediaType)
        }
    }

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

    def logBoth(LogRequest request) {
        logInternal(request)
        logExternal(request)
    }

    def logBoth(String title, String status, Object payload) {
        def req = new LogRequest(stepName: title, status: status, payload: payload)
        logInternal(req)
        logExternal(req)
    }
}