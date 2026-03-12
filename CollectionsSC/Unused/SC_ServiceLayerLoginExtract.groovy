import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper

/**
 * SC_ExtractLoginDataFromBody.groovy
 * 
 * Extracts Service Layer session credentials and URL from the message body 
 * (sent as JSON by SC_ServiceLayerLogin.groovy) and maps them to Properties.
 *
 * <p>Expected JSON Body Plan:</p>
 * <pre>
 * {
 *   "SessionId": "...",
 *   "BaseUrl": "...",
 *   "SessionCookie": "B1SESSION=..."
 * }
 * </pre>
 *
 */
def Message processData(Message message) {
    def body = message.getBody(java.io.Reader)
    
    if (body == null) {
        throw new RuntimeException("Empty message body. Cannot extract Login data.")
    }

    try {
        def json = new JsonSlurper().parse(body)
        
        // Map values to Properties for easy use in subsequent adapters/scripts
        if (json.SessionId) {
            message.setProperty("B1SESSION", json.SessionId)
            message.setProperty("SL_SessionCookie", json.SessionCookie)
            message.setProperty("SL_BaseURL", json.BaseUrl)
            
            // Log for traceability (optional)
            // def logger = ITApiFactory.getService(LoggerService.class, null)
            // logger.logMessage("Extracted SL Session: ${json.SessionId}")
        } else {
            throw new RuntimeException("SessionId missing from the Login response body.")
        }
    } catch (Exception e) {
        throw new RuntimeException("Failed to parse Service Layer Login JSON: " + e.message)
    } finally {
        body.close()
    }

    return message
}
