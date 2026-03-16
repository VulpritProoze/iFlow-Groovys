/**
 * ExtractSLCredentials.groovy
 * 
 * Dependencies:
 * - None
 */
import com.sap.gateway.ip.core.customdev.util.Message


class Constants {
    static final String SESSION_VAR_PROP_NAME = "[B1SESSION]"
    static final String BASE_URL_PROP_NAME = "[SL_BaseURL]"
}

/**
 * Standalone method to extract SessionId from a Message Property.
 * Returns standardized Result Map.
 *
 * Usage in another script:
 * def cookieMap = extractSessionCookie(message)
 */
def extractSessionCookie(Message message) {
    String sessionCookie = message.getProperty(Constants.SESSION_VAR_PROP_NAME)
    
    if (!sessionCookie) {
        return [status: -1, message: "SessionCookie is missing."]
    }
    return [status: 1, message: "Success", payload: sessionCookie]
}

/**
 * Extracts the BaseUrl from a Message Property.
 * 
 * @param message The SAP CI Message object.
 * @return Map Result structure with status, message, payload.
 */
def extractBaseUrl(Message message) {
    String baseUrl = message.getProperty(Constants.BASE_URL_PROP_NAME)

    if (!baseUrl) {
        return [status: -1, message: "BaseUrl is missing."]
    }
    return [status: 1, message: "Success", payload: baseUrl]
}
