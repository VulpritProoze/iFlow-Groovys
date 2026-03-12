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
 * SC_ServiceLayerLogin.groovy
 * 
 * Centralized script for SAP Business One Service Layer authentication.
 * 
 * 1. processData: Performs extraction, HTTP login, and global token storage in one step.
 * 2. fetchLoginToken: Attaches the stored token as a Cookie header for subsequent calls.
 *
 * <p>Example usage in a consumer iFlow (extracting the token):</p>
 * <pre>
 * {@code
 *  // In a Script Task just before a Service Layer OData call:
 *  import com.sap.gateway.ip.core.customdev.util.Message
 *
 *  def Message processData(Message message) {
 *      def token = message.getProperty("B1SESSION")  // SessionID
 *
 *      ... other code
 *  }
 * }
 * </pre>
 *
 */

/**
 * SINGLE METHOD LOGIN FLOW
 * 1. Extracts credentials from Secure Store.
 * 2. Performs HTTP POST to Service Layer /Login.
 * 3. Saves SessionId to Global Variable.
 */
def Message processData(Message message) {
    def secureStore = ITApiFactory.getService(SecureStoreService.class, null)
    def creds = extractSLCredentials(secureStore)
    
    // Perform Login Request
    def loginResponse = performServiceLayerLogin(creds)
    
    // Store Session Token and URL
    if (loginResponse.SessionId) {
        // Prepare a structured JSON body to pass to the next iFlow
        def responseBody = JsonOutput.toJson([
            SessionId: loginResponse.SessionId,
            BaseUrl: creds.baseUrl,
            SessionCookie: "${Constants.SESSION_VAR}=${loginResponse.SessionId}"
        ])
        
        message.setBody(responseBody)
        message.setHeader("Content-Type", "application/json")
    } else {
        throw new RuntimeException("Service Layer Login failed: No SessionId in response.")
    }

    // Since we just SET the body, we don't need to close a reader here 
    // as we've overwritten the previous stream with our new JSON string.
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