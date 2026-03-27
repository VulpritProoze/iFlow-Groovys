/**
 * W3P_GetStockTransfer.groovy
 * 
 * Dependencies:
 * - Misc/LoggerService.groovy (Standalone implementation appended below)
 * - Misc/SOAPConnection.groovy (Integrated logic)
 * - Misc/ExtractW3PCredentials.groovy (Helper methods)
 * - Misc/ExtractW3PTimestampAndBatch.groovy
 */
import java.net.URL
import java.net.HttpURLConnection
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.XmlSlurper
import groovy.xml.XmlUtil
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset


class Constants {
    static final String STEP_NAME = "W3P_GetStockTransfer"
    static final String ACTION = "GET_STOCK_TRANSFER"
    static final String W3P_CRED = "[W3P_CRED]"
    static final String W3P_URL = "[W3P_URL]"

    // Default filter values for WebPOS SOAP requests.
    static final Map FILTERS = [:]

    // Logging constants
    static final String LOG_RECID = "W3P"

    // W3P keys store constants
    // We avoid conflicts with other global variables by ff. this standard
    static final String NEW_BATCHID_PROP_NAME = "[ProjectName]_GetStockTransfer_fnew_batchid"
    static final String LAST_BATCHID_PROP_NAME = "[ProjectName]_GetStockTransfer_flast_batchid"
    static final String LAST_KEY_PROP_NAME = "[ProjectName]_GetStockTransfer_flast_key"
    static final String FDONE_PROP_NAME = "[ProjectName]_GetWarehouse_fdone"
}

/**
 * Processes WebPOS endpoints by issuing GET requests and aggregating their responses.
 *
 * Behavior:
 * - Performs the initial GET and continues requesting subsequent pages until
 *   the W3P service indicates completion.
 * - Builds a consolidated XML containing all <record> elements collected from
 *   each page; only the final page's control keys are appended:
 *   `fnew_batchid`, `flast_batchid`, `flast_key`, `fdone`.
 * - The last 4 keys are also set to property to be stored in global variable store
 *   to be pulled again on next cycle to ensure the old cycle does not requery same
 *   response (NOT YET IMPLEMENTED).
 *
 * @param message the incoming iFlow Message
 * @return the iFlow Message with the aggregated SOAP-wrapped XML set as body
 */
def Message processData(Message message) {
    def logger = new LoggerService(messageLogFactory, message)
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
    
    // 1. Extract W3P Credentials from Secure Store
    // Using the logic from SC_ExtractW3PCredentials.groovy to get Id, Key and BaseUrl
    try {
        def credsMap = extractW3PCredentials()
        if (credsMap.status != 1) {
            logger.logBoth(new LogRequest(title: Constants.LOG_RECID, stepName: Constants.STEP_NAME, status: "ERROR", inputPayload: payload, outputPayload: credsMap.message))
            return message
        }

        // 2. Initialize the SOAP Connection
        def soapConn = new HTTPSOAPConnection(credsMap.baseUrl).setId(credsMap.id).setKey(credsMap.key)

        // NEW STEP: read previously stored W3P keys from message properties (use empty string when missing)
        def fnewFromProp = message.getProperty(Constants.NEW_BATCHID_PROP_NAME) ?: ''

        // 3. Prepare the Request. Takes into account new updates from W3P
        def request = new SOAPRequestBody(action: Constants.ACTION)
        request.filters = (Constants.FILTERS instanceof Map) ? new HashMap(Constants.FILTERS) : [:] // using mutable copy of Constants
        request.filters['flast_batchid'] = fnewFromProp

        // 4. Execute paginated SOAP calls (initial post + subsequent pages)
        def pagRes = soapConn.postAllPagination(request)
        if (pagRes.status != 1) {
            logger.logBoth(new LogRequest(title: Constants.LOG_RECID, stepName: Constants.STEP_NAME, status: "ERROR", inputPayload: payload, outputPayload: pagRes.message ?: "SOAP call failed"))
            return message
        }
        def responseXml = pagRes.payload?.toString() ?: ""

        logger.logBoth(new LogRequest(title: Constants.LOG_RECID, stepName: Constants.STEP_NAME, status: "OK", inputPayload: payload, outputPayload: responseXml))

        // Extract W3P fields (fnew_batchid, flast_batchid, flast_key, fdone)
        def extractRes = extractW3PTimestampAndBatch(responseXml)
        if (extractRes?.status == 1) {
            def p = extractRes.payload ?: [:]
            try {
                message.setProperty(Constants.NEW_BATCHID_PROP_NAME, p.fnew ?: '')
                message.setProperty(Constants.LAST_BATCHID_PROP_NAME, p.flast ?: '')
                message.setProperty(Constants.LAST_KEY_PROP_NAME, p.fkey ?: '')
                message.setProperty(Constants.FDONE_PROP_NAME, p.fdone ?: '')
            } catch (e) {
                logger.logBoth(new LogRequest(title: Constants.LOG_RECID, stepName: Constants.STEP_NAME, status: "ERROR", inputPayload: payload, outputPayload: "Failed setting W3P properties: ${e.message}"))
            }
        } else {
            logger.logBoth(new LogRequest(title: Constants.LOG_RECID, stepName: Constants.STEP_NAME, status: "ERROR", inputPayload: payload, outputPayload: "extractW3PTimestampAndBatch failed: ${extractRes?.message}"))
        }

        message.setBody(responseXml)

    } catch (Exception e) {
        logger.logBoth(new LogRequest(title: Constants.LOG_RECID, stepName: Constants.STEP_NAME, status: "ERROR", inputPayload: payload, outputPayload: "Exception: ${e.message}\nStacktrace: ${e.stackTrace.take(5).join('\n')}"))
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
    /** Optional full custom XML envelope string. If provided, default envelope building is bypassed. */
    String customEnvelope = ""

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
            return null
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
     * @return Result Map Structure.
     */
    public def post(SOAPRequestBody request) {
        try {
            def con = connect()
            if (con == null) {
                return [status: -1, message: "Connection URL cannot be empty. Please set the baseUrl"]
            }

            try {
                con.setRequestMethod('POST')
                con.doOutput = true
                
                for (prop in request.requestProperty) {
                    con.setRequestProperty(prop.key, prop.value)
                }

                String soapEnvelope = buildEnvelope(request)
                if (soapEnvelope instanceof Map) return soapEnvelope // Return validation error from buildEnvelope

                con.outputStream.withCloseable { it << soapEnvelope }

                if (con.responseCode >= 200 && con.responseCode < 300) {
                    return [status: 1, message: "Success", payload: con.inputStream.text]
                } else {
                    def errorText = con.errorStream?.text ?: "No error details provided"
                    return [status: -1, message: "SOAP request failed to ${this.baseUrl}. HTTP ${con.responseCode}: $errorText"]
                }
            } finally {
                con.disconnect()
            }
        } catch (Exception e) {
            return [status: -1, message: "SOAP exception: ${e.message}"]
        }
    }

    /**
     * Note: This endpoint is only for 'GET_' endpoints in W3P'.
     * Posts the initial request, then repeatedly posts with the `flast_key` filter set to
     * the last seen `flast_key` value. Collects all `<record>` nodes from each page and
     * returns a single well-formed XML payload containing all records and the four
     * control keys (`fnew_batchid`, `flast_batchid`, `flast_key`, `fdone`) taken from
     * the last page fetched.
     *
     * Warning: This only captures the last page's 4 keys and return code.
     */
    public def postAllPagination(SOAPRequestBody baseRequest) {
        try {
            if (!baseRequest) {
                return [status: 0, message: "postAllPagination: baseRequest is required"]
            }
            if (!baseRequest.action || !(baseRequest.action instanceof String) || !baseRequest.action.contains('GET_')) {
                return [status: 0, message: "postAllPagination: baseRequest.action must contain 'GET_' (only GET_ endpoints supported)"]
            }
            // Prepare a mutable copy of the request so we can append pagination filters
            SOAPRequestBody request = new SOAPRequestBody()
            request.action = baseRequest.action
            request.record = baseRequest.record
            request.customEnvelope = baseRequest.customEnvelope
            request.requestProperty = baseRequest.requestProperty ?: ['Content-Type': 'application/xml']
            request.filters = baseRequest.filters ? new HashMap(baseRequest.filters) : [:]

            // Helper: parse XML using a secure SAX parser (best-effort to disable external entities)
            def parseSafe = { String xmlStr ->
                def spf = javax.xml.parsers.SAXParserFactory.newInstance()
                try {
                    spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    spf.setFeature("http://xml.org/sax/features/external-general-entities", false)
                    spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                } catch (Exception se) {
                    // feature not supported - continue with best-effort parser
                }
                spf.setXIncludeAware(false)
                spf.setNamespaceAware(false)
                def parser = spf.newSAXParser()
                def reader = parser.getXMLReader()
                return new XmlSlurper(reader).parseText(xmlStr)
            }

            List<String> allRecordFragments = []
            List<String> pageResults = []
            String finalFnewBatchid = ""
            String finalFlastBatchid = ""
            String finalFlastKey = ""
            String finalFdone = "0"
            String finalReturnCode = "0"

            while (true) {
                def resp = this.post(request)
                if (resp.status != 1) {
                    return [status: -1, message: "postAllPagination: POST failed: ${resp.message}", lastResponse: resp]
                }

                def payload = resp.payload ?: ""
                if (!payload) {
                    return [status: -1, message: "postAllPagination: empty payload"]
                }

                // SOAP wrapper -> extract Result element text (which itself contains escaped XML)
                def soapParsed = parseSafe(payload)
                def resultNode = soapParsed.'**'.find { it.name() == 'Result' }
                def resultText = resultNode?.text()
                if (!resultText) {
                    return [status: -1, message: "postAllPagination: Result element not found in SOAP response", payload: payload]
                }

                pageResults << resultText

                // parse inner page XML
                def pageParsed = parseSafe(resultText)

                // extract pagination control keys
                finalFnewBatchid = pageParsed.data.fnew_batchid?.text() ?: finalFnewBatchid
                finalFlastBatchid = pageParsed.data.flast_batchid?.text() ?: finalFlastBatchid
                finalFlastKey = pageParsed.data.flast_key?.text() ?: finalFlastKey
                finalFdone = pageParsed.data.fdone?.text() ?: finalFdone

                // extract return_code if present anywhere under the page (may be sibling of <data>)
                def rcNode = pageParsed.'**'.find { it.name() == 'return_code' }
                finalReturnCode = rcNode?.text() ?: finalReturnCode

                // collect records - robust multi-strategy extraction to avoid truncation
                def collectPageRecordFragments = { String pageText, def parsedPage ->
                    def fragments = []

                    // 1) Try serializing parsed <record> nodes (best when parser read correctly)
                    try {
                        parsedPage.data.record.each { rec ->
                            String recXml = XmlUtil.serialize(rec)
                            recXml = recXml.replaceFirst(/<\?xml.*?\?>\s*/, '')
                            if (recXml?.trim()) fragments << recXml
                        }
                    } catch (e) { /* ignore */ }
                    if (fragments) return fragments

                    // 2) Try direct regex on pageText
                    try {
                        def m = (pageText =~ /(?s)<record\b[^>]*?(?:\/>|>.*?<\/record>)/)
                        if (m && m.size() > 0) {
                            m.each { mm -> if (mm[0]?.trim()) fragments << mm[0] }
                        }
                    } catch (e) { /* ignore */ }
                    if (fragments) return fragments

                    // 3) Iteratively unescape and regex (handles double-escaped content)
                    try {
                        def unescaped = pageText ?: ''
                        while (true) {
                            def next = unescaped.replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&quot;', '"').replaceAll('&apos;', "'").replaceAll('&amp;', '&')
                            if (next == unescaped) break
                            unescaped = next
                        }
                        def m2 = (unescaped =~ /(?s)<record\b[^>]*?(?:\/>|>.*?<\/record>)/)
                        if (m2 && m2.size() > 0) {
                            m2.each { mm -> if (mm[0]?.trim()) fragments << mm[0] }
                        }
                    } catch (e) { /* ignore */ }
                    if (fragments) return fragments

                    // 4) Fallback: serialize entire <data> then extract
                    try {
                        String dataXml = XmlUtil.serialize(parsedPage.data)
                        dataXml = dataXml.replaceFirst(/<\?xml.*?\?>\s*/, '')
                        String inner = dataXml.replaceFirst(/^\s*<data[^>]*>/, '').replaceFirst(/<\/data>\s*$/, '')
                        def m3 = (inner =~ /(?s)<record\b[^>]*?(?:\/>|>.*?<\/record>)/)
                        if (m3 && m3.size() > 0) { m3.each { mm -> if (mm[0]?.trim()) fragments << mm[0] } }
                    } catch (e) { /* ignore */ }

                    return fragments
                }

                def pageFrags = collectPageRecordFragments(resultText, pageParsed)
                if (pageFrags && pageFrags.size() > 0) {
                    pageFrags.each { f -> allRecordFragments << f }
                }

                // stop condition
                if (finalFdone == '1' || finalFdone?.toLowerCase() == 'true') {
                    break
                }

                // prepare next iteration: set flast_key to last seen flast_key
                request.filters = request.filters ?: [:]
                request.filters['flast_key'] = finalFlastKey
            }

            // Build finalResponse: if no records were collected, return concatenated page results as fallback
            // Use manual concatenation to avoid MarkupBuilder edge-cases that may truncate
            // or mangle raw fragments when they contain unexpected constructs.
            String finalResponse
            if (allRecordFragments.size() == 0) {
                finalResponse = pageResults.join('\n')
            } else {
                def sb = new StringBuilder()
                sb.append("""<?xml version='1.0' encoding='utf-8'?>\n<root>\n  <return_code>${finalReturnCode}</return_code>\n  <data>\n""")
                sb.append("    <fnew_batchid>${finalFnewBatchid}</fnew_batchid>\n")
                sb.append("    <flast_batchid>${finalFlastBatchid}</flast_batchid>\n")
                sb.append("    <flast_key>${finalFlastKey}</flast_key>\n")
                sb.append("    <fdone>${finalFdone}</fdone>\n")
                allRecordFragments.each { r ->
                    try {
                        String rec = r.replaceFirst(/^\s*<\?xml.*?\?>\s*/, '')
                        sb.append(rec)
                        if (!rec.endsWith('\n')) sb.append('\n')
                    } catch (e) {
                        sb.append(r)
                        if (!r.endsWith('\n')) sb.append('\n')
                    }
                }
                sb.append('  </data>\n</root>\n')
                finalResponse = sb.toString()
            }

            // Escape the inner XML and wrap it in the SOAP envelope/Result structure
            def escapeXmlForResult = { String s ->
                if (s == null) return ''
                // escape ampersand first
                s = s.replace('&', '&amp;')
                s = s.replace('<', '&lt;')
                s = s.replace('>', '&gt;')
                return s
            }

            String escapedInner = escapeXmlForResult(finalResponse)

            String soapWrapped = '<?xml version="1.0" encoding="UTF-8"?>' +
                '<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns1="urn:localhost-main" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:SOAP-ENC="http://schemas.xmlsoap.org/soap/encoding/" SOAP-ENV:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">' +
                '<SOAP-ENV:Body><ns1:callResponse><Result xsi:type="xsd:string">' +
                escapedInner +
                '</Result></ns1:callResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>'

            return [status: 1, message: 'Success', payload: soapWrapped]
        } catch (Exception e) {
            return [status: -1, message: "postAllPagination error: ${e.message}"]
        }
    }

    public def buildEnvelope(SOAPRequestBody request) {
        int provided = (request.customEnvelope ? 1 : 0) + (request.record ? 1 : 0) + (request.filters ? 1 : 0)
        if (provided > 1) {
            return [status: 0, message: "SOAPRequestBody: Only one of 'customEnvelope', 'record', or 'filters' can be provided."]
        }

        String dataContent = ""
        
        if (request.customEnvelope) {
            dataContent = request.customEnvelope
        } else if (request.record) {
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
 * Method to extract W3P credentials from the SAP Secure Store.
 */
def extractW3PCredentials() {
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
        if (w3pCreds == null) {
            return [status: -1, message: "Credential '${Constants.W3P_CRED}' not found in Security Material."]
        }

        def w3pUrlCreds = getCreds(Constants.W3P_URL)
        if (w3pUrlCreds == null) {
            return [status: -1, message: "Credential '${Constants.W3P_URL}' not found in Security Material."]
        }

        String id = w3pCreds.getUsername()
        String key = new String(w3pCreds.getPassword())
        String baseUrl = new String(w3pUrlCreds.getPassword())

        if (!id || !key || !baseUrl) {
            return [status: -1, message: "Missing W3P Configuration in Secure Store (${Constants.W3P_CRED}/${Constants.W3P_URL})."]
        }

        return [
            status: 1,
            message: "Success",
            id: id,
            key: key,
            baseUrl: baseUrl
        ]
    } catch (Exception e) {
        return [status: -1, message: "Error extracting credentials: ${e.message}"]
    }
}


/**
 * Extracts W3P timestamp and batch fields from the provided XML. 
 * Fields extracted: fnew_batchid, flast_batchid, flast_key, fdone.
 *
 * Returns a result Map: [status:1,message:'Success',payload:[...]] or error map.
 */
def extractW3PTimestampAndBatch(String xml) {
    try {
        if (!xml) return [status: 0, message: 'No XML provided', payload: null]

        def parser = new XmlSlurper()
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false)

        def root = parser.parseText(xml)

        // If the payload is a SOAP envelope with an escaped inner XML inside <Result> then
        // unescape and reparse the inner XML so we can find the fnew/flast/fkey/fdone nodes.
        def resultNode = root.'**'.find { it.name() == 'Result' }
        if (resultNode) {
            def innerText = resultNode.text()
            if (innerText) {
                def unescapeXml = { String s ->
                    if (s == null) return ''
                    def x = s
                    x = x.replaceAll('&lt;', '<')
                    x = x.replaceAll('&gt;', '>')
                    x = x.replaceAll('&quot;', '"')
                    x = x.replaceAll('&apos;', "'")
                    x = x.replaceAll('&amp;', '&')
                    return x
                }

                // If innerText appears escaped (contains &lt;), unescape and try to parse
                if (innerText.contains('&lt;')) {
                    try {
                        def innerUnescaped = unescapeXml(innerText)
                        root = parser.parseText(innerUnescaped)
                    } catch (e) {
                        // ignore and keep original root
                    }
                } else {
                    // Not escaped; maybe already raw XML string — attempt to parse
                    try { root = parser.parseText(innerText) } catch (e) { /* ignore */ }
                }
            }
        }

        def findFirst = { String name ->
            def node = root.'**'.find { it.name() == name }
            return node ? node.text() : null
        }

        def fnew = findFirst('fnew_batchid')
        def flast = findFirst('flast_batchid')
        def fkey = findFirst('flast_key')
        def fdone = findFirst('fdone')

        return [status: 1, message: 'Success', payload: [fnew: fnew, flast: flast, fkey: fkey, fdone: fdone]]
    } catch (Exception e) {
        return [status: 0, message: "extractW3PTimestampAndBatch error: ${e?.message}", payload: null]
    }
}