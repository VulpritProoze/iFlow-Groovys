/*
** This script is intended to be used as a reusable script collection in SAP Cloud Integration.
** It provides functions for extracting W3P credentials from the Secure Store.
*/

import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService
import com.sap.it.api.securestore.UserCredential

/**
 * SAP BTP iFlow Script function to extract W3P credentials and store them in message headers.
 */
def Message processData(Message message) {
    // 1. Initialize SAP Secure Store Service
    def service = ITApiFactory.getService(SecureStoreService.class, null)
    
    // 2. Extract W3P Credentials
    def w3pCreds = getCredential(service, "[W3PCreds]")

    // 3. Pass values to Message Headers
    message.setHeader("W3P_Id", w3pCreds.getUser())
    message.setHeader("W3P_Key", new String(w3pCreds.getPassword()))

    return message
}

/**
 * Logic from GetCredentials.groovy
 */
def getCredential(Object auth, String credentialKey) {
    if (auth == null) {
        throw new IllegalStateException("Auth is not available. Please create an auth detail via ITAPIFactory.getService passed with SecureStoreService.")
    }
    
    def creds = auth.getUserCredential(credentialKey.toString())
    if (creds == null) {
        throw new IllegalStateException("This credential does not exist. Make sure ${credentialKey} is created in the Security Material.")
    }
    
    return creds
}
