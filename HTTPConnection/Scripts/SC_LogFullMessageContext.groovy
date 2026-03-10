import com.sap.gateway.ip.core.customdev.util.Message
import java.io.Reader

/**
 * SC_LogFullMessageContext.groovy
 * 
 * Debugging utility to log the Message Body, all Headers, and all Properties
 * to the SAP Cloud Integration Message Processing Log (MPL).
 * 
 * Highly recommended for troubleshooting iFlow logic or inspecting data
 * between steps.
 * 
 */
def Message processData(Message message) {
    def messageLog = messageLogFactory.getMessageLog(message)
    
    if (messageLog != null) {
        // 1. Log Headers
        def headers = message.getHeaders()
        def headerLog = new StringBuilder("--- MESSAGE HEADERS ---\n")
        headers.each { key, value ->
            headerLog.append("${key}: ${value}\n")
        }
        messageLog.addAttachmentAsString("Debug_Headers", headerLog.toString(), "text/plain")

        // 2. Log Properties
        def properties = message.getProperties()
        def propertyLog = new StringBuilder("--- MESSAGE PROPERTIES ---\n")
        properties.each { key, value ->
            propertyLog.append("${key}: ${value}\n")
        }
        messageLog.addAttachmentAsString("Debug_Properties", propertyLog.toString(), "text/plain")

        // 3. Log Message Body (Streaming-Safe)
        def reader = message.getBody(Reader.class)
        if (reader != null) {
            def bodyContent = reader.text
            messageLog.addAttachmentAsString("Debug_Body", bodyContent ?: "[Empty Body]", "text/plain")
            
            // Re-set the body so it can be read by subsequent steps
            message.setBody(bodyContent)
            reader.close()
        } else {
            messageLog.addAttachmentAsString("Debug_Body", "[No Body Found]", "text/plain")
        }
    }

    return message
}
