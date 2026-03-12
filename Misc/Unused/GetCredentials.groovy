/**
 * GetCredentials.groovy
 * 
 * Dependencies:
 * - None
 */
def getCredential(Object auth, String credentialKey) {
    if (auth == null) {
        throw new IllegalStateException("Auth is not available. Please create an auth detail via ITAPIFactory.getService passed with SecureStoreService.")
    }
    
    def creds = auth.getUserCredential(credentialKey.toString())
    if (creds == null) {
        throw new IllegalStateException("This credential does not exist. Make sure ${credentialKey} is created.")
    }
    
    return creds
}