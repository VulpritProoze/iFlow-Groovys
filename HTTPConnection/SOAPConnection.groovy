/*
** Note that this is an HTTP Connection by default. For a more secure connection, please use HTTPS.
** This is only intended for testing.
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

/**
 * Represents the configuration for an HTTP SOAP request.
 * Acts as a Data Transfer Object (DTO) to consolidate URL, payload, and headers.
 */
class SOAPRequestBody {
    /** The Map of parameters to be sent in the SOAP body */
    String action
    Map<String, Object> filters = [:]

    /** Request headers (defaults to text/xml) */
    Map<String, String> requestProperty = [
        'Content-Type': 'application/xml'
    ]
}

class HTTPSOAPConnection {

    private String baseUrl
    private String id
    private String key
    private String company

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

    public def setCompany(String company) {
        this.company = company
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
    }

    private String buildEnvelope(SOAPRequestBody request) {
        return """<call>
            <action>${request.action}</action>
            <params>
                <id>
                    <fw3p_id>${this.id}</fw3p_id>
                    <fw3p_key>${this.key}</fw3p_key>
                    <fw3p_company>${this.company}</fw3p_company>
                </id>
                <data>
                    <filter>
                         ${request.filters?.collect { k, v -> "<$k>$v</$k>" }?.join('\n                         ') ?: ''}
                    </filter>
                </data>
            </params>
        </call>"""
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
