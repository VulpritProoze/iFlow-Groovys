import com.sap.gateway.ip.core.customdev.util.Message

/**
 * Standard SAP Cloud Integration script to clear sensitive headers and properties.
 */
def Message clearSensitiveCredentials(Message message) {
    // List of sensitive keys to be removed from both headers and properties
    def sensitiveKeys = [
        "UserName",
        "Password",
        "CompanyDB",
        "W3P_Id",
        "W3P_Key",
        "SessionId",
        "Authorization"
    ]

    def headers = message.getHeaders()
    def properties = message.getProperties()

    sensitiveKeys.each { key ->
        if (headers.containsKey(key)) {
            headers.remove(key)
        }
        if (properties.containsKey(key)) {
            properties.remove(key)
        }
    }

    return message
}