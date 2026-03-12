/**
 * ExtractSLCredentials.groovy
 * 
 * Dependencies:
 * - None
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper

/**
 * Standalone method to extract SessionId from a Message Property and return 
 * it as a formatted B1SESSION cookie string.
 *
 * Usage in another script:
 * def cookie = extractSessionCookie(message)
 */
String extractSessionCookie(Message message) {
    String sessionId = message.getProperty("B1SESSION")
    
    if (sessionId) {
        // Handle case where property might already contain "B1SESSION="
        if (sessionId.startsWith("B1SESSION=")) {
            return sessionId
        }
        return "B1SESSION=" + sessionId
    }
    return null
}

/**
 * Extracts the BaseUrl from a Message Property.
 * 
 * @param message The SAP CI Message object.
 * @return String The Base Url, or null if not found.
 */
String extractBaseUrl(Message message) {
    return message.getProperty("SL_BaseURL") ?: message.getProperty("BaseUrl")
}
