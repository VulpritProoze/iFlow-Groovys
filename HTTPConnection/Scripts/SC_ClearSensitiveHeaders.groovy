/*
** This script is intended to be used as a reusable script collection in SAP Cloud Integration.
** It provides a function to clear sensitive credentials from message headers.
*/

import com.sap.gateway.ip.core.customdev.util.Message

/**
 * SAP BTP iFlow Script function to clear sensitive credentials from headers.
 * This should be used at the end of an iFlow or after an external call to prevent 
 * sensitive data from being logged or forwarded.
 */
def Message clearSensitiveHeaders(Message message) {
    // List of sensitive headers to be removed
    def sensitiveHeaders = [
        "UserName",
        "Password",
        "CompanyDB",
        "W3P_Id",
        "W3P_Key",
        "SessionId"
    ]

    sensitiveHeaders.each { headerName ->
        if (message.getHeaders().containsKey(headerName)) {
            message.removeHeader(headerName)
        }
    }

    return message
}
