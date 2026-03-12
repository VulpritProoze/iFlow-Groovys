/**
 * SAP_PostWarehouseBulk.groovy
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

class Constants {
    static final String STEP_NAME = "SAP_PostWarehouseBulk"
    static final String SESSION_VAR_PROP_NAME = "[B1SESSION]"
    static final String BASE_URL_PROP_NAME = "[SL_BaseURL]"
}

def Message processData(Message message) {
    def sessionCookie = extractSessionCookie(message)
    def baseUrl = extractBaseUrl(message)
    
    if (!sessionCookie || !baseUrl) {
        throw new RuntimeException("Missing SessionCookie or BaseUrl")
    }

    def reader = message.getBody(java.io.Reader)
    def warehouseList = new JsonSlurper().parse(reader) 

    // 1. Define Unique Boundaries
    String batchId = "batch_" + java.util.UUID.randomUUID().toString()
    String changesetId = "changeset_" + java.util.UUID.randomUUID().toString()

    // 2. Build the Multipart Batch Body
    // Note: Using \r\n (CRLF) is mandatory for multipart/mixed standards
    StringBuilder batchBody = new StringBuilder()
    batchBody.append("--${batchId}\r\n")
    batchBody.append("Content-Type: multipart/mixed; boundary=${changesetId}\r\n\r\n")

    warehouseList.each { wh ->
        batchBody.append("--${changesetId}\r\n")
        batchBody.append("Content-Type: application/http\r\n")
        batchBody.append("Content-Transfer-Encoding: binary\r\n\r\n")
        batchBody.append("POST /b1s/v1/Warehouses\r\n")
        batchBody.append("Content-Type: application/json\r\n\r\n")
        batchBody.append(JsonOutput.toJson(wh)).append("\r\n\r\n")
    }

    batchBody.append("--${changesetId}--\r\n")
    batchBody.append("--${batchId}--")

    // 3. Setup Request Object
    // We map the batchBody string to the 'payload' field as defined in your DTO
    def request = new ODataRequestBody(
        url: "/\$batch",
        payload: batchBody.toString(),
        requestProperty: [
            'Content-Type': "multipart/mixed; boundary=${batchId}"
        ]
    )

    def conn = new HTTPODataConnection(baseUrl).setSessionCookie(sessionCookie)
    def logger = new LoggerService(messageLogFactory, message)

    try {
        // Your connection class 'post' method writes request.payload to the output stream
        conn.post(request)
        
        // Use the raw body if it exists, otherwise fallback to empty string
        String rawBody = conn.getBody() ?: ""
        
        logger.logInternal(new LogRequest (
            stepName: Constants.STEP_NAME,
            title: "Batch Request Successful",
            status: "Success", 
            payload: "Items processed: ${warehouseList.size()}\n\nResponse:\n${formatBatchResponse(rawBody)}"
        ))
        
        message.setBody(rawBody)
    } catch (Exception e) {
        logger.logInternal(new LogRequest (
            stepName: Constants.STEP_NAME,
            title: "Batch Request Failed",
            status: "Error", 
            payload: e.getMessage()
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
        if (url == null || '') {
            throw new IllegalStateException('Connection URL cannot be empty. Please set the baseUrl for this connection')
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
     * @return The parsed JSON response as a Map or List.
     */
    public def get(ODataRequestBody request) {
        def con = connect(request.url)
        con.setRequestMethod('GET')
        con.doOutput = true
        for (prop in request.requestProperty) {
            con.setRequestProperty(prop.key, prop.value)
        }

        if (request.isPassSession) {
            if (!sessionCookie) {
                throw new RuntimeException('Missing sessionCookie for Connection')
            }
            con.setRequestProperty('Cookie', sessionCookie)
        }

        if (request.payload) {
            con.outputStream.withCloseable { it << request.payload }
        }

        if (con.responseCode >= 200 && con.responseCode < 300) {
            return new JsonSlurper().parse(con.inputStream.newReader())
        } else {
            def errorText = con.errorStream?.text ?: "No error details provided"
            throw new RuntimeException("POST failed. HTTP ${con.responseCode}: $errorText")
        }
    }

    /**
     * Executes an HTTP PUT request.
     * @param request The configuration object containing the URL, payload, and headers.
     * @return The parsed JSON response.
     */
    public def put(ODataRequestBody request) {
        def con = connect(request.url)
        con.setRequestMethod('PUT')
        con.doOutput = true
        for (prop in request.requestProperty) {
            con.setRequestProperty(prop.key, prop.value)
        }

        if (request.isPassSession) {
            if (!sessionCookie) {
                throw new RuntimeException('Missing sessionCookie for Connection')
            }
            con.setRequestProperty('Cookie', sessionCookie)
        }

        if (request.payload) {
            con.outputStream.withCloseable { it << request.payload }
        }

        if (con.responseCode >= 200 && con.responseCode < 300) {
            return new JsonSlurper().parse(con.inputStream.newReader())
        } else {
            def errorText = con.errorStream?.text ?: "No error details provided"
            throw new RuntimeException("PUT failed. HTTP ${con.responseCode}: $errorText")
        }
    }

    /**
     * Executes an HTTP DELETE request.
     * @param request The configuration object containing the URL and headers.
     * @return The parsed JSON response or a success status map for 204 responses.
     */
    public def delete(ODataRequestBody request) {
        def con = connect(request.url)
        con.setRequestMethod('DELETE')
        for (prop in request.requestProperty) {
            con.setRequestProperty(prop.key, prop.value)
        }

        if (request.isPassSession) {
            if (!sessionCookie) {
                throw new RuntimeException('Missing sessionCookie for Connection')
            }
            con.setRequestProperty('Cookie', sessionCookie)
        }

        // DELETE can have a payload, though it is not standard
        if (request.payload) {
            con.doOutput = true
            con.outputStream.withCloseable { it << JsonOutput.toJson(request.payload) }
        }

        if (con.responseCode >= 200 && con.responseCode < 300) {
            // Some DELETE responses are 204 No Content
            if (con.responseCode == 204) return [status: 'Success']
            return new JsonSlurper().parse(con.inputStream.newReader())
        } else {
            def errorText = con.errorStream?.text ?: "No error details provided"
            throw new RuntimeException("DELETE failed. HTTP ${con.responseCode}: $errorText")
        }
    }

    /**
     * Executes an HTTP POST request.
     * Use this method for standard creation or OData $batch requests.
     * For batch requests, use url: "/$batch" and provide a multipart/mixed payload.
     * @param request The configuration object containing the URL, payload, and headers.
     * @return The updated HTTPODataConnection instance for chaining.
     */
    public def post(ODataRequestBody request) {
        def con = connect(request.url)
        con.setRequestMethod('POST')
        con.setDoOutput(true)
        for (prop in request.requestProperty) {
            con.setRequestProperty(prop.key, prop.value)
        }

        if (request.isPassSession) {
            if (!sessionCookie) {
                throw new RuntimeException('Missing sessionCookie for Connection')
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
            return this
        } else {
            def errorText = con.errorStream?.text ?: "No error details provided"
            throw new RuntimeException("POST request failed to ${request.url}. HTTP $responseCode: $errorText")
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
 * Standalone method to extract SessionId from a Message Property and return 
 * it as a formatted B1SESSION cookie string.
 *
 * Usage in another script:
 * def cookie = extractSessionCookie(message)
 */
String extractSessionCookie(Message message) {
    String sessionCookie = message.getProperty(Constants.SESSION_VAR_PROP_NAME)
    
    if (!sessionCookie || !sessionCookie.startsWith(Constants.SESSION_VAR_PROP_NAME + "=")) {
        throw new RuntimeException("SessionCookie has invalid format or not found.")
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
    String baseUrl = message.getProperty(Constants.BASE_URL_PROP_NAME)

    if (!baseUrl) {
        throw new RuntimeException("BaseUrl is missing.")
    }
    return baseUrl
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
    /** The name of the process step being logged */
    String stepName
    /** The title of the log entry / attachment */
    String title
    /** The status of the step (e.g., Success, Error, Info) */
    String status
    /** The content or object to be logged */
    Object payload
    /** Optional media type for the internal attachment (default: text/plain) */
    String mediaType = "text/plain"
}

/**
 * Handles internal and external logging for SAP Cloud Integration.
 * 
 * Example usage:
 * LoggerService logger = new LoggerService(messageLogFactory, message)
 * logger.logInternal("Payload Received", body)
 * logger.logExternal("RequestStep", "Success", [id: 123])
 * logger.logBoth("Final Status", "Completed", responseBody)
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
     * Appends the Step Name to the payload content for better visibility.
     * @param request The LogRequest object containing all logging details.
     */
    def logInternal(LogRequest request) {
        if (this.messageLog != null && request.payload != null) {
            String enrichedPayload = "Step: ${request.stepName}\nTitle: ${request.title ?: 'N/A'}\nStatus: ${request.status}\n\n${request.payload.toString()}"
            this.messageLog.addAttachmentAsString(request.stepName ?: request.title, enrichedPayload, request.mediaType)
        }
    }

    /**
     * Prints a JSON-structured log to STDOUT for external aggregation (Kibana).
     * @param request The LogRequest object containing all logging details.
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
     * @param request The LogRequest object containing all logging details.
     */
    def logBoth(LogRequest request) {
        logInternal(request)
        logExternal(request)
    }

    /**
     * Overloaded method for quick logging without creating a LogRequest object.
     * @param title The title/step name for the log.
     * @param status The status of the operation.
     * @param payload The data to be logged.
     */
    def logBoth(String title, String status, Object payload) {
        def req = new LogRequest(stepName: title, status: status, payload: payload)
        logInternal(req)
        logExternal(req)
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
 *  String formattedJson = formatBatchResponse(rawResponse)
 *  println(formattedJson)
 * }
 * </pre>
 * 
 * @param body The raw multipart/mixed string from the OData response
 * @return String A prettified JSON string containing success counts and per-item details
 */
def String formatBatchResponse(String body) {
    def results = []

    if (!body || !body.contains("--batchresponse")) {
        return body // Not a batch response, return as is
    }

    try {
        // 1. Split by boundary (find the first boundary to determine the pattern)
        String boundary = ""
        // Account for leading whitespace or quotes if they somehow leak in
        def boundaryMatcher = (body =~ /--batchresponse_[a-zA-Z0-9-]+/)
        if (boundaryMatcher.find()) {
            boundary = boundaryMatcher.group()
        }

        if (!boundary) return body

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
        return JsonOutput.prettyPrint(JsonOutput.toJson([
            batchSummary: [
                totalItems: results.size(),
                successCount: results.count { it.statusCode >= 200 && it.statusCode < 300 },
                errorCount: results.count { it.statusCode >= 400 }
            ],
            details: results
        ]))
        
    } catch (Exception e) {
        return "Batch Parsing Error: ${e.message}\n\nOriginal Body:\n${body}"
    }
}

