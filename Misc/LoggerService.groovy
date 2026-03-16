/**
 * LoggerService.groovy
 * 
 * Dependencies:
 * - ExtractW3PCredentials.groovy
 */
/*
** This service handles dual-layered logging for SAP Cloud Integration (iFlows).
** logInternal: Adds an attachment to the SAP Message Processing Log (MPL) for debugging in the SAP Monitor.
** logProcess: Sends a SOAP-structured log to an external service (W3P) for process tracking.
** logBoth: Executes both internal and process logging simultaneously.
*/

import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService
import groovy.json.JsonOutput

/**
 * Global constants. May be present on multiple files
 */
class Constants {
    static final String W3P_CRED = "[W3P_CRED]"
    static final String W3P_URL = "[W3P_URL]"
}


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
    /** Input-related data for the log */
    Object inputPayload
    /** Output or response-related data for the log */
    Object outputPayload
    /** Optional media type for the internal attachment (default: text/plain) */
    String mediaType = "text/plain"
}

/**
 * Handles internal and process logging for SAP Cloud Integration.
 * 
 * Example usage:
 * LoggerService logger = new LoggerService(messageLogFactory, message)
 * logger.setSoapConnection(new HTTPSOAPConnection(baseUrl))
 * logger.logInternal(new LogRequest(stepName: "Step1", title: "WAREHOUSE", status: "OK", inputPayload: "data"))
 * logger.logBoth("WAREHOUSE", "ProcessStep", "OK", input, output)
 */
class LoggerService {
    def messageLog
    def correlationId
    private def soapConnection

    // Valid Log Statuses
    public static final List<String> VALID_STATUSES = ["OK", "ERROR"]

    // Valid Record IDs
    public static final List<String> VALID_RECORD_IDS = ["PRODUCT", "SALES", "INVENTORY", "ACCOUNT", "WAREHOUSE", "WEBHOOK", "W3P"]

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
     * Injects a SOAP connection service for process logging.
     * @param soapConn The HTTPSOAPConnection instance.
     */
    def setSoapConnection(def soapConn) {
        this.soapConnection = soapConn
        return this
    }

    /**
     * Adds an attachment to the SAP Message Processing Log (MPL).
     * Appends the Step Name to the payload content for better visibility.
     * @param request The LogRequest object containing all logging details.
     */
    def logInternal(LogRequest request) {
        if (this.messageLog != null) {
            String combinedPayload = ""
            if (request.inputPayload != null) combinedPayload += "Input:\n${request.inputPayload.toString()}\n\n"
            if (request.outputPayload != null) combinedPayload += "Output:\n${request.outputPayload.toString()}"
            
            if (combinedPayload) {
                String enrichedPayload = "Step: ${request.stepName}\nTitle: ${request.title ?: 'N/A'}\nStatus: ${request.status}\n\n${combinedPayload}"
                this.messageLog.addAttachmentAsString(request.stepName ?: request.title, enrichedPayload, request.mediaType)
            }
        }
    }

    /**
     * Triggers both internal and process (SOAP) logging.
     * @param request The LogRequest object containing internal logging details.
     */
    def logBoth(LogRequest request) {
        logInternal(request)
        logProcess(request)
    }

    /**
     * Executes process logging via SOAP if the connection is available.
     * @param request The LogRequest object containing internal logging details.
     */
    def logProcess(LogRequest request) {
        if (this.soapConnection == null) {
            throw new IllegalStateException("LoggerService: logProcess failed - soapConnection not injected.")
        }

        // Validate Status
        String status = request.status?.toUpperCase()
        if (!(status in VALID_STATUSES)) {
            throw new IllegalArgumentException("LoggerService: Invalid status '${status}'. Expected one of: ${VALID_STATUSES}")
        }

        // Validate Record ID (derived from title)
        String recordId = request.title?.toUpperCase()
        if (!(recordId in VALID_RECORD_IDS)) {
            throw new IllegalArgumentException("LoggerService: Invalid recordId '${recordId}' (derived from title). Expected one of: ${VALID_RECORD_IDS}")
        }

        try {
            // Use SOAPRequestBody to leverage HTTPSOAPConnection's internal buildEnvelope method
            def soapRequest = new SOAPRequestBody()
            soapRequest.action = "POST_LOG"
            
            // Map the data fields for the record/data section
            soapRequest.filters = [
                fstatus_flag: status,
                frecordid   : recordId,
                finput_param: request.inputPayload != null ? request.inputPayload.toString() : "null",
                foutput_param: request.outputPayload != null ? request.outputPayload.toString() : "null"
            ]

            // Credentials extraction handled automatically via Secure Store
            try {
                def service = ITApiFactory.getService(SecureStoreService.class, null)
                def credsMap = extractW3PCredentials(service)
                
                if (credsMap.id) this.soapConnection.setId(credsMap.id)
                if (credsMap.key) this.soapConnection.setKey(credsMap.key)
            } catch (Exception e) {
                throw new RuntimeException("LoggerService: Error extracting credentials: ${e.message}", e)
            }

            this.soapConnection.post(soapRequest)
        } catch (Exception e) {
            throw new RuntimeException("LoggerService: logProcess error: ${e.message}", e)
        }
    }

}


/**
 * ExtractW3PCredentials.groovy
 * 
 * Dependencies:
 * - None
 */

/**
 * Logic to extract W3P credentials from the SAP Secure Store and map them to integration variables.
 * 
 * <p>Example usage in a main script:</p>
 * <pre>
 * {@code
 *  import com.sap.it.api.ITApiFactory
 *  import com.sap.it.api.securestore.SecureStoreService
 *
 *  def Message processData(Message message) {
 *      def service = ITApiFactory.getService(SecureStoreService.class, null)
 *      def credsMap = extractW3PCredentials(service)
 *
 *      message.setHeader("W3P_Id", credsMap.id)
 *      message.setHeader("W3P_Key", credsMap.key)
 *      message.setProperty("W3P_BaseUrl", credsMap.baseUrl)
 *
 *      return message
 *  }
 * }
 * </pre>
 */


/**
 * Method to extract W3P credentials using the SecureStoreService.
 */
def extractW3PCredentials(SecureStoreService service) {
    // 1. Extract W3P Credentials from Secure Store using Constants
    def w3pCreds = getSecureCredential(service, Constants.W3P_CRED)
    def w3pUrlCreds = getSecureCredential(service, Constants.W3P_URL)

    return [
        id: w3pCreds.getUsername(),
        key: new String(w3pCreds.getPassword()),
        baseUrl: new String(w3pUrlCreds.getPassword())
    ]
}

/**
 * Helper method to safely retrieve credentials from the Secure Store.
 */
private def getSecureCredential(SecureStoreService service, String credentialKey) {
    if (service == null) {
        throw new IllegalStateException("SecureStoreService is not available.")
    }
    
    def creds = service.getUserCredential(credentialKey)
    if (creds == null) {
        throw new IllegalStateException("Credential '${credentialKey}' not found in Security Material.")
    }
    
    return creds
}