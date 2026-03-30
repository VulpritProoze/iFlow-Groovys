/**
 * AgnosticSAPPoster.groovy
 * 
 * Dependencies:
 * - Misc/ExtractSLCredentials.groovy (Helper methods appended/integrated)
 * - Misc/ODataConnection.groovy (Integrated logic)
 * - Misc/LoggerService.groovy (Standalone implementation appended below)
 * - Misc/FormatBatchResponse.groovy (Integrated logic)
 */
import java.net.URL
import java.net.HttpURLConnection
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import java.security.cert.X509Certificate 
import com.sap.gateway.ip.core.customdev.util.Message
import java.util.regex.Matcher
import java.util.regex.Pattern
import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService

class Constants {
    static final String STEP_NAME = "[StepName]"
    static final String SESSION_VAR_PROP_NAME = "[B1SESSION]"
    static final String BASE_URL_PROP_NAME = "[SL_BaseURL]"
    /** The relative OData endpoint for the entity (e.g., /Warehouses) */
    static final String ENTITY_ENDPOINT = "/[Endpoint]"

    // For logging
    static final String W3P_CRED = "[W3P_CRED]"
    static final String W3P_URL = "[W3P_URL]"

    // Logging Constant/s
    static final String LOG_RECID = "W3P"
}


/**
 * Process the incoming response and post all contained records to SAP in one bulk request.
 *
 * Parses the message body (SOAP or JSON), extracts all <record> elements, converts them
 * to JSON, and submits them as a single multipart OData $batch (changeset) POST.
 * Expects `SESSION_VAR_PROP_NAME` and `BASE_URL_PROP_NAME` to be available as message
 * properties for session and service endpoint. Logs summary and details of the batch result.
 *
 * Note: This code is not customizable.
 */
def Message processData(Message message) {
    def logger = new LoggerService(messageLogFactory, message)
    try {
        logger.injectW3PCredentials()
    } catch (Exception e) {
        logger.logInternal(new LogRequest(stepName: "${Constants.STEP_NAME}_LOGGER_FAILURE", title: Constants.LOG_RECID, status: "ERROR", inputPayload: 'Nothing yet.', outputPayload: "LoggerService failed: ${e.message}"))
    }

    def payload = ''
    def reader = message.getBody(java.io.Reader)
    if (reader != null) {
        try {
            payload = reader.getText() ?: ''
        } finally {
            try { reader.close() } catch (e) { /* ignore close errors */ }
        }
    } else {
        payload = (message.getBody(java.lang.String) ?: '')
    }

    def _p = payload?.toString()?.trim()
    if (!_p || _p == '[]' || _p == 'null' || _p == '') {
        logger.logBoth(new LogRequest(stepName: Constants.STEP_NAME, title: Constants.LOG_RECID, status: "OK", inputPayload: payload, outputPayload: "Mapping payload is empty. Skipping POST Requests"))
        return message
    }

    def sapCreds = extractSLCredentials(message)
    if (sapCreds.status != 1) {
        logger.logInternal(new LogRequest(stepName: "CREDENTIAL_FAILURE", title: Constants.LOG_RECID, status: "ERROR", inputPayload: payload, outputPayload: sapCreds.message))
        message.setBody(JsonOutput.toJson([]))
        return message
    }

    def sessionCookie = sapCreds.sessionCookie
    def baseUrl = sapCreds.baseUrl

    def recordList = new JsonSlurper().parseText(payload) 

    // Build the multipart batch request from the record list
    def request = sapRequestBatchBuilder(recordList)

    def conn = new HTTPODataConnection(baseUrl).setSessionCookie(sessionCookie)

    try {
        // Your connection class 'post' method writes request.payload to the output stream
        conn.post(request)
        
        // Use the raw body if it exists, otherwise fallback to empty string
        String rawBody = conn.getBody() ?: ""
        
        def formattedResponse = formatBatchResponse(rawBody)
        if (formattedResponse.status != 1) {
            logger.logBoth(new LogRequest(stepName: Constants.STEP_NAME, title: Constants.LOG_RECID, status: "ERROR", inputPayload: request.payload, outputPayload: "Batch Parsing Error: ${formattedResponse.message}\n\nOriginal Body:\n${rawBody}"))
            message.setBody(rawBody)
            return message
        }

        logger.logBoth(new LogRequest(stepName: Constants.STEP_NAME, title: Constants.LOG_RECID, status: "OK", inputPayload: request.payload, outputPayload: "Records processed: ${recordList.size()}\n\nResponse:\n${formattedResponse.payload}"))
        
        message.setBody(rawBody)
    } catch (Exception e) {
        logger.logBoth(new LogRequest(stepName: Constants.STEP_NAME, title: Constants.LOG_RECID, status: "ERROR", inputPayload: request.payload, outputPayload: e.getMessage()))
    }

    return message
}


/**
 * Build an OData $batch multipart request from a list of records.
 * Returns an `ODataRequestBody` ready to be posted by `HTTPODataConnection.post()`.
 */
def sapRequestBatchBuilder(recordList) {
    String batchId = "batch_" + java.util.UUID.randomUUID().toString()
    String changesetId = "changeset_" + java.util.UUID.randomUUID().toString()

    StringBuilder batchBody = new StringBuilder()
    batchBody.append("--${batchId}\r\n")
    batchBody.append("Content-Type: multipart/mixed; boundary=${changesetId}\r\n\r\n")

    recordList.each { record ->
        batchBody.append("--${changesetId}\r\n")
        batchBody.append("Content-Type: application/http\r\n")
        batchBody.append("Content-Transfer-Encoding: binary\r\n\r\n")
        batchBody.append("POST /b1s/v1${Constants.ENTITY_ENDPOINT}\r\n")
        batchBody.append("Content-Type: application/json\r\n\r\n")
        batchBody.append(JsonOutput.toJson(record)).append("\r\n\r\n")
    }

    batchBody.append("--${changesetId}--\r\n")
    batchBody.append("--${batchId}--")

    return new ODataRequestBody(
        url: "/\$batch",
        payload: batchBody.toString(),
        requestProperty: [
            'Content-Type': "multipart/mixed; boundary=${batchId}"
        ]
    )
}


/**
 * Represents the configuration for an HTTP OData request.
 * Acts as a Data Transfer Object (DTO) to consolidate URL, payload, and headers.
 */
class ODataRequestBody {

    /*
    **  The relative endpoint URL to be appended to the base URL 
    **  Note: Add filters to the url if needed (e.g. /Items?$filter=ItemCode%20eq%20'i001')
    */
    String url
    
    /** String of payload to be sent as body */
    String payload
    
    /** Request headers (defaults to application/json) */
    Map<String, String> requestProperty = [
        'Content-Type': 'application/json'
    ]
    
    /** Whether to automatically append the session cookie to the request */
    boolean isPassSession = true
}


class HTTPODataConnection {

    private String sessionCookie
    private String baseUrl
    private Object responseBody

    public HTTPODataConnection(String baseUrl) {
        this.baseUrl = baseUrl
    }

    public def setSessionCookie(String cookie) {
        this.sessionCookie = cookie
        return this
    }

    public def setBaseUrl(String url) {
        baseUrl = url
        return this
    }

    /**
     * Extracts the first element if the internal responseBody is a List, otherwise returns an empty Map.
     * Updates the internal reference to allow chaining.
     * @return the current instance for chaining.
     */
    public def parse() {
        if (this.responseBody instanceof List && !this.responseBody.isEmpty()) {
            this.responseBody = this.responseBody[0]
        } else if (this.responseBody instanceof Map && this.responseBody.value instanceof List) {
            this.responseBody = this.responseBody.value[0]
        }
        return this
    }

    /**
     * Converts the current internal responseBody to a JSON string.
     * @return A JSON formatted String.
     */
    public def toJson() {
        if (!this.responseBody) return "{}"
        return JsonOutput.toJson(this.responseBody)
    }

    /**
     * Returns the raw response body.
     */
    public Object getBody() {
        return this.responseBody
    }

    public HttpURLConnection connect(String url) {
        if (url == null || url == '') {
            return null
        }

        URL endpoint = new URL(baseUrl + url)
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection()

        if (connection instanceof HttpsURLConnection) {
            disableSSL()
        }

        return connection
    }

    /**
     * Executes an HTTP GET request.
     * @param request The configuration object containing the URL and headers.
     * @return Result Map Structure.
     */
    public def get(ODataRequestBody request) {
        try {
            def con = connect(request.url)
            if (con == null) {
                return [status: -1, message: "Connection URL cannot be empty. Please set the baseUrl for this connection"]
            }
            con.setRequestMethod('GET')
            for (prop in request.requestProperty) {
                con.setRequestProperty(prop.key, prop.value)
            }

            if (request.isPassSession) {
                if (!sessionCookie) {
                    return [status: -1, message: "Missing sessionCookie for Connection"]
                }
                con.setRequestProperty('Cookie', sessionCookie)
            }

            if (request.payload) {
                con.doOutput = true
                con.outputStream.withCloseable { it << request.payload }
            }

            if (con.responseCode >= 200 && con.responseCode < 300) {
                def result = new JsonSlurper().parse(con.inputStream.newReader())
                return [status: 1, message: "Success", payload: result]
            } else {
                def errorText = con.errorStream?.text ?: "No error details provided"
                return [status: -1, message: "GET failed. HTTP ${con.responseCode}: $errorText"]
            }
        } catch (Exception e) {
            return [status: -1, message: "GET exception: ${e.message}"]
        }
    }

    /**
     * Executes an HTTP PUT request.
     * @param request The configuration object containing the URL, payload, and headers.
     * @return Result Map Structure.
     */
    public def put(ODataRequestBody request) {
        try {
            def con = connect(request.url)
            if (con == null) {
                return [status: -1, message: "Connection URL cannot be empty. Please set the baseUrl for this connection"]
            }
            con.setRequestMethod('PUT')
            con.doOutput = true
            for (prop in request.requestProperty) {
                con.setRequestProperty(prop.key, prop.value)
            }

            if (request.isPassSession) {
                if (!sessionCookie) {
                    return [status: -1, message: "Missing sessionCookie for Connection"]
                }
                con.setRequestProperty('Cookie', sessionCookie)
            }

            if (request.payload) {
                con.outputStream.withCloseable { it << request.payload }
            }

            if (con.responseCode >= 200 && con.responseCode < 300) {
                def result = new JsonSlurper().parse(con.inputStream.newReader())
                return [status: 1, message: "Success", payload: result]
            } else {
                def errorText = con.errorStream?.text ?: "No error details provided"
                return [status: -1, message: "PUT failed. HTTP ${con.responseCode}: $errorText"]
            }
        } catch (Exception e) {
            return [status: -1, message: "PUT exception: ${e.message}"]
        }
    }

    /**
     * Executes an HTTP DELETE request.
     * @param request The configuration object containing the URL and headers.
     * @return Result Map Structure.
     */
    public def delete(ODataRequestBody request) {
        try {
            def con = connect(request.url)
            if (con == null) {
                return [status: -1, message: "Connection URL cannot be empty. Please set the baseUrl for this connection"]
            }

            con.setRequestMethod('DELETE')
            for (prop in request.requestProperty) {
                con.setRequestProperty(prop.key, prop.value)
            }

            if (request.isPassSession) {
                if (!sessionCookie) {
                    return [status: -1, message: "Missing sessionCookie for Connection"]
                }
                con.setRequestProperty('Cookie', sessionCookie)
            }

            if (request.payload) {
                con.doOutput = true
                con.outputStream.withCloseable { it << request.payload }
            }

            if (con.responseCode >= 200 && con.responseCode < 300) {
                if (con.responseCode == 204) return [status: 1, message: "Success"]
                def result = new JsonSlurper().parse(con.inputStream.newReader())
                return [status: 1, message: "Success", payload: result]
            } else {
                def errorText = con.errorStream?.text ?: "No error details provided"
                return [status: -1, message: "DELETE failed. HTTP ${con.responseCode}: $errorText"]
            }
        } catch (Exception e) {
            return [status: -1, message: "DELETE exception: ${e.message}"]
        }
    }

    /**
     * Executes an HTTP POST request.
     * @param request The configuration object containing the URL, payload, and headers.
     * @return Result Map Structure.
     */
    public def post(ODataRequestBody request) {
        try {
            def con = connect(request.url)
            if (con == null) {
                return [status: -1, message: "Connection URL cannot be empty. Please set the baseUrl for this connection"]
            }
            
            con.setRequestMethod('POST')
            con.setDoOutput(true)
            for (prop in request.requestProperty) {
                con.setRequestProperty(prop.key, prop.value)
            }

            if (request.isPassSession) {
                if (!sessionCookie) {
                    return [status: -1, message: "Missing sessionCookie for Connection"]
                }
                con.setRequestProperty('Cookie', sessionCookie)
            }

            if (request.payload) {
                con.outputStream.withCloseable { it << request.payload }
            }

            int responseCode = con.responseCode
            if (responseCode >= 200 && responseCode < 300) {
                String contentType = con.getHeaderField("Content-Type")
                if (contentType && contentType.contains("multipart/mixed")) {
                    this.responseBody = con.inputStream.text
                } else {
                    this.responseBody = new JsonSlurper().parse(con.inputStream.newReader())
                }
                return [status: 1, message: "Success", payload: this.responseBody]
            } else {
                def errorText = con.errorStream?.text ?: "No error details provided"
                return [status: -1, message: "POST failed. HTTP $responseCode: $errorText"]
            }
        } catch (Exception e) {
            return [status: -1, message: "POST exception: ${e.message}"]
        }
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
}



/**
 * ExtractSLCredentials.groovy
 * 
 * Dependencies:
 * - None
 */

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




/**
 * LoggerService.groovy
 * 
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
    private String w3pId = null
    private String w3pKey = null
    private String w3pBaseUrl = null

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
        if (w3pId == null || w3pBaseUrl == null || w3pKey == null) {
            return [status: 0, message: "Missing W3P Credentials. LogProcess cannot be used"]
        }

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
            String dataContent = """
                    <fstatus_flag>${status}</fstatus_flag>
                    <frecordid>${recordId}</frecordid>
                    <finput_param>Step: ${request.stepName}\nTitle: ${request.title}\n\n${escapeXml(request.inputPayload)}</finput_param>
                    <foutput_param>${escapeXml(request.outputPayload)}</foutput_param>
            """.trim()
            String soapEnvelope = buildSoapEnvelope("POST_LOG", this.w3pId, this.w3pKey, dataContent)

            return postSoap(this.w3pBaseUrl, soapEnvelope)
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
     * Internal helper to inject W3P credentials.
     * Defined inside the class to ensure it's accessible to class methods.
     * @return Map with credentials or error structure.
     */
    private def injectW3PCredentials() {
        def service = ITApiFactory.getService(SecureStoreService.class, null)
        if (service == null) {
            throw IllegalStateException("SecureStoreService is not available.")
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
        if (w3pCreds == null) throw IllegalStateException("Credential '${Constants.W3P_CRED}' not found in Security Material.")
        
        def w3pUrlCreds = getCreds(Constants.W3P_URL)
        if (w3pUrlCreds == null) throw IllegalStateException("Credential '${Constants.W3P_URL}' not found in Security Material.")

        // Set private instance variables; do NOT return credentials in the response
        this.w3pId = w3pCreds.getUsername()
        this.w3pKey = new String(w3pCreds.getPassword())
        this.w3pBaseUrl = new String(w3pUrlCreds.getPassword())

        return this
    }
}




/**
 * Reusable utility to parse OData $batch multipart responses and convert them into a 
 * single, human-readable JSON array of results.
 * 
 * <p>Example usage in another script:</p>
 * <pre>
 * {@code
 *  String rawResponse = conn.post(batchRequest).toJson()
 *  def parsedResponse = formatBatchResponse(rawResponse)
 *  if (parsedResponse.status == 1) {
 *      println(parsedResponse.payload)
 *  } }
 * </pre>
 * 
 * @param body The raw multipart/mixed string from the OData response
 * @return Map A result map with status, message, and formatted JSON payload
 */
def formatBatchResponse(String body) {
    def results = []

    if (!body || !body.contains("--batchresponse")) {
        return [status: 1, message: "Not a batch response", payload: body] // Not a batch response, return as is safely
    }

    try {
        // 1. Split by boundary (find the first boundary to determine the pattern)
        String boundary = ""
        // Account for leading whitespace or quotes if they somehow leak in
        def boundaryMatcher = (body =~ /--batchresponse_[a-zA-Z0-9-]+/)
        if (boundaryMatcher.find()) {
            boundary = boundaryMatcher.group()
        }

        if (!boundary) return [status: 1, message: "Boundary not found", payload: body]

        // 2. Extract individual response parts
        def parts = body.split(java.util.regex.Pattern.quote(boundary))
        
        parts.each { part ->
            if (part.contains("HTTP/1.1")) {
                def result = [:]
                
                // Extract HTTP Status Code (e.g., 201 Created or 400 Bad Request)
                def statusMatcher = (part =~ /HTTP\/1\.1 (\d{3}) .*/)
                if (statusMatcher.find()) {
                    result.statusCode = statusMatcher.group(1).toInteger()
                }

                // Extract JSON Body if exists
                if (part.contains("{")) {
                    int jsonStart = part.indexOf("{")
                    int jsonEnd = part.lastIndexOf("}") + 1
                    String jsonString = part.substring(jsonStart, jsonEnd)
                    
                    try {
                        result.body = new JsonSlurper().parseText(jsonString)
                    } catch (Exception e) {
                        result.body = jsonString // Fallback to raw text
                    }
                }
                
                if (result.statusCode) results << result
            }
        }

        // 3. Format the final output as a pretty JSON string
        String formattedPayload = JsonOutput.prettyPrint(JsonOutput.toJson([
            batchSummary: [
                totalItems: results.size(),
                successCount: results.count { it.statusCode >= 200 && it.statusCode < 300 },
                errorCount: results.count { it.statusCode >= 400 }
            ],
            details: results
        ]))
        
        return [status: 1, message: "Success", payload: formattedPayload]
    } catch (Exception e) {
        return [status: -1, message: e.message, payload: body]
    }
}

