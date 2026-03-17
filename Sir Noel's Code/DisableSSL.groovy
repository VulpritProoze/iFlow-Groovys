import com.sap.gateway.ip.core.customdev.util.Message
import javax.net.ssl.*
import java.security.cert.X509Certificate

def Message processData(Message message) {

    // Disable SSL validation - for testing only!
    def trustAllCerts = [ new X509TrustManager() {
        X509Certificate[] getAcceptedIssuers() { null }
        void checkClientTrusted(X509Certificate[] certs, String authType) {}
        void checkServerTrusted(X509Certificate[] certs, String authType) {}
    }] as TrustManager[]

    SSLContext sc = SSLContext.getInstance("SSL")
    sc.init(null, trustAllCerts, new java.security.SecureRandom())
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory())
    HttpsURLConnection.setDefaultHostnameVerifier({ hostname, session -> true } as HostnameVerifier)

    return message
}
