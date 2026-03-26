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
 * For extracting Service Layer credentials
 * Returns map with status/message and the values as named items (no `payload` key).
 * Example success: [status:1, message:'Success', sessionCookie: '...', baseUrl: '...']
 */
def extractSLCredentials(Message message) {
    String sessionCookie = message.getProperty(Constants.SESSION_VAR_PROP_NAME)
    String baseUrl = message.getProperty(Constants.BASE_URL_PROP_NAME)

    if (!sessionCookie) {
        return [status: -1, message: "SessionCookie is missing."]
    }
    if (!baseUrl) {
        return [status: -1, message: "BaseUrl is missing."]
    }

    return [status: 1, message: "Success", sessionCookie: sessionCookie, baseUrl: baseUrl]
}
