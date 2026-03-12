/**
 * ExtractSLCredentials.groovy
 * 
 * Dependencies:
 * - None
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper


class Constants {
    static final String SESSION_VAR_PROP_NAME = "[B1SESSION]"
    static final String BASE_URL_PROP_NAME = "[SL_BaseURL]"
}

/**
 * Standalone method to extract SessionId from a Message Property and return 
 * it as a formatted B1SESSION cookie string.
 *
 * Usage in another script:
 * def cookie = extractSessionCookie(message)
 */
String extractSessionCookie(Message message) {
    String sessionCookie = message.getProperty(SESSION_VAR_PROP_NAME)
    
    if (!sessionCookie || !sessionCookie.startsWith(CONSTANTS.SESSION_VAR_PROP_NAME + "=")) {
        throw RuntimeException("SessionCookie has invalid format or not found.")
    }
    return sessionCookie
}

/**
 * Extracts the BaseUrl from a Message Property.
 * 
 * @param message The SAP CI Message object.
 * @return String The Base Url.
 */
String extractBaseUrl(Message message) {
    String baseUrl = message.getProperty(BASE_URL_PROP_NAME)

    if (!baseUrl) {
        throw RuntimeException("BaseUrl is missing.")
    }
    return baseUrl
}
