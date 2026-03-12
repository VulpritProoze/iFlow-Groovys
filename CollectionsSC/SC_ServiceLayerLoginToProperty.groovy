/**
 * SC_ServiceLayerLoginToProperty.groovy
 * 
 * Dependencies:
 * - None
 */
import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper


class Constants {
    static final String LOGIN_CREDENTIALS = "SL_LOGIN_CRED" 
    static final String COMPANY_CREDENTIALS = "SL_COMPANY_CRED" 
    static final String BASE_URL = "SL_BASE_URL" 
    static final String SESSION_VAR = "B1SESSION"
}



/**
 * SC_ServiceLayerLoginToProperty.groovy
 * 
 * Centralized script for SAP Business One Service Layer authentication.
 * Performs extraction, HTTP login, and stores the session token as a Message Property.
 * Details: Logs in to Service Layer, and places cookie to Message Property
 *
 * <p>Example usage in the same iFlow:</p>
 * <pre>
 * {@code
 *  // You can access the token in subsequent steps using:
 *  // ${property.B1SESSION}
 *  // ${property.SL_BaseURL}
 * }
 * </pre>
 *
 */

/**
 * SINGLE METHOD LOGIN FLOW
 * 1. Extracts credentials from Secure Store.
 * 2. Performs HTTP POST to Service Layer /Login.
 * 3. Saves SessionId to Message Property.
 */
def Message processData(Message message) {
    def secureStore = ITApiFactory.getService(SecureStoreService.class, null)
    def creds = extractSLCredentials(secureStore)
    
    // Perform Login Request
    def loginResponse = performServiceLayerLogin(creds)
    
    // Store Session Token and URL as Properties
    if (loginResponse.SessionId) {
        // Store in property using format: B1SESSION=SessionID
        message.setProperty(Constants.SESSION_VAR, Constants.SESSION_VAR + "=" + loginResponse.SessionId)
        
        // Pass the Base URL to a Property for the next OData adapter/call
        message.setProperty("SL_BaseURL", creds.baseUrl)
    } else {
        throw new RuntimeException("Service Layer Login failed: No SessionId in response.")
    }

    // Explicitly handle message body as stream to satisfy CI best practices (High-Priority Warning fix)
    def reader = message.getBody(java.io.Reader)
    if (reader != null) {
        reader.close()
    }

    return message
}

/*
 * =====================================================================================
 * PRIVATE HELPER METHODS
 * =====================================================================================
 */

/**
 * Performs the actual HTTP POST to Service Layer.
 */
private Map performServiceLayerLogin(Map creds) {
    def loginUrl = "${creds.baseUrl}/Login"
    def connection = new URL(loginUrl).openConnection() as HttpURLConnection
    
    connection.setRequestMethod("POST")
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setConnectTimeout(10000)
    connection.setReadTimeout(30000)
    connection.doOutput = true

    def loginBody = JsonOutput.toJson([
        UserName: creds.userName,
        Password: creds.password,
        CompanyDB: creds.companyDB
    ])

    connection.outputStream.withCloseable { it << loginBody }

    if (connection.responseCode == 200) {
        // Use a Reader for streaming the response body to JsonSlurper
        return connection.inputStream.withReader { reader ->
            new JsonSlurper().parse(reader)
        }
    } else {
        def errorText = connection.errorStream?.text ?: "No error details available"
        throw new RuntimeException("Service Layer Login HTTP ${connection.responseCode}: ${errorText}")
    }
}

/**
 * Extracts coordinates from Secure Store.
 */
private Map extractSLCredentials(SecureStoreService service) {
    def userCreds = getSecureCredential(service, Constants.LOGIN_CREDENTIALS)
    def companyCreds = getSecureCredential(service, Constants.COMPANY_CREDENTIALS)
    def urlCreds = getSecureCredential(service, Constants.BASE_URL)

    return [
        userName: userCreds.getUsername(),
        password: new String(userCreds.getPassword()),
        companyDB: new String(companyCreds.getPassword()),
        baseUrl: new String(urlCreds.getPassword())
    ]
}

private def getSecureCredential(SecureStoreService service, String alias) {
    def creds = service.getUserCredential(alias)
    if (!creds) throw new IllegalStateException("Credential '${alias}' not found.")
    return creds
}
