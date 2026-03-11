import java.net.URL
import java.net.HttpURLConnection
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService
import com.sap.it.api.securestore.UserCredential
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import java.security.cert.X509Certificate


class Constants {
    static final String STEP_NAME = "W3P GET_WAREHOUSE"
    static final String ACTION = "GET_WAREHOUSE"
    static final String W3P_CRED = "W3P_CRED"
    static final String W3P_URL = "W3P_URL"
}

def Message processData(Message message) {
    def logger = new LoggerService(messageLogFactory, message)
    
    // 1. Extract W3P Credentials from Secure Store
    // Using the logic from SC_ExtractW3PCredentials.groovy to get Id, Key and BaseUrl
    try {
        def service = ITApiFactory.getService(SecureStoreService.class, null)
        
        def w3pCreds = getSecureCredential(service, Constants.W3P_CRED)
        def w3pUrlCreds = getSecureCredential(service, Constants.W3P_URL)

        String w3pId = w3pCreds.getUsername() // Using User field for ID
        String w3pKey = new String(w3pCreds.getPassword())
        String baseUrl = new String(w3pUrlCreds.getPassword())

        if (!w3pId || !w3pKey || !baseUrl) {
            throw new RuntimeException("Missing W3P Configuration in Secure Store (${Constants.W3P_CRED}/${Constants.W3P_URL}).")
        }

        // 2. Initialize the SOAP Connection
        def soapConn = new HTTPSOAPConnection(baseUrl)
            .setId(w3pId)
            .setKey(w3pKey)

        // 3. Prepare the Request Body
        def request = new SOAPRequestBody(
            action: Constants.ACTION
        )

        // 4. Execute the SOAP Call
        String responseXml = soapConn.post(request)
        
        logger.logInternal(new LogRequest (
            title: "W3P SOAP Response",
            stepName: Constants.STEP_NAME,
            status: "SUCCESS",
            payload: responseXml
        ))
        
        // 5. Update the Message Body with the XML response as a String
        message.setBody(responseXml)

    } catch (Exception e) {
        logger.logInternal(new LogRequest (
            title: "W3P SOAP Response",
            stepName: Constants.STEP_NAME,
            status: "ERROR",
            payload: "Exception: ${e.message}\nStacktrace: ${e.stackTrace.take(5).join('\n')}"
        ))
        throw e
    }
    
    return message
}


/*
** Note that this is an HTTP Connection by default. For a more secure connection, please use HTTPS.
** This is only intended for testing.
*/

/**
 * Represents the configuration for an HTTP SOAP request.
 * Acts as a Data Transfer Object (DTO) to consolidate URL, payload, and headers.
 */
class SOAPRequestBody {
    /** The Map of parameters to be sent in the SOAP body */
    String action
    Map<String, Object> filters = [:]
    /** Optional XML string for the record/payload (used for POST/PUT) */
    String record = ""

    /** Request headers (defaults to text/xml) */
    Map<String, String> requestProperty = [
        'Content-Type': 'application/xml'
    ]
}

class HTTPSOAPConnection {

    private String baseUrl
    private String id
    private String key

    public HTTPSOAPConnection(String baseUrl) {
        this.baseUrl = baseUrl
    }

    public def setId(String id) {
        this.id = id
        return this
    }

    public def setKey(String key) {
        this.key = key
        return this
    }

    public def setBaseUrl(String url) {
        this.baseUrl = url
        return this
    }

    private HttpURLConnection connect() {
        if (this.baseUrl == null || this.baseUrl == '') {
            throw new IllegalStateException('Connection URL cannot be empty. Please set the baseUrl')
        }

        URL endpoint = new URL(this.baseUrl)
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection()

        if (connection instanceof HttpsURLConnection) {
            disableSSL()
        }

        return connection
    }

    /**
     * Executes a SOAP POST request.
     * @param request The configuration object containing the XML action, credentials, and filters.
     * @return The raw XML response body.
     */
    public String post(SOAPRequestBody request) {
        def con = connect()
        try {
            con.setRequestMethod('POST')
            con.doOutput = true
            
            for (prop in request.requestProperty) {
                con.setRequestProperty(prop.key, prop.value)
            }

            String soapEnvelope = buildEnvelope(request)

            con.outputStream.withCloseable { it << soapEnvelope }

            if (con.responseCode >= 200 && con.responseCode < 300) {
                return con.inputStream.text
            } else {
                def errorText = con.errorStream?.text ?: "No error details provided"
                throw new RuntimeException("SOAP request failed to ${this.baseUrl}. HTTP ${con.responseCode}: $errorText")
            }
        } finally {
            con.disconnect()
        }
    }

    private String buildEnvelope(SOAPRequestBody request) {
        String dataContent = ""
        
        if (request.record) {
            dataContent = "<record>${request.record}</record>"
        } else if (request.filters) {
            dataContent = """<filter>
                                ${request.filters.collect { k, v -> "<$k>$v</$k>" }.join('\n                         ')}
                            </filter>"""
        }

        return """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
            <soapenv:Header/>
            <soapenv:Body>
                <call>
                    <action>${request.action}</action>
                    <params>
                        <id>
                            <fw3p_id>${this.id}</fw3p_id>
                            <fw3p_key>${this.key}</fw3p_key>
                        </id>
                        <data>
                            $dataContent
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
}



/*
** This service handles dual-layered logging for SAP Cloud Integration (iFlows).
** logInternal: Adds an attachment to the SAP Message Processing Log (MPL) for debugging in the SAP Monitor.
** logExternal: Prints a JSON-structured log to STDOUT for external aggregation and analysis (e.g., Kibana).
** logBoth: Executes both internal and external logging simultaneously.
*/

/**
 * Data Transfer Object (DTO) for structured logging.
 * Consolidates step information, status, and payload.
 */
class LogRequest {
    /** The title of the log entry / attachment */
    String title
    /** The name of the process step being logged */
    String stepName
    /** The status of the step (e.g., Success, Error, Info) */
    String status
    /** The content or object to be logged */
    Object payload
    /** Optional media type for the internal attachment (default: text/plain) */
    String mediaType = "text/plain"
}

/**
 * Handles internal and external logging for SAP Cloud Integration.
 */
class LoggerService {
    def messageLog
    def correlationId

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
     */
    def logInternal(LogRequest request) {
        if (this.messageLog != null && request.payload != null) {
            String enrichedPayload = "Step: ${request.stepName}\nTitle: ${request.title ?: 'N/A'}\nStatus: ${request.status}\n\n${request.payload.toString()}"
            this.messageLog.addAttachmentAsString(request.title ?: request.stepName, enrichedPayload, request.mediaType)
        }
    }

    /**
     * Prints a JSON-structured log to STDOUT for external aggregation (Kibana).
     */
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

    /**
     * Triggers both internal and external logging using a LogRequest object.
     */
    def logBoth(LogRequest request) {
        logInternal(request)
        logExternal(request)
    }

    /**
     * Overloaded method for quick logging without creating a LogRequest object.
     */
    def logBoth(String title, String status, Object payload) {
        def req = new LogRequest(stepName: title, status: status, payload: payload)
        logInternal(req)
        logExternal(req)
    }
}


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
 *
 * @author Ram Alin
 * @version 1.0.0
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