/*
** Note that this is an HTTP Connection by default. For a more secure connection, please use HTTPS.
** This is only intended for testing.
*/

import java.net.URL
import java.net.HttpURLConnection
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Represents the configuration for an HTTP request.
 * Acts as a Data Transfer Object (DTO) to consolidate URL, payload, and headers.
 */
class RequestBody {

    /** The relative endpoint URL to be appended to the base URL */
    String url
    
    /** The Map of data to be sent as a JSON body */
    Map<String, Object> payload
    
    /** Request headers (defaults to application/json) */
    Map<String, String> requestProperty = [
        'Content-Type': 'application/json'
    ]
    
    /** Whether to automatically append the session cookie to the request */
    boolean isPassSession = true
}

class HTTPSessionBasedConnection {

    private String sessionVar
    private String sessionId
    private String baseUrl
    private Object responseBody

    public HTTPSessionBasedConnection(String baseUrl, String sessionVariable) {
        this.baseUrl = baseUrl
        this.sessionVar = sessionVariable
    }

    public def setSessionVar(String variable) {
        sessionVar = variable
        return this
    }

    public def setSessionId(String id) {
        sessionId = id
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
    public def get(RequestBody request) {
        def con = connect(request.url)
        con.setRequestMethod('GET')
        con.doOutput = true
        for (prop in request.requestProperty) {
            con.setRequestProperty(prop.key, prop.value)
        }

        if (request.isPassSession) {
            if (!sessionId && !sessionVar) {
                throw new RuntimeException('Missing sessionId and sessionVar for Connection')
            }
            con.setRequestProperty('Cookie', sessionVar + '=' + sessionId)
        }

        if (request.payload) {
            con.outputStream.withCloseable { it << JsonOutput.toJson(request.payload) }
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
    public def put(RequestBody request) {
        def con = connect(request.url)
        con.setRequestMethod('PUT')
        con.doOutput = true
        for (prop in request.requestProperty) {
            con.setRequestProperty(prop.key, prop.value)
        }

        if (request.isPassSession) {
            if (!sessionId && !sessionVar) {
                throw new RuntimeException('Missing sessionId and sessionVar for Connection')
            }
            con.setRequestProperty('Cookie', sessionVar + '=' + sessionId)
        }

        if (request.payload) {
            con.outputStream.withCloseable { it << JsonOutput.toJson(request.payload) }
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
    public def delete(RequestBody request) {
        def con = connect(request.url)
        con.setRequestMethod('DELETE')
        for (prop in request.requestProperty) {
            con.setRequestProperty(prop.key, prop.value)
        }

        if (request.isPassSession) {
            if (!sessionId && !sessionVar) {
                throw new RuntimeException('Missing sessionId and sessionVar for Connection')
            }
            con.setRequestProperty('Cookie', sessionVar + '=' + sessionId)
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

        if (request.isPassSession) {
            if (!sessionId && !sessionVar) {
                throw new RuntimeException('Missing sessionId and sessionVar for Connection')
            }
            con.setRequestProperty('Cookie', sessionVar + '=' + sessionId)
        }

        if (request.payload) {
            con.setDoOutput(true)
            con.outputStream.withCloseable { it << JsonOutput.toJson(request.payload) }
        }

        int responseCode = con.responseCode
        if (responseCode >= 200 && responseCode < 300) {
            return new JsonSlurper().parse(con.inputStream.newReader())
        } else {
            def errorText = con.errorStream?.text ?: "No error details provided"
            throw new RuntimeException("GET request failed to ${request.url}. HTTP $responseCode: $errorText")
        }
    }

    public def post(RequestBody request) {
        def con = connect(request.url)
        con.setRequestMethod('POST')
        con.setDoOutput(true)
        for (prop in request.requestProperty) {
            con.setRequestProperty(prop.key, prop.value)
        }

        if (request.isPassSession) {
            if (!sessionId && !sessionVar) {
                throw new RuntimeException('Missing sessionId and sessionVar for Connection')
            }
            con.setRequestProperty('Cookie', sessionVar + '=' + sessionId)
        }

        if (request.payload) {
            con.outputStream.withCloseable { it << JsonOutput.toJson(request.payload) }
        }

        int responseCode = con.responseCode
        if (responseCode >= 200 && responseCode < 300) {
            return new JsonSlurper().parse(con.inputStream.newReader())
        } else {
            def errorText = con.errorStream?.text ?: "No error details provided"
            throw new RuntimeException("POST request failed to ${request.url}. HTTP $responseCode: $errorText")
        }
    }

    // Function to log in to the SAP B1 Service Layer
    public def login(Map<String, String> payload) {
        try {
            def url = '/Login'
            def con = connect(url)

            con.setRequestMethod('POST')
            con.setDoOutput(true)
            con.setRequestProperty('Content-Type', 'application/json')

            con.outputStream.withCloseable { it << JsonOutput.toJson(payload) }

            // Read the response
            int code = con.responseCode
            if (con.responseCode == 200) {
                def response = new JsonSlurper().parse(con.inputStream.newReader())
                this.sessionId = response.SessionId
                return parse(response.value)
            } else {
                def errorText = con.getErrorStream()?.text ?: 'Unknown error'
                throw new RuntimeException("Service Layer login failed. HTTP Response Code: $code, Error: $errorText")
            }
        } catch (Exception e) {
            throw new RuntimeException("Error: ${e.message}", e)
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