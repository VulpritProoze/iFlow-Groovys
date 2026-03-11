import com.sap.gateway.ip.core.customdev.util.Message

/**
 * Standard SAP Cloud Integration script to clear sensitive headers and properties.
 */
class Constants {
    static final List<String> SENSITIVE_PROPS_TO_CLEAR = [
        "UserName",
        "Password",
        "CompanyDB",
        "B1SESSION"
    ]
}

def Message clearSensitiveCredentials(Message message) {
    def headers = message.getHeaders()
    def properties = message.getProperties()

    Constants.SENSITIVE_PROPS_TO_CLEAR.each { key ->
        if (headers.containsKey(key)) {
            headers.remove(key)
        }
        if (properties.containsKey(key)) {
            properties.remove(key)
        }
    }

    return message
}
