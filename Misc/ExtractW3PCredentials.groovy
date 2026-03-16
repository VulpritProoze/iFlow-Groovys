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
class Constants {
    // Security Material Alias Names
    static final String W3P_CRED = "[W3P_CRED]"
    static final String W3P_URL = "[W3P_URL]"
}


/**
 * Method to extract W3P credentials from the SAP Secure Store.
 */
def extractW3PCredentials() {
    def service = ITApiFactory.getService(SecureStoreService.class, null)
    if (service == null) {
        throw new IllegalStateException("SecureStoreService is not available.")
    }

    // Extraction lambda/helper for internal use
    def getCreds = { String key ->
        def creds = service.getUserCredential(key)
        if (creds == null) {
            throw new IllegalStateException("Credential '${key}' not found in Security Material.")
        }
        return creds
    }

    def w3pCreds = getCreds(Constants.W3P_CRED)
    def w3pUrlCreds = getCreds(Constants.W3P_URL)

    return [
        id: w3pCreds.getUsername(),
        key: new String(w3pCreds.getPassword()),
        baseUrl: new String(w3pUrlCreds.getPassword())
    ]
}