import java.net.URL
import java.net.HttpURLConnection
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

class RequestBody {

    String url
    Map<String, Object> payload
    Map<String, String> requestProperty = [
        'Content-Type': 'application/json'
    ]
    boolean isPassSession = true
}

class SessionBasedConnection {

    private String sessionVar
    private String sessionId
    private String baseUrl

    public SessionBasedConnection(String baseUrl, String sessionVariable) {
        this.baseUrl = baseUrl
        this.sessionVar = sessionVariable
    }

    String setSessionVar(String variable) {
        sessionVar = variable
    }

    String setSessionId(String id) {
        sessionId = id
    }

    String setBaseUrl(String url) {
        baseUrl = url
    }

    Object parse(Object payload) {
        if (payload instanceof List && !payload.isEmpty()) {
            return payload[0]
        } else {
            return [:]
        }
    }

    Object parseToJson(Object payload) {
        def transformedPayload = parse(payload)
        if (!transformedPayload || transformedPayload == [:]) {
            return [:]
        }

        return JsonOutput.toJson(transformedPayload)
    }

    public HttpURLConnection connect(String url) {
        if (url == null || '') {
            throw new IllegalStateException('Connection URL cannot be empty. Please set the baseUrl for this connection')
        }

        URL endpoint = new URL(baseUrl + url)
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection()

        if (connection instanceof HttpsURLConnection) {
            disableSSL()
        // throw new IllegalStateException("HTTP connections only.")
        }

        return connection
    }

    public Object get(RequestBody request) {
        def con = connect(request.url)
        con.setRequestMethod('GET')
        for (prop in request.requestProperty) {
            con.setRequestProperty(prop.key, prop.value)
        }

        if (!sessionId && !sessionVar) {
            throw new RuntimeException('Missing sessionId and sessionVar for Connection')
        }

        if (request.isPassSession) {
            con.setRequestProperty('Cookie', sessionVar + '=' + sessionId)
        }

        if (request.payload) {
            con.setDoOutput(true)
            con.outputStream.withCloseable { it << JsonOutput.toJson(request.payload) }
        }

        def response = new JsonSlurper().parse(con.inputStream.newReader())
        return response
    }

    // Function to log in to the SAP Service Layer
    public Object login(Map<String, String> payload) {
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

    void disableSSL() {
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
