/**
 * ExtractW3PCredentials.groovy
 * 
 * Dependencies:
 * - None
 */
import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService
import com.sap.gateway.ip.core.customdev.util.Message

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
 *      def service = ITApiFactory.getService(SecureStoreService.class, null)
 *      def credsMap = extractW3PCredentials(service)
 *
 *      message.setHeader("W3P_Id", credsMap.id)
 *      message.setHeader("W3P_Key", credsMap.key)
 *      message.setProperty("W3P_BaseUrl", credsMap.baseUrl)
 *
 *      return message
 *  }
 * }
 * </pre>
 *
 * @author Ram Alin
 * @version 1.0.0
 */

/**
 * Constants used across integration scripts.
 * Use these to maintain consistency when accessing Security Material.
 */
class Constants {
    // Security Material Alias Names
    static final String W3P_CRED = "W3P_CRED"
    static final String W3P_URL = "W3P_URL"
}


/**
 * Method to extract W3P credentials using the SecureStoreService.
 */
def extractW3PCredentials(SecureStoreService service) {
    // 1. Extract W3P Credentials from Secure Store using Constants
    def w3pCreds = getSecureCredential(service, Constants.W3P_CRED)
    def w3pUrlCreds = getSecureCredential(service, Constants.W3P_URL)

    return [
        id: w3pCreds.getUsername(),
        key: new String(w3pCreds.getPassword()),
        baseUrl: new String(w3pUrlCreds.getPassword())
    ]
}

/**
 * Helper method to safely retrieve credentials from the Secure Store.
 */
private def getSecureCredential(SecureStoreService service, String credentialKey) {
    if (service == null) {
        throw new IllegalStateException("SecureStoreService is not available.")
    }
    
    def creds = service.getUserCredential(credentialKey)
    if (creds == null) {
        throw new IllegalStateException("Credential '${credentialKey}' not found in Security Material.")
    }
    
    return creds
}