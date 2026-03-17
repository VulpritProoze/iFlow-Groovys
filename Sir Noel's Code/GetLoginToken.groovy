import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import javax.net.ssl.*
import java.security.cert.X509Certificate
import java.net.HttpURLConnection
import java.io.OutputStream
import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService
import com.sap.it.api.securestore.UserCredential


def Message processData(Message message) {
    // Disable SSL validation (non-prod only)
    disableSSL()

    // Get Secure Store Service
    SecureStoreService secureStoreService =
            ITApiFactory.getApi(SecureStoreService.class, null)

    if (secureStoreService == null) {
        throw new IllegalStateException("SecureStoreService not available")
    }

    // --- Secure Parameters ---
    def getSecureValue = { alias ->
        UserCredential cred = secureStoreService.getUserCredential(alias)
        if (cred == null) {
            throw new IllegalStateException("Security material not found: " + alias)
        }
        return new String(cred.getPassword())
    }

    String baseUrl     = getSecureValue("SL_URL")
    String companyDB   = getSecureValue("SL_COMPANY")
    String posBaseUrl  = getSecureValue("WEBPOS_URL")


    // 🔹 Get User Credentials
    UserCredential credential =
            secureStoreService.getUserCredential("SL_LOGIN")

    if (credential == null) {
        throw new IllegalStateException("User Credential SL_LOGIN not found")
    }
    String username = credential.getUsername()
    String password = new String(credential.getPassword())

     // 🔹 Get W3P User Credentials
    UserCredential w3pCreds =
            secureStoreService.getUserCredential("W3P_CREDS")

    if (w3pCreds == null) {
        throw new IllegalStateException("User Credential W3P_CREDS not found")
    }
    String w3pUsername = w3pCreds.getUsername()
    String w3pPassword = new String(w3pCreds.getPassword())

   

    // 🔹 Build Login URL
    def serviceLayerUrl = baseUrl + "/Login"

    // 🔹 Build Login Payload
    def loginPayload = [
            "CompanyDB": companyDB,
            "UserName" : username,
            "Password" : password
    ]
    def sessionId = loginToServiceLayer(serviceLayerUrl, loginPayload)
    
    // Store the session ID in message headers or properties for further use
    if (sessionId) {
        message.setProperty("ServiceLayerToken", sessionId)
        message.setProperty("ServiceLayerCookie", "B1SESSION=" + sessionId)
        message.setProperty("ServiceLayerUrl", baseUrl)
        message.setProperty("WebposUrl", posBaseUrl)
        message.setProperty("W3P_ID", w3pUsername)
        message.setProperty("W3P_KEY", w3pPassword)
    } else {
        throw new RuntimeException("Failed to obtain session ID from Service Layer login response.")
    }

    return message
}

// Function to disable SSL validation
void disableSSL() {
    TrustManager[] trustAllCerts = [
        new X509TrustManager() {
            X509Certificate[] getAcceptedIssuers() { return null }
            void checkClientTrusted(X509Certificate[] certs, String authType) {}
            void checkServerTrusted(X509Certificate[] certs, String authType) {}
        }
    ] as TrustManager[]

    SSLContext sc = SSLContext.getInstance("TLS")
    sc.init(null, trustAllCerts, new java.security.SecureRandom())
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory())

    // Replace lambda with an anonymous class for the HostnameVerifier
    HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
        boolean verify(String hostname, SSLSession session) {
            return true
        }
    })
}

// Function to log in to the SAP Service Layer
String loginToServiceLayer(String url, Map<String, String> payload) {
    try {
        URL endpoint = new URL(url)
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection()

        if (connection instanceof HttpsURLConnection) {
            disableSSL()
        }

        connection.setRequestMethod("POST")
        connection.setDoOutput(true)
        connection.setRequestProperty("Content-Type", "application/json")

        // Write payload as JSON
        String jsonPayload = JsonOutput.toJson(payload)
        OutputStream os = connection.getOutputStream()
        os.write(jsonPayload.bytes)
        os.flush()
        os.close()

        // Read the response
        int responseCode = connection.getResponseCode()
        if (responseCode == HttpURLConnection.HTTP_OK) {
            def responseText = connection.getInputStream().text
            def jsonResponse = new JsonSlurper().parseText(responseText)
            return jsonResponse.SessionId
        } else {
            def errorText = connection.getErrorStream()?.text ?: "Unknown error"
            throw new RuntimeException("Service Layer login failed. HTTP Response Code: $responseCode, Error: $errorText")
        }
    } catch (Exception e) {
        throw new RuntimeException("Error during Service Layer login: ${e.message}", e)
    }
}
