/**
 * W3PtoSAP_MapUOM.groovy
 * 
 * Dependencies:
 * - Misc/Mapper.groovy (Logic refactored and appended below)
 * - Misc/LoggerService.groovy (Logic refactored and appended below)
 */
import com.sap.gateway.ip.core.customdev.util.Message;
import groovy.util.XmlSlurper;
import groovy.util.slurpersupport.GPathResult;
import groovy.json.JsonSlurper;
import groovy.json.JsonBuilder;
import groovy.json.JsonOutput
import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService
import java.net.URL
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import java.security.cert.X509Certificate

class Constants {
    static final String W3P_CRED = "[W3P_CRED]"
    static final String W3P_URL = "[W3P_URL]"
    static final String STEP_NAME = "W3PtoSAP_MapUOM"
    static final Map MAPPING = [
        "Code"                : "fuomid",
        "Name"                : "fname",
        "InternationalSymbol" : "fuomid" // Mapping ID to symbol as common practice if symbol is missing
    ]
    static final Map CUSTOM_RULES = [:]

    // Logging Constant/s
    static final String LOG_RECID = "W3P"
}

/**
 * Agnostic Mapping Configuration.
 * Transforms source data structures (XML/JSON) into the target format
 * based on the definitions in the Constants class.
 * 
 * {@code
 * // Example usage in processData:
 * def mappedRecords = extractMappedRecords(payload, Constants.MAPPING, Constants.CUSTOM_RULES)
 * }
 */
def Message processData(Message message) {
    def logger = new LoggerService(messageLogFactory, message)
    
    // Extract W3P URL to initialize SOAP connection
    def credsMap = LoggerService.extractW3PCredentials()
    logger.setSoapConnection(new HTTPSOAPConnection(credsMap.baseUrl))

    def payload = message.getBody(java.lang.String);

    try {
        // Use the extracted mapping utility
        def mappedRecords = extractMappedRecords(
            payload, 
            Constants.MAPPING, 
            Constants.CUSTOM_RULES
        )
        
        if (mappedRecords.isEmpty() && payload.trim().startsWith("<")) {
            throw new RuntimeException("No records found or failed to parse XML <Result>.")
        }

        // 4. Wrap it in a uniform JSON structure
        def jsonResult = JsonOutput.toJson(mappedRecords)
        
        // Log Success using logBoth
        logger.logBoth(new LogRequest(
            stepName: Constants.STEP_NAME,
            title: Constants.LOG_RECID, // Used as recordid
            status: "OK",
            inputPayload: "Records Mapped: ${mappedRecords.size()}",
            outputPayload: JsonOutput.prettyPrint(jsonResult)
        ))
        
        // 5. Set the new JSON body back to the message
        message.setBody(jsonResult);
        
    } catch (Exception e) {
        // Log Error using logBoth
        logger.logBoth(new LogRequest(
            stepName: Constants.STEP_NAME,
            title: Constants.LOG_RECID, // Used as recordid
            status: "ERROR",
            inputPayload: "Original Payload length: ${payload?.length() ?: 0}",
            outputPayload: "Exception: ${e.message}\nStacktrace: ${e.stackTrace.take(10).join('\n')}"
        ))
        throw e
    }
    
    return message;
}



/**
 * Agnostic Payload Processor (Refactored from Mapper.groovy)
 * Dynamically handles both XML (SOAP) and JSON data formats.
 */
def extractMappedRecords(String payload, Map mapping, Map customRules = [:]) {
    def records = []
    
    if (payload.trim().startsWith("<")) {
        def soapParser = new XmlSlurper()
        soapParser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        def envelope = soapParser.parseText(payload)
        
        String innerXml = envelope.Body.callResponse.Result.text() ?: envelope.'**'.find { it.name() == 'Result' }?.text()
        
        if (!innerXml) return []

        def parser = new XmlSlurper()
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
        
        def root = parser.parseText(innerXml)
        records = root.data.record.collect { it }
    } else {
        def jsonSlurper = new JsonSlurper()
        def root = jsonSlurper.parseText(payload)
        
        if (root instanceof List) {
            records = root
        } else if (root.params?.data?.record) {
            records = root.params.data.record
        } else if (root.data?.record) {
            records = root.data.record
        } else if (root.record) {
            records = root.record
        } else {
            records = [root]
        }
    }

    return records.collect { record ->
        def result = [:]
        mapping.each { target, source ->
            def val = (record instanceof GPathResult) ? 
                      (record."$source".text() ?: record."$target".text()) : 
                      (record[source] ?: record[target])
            
            if (customRules.containsKey(target)) {
                result[target] = customRules[target](val)
            } else {
                result[target] = val ?: ""
            }
        }
        result
    }
}



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
                def credsMap = extractW3PCredentials()
                
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

    /**
     * Internal helper to extract W3P credentials from the SAP Secure Store.
     * Defined inside the class to ensure it's accessible to class methods.
     */
    private static Map extractW3PCredentials() {
        def service = ITApiFactory.getService(SecureStoreService.class, null)
        if (service == null) {
            throw new IllegalStateException("SecureStoreService is not available.")
        }

        // Extraction lambda/helper for internal use
        def getCreds = { String key ->
            def creds = service.getUserCredential(key)
            if (creds == null) {
                throw new IllegalStateException("Credential '${key}' not found in Security Material.")
            }
            return creds
        }

        def w3pCreds = getCreds(Constants.W3P_CRED)
        def w3pUrlCreds = getCreds(Constants.W3P_URL)

        return [
            id: w3pCreds.getUsername(),
            key: new String(w3pCreds.getPassword()),
            baseUrl: new String(w3pUrlCreds.getPassword())
        ]
    }

}


/**
 * SOAPConnection.groovy
 * 
 * Dependencies:
 * - None
 */
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
