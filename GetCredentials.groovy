def getCredential(Object auth, String credentialKey) {
    if (auth == null) {
        throw new IllegalStateException("auth not available")
    }
    
    def creds = auth.getUserCredential(credentialKey.toString())
    if (creds == null) {
        throw new IllegalStateException("This credential does not exist. Make sure ${credentialKey} is created.")
    }
    
    return creds
}