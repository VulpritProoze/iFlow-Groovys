/**
 * SC_ServiceLayerLoginToProperty.groovy
 * 
 * Dependencies:
 * - LoggerService.groovy
 */
import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import java.security.cert.X509Certificate
import java.net.URL
import java.net.HttpURLConnection

/**
 * Global constants. May be present on multiple files
 */
class Constants {
    static final String LOGIN_CREDENTIALS = "[SL_LOGIN_CRED]" 
    static final String COMPANY_CREDENTIALS = "[SL_COMPANY_CRED]" 
    static final String BASE_URL = "[SL_BaseUrl]" 
    static final String SESSION_VAR = "B1SESSION"
    static final String SESSION_VAR_PROP_NAME = "[SL_Session]"
    static final String BASE_URL_PROP_NAME = "[SL_BaseURL]"

    // Logging
    static final String W3P_CRED = "[W3P_CRED]"
    static final String W3P_URL = "[W3P_URL]"
    static final String LOG_RECID = "W3P"
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
    def logger = new LoggerService(messageLogFactory, message)
    
    try {
        def credsResult = extractSLCredentialsFromSecureStore(secureStore)
        if (credsResult.status != 1) {
            logger.logBoth(new LogRequest(stepName: "SL Login to Property", title: Constants.LOG_RECID, status: "ERROR", inputPayload: "Store Lookup", outputPayload: "Failed: ${credsResult.message}"))
            return message
        }
        
        // Perform Login Request
        def loginResponseResult = performServiceLayerLogin(credsResult)
        if (loginResponseResult.status != 1) {
            logger.logBoth(new LogRequest(stepName: "SL Login to Property", title: Constants.LOG_RECID, status: "ERROR", inputPayload: "SL Login Request", outputPayload: "Failed: ${loginResponseResult.message}"))
            return message
        }
        
        def loginResponse = loginResponseResult.payload
        
        // Store Session Token and URL as Properties
        if (loginResponse.SessionId) {
            // Store in property using format: B1SESSION=SessionID
            message.setProperty(Constants.SESSION_VAR_PROP_NAME, Constants.SESSION_VAR + "=" + loginResponse.SessionId)
            
            // Pass the Base URL to a Property for the next OData adapter/call
            message.setProperty(Constants.BASE_URL_PROP_NAME, credsResult.baseUrl)
            
            logger.logBoth(new LogRequest(stepName: "SL Login to Property", title: Constants.LOG_RECID, status: "OK", inputPayload: "User: ${credsResult.userName} URL: ${credsResult.baseUrl}", outputPayload: "Success: B1SESSION acquired"))
        } else {
            logger.logBoth(new LogRequest(stepName: "SL Login to Property", title: Constants.LOG_RECID, status: "ERROR", inputPayload: "SL Login Validation", outputPayload: "Failed: No SessionId in response"))
        }
    } catch (Exception e) {
        logger.logBoth(new LogRequest(stepName: "SL Login to Property", title: Constants.LOG_RECID, status: "ERROR", inputPayload: "SL Authentication", outputPayload: "Exception: ${e.message}"))
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
    try {
        def loginUrl = "${creds.baseUrl}/Login"
        def connection = new URL(loginUrl).openConnection() as HttpURLConnection
        
        if (connection instanceof HttpsURLConnection) {
            disableSSL()
        }

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
            def payload = connection.inputStream.withReader { reader ->
                new JsonSlurper().parse(reader)
            }
            return [status: 1, message: "Success", payload: payload]
        } else {
            def errorText = connection.errorStream?.text ?: "No error details available"
            return [status: -1, message: "Service Layer Login HTTP ${connection.responseCode}: ${errorText}"]
        }
    } catch (Exception e) {
        return [status: -1, message: "Service Layer Login Exception: ${e.message}"]
    }
}

/**
 * Extracts coordinates from Secure Store.
 */
private Map extractSLCredentialsFromSecureStore(SecureStoreService service) {
    try {
        def userCreds = getSecureCredential(service, Constants.LOGIN_CREDENTIALS)
        def companyCreds = getSecureCredential(service, Constants.COMPANY_CREDENTIALS)
        def urlCreds = getSecureCredential(service, Constants.BASE_URL)

        return [
            status: 1,
            message: "Success",
            userName: userCreds.getUsername(),
            password: new String(userCreds.getPassword()),
            companyDB: new String(companyCreds.getPassword()),
            baseUrl: new String(urlCreds.getPassword())
        ]
    } catch (Exception e) {
        return [status: -1, message: "Error extracting credentials: ${e.message}"]
    }
}

private def getSecureCredential(SecureStoreService service, String alias) {
    def creds = service.getUserCredential(alias)
    if (!creds) throw new IllegalStateException("Credential '${alias}' not found.")
    return creds
}

private void disableSSL() {
    TrustManager[] trustAllCerts = [
        new X509TrustManager() {

        X509Certificate[] getAcceptedIssuers() { return null }
        void checkClientTrusted(X509Certificate[] certs, String authType) { }
        void checkServerTrusted(X509Certificate[] certs, String authType) { }

        }
    ] as TrustManager[]

    SSLContext sc = SSLContext.getInstance('TLS')
    sc.init(null, trustAllCerts, new java.security.SecureRandom())
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory())

    HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {

        boolean verify(String hostname, SSLSession session) {
            return true
        }

    })
}

/**
 * LoggerService.groovy
 * 
 * Dependencies:
 * - ExtractW3PCredentials.groovy (as private method)
 */
/*
** This service handles dual-layered logging for SAP Cloud Integration (iFlows).
** logInternal: Adds an attachment to the SAP Message Processing Log (MPL) for debugging in the SAP Monitor.
** logProcess: Sends a SOAP-structured log to an external service (W3P) for process tracking.
** logBoth: Executes both internal and process logging simultaneously.
*/


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
 * logger.logInternal(new LogRequest(stepName: "Step1", title: "WAREHOUSE", status: "OK", inputPayload: "data"))
 * logger.logBoth("WAREHOUSE", "ProcessStep", "OK", input, output)
 */
class LoggerService {
    def messageLog
    def correlationId

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
     * @return Map with status (1: success, 0: validation error, -1: system/server error), message, and payload.
     */
    def logProcess(LogRequest request) {
        // Validate Status
        String status = request.status?.toUpperCase()
        if (!(status in VALID_STATUSES)) {
            return [status: 0, message: "LoggerService: Invalid status '${status}'. Expected one of: ${VALID_STATUSES}"]
        }

        // Validate Record ID (derived from title)
        String recordId = request.title?.toUpperCase()
        if (!(recordId in VALID_RECORD_IDS)) {
            return [status: 0, message: "LoggerService: Invalid recordId '${recordId}' (derived from title). Expected one of: ${VALID_RECORD_IDS}"]
        }

        try {
            // Credentials extraction handled automatically via Secure Store
            def credsMap = extractW3PCredentials()
            if (credsMap.status != 1) {
                return credsMap
            }

            String dataContent = """
                    <fstatus_flag>${status}</fstatus_flag>
                    <frecordid>${recordId}</frecordid>
                    <finput_param>Step: ${request.stepName}\nTitle: ${request.title}\n\n${escapeXml(request.inputPayload)}</finput_param>
                    <foutput_param>${escapeXml(request.outputPayload)}</foutput_param>
            """.trim()

            String soapEnvelope = buildSoapEnvelope("POST_LOG", credsMap.id, credsMap.key, dataContent)

            return postSoap(credsMap.baseUrl, soapEnvelope)
        } catch (Exception e) {
            return [status: -1, message: "LoggerService: logProcess error: ${e.message}"]
        }
    }

    /**
     * Internal helper to escape XML special characters in payloads.
     */
    private String escapeXml(Object payload) {
        if (payload == null) return "null"
        return payload.toString()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private def postSoap(String baseUrl, String soapEnvelope) {
        try {
            if (baseUrl == null || baseUrl == '') {
                return [status: -1, message: "Connection URL cannot be empty. Please set the baseUrl"]
            }

            HttpURLConnection con = (HttpURLConnection) new URL(baseUrl).openConnection()
            if (con instanceof HttpsURLConnection) {
                disableSSL()
            }

            try {
                con.setRequestMethod('POST')
                con.doOutput = true
                con.setRequestProperty('Content-Type', 'application/xml')
                con.outputStream.withCloseable { it << soapEnvelope }

                if (con.responseCode >= 200 && con.responseCode < 300) {
                    return [status: 1, message: "Success", payload: con.inputStream.text]
                }

                def errorText = con.errorStream?.text ?: "No error details provided"
                return [status: -1, message: "SOAP request failed to ${baseUrl}. HTTP ${con.responseCode}: $errorText"]
            } finally {
                con.disconnect()
            }
        } catch (Exception e) {
            return [status: -1, message: "SOAP exception: ${e.message}"]
        }
    }

    private String buildSoapEnvelope(String action, String id, String key, String dataContent) {
        return """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
            <soapenv:Header/>
            <soapenv:Body>
                <call>
                    <action>${action}</action>
                    <params>
                        <id>
                            <fw3p_id>${id}</fw3p_id>
                            <fw3p_key>${key}</fw3p_key>
                        </id>
                        <data>
                            ${dataContent ?: ''}
                        </data>
                    </params>
                </call>
            </soapenv:Body>
        </soapenv:Envelope>
        """
    }

    private void disableSSL() {
        TrustManager[] trustAllCerts = [
            new X509TrustManager() {
                X509Certificate[] getAcceptedIssuers() { return null }
                void checkClientTrusted(X509Certificate[] certs, String authType) { }
                void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }
        ] as TrustManager[]

        SSLContext sc = SSLContext.getInstance('TLS')
        sc.init(null, trustAllCerts, new java.security.SecureRandom())
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory())

        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            boolean verify(String hostname, SSLSession session) { return true }
        })
    }

    /**
     * Internal helper to extract W3P credentials from the SAP Secure Store.
     * Defined inside the class to ensure it's accessible to class methods.
     * @return Map with credentials or error structure.
     */
    private static Map extractW3PCredentials() {
        try {
            def service = ITApiFactory.getService(SecureStoreService.class, null)
            if (service == null) {
                return [status: -1, message: "SecureStoreService is not available."]
            }

            // Extraction lambda/helper for internal use
            def getCreds = { String key ->
                def creds = service.getUserCredential(key)
                if (creds == null) {
                    return null
                }
                return creds
            }

            def w3pCreds = getCreds(Constants.W3P_CRED)
            if (w3pCreds == null) return [status: -1, message: "Credential '${Constants.W3P_CRED}' not found in Security Material."]
            
            def w3pUrlCreds = getCreds(Constants.W3P_URL)
            if (w3pUrlCreds == null) return [status: -1, message: "Credential '${Constants.W3P_URL}' not found in Security Material."]

            return [
                status: 1,
                id: w3pCreds.getUsername(),
                key: new String(w3pCreds.getPassword()),
                baseUrl: new String(w3pUrlCreds.getPassword())
            ]
        } catch (Exception e) {
            return [status: -1, message: "Error extracting credentials: ${e.message}"]
        }
    }

}
