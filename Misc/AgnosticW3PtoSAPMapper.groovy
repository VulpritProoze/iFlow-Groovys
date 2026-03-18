/**
 * W3PtoSAP_MapWarehouse.groovy
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
    static final String STEP_NAME = "W3PtoSAP_[StepName]"
    
    /**
     * Mapping configuration
     * - Keys are target field names.
     * - Values are source expressions. Supported expression forms and behaviour:
     *     1) `RESPONSE_KEY.field` -> looks up `field` from the response identified by `RESPONSE_KEY`.
     *        When the mapper emits one object per source record it will prefer the per-index record; otherwise the first record is used.
     *     2) `fieldName` -> resolves against the current record when mapping record-by-record.
     *     3) Numeric / arithmetic: supports `+`, `-`, `*`, and `/` with operator precedence (`*` and `/` before `+` and `-`).
     *        `+` performs numeric addition when both operands are numeric; otherwise it concatenates as strings.
     *     4) Literal strings: use single or double quotes, e.g. `'PRE-' + GET_ITEM1.fitemcode`.
     *
     * Notes:
     * - `extractMappedRecords` returns a Result map: `[status:1|0, message:'...', payload: [...]]`.
     *   On mapping errors (non-numeric operand for `*`/`/`/`-`, division by zero, malformed expressions) the function returns
     *   `status: 0` with a descriptive `message` and an empty `payload`.
     * - Missing fields or unresolved references evaluate to an empty string by default.
     * - Custom transformation functions can be provided via `CUSTOM_RULES` and are applied to resolved values.
     */
    static final Map MAPPING = [:]
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
    def payload = message.getBody(java.lang.String)
    
    // Extract W3P URL to initialize SOAP connection
    def credsMap = LoggerService.extractW3PCredentials()
    if (credsMap.status != 1) {
        logger.logInternal(new LogRequest(stepName: "CREDENTIAL_FAILURE", title: Constants.LOG_RECID, status: "ERROR", inputPayload: payload, outputPayload: credsMap.message))
        return message // Premature return instead of exception
    }

    try {
        // 1. Map data -> returns a Result map: [status:1|0, message: '', payload: [...]]
        def result = extractMappedRecords(
            payload, 
            Constants.MAPPING, 
            Constants.CUSTOM_RULES
        )

        if (!(result instanceof Map)) {
            logger.logBoth(new LogRequest(stepName: "MAPPING_FAILURE", title: Constants.LOG_RECID, status: "ERROR", inputPayload: payload, outputPayload: "Mapper returned unexpected type"))
            return message
        }

        if (result.status != 1) {
            logger.logBoth(new LogRequest(stepName: "MAPPING_ERROR", title: Constants.LOG_RECID, status: "ERROR", inputPayload: payload, outputPayload: result.message ?: 'Mapping failed'))
            return message
        }

        def mappedRecords = result.payload ?: []

        if (mappedRecords.isEmpty() && payload.trim().startsWith("<")) {
            logger.logBoth(new LogRequest(stepName: "MAPPING_NO_RECORDS", title: Constants.LOG_RECID, status: "ERROR", inputPayload: payload, outputPayload: "No records found or failed to parse XML <Result>."))
            return message // Premature return instead of exception
        }

        // 2. Wrap it in a uniform JSON structure
        def jsonResult = JsonOutput.toJson(mappedRecords)
        
        // 3. Log Success using logBoth and handle result
        def logResult = logger.logBoth(new LogRequest(stepName: Constants.STEP_NAME, title: Constants.LOG_RECID, status: "OK", inputPayload: payload, outputPayload: JsonOutput.prettyPrint(jsonResult)))
        
        if (logResult.status != 1) {
            // Log the logging failure using logBoth
            logger.logBoth(new LogRequest(stepName: "PROCESS_LOG_FAILURE", title: Constants.LOG_RECID, status: "ERROR", inputPayload: payload, outputPayload: "LogProcess failed: ${logResult.message}"))
        }
        
        // 4. Set the new JSON body back to the message
        message.setBody(jsonResult)
        
    } catch (Exception e) {
        // Log Error using logBoth
        def stackTrace = e.stackTrace.take(15).join('\n')
        def logErrResult = logger.logBoth(new LogRequest(stepName: Constants.STEP_NAME, title: Constants.LOG_RECID, status: "ERROR", inputPayload: "Original Payload length: ${payload?.length() ?: 0}", outputPayload: "Exception: ${e.message}\nStacktrace: ${stackTrace}"))

        if (logErrResult.status != 1) {
            logger.logBoth(new LogRequest(stepName: "ERROR_LOGGING_FAILURE", title: Constants.LOG_RECID, status: "ERROR", inputPayload: payload, outputPayload: "Failed to log original error to process: ${logErrResult.message}\n\nOriginal Error: ${e.message}"))
        }
    }
    
    return message
}



/**
 * Agnostic Payload Processor (Refactored from Mapper.groovy)
 * Dynamically handles both XML (SOAP) and JSON data formats.
 */
def extractMappedRecords(String payload, Map mapping, Map customRules = [:]) {
    if (!payload) return [status: 0, message: 'Empty payload', payload: []]
    payload = payload.toString()

    // Helper: safe XmlSlurper factory
    def newSafeSlurper = {
        def sp = new XmlSlurper()
        try {
            sp.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            sp.setFeature("http://xml.org/sax/features/external-general-entities", false)
            sp.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (e) {
            // ignore if not supported
        }
        return sp
    }

    // Convert a GPathResult <record> to a plain map (text values unescaped)
    def recordToMap = { GPathResult rec ->
        def m = [:]
        def rid = rec.@id?.toString()
        if (rid) m['id'] = rid
        rec.children().each { ch ->
            if (ch?.name()) m[ch.name()] = ch.text()
        }
        return m
    }

    // Resolve a single token (either RESPONSE.field or a plain field or literal)
    // idx: optional index into response records when producing per-record mapped outputs
    def resolveToken = { String token, Map responsesMap, Map currentRecord = null, Integer idx = null ->
        if (!token) return ''
        token = token.trim()
        // literal quoted string
        if ((token.startsWith("\'") && token.endsWith("\'")) || (token.startsWith('"') && token.endsWith('"'))) {
            return token.substring(1, token.length() - 1)
        }

        // RESPONSE.field lookup
        def m = token =~ /^([A-Za-z0-9_]+)\.([A-Za-z0-9_]+)$/
        if (m.matches()) {
            def rkey = m[0][1]
            def fname = m[0][2]
            def entry = responsesMap[rkey]
            if (!entry) return ''
            // If an index is provided and the response has multiple records, prefer the indexed record
            if (idx != null && entry.records && entry.records.size() > idx) {
                def val = entry.records[idx]?.get(fname)
                if (val != null && val.toString() != '') return val
            }
            // fall back to the first record
            def rv = entry.first?.get(fname)
            return rv ?: ''
        }

        // current record field
        if (currentRecord != null && currentRecord.containsKey(token)) {
            return currentRecord[token] ?: ''
        }

        // fallback literal
        return token
    }

    // Numeric helper: try convert to BigDecimal, return null on failure
    def toBigDecimal = { val ->
        if (val == null) return null
        if (val instanceof Number) return new BigDecimal(val.toString())
        try {
            def s = val.toString().trim()
            if (s == '') return null
            return new BigDecimal(s)
        } catch (Exception e) {
            return null
        }
    }

    // Tokenize expression into numbers, quoted strings, identifiers, and operators
    def tokenizeExpr = { String expr ->
        if (!expr) return []
        def pattern = /'[^']*'|"[^"]*"|\d+(?:\.\d+)?|[A-Za-z0-9_\[\]=\.]+|[()+\-\*\/]/
        def m = expr =~ pattern
        def tokens = []
        while (m.find()) { tokens << m.group().trim() }
        return tokens
    }

    // Resolve an expression supporting +, -, *, / with precedence. '+' behaves as concat when any operand is non-numeric.
    // Throws IllegalArgumentException on invalid numeric usage (e.g. '*' on non-numeric fields).
    def resolveExpression = { String expr, Map responsesMap, Map currentRecord = null, Integer idx = null ->
        if (expr == null) return ''
        if (!(expr instanceof String)) return expr.toString()
        expr = expr.trim()
        if (expr == '') return ''

        def tokens = tokenizeExpr(expr)
        if (!tokens) return ''

        // Helper to get runtime value for a token (using resolveToken closure above)
        def resolveOperand = { String tok ->
            if (tok == null) return ''
            return resolveToken(tok, responsesMap, currentRecord, idx)
        }

        // First pass: handle * and /
        def tlist = tokens.collect { it }
        int j = 0
        while (j < tlist.size()) {
            def tk = tlist[j]
            if (tk == '*' || tk == '/') {
                if (j == 0 || j == tlist.size() - 1) throw new IllegalArgumentException("Invalid expression: ${expr}")
                def leftTok = tlist[j - 1]
                def rightTok = tlist[j + 1]
                def leftVal = resolveOperand(leftTok)
                def rightVal = resolveOperand(rightTok)
                def leftNum = toBigDecimal(leftVal)
                def rightNum = toBigDecimal(rightVal)
                if (leftNum == null || rightNum == null) {
                    throw new IllegalArgumentException("Expression '${expr}': operator '${tk}' requires numeric operands (left='${leftVal}', right='${rightVal}')")
                }
                if (tk == '/' && rightNum.compareTo(BigDecimal.ZERO) == 0) {
                    throw new IllegalArgumentException("Expression '${expr}': division by zero (left='${leftVal}', right='${rightVal}')")
                }
                def resNum = (tk == '*') ? leftNum.multiply(rightNum) : leftNum.divide(rightNum, 10, BigDecimal.ROUND_HALF_UP)
                def resStr = resNum.stripTrailingZeros().toPlainString()
                // replace left,op,right with result
                tlist[j - 1] = resStr
                tlist.remove(j + 1)
                tlist.remove(j)
                j = Math.max(j - 1, 0)
            } else {
                j++
            }
        }

        // Second pass: handle + and - (left to right). + is numeric add when both numeric, else concatenation
        int i = 0
        while (i < tlist.size()) {
            def tk = tlist[i]
            if (tk == '+' || tk == '-') {
                if (i == 0 || i == tlist.size() - 1) throw new IllegalArgumentException("Invalid expression: ${expr}")
                def leftTok = tlist[i - 1]
                def rightTok = tlist[i + 1]
                def leftVal = resolveOperand(leftTok)
                def rightVal = resolveOperand(rightTok)
                def leftNum = toBigDecimal(leftVal)
                def rightNum = toBigDecimal(rightVal)
                def resStr
                if (tk == '+') {
                    if (leftNum != null && rightNum != null) {
                        def sum = leftNum.add(rightNum)
                        resStr = sum.stripTrailingZeros().toPlainString()
                    } else {
                        // string concatenation
                        resStr = (leftVal == null ? '' : leftVal.toString()) + (rightVal == null ? '' : rightVal.toString())
                    }
                } else {
                    // '-' requires numeric
                    if (leftNum == null || rightNum == null) {
                        throw new IllegalArgumentException("Expression '${expr}': operator '-' requires numeric operands (left='${leftVal}', right='${rightVal}')")
                    }
                    def diff = leftNum.subtract(rightNum)
                    resStr = diff.stripTrailingZeros().toPlainString()
                }
                tlist[i - 1] = resStr
                tlist.remove(i + 1)
                tlist.remove(i)
                i = Math.max(i - 1, 0)
            } else {
                i++
            }
        }

        // Final token: resolve and return
        def finalTok = tlist.size() ? tlist[0] : ''
        if (finalTok == null) return ''
        // If it's a quoted literal, strip quotes
        if ((finalTok.startsWith("'") && finalTok.endsWith("'")) || (finalTok.startsWith('"') && finalTok.endsWith('"'))) {
            return finalTok.substring(1, finalTok.length() - 1)
        }
        // Otherwise resolve via resolveToken to get runtime value (handles RESPONSE.field and currentRecord)
        return resolveToken(finalTok, responsesMap, currentRecord, idx) ?: ''
    }

    // Parse XML payload
    if (payload.trim().startsWith('<')) {
        def sl = newSafeSlurper()
        def envelope = sl.parseText(payload)

        // Detect if payload is an aggregated <responses> wrapper
        def responsesRoot = null
        if (envelope.name() == 'responses') {
            responsesRoot = envelope
        } else {
            responsesRoot = envelope.'**'.find { it.name() == 'responses' }
        }

        if (responsesRoot) {
            // Build responses map: key -> { records: [map], first: map }
            def responsesMap = [:]
            responsesRoot.response.each { resp ->
                def rkey = resp.key?.text()?.trim()
                if (!rkey) return
                def respValue = resp.value?.text() ?: ''
                def entry = [records: [], first: [:]]
                try {
                    if (respValue) {
                        def valEnv = newSafeSlurper().parseText(respValue)
                        def inner = valEnv.Body?.callResponse?.Result?.text() ?: valEnv.'**'.find { it.name() == 'Result' }?.text()
                        if (inner) {
                            def innerRoot = newSafeSlurper().parseText(inner)
                            innerRoot.data.record.each { rec ->
                                entry.records << recordToMap(rec)
                            }
                            entry.first = entry.records ? entry.records[0] : [:]
                            entry.raw = inner
                        }
                    }
                } catch (e) {
                    // ignore parsing errors for this response
                }
                responsesMap[rkey] = entry
            }

            // Decide whether to produce one mapped result per record
            int maxRecords = 0
            responsesMap.each { k, v -> if (v.records) maxRecords = Math.max(maxRecords, v.records.size()) }

            if (maxRecords <= 1) {
                // Single-result behavior (backwards compatible)
                try {
                    def mapped = [:]
                    mapping.each { target, sourceSpec ->
                        def resolved = resolveExpression(sourceSpec?.toString(), responsesMap, null, null)
                        if (customRules.containsKey(target)) mapped[target] = customRules[target](resolved) else mapped[target] = resolved ?: ''
                    }
                    return [status: 1, message: 'OK', payload: [mapped]]
                } catch (IllegalArgumentException e) {
                    return [status: 0, message: "Mapping error: ${e.message}", payload: []]
                }
            }

            // Multi-record behavior: build one mapped object per index (align by position)
            try {
                def results = []
                def singleKey = (responsesMap.keySet().size() == 1) ? responsesMap.keySet().iterator().next() : null
                for (int i = 0; i < maxRecords; i++) {
                    Map currentRecord = null
                    if (singleKey) currentRecord = (responsesMap[singleKey].records && responsesMap[singleKey].records.size() > i) ? responsesMap[singleKey].records[i] : [:]
                    def mapped = [:]
                    mapping.each { target, sourceSpec ->
                        def resolved = resolveExpression(sourceSpec?.toString(), responsesMap, currentRecord, i)
                        if (customRules.containsKey(target)) mapped[target] = customRules[target](resolved) else mapped[target] = resolved ?: ''
                    }
                    results << mapped
                }
                return [status: 1, message: 'OK', payload: results]
            } catch (IllegalArgumentException e) {
                return [status: 0, message: "Mapping error: ${e.message}", payload: []]
            }
        }

        // Not a <responses> wrapper: parse as existing SOAP->Result->innerXml flow
        def innerXml = envelope.Body?.callResponse?.Result?.text() ?: envelope.'**'.find { it.name() == 'Result' }?.text()
        if (!innerXml) {
            // maybe direct data structure
            def root = envelope
            def rawRecords = []
            try { rawRecords = root.data.record.collect { it } } catch (e) { rawRecords = [] }
            try {
                def mappedList = rawRecords.collect { rec ->
                    def recMap = (rec instanceof GPathResult) ? recordToMap(rec) : (rec instanceof Map ? rec : [value: rec.toString()])
                    def mapped = [:]
                    mapping.each { target, sourceSpec ->
                        def resolved = resolveExpression(sourceSpec?.toString(), [:], recMap)
                        if (customRules.containsKey(target)) mapped[target] = customRules[target](resolved) else mapped[target] = resolved ?: ''
                    }
                    mapped
                }
                return [status: 1, message: 'OK', payload: mappedList]
            } catch (IllegalArgumentException e) {
                return [status: 0, message: "Mapping error: ${e.message}", payload: []]
            }
        }

        try {
            def innerRoot = newSafeSlurper().parseText(innerXml)
            def gRecords = innerRoot.data.record
            def mappedList = gRecords.collect { rec ->
                def recMap = recordToMap(rec)
                def mapped = [:]
                mapping.each { target, sourceSpec ->
                    def resolved = resolveExpression(sourceSpec?.toString(), [:], recMap)
                    if (customRules.containsKey(target)) mapped[target] = customRules[target](resolved) else mapped[target] = resolved ?: ''
                }
                mapped
            }
            return [status: 1, message: 'OK', payload: mappedList]
        } catch (IllegalArgumentException e) {
            return [status: 0, message: "Mapping error: ${e.message}", payload: []]
        }
    } else {
        // JSON path (unchanged)
        def jsonSlurper = new JsonSlurper()
        def root = jsonSlurper.parseText(payload)
        def records = []
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

        try {
            def mappedList = records.collect { record ->
                def recMap = (record instanceof Map) ? record : record
                def mapped = [:]
                mapping.each { target, sourceSpec ->
                    def resolved = resolveExpression(sourceSpec?.toString(), [:], recMap)
                    if (customRules.containsKey(target)) mapped[target] = customRules[target](resolved) else mapped[target] = resolved ?: ''
                }
                mapped
            }
            return [status: 1, message: 'OK', payload: mappedList]
        } catch (IllegalArgumentException e) {
            return [status: 0, message: "Mapping error: ${e.message}", payload: []]
        }
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

