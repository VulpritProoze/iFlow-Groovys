import javax.net.ssl.*
import java.security.cert.*

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