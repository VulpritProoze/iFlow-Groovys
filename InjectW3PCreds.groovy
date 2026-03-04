def InjectW3P() {

}




import com.sap.gateway.ip.core.customdev.util.Message;
import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService
import groovy.xml.XmlUtil
import javax.xml.parsers.SAXParserFactory

def Message processData(Message message) {
    def auth = ITApiFactory.getService(SecureStoreService.class, null)
    def body = message.getBody(java.lang.String)
    
    if (body == null || body.isEmpty()) {
        return message
    }

    def userCredentialName = '[User credential name here]'
    
    def creds = getCredential(auth, userCredentialName)
    def id = creds.getUsername().toString()
    def key = creds.getPassword().toString()
    
    // Security features for XML parsing
    def factory = SAXParserFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    
    def reader = factory.newSAXParser().XMLReader
    def root = new XmlSlurper(reader).parseText(body)
    root.'**'.find { it.name() == 'fw3p_id' }?.replaceBody(id)
    root.'**'.find { it.name() == 'fw3p_key' }?.replaceBody(key)
    message.setBody(XmlUtil.serialize(root))
    
    return message;
}

Object getCredential(Object auth, String credentialKey) {
    if (auth == null) {
        throw new IllegalStateException("auth not available")
    }
    
    def creds = auth.getUserCredential(credentialKey.toString())
    if (creds == null) {
        throw new IllegalStateException("This credential does not exist. Make sure ${credentialKey} is created.")
    }
    
    return creds
}