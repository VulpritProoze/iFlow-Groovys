/**
 * W3PtoSAP_MapUOM.groovy
 * 
 * Dependencies:
 * - Misc/Mapper.groovy (Logic refactored and appended below)
 * - Misc/LoggerService.groovy (Logic refactored and appended below)
 * - Misc/ODataConnection.groovy
 * - Misc/SOAPConnection.groovy
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

    /**
     * QUICK NOTE: Mapping Constants here are intended for mappings that do not require
     * complex orchestration by default. For more complex scenarios see the Remarks below.
     *
     * Mapping configuration for transforming source records into target objects.
     *
     * Capabilities:
     * - Simple flat mappings: map target field -> source token (e.g. ["Code":"fuomid","Name":"fname"]).
     * - Nested mappings: Map values produce nested objects recursively.
     * - Lists: List values produce arrays of resolved items.
     * - Closures: dynamic generators with signature { responsesMap, currentRecord, idx -> ... }.
     * - Expressions: string expressions supporting +, -, *, / with operator precedence.
     *   - Use RESPONSE.field to reference other response sets (for example: "GET_WAREHOUSE.fsiteid").
     *   - Use quoted literals 'text' or "text".
     *   - '+' will concatenate when operands are non-numeric, otherwise perform numeric addition.
     * - Custom rules: use `Constants.CUSTOM_RULES` to register per-path transformation closures.
     *
     * Examples:
     * static final Map MAPPING = [
     *   "Code": "fuomid",                     // simple field mapping
     *   "Name": "fname",                      // simple field mapping
     *   "Dimensions": [                         // nested object
     *       "Height": "h",
     *       "Width": "w"
     *   ],
     *   "Tags": ["tag1", "tag2"],           // static list
     *   "DynamicList": { responses, rec, idx ->   // closure-based dynamic value
     *       return [ A: rec.fname ?: '', B: responses.GET_X?.first?.value ?: '' ]
     *   },
     *   "Price": "unitPrice * quantity"       // arithmetic expression
     * ]
     *
     * Notes:
     * - Expressions and RESPONSE lookups are resolved at runtime by the mapper.
     * - Use `Constants.CUSTOM_RULES['Path.To.Field'] = { val -> ... }` to post-process resolved values.
     *
     * Remarks:
     * - Multi-call mappings: this mapper file exposes both SOAP (`HTTPSOAPConnection`) and OData
     *   (`HTTPODataConnection`) helpers. For complex mappings that need to call additional
     *   APIs (e.g., enrich a record with multiple remote lookups) you can perform those calls
     *   inside mapping `Closure`s or orchestrate them in a pre-processing step and place
     *   the results into a `responses` map referenced via `RESPONSE.field` tokens.
     *
     * - SAP login / session handling: if a mapping or pre-processing step requires logging
     *   into SAP, use the provided utilities (see
     *   the `OData` / `SOAP` connection classes). In an iFlow, add a Content Modifier
     *   before this mapping step to extract and store the SAP login token (or session) into
     *   the process variable store; then the mapping code or closures can read that value
     *   from the variable store or from the `responses` map to attach to downstream calls.
     *
     * - LoggerService.ExtractW3PCredentials is a private method. Use extractW3PCredentials() instead
     */
    static final Map MAPPING = [:]
    static final Map CUSTOM_RULES = [:]

    // Logging Constant/s
    static final String LOG_RECID = "W3P"

    // Uncomment these constants if logging in to SAP
    static final String SESSION_VAR_PROP_NAME = "[B1SESSION]"
    static final String BASE_URL_PROP_NAME = "[SL_BaseURL]"

}

/**
 * Agnostic Mapping Configuration.
 *
 * Usage (default):
 * - Add field mappings to `Constants.MAPPING`. The mapper will automatically
 *   map source fields to target fields and produce JSON suitable for POST by
 *   the next flow step.
 *
 * Advanced/customized usage:
 * - Uncomment and populate `Constants.SESSION_VAR_PROP_NAME` and
 *   `Constants.BASE_URL_PROP_NAME` if your mapping requires an authenticated
 *   SAP session.
 * - To supply SAP login/session token, attach a Content Modifier
 *   before this flow to extract the SAP session into the configured message
 *   property names (the constants above).
 * - Remove the entire `try` block inside `processData` and implement your 
 *   orchestration there (calls, enrichment, batching, etc.).
 *
 * Important:
 * - Do not modify the helper methods below (`extractMappedRecords`, etc.).
 * - If you need additional helpers, add them only after `processData` so the
 *   core helpers remain stable and reusable across flows.
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
    
    // Extract W3P URL to initialize SOAP connection
    def credsMap = LoggerService.extractW3PCredentials()
    if (credsMap.status != 1) {
        logger.logInternal(new LogRequest(stepName: "CREDENTIAL_FAILURE", title: Constants.LOG_RECID, status: "ERROR", inputPayload: payload, outputPayload: credsMap.message))
        return message // Premature return instead of exception
    }

    // If the W3P response indicates processing is done (fdone == 1),
    // short-circuit and return an empty mapping to avoid downstream work.
    if (isFdoneOne(payload)) {
        logger.logBoth(new LogRequest(stepName: "SKIP_DONE", title: Constants.LOG_RECID, status: "OK", inputPayload: payload, outputPayload: "fdone == 1 - skipping mapping"))
        message.setBody(JsonOutput.toJson([]))
        return message
    }

    // Clear out code in try block if customize
    try {
    

    } catch (Exception e) {
        message.setBody(JsonOutput.toJson([]))
        def stackTrace = e.stackTrace.take(15).join('\n')
        def logErrResult = logger.logBoth(new LogRequest(stepName: Constants.STEP_NAME, title: Constants.LOG_RECID, status: "ERROR", inputPayload: "Original Payload length: ${payload?.length() ?: 0}", outputPayload: "Exception: ${e.message}\nStacktrace: ${stackTrace}"))

        if (logErrResult.status != 1) {
            logger.logBoth(new LogRequest(stepName: "ERROR_LOGGING_FAILURE", title: Constants.LOG_RECID, status: "ERROR", inputPayload: payload, outputPayload: "Failed to log original error to process: ${logErrResult.message}\n\nOriginal Error: ${e.message}"))
        }
    }
    
    return message
}

// Add other methods here for customization




















// ================================
// HELPER METHODS
// Do not modify


/**
 * Agnostic Payload Processor (Refactored from Mapper.groovy)
 * Given SOAP XML response (payload), mapping, and mapping rules,
 * outputs an array of Maps consisting of mapped response
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

    // Resolve a single token (plain field)
    // idx: optional index into response records when producing per-record mapped outputs
    def resolveToken = { String token, Map responsesMap, Map currentRecord = null, Integer idx = null ->
        if (!token) return ''
        token = token.trim()
        // literal quoted string
        if ((token.startsWith("\'") && token.endsWith("\'")) || (token.startsWith('"') && token.endsWith('"'))) {
            return token.substring(1, token.length() - 1)
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

        // Resolve a mapping specification which may be:
        // - a String expression -> resolves via resolveExpression
        // - a Map -> treated as a nested mapping (recurses)
        // - a List -> each element resolved and returned as a list
        // - a Closure -> dynamic constructor invoked as closure(responsesMap, currentRecord, idx)
        // path: dot-delimited key path used to apply customRules (if provided)
    def resolveMappingValue = { def spec, Map responsesMap, Map currentRecord = null, Integer idx = null, String path = null ->
        if (spec == null) return ''

        if (spec instanceof Closure) {
            try {
                return spec(responsesMap, currentRecord, idx)
            } catch (e) {
                throw new IllegalArgumentException("Mapping closure for '${path ?: 'root'}' threw: ${e.message}")
            }
        }

        // Nested map -> build nested object recursively
        if (spec instanceof Map) {
            def nested = [:]
            spec.each { nk, nSpec ->
                def childPath = (path ? (path + '.' + nk) : nk.toString())
                nested[nk] = resolveMappingValue(nSpec, responsesMap, currentRecord, idx, childPath)
            }
            return nested
        }

        // List -> resolve each element
        if (spec instanceof List) {
            return spec.collect { item -> resolveMappingValue(item, responsesMap, currentRecord, idx, path) }
        }

        // Leaf: string/primitive -> resolve expression and apply customRules if any for full path
        def resolved = resolveExpression(spec?.toString(), responsesMap, currentRecord, idx)
        if (path != null && customRules.containsKey(path)) {
            return customRules[path](resolved)
        }
        return resolved ?: ''
    }

    // Parse SOAP XML payload using centralized extractor
    if (payload.trim().startsWith('<')) {
        try {
            def records = extractRecordsFromPayload(payload)
            if (records == null) {
                return [status: 0, message: 'Invalid SOAP payload: missing XML declaration or SOAP Envelope', payload: []]
            }

            def mappedList = records.collect { rec ->
                def recMap = (rec instanceof GPathResult) ? recordToMap(rec) : (rec instanceof Map ? rec : [value: rec.toString()])
                def mapped = [:]
                mapping.each { target, sourceSpec ->
                    mapped[target] = resolveMappingValue(sourceSpec, [:], recMap, null, target)
                }
                mapped
            }
            return [status: 1, message: 'OK', payload: mappedList]
        } catch (IllegalArgumentException e) {
            return [status: 0, message: "Mapping error: ${e.message}", payload: []]
        } catch (Exception e) {
            return [status: 0, message: "Failed to parse records: ${e.message}", payload: []]
        }
    } else {
        // Only SOAP/XML payloads are supported by this mapper.
        return [status: 0, message: 'Invalid payload: only SOAP/XML (SOAP envelope) supported', payload: []]
    }
}


/**
 * Detect whether the payload (SOAP envelope or inner Result) contains
 * an <fdone> element with value '1'. This is used to skip mapping when
 * W3P indicates processing is finished.
 */
def isFdoneOne(String payload) {
    if (!payload) return false
    payload = payload.toString()

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

    def trimmed = payload.trim()
    if (! (trimmed.startsWith('<?xml') || trimmed.toLowerCase().startsWith('<soapenv:') || trimmed.toLowerCase().startsWith('<soap:')) ) {
        // Not XML/SOAP — no fdone to inspect
        return false
    }

    try {
        def sl = newSafeSlurper()
        def envelope = sl.parseText(payload)

        // Look for direct <fdone> in envelope
        def direct = envelope.'**'.find { it.name() == 'fdone' }
        if (direct && direct.text()?.trim() == '1') return true

        // If there's an inner <Result> string, unescape and parse it then search for fdone
        def innerXml = envelope.Body?.callResponse?.Result?.text() ?: envelope.'**'.find { it.name() == 'Result' }?.text()
        if (innerXml) {
            def innerUnescaped = unescapeXml(innerXml)
            try {
                def innerRoot = newSafeSlurper().parseText(innerUnescaped)
                def innerFdone = innerRoot.'**'.find { it.name() == 'fdone' }
                if (innerFdone && innerFdone.text()?.trim() == '1') return true
            } catch (e) {
                // ignore parsing errors of inner content
            }
        }
    } catch (e) {
        return false
    }

    return false
}

/**
 * Extracts <record> nodes from a SOAP/XML payload.
 * Returns: List of GPathResult record nodes, empty list when none found,
 * or null when payload is not a SOAP/XML envelope (invalid SOAP).
 */
def extractRecordsFromPayload(String payload) {
    if (!payload) return []
    payload = payload.toString()

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

    def trimmed = payload.trim()
    if (! (trimmed.startsWith('<?xml') || trimmed.toLowerCase().startsWith('<soapenv:') || trimmed.toLowerCase().startsWith('<soap:')) ) {
        return null
    }

    def sl = newSafeSlurper()
    def envelope = sl.parseText(payload)

    // Prefer inner <Result> content when present (may contain escaped inner XML)
    def innerXml = envelope.Body?.callResponse?.Result?.text() ?: envelope.'**'.find { it.name() == 'Result' }?.text()
    if (innerXml) {
        def innerUnescaped = unescapeXml(innerXml)
        def innerRoot = newSafeSlurper().parseText(innerUnescaped)
        def gRecords = []
        try { gRecords = innerRoot.data.record } catch (e) { gRecords = [] }
        return gRecords.collect { it }
    }

    // Fallback: try to read <data><record> directly from envelope
    def rawRecords = []
    try { rawRecords = envelope.data.record.collect { it } } catch (e) { rawRecords = [] }
    return rawRecords
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

/**
 * ODataConnection.groovy
 * 
 * Dependencies:
 * - None
 */
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
     * Executes an HTTP PATCH request.
     * @param request The configuration object containing the URL, payload, and headers.
     * @return Result Map Structure.
     */
    public def patch(ODataRequestBody request) {
        try {
            def con = connect(request.url)
            if (con == null) {
                return [status: -1, message: "Connection URL cannot be empty. Please set the baseUrl for this connection"]
            }
            con.setRequestMethod('PATCH')
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
                if (con.responseCode == 204) return [status: 1, message: "Success"]
                def result = new JsonSlurper().parse(con.inputStream.newReader())
                return [status: 1, message: "Success", payload: result]
            } else {
                def errorText = con.errorStream?.text ?: "No error details provided"
                return [status: -1, message: "PATCH failed. HTTP ${con.responseCode}: $errorText"]
            }
        } catch (Exception e) {
            return [status: -1, message: "PATCH exception: ${e.message}"]
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
 * Standalone method to extract SessionId from a Message Property.
 * Returns standardized Result Map.
 *
 * Usage in another script:
 * def cookieMap = extractSessionCookie(message)
 */
def extractSessionCookie(Message message) {
    String sessionCookie = message.getProperty(Constants.SESSION_VAR_PROP_NAME)
    
    if (!sessionCookie) {
        return [status: -1, message: "SessionCookie is missing."]
    }
    return [status: 1, message: "Success", payload: sessionCookie]
}

/**
 * Extracts the BaseUrl from a Message Property.
 * 
 * @param message The SAP CI Message object.
 * @return Map Result structure with status, message, payload.
 */
def extractBaseUrl(Message message) {
    String baseUrl = message.getProperty(Constants.BASE_URL_PROP_NAME)

    if (!baseUrl) {
        return [status: -1, message: "BaseUrl is missing."]
    }
    return [status: 1, message: "Success", payload: baseUrl]
}



/**
 * ExtractW3PCredentials.groovy
 * 
 * Dependencies:
 * - None
 */


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
 *      def credsMap = extractW3PCredentials()
 *
 *      message.setHeader("W3P_Id", credsMap.id)
 *      message.setHeader("W3P_Key", credsMap.key)
 *      message.setProperty("W3P_BaseUrl", credsMap.baseUrl)
 *
 *      return message
 *  }
 * }
 * </pre>
 */

/**
 * Constants used across integration scripts.
 * Use these to maintain consistency when accessing Security Material.
 */


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