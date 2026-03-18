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

import java.net.URL
import java.net.HttpURLConnection
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.XmlUtil
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import java.security.cert.X509Certificate

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
                sb.append("""<?xml version='1.0' encoding='utf-8'?>\n<root>\n  <data>\n""")
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
