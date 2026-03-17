import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import javax.net.ssl.*
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.ZonedDateTime

def getLoginToken(Message message) {
    // Parse the response from Service Layer
    def body = message.getBody(String);
    def jsonSlurper = new JsonSlurper();
    def jsonResponse = jsonSlurper.parseText(body);

    // Extract the session ID token
    def sessionId = jsonResponse.SessionId;

    // Store the session ID in message headers or properties for further requests
    message.setHeader("ServiceLayerToken", sessionId);
    message.setProperty("ServiceLayerToken", sessionId);
     message.setProperty("ServiceLayerCookie", "B1SESSION=" + sessionId);


    
    return message;


    // disableSSLVerification()
    // // Login to the SAP Service Layer and get the session token
    // def serviceLayerUrl = "https://4.145.29.211/b1s/v1/Login"
    // def loginPayload = [
    //     "CompanyDB": "OFWSAPDB",
    //     "UserName": "manager",
    //     "Password": "Ofw@031324"
    // ]
    // def sessionId = loginToServiceLayer(serviceLayerUrl, loginPayload)

    // // Store the session ID in message headers or properties for further use
    // if (sessionId) {
    //     message.setHeader("ServiceLayerToken", sessionId)
    //     message.setProperty("ServiceLayerToken", sessionId)
    // } else {
    //     message.setProperty("Login", "False")
    //     throw new RuntimeException("Failed to obtain session ID from Service Layer login response.")
    // }

    // return message;
}

String loginToServiceLayer(String url, Map<String, String> payload) {
    try {
        URL endpoint = new URL(url)
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection()

        if (connection instanceof HttpsURLConnection) {
            disableSSLVerification()
        }

        connection.setRequestMethod("POST")
        connection.setDoOutput(true)
        connection.setRequestProperty("Content-Type", "application/json")

        // Write payload as JSON
        String jsonPayload = JsonOutput.toJson(payload)
        OutputStream os = connection.getOutputStream()
        os.write(jsonPayload.bytes)
        os.flush()
        os.close()

        // Read the response
        int responseCode = connection.getResponseCode()
        if (responseCode == HttpURLConnection.HTTP_OK) {
            def responseText = connection.getInputStream().text
            def jsonResponse = new JsonSlurper().parseText(responseText)
            return jsonResponse.SessionId
        } else {
            def errorText = connection.getErrorStream()?.text ?: "Unknown error"
            throw new RuntimeException("Service Layer login failed. HTTP Response Code: $responseCode, Error: $errorText")
        }
    } catch (Exception e) {
        throw new RuntimeException("Error during Service Layer login: ${e.message}", e)
    }
}



// Function to disable SSL certificate validation (use only for testing)
def disableSSLVerification() {
    TrustManager[] trustAllCerts = [ new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() { null }
        public void checkClientTrusted(X509Certificate[] certs, String authType) { }
        public void checkServerTrusted(X509Certificate[] certs, String authType) { }
    } ]

    SSLContext sc = SSLContext.getInstance("SSL")
    sc.init(null, trustAllCerts, new java.security.SecureRandom())
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory())
    HttpsURLConnection.setDefaultHostnameVerifier({ hostname, session -> true })
}

// Define the Message processing function
def Message processData(Message message) {
    // disableSSLVerification() // Disable SSL verification for testing

    def body = message.getBody(String)
    body = body.replaceAll("<\\?xml[^>]*\\?>", "").trim()
    def messageLog = messageLogFactory.getMessageLog(message)
    if (messageLog != null) {
        messageLog.addAttachmentAsString("Body After XML Declaration Removal", body, "text/xml")
    }
    body = body.replaceAll("&lt;", "<").replaceAll("&gt;", ">")
    body = body.replaceAll("(?i)<\\?xml[^>]*\\?>", "")

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(true)
    def builder = factory.newDocumentBuilder()
    def inputStream = new ByteArrayInputStream(body.getBytes("UTF-8"))
     message.setProperty("inputStream", inputStream)
    def results = []
    try {
        Document doc = builder.parse(inputStream)
        NodeList recordNodes = doc.getElementsByTagName("record")

        // Extract the specific elements using DOM methods
        def fnew_batchidNode = doc.getElementsByTagName("fnew_batchid").item(0)
        def flast_batchidNode = doc.getElementsByTagName("flast_batchid").item(0)
        def flast_keyNode = doc.getElementsByTagName("flast_key").item(0)
        def fdoneNode = doc.getElementsByTagName("fdone").item(0)
        def jsonRecords = []

        for (int i = 0; i < recordNodes.getLength(); i++) {
            def item = recordNodes.item(i)
            def jsonRecord = [
                ItemCode: item.getElementsByTagName("fproductid").item(0).textContent,
                ItemName: item.getElementsByTagName("fname").item(0).textContent,
                ItemType: "itItems"
            ]
            jsonRecords << jsonRecord
        }
        def jsonBuilder = new JsonBuilder(jsonRecords)
        for (def record : jsonRecords) {
            def apiResponse
            try {

                apiResponse = recordExists(record.ItemCode, message) ? patchData(record, message) : postData(record, message)
                // Check for errorCode in the API response
                if (apiResponse?.errorCode) {
                    results << [record: record, status: "Error", errorMessage: apiResponse.errorMessage]
                    message.setProperty("errorFound", "true")

                    // byPassData(message, "SAVE_PRODUCT",record.ItemCode,record,apiResponse.errorMessage,"SAVE_PRODUCT.PushtoSAP") 
                    // postLog(message, "ERROR",record,apiResponse.errorMessage,"1","Product.PushtoSAP") 

                    if (byPassData(message, "SAVE_PRODUCT", record.ItemCode, record, apiResponse.errorMessage, "SAVE_PRODUCT.PushtoSAP") == 0) {
                        postLog(message, "ERROR", record, apiResponse.errorMessage, "1", "Product.PushtoSAP")
                    }
                    break
                } else {
                    results << [record: record, status: "Success", response: apiResponse]
                }
            } catch (Exception e) {
                results << [record: record, status: "Failed", error: e.getMessage()]
                break
            }
        }

        if (messageLog != null) {
            messageLog.addAttachmentAsString("API Responses", results.toString(), "text/plain")
        }

        message.setBody(jsonBuilder.toString())
        message.setProperty("groovyResult", jsonBuilder.toString())
        message.setProperty("w3p_product_last_batchid", flast_batchidNode?.textContent)
        message.setProperty("w3p_product_last_key", flast_keyNode?.textContent)
        message.setProperty("w3p_product_new_batchid", fnew_batchidNode?.textContent)
        message.setProperty("fdone", fdoneNode?.textContent)
        
         // Save local keys after processing
        def localKeyData = buildLocalKeyData(message, recordNodes,"w3p")
        def localKeySoapRequest = buildSoapRequest("SAVE_LOCAL_KEY", "22081685", "12345", localKeyData)

        // Set SOAP XML string as the message body
        ByteArrayInputStream localKeyInput = new ByteArrayInputStream(localKeySoapRequest.getBytes("UTF-8"))
        message.setBody(localKeyInput)
        message.setProperty("localKeyInput", localKeyInput)
        
        
        // Set results property to pass to content modifier
        message.setProperty("postingResults", new JsonBuilder(results).toString())
    } catch (Exception e) {
        if (messageLog != null) {
            messageLog.addAttachmentAsString("XML Parsing Error", e.getMessage(), "text/plain")
        }
        throw e
    }

    return message
}

def recordExists(String itemCode, Message message) {
    disableSSLVerification()

    message.setProperty("gothere", "gothere")

    def sessionID = message.getProperty("ServiceLayerToken")
    def url = new URL("https://saptest.alliancewebpos.com/b1s/v1/Items('${itemCode}')")
    HttpURLConnection connection = (HttpURLConnection) url.openConnection()
    connection.setRequestMethod("GET")
    connection.setRequestProperty("Cookie", "B1SESSION=" + sessionID)

    try {
        def responseCode = connection.getResponseCode()
        return responseCode == 200
    } catch (Exception e) {
        println "Error checking record: ${e.message}"
        return false
    }
}

def postData(Map record, Message message) {
    disableSSLVerification()

     message.setProperty("gothere", "gothere")


    def jsonData = new JsonBuilder(record).toString()
    def sessionID = message.getProperty("ServiceLayerToken")
    def url = new URL("https://saptest.alliancewebpos.com/b1s/v1/Items")
    HttpURLConnection connection = (HttpURLConnection) url.openConnection()
    connection.setRequestMethod("POST")
    connection.setDoOutput(true)
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setRequestProperty("Cookie", "B1SESSION=" + sessionID)

    connection.getOutputStream().write(jsonData.getBytes("UTF-8"))

    def responseCode = connection.getResponseCode()
    // def response = connection.inputStream.text


    // return response


    def responseText = ""
    
    if (responseCode >= 200 && responseCode < 300) {
        // Read successful response
        responseText = connection.inputStream.text
    } else {
        // Read error response
        responseText = connection.errorStream.text
    }

    // Parse JSON response
    def jsonSlurper = new JsonSlurper()
    def jsonResponse = jsonSlurper.parseText(responseText)

    message.setProperty("jsonResponse", jsonResponse)


    if (jsonResponse?.error) {
        def errorCode = jsonResponse.error.code
        def errorMessage = jsonResponse.error.message?.value
        
        // Return error details if necessary
        return [errorCode: errorCode, errorMessage: errorMessage]
    }

    return jsonResponse

}

def patchData(Map record, Message message) {
    disableSSLVerification()
    def jsonData = new JsonBuilder(record).toString()
    def sessionID = message.getProperty("ServiceLayerToken")
    def serviceLayerURL = new URL("https://saptest.alliancewebpos.com/b1s/v1/Items('${record.ItemCode}')")

    HttpURLConnection connection = (HttpURLConnection) serviceLayerURL.openConnection()
    connection.setRequestProperty("X-HTTP-Method-Override", "PATCH") // Override method
    connection.setRequestMethod("POST") // Set as POST but override as PATCH
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setRequestProperty("Cookie", "B1SESSION=" + sessionID)
    connection.setDoOutput(true)

    connection.getOutputStream().write(jsonData.getBytes("UTF-8"))

    def responseCode = connection.getResponseCode()
    // def response = connection.inputStream.text
    // return response
    def responseText = ""
    
    if (responseCode >= 200 && responseCode < 300) {
        // Read successful response
        responseText = connection.inputStream.text
    } else {
        // Read error response
        responseText = connection.errorStream.text
    }

    // Parse JSON response
    def jsonSlurper = new JsonSlurper()
    def jsonResponse = jsonSlurper.parseText(responseText)

    if (jsonResponse?.error) {
        def errorCode = jsonResponse.error.code
        def errorMessage = jsonResponse.error.message?.value
        
        // Return error details if necessary
        return [errorCode: errorCode, errorMessage: errorMessage]
    }

    return jsonResponse
}

def getData(Message message) {
    disableSSLVerification()  // Ignore SSL verification (if needed)

    
    def sessionID = message.getProperty("ServiceLayerToken")
    if (!sessionID) {
        throw new Exception("Session token is missing or invalid.")
    }
    def url = new URL("https://saptest.alliancewebpos.com/b1s/v1/Items?\$select=ItemCode,ItemName,U_fupdated_date&\$filter=U_fupdated_date ge '' and U_fupdated_date le '20250203142640'&\$orderby=U_fupdated_date asc&\$top=100&\$skip=0")  // SAP B1 API Endpoint
    // Construct URL with encoded parameters
    def baseUrl = "https://saptest.alliancewebpos.com/b1s/v1/Items"
    def queryParams = "?\$select=ItemCode,ItemName,U_fupdated_date&\$filter=" +
                      URLEncoder.encode("U_fupdated_date ge '' and U_fupdated_date le '20250203142640'", "UTF-8") +
                      "&\$orderby=U_fupdated_date asc&\$top=100&\$skip=0"

    //def url = new URL(baseUrl + queryParams)
    // Open connection
    HttpURLConnection connection = (HttpURLConnection) url.openConnection()
    connection.setRequestMethod("GET")
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setRequestProperty("Accept", "application/json")  // Ensure JSON response
    connection.setRequestProperty("Cookie", "B1SESSION=" + sessionID)

    // Get response code
    def responseCode = connection.getResponseCode()
    message.setProperty("responseCode", responseCode)

    def responseText = ""

    if (responseCode >= 200 && responseCode < 300) {
        responseText = connection.inputStream.text  // Read successful response
    } else {
        responseText = connection.errorStream?.text ?: "Error with no response text"
    }

    // Check if response is JSON
    def contentType = connection.getContentType()
    if (!contentType?.toLowerCase()?.contains("application/json")) {
        throw new Exception("Unexpected response type: " + contentType + "\nResponse:\n" + responseText)
    }

    // Parse JSON response
    def jsonSlurper = new JsonSlurper()
    def jsonResponse = jsonSlurper.parseText(responseText)

    message.setProperty("jsonResponse", jsonResponse)  // Store response for further use

    if (jsonResponse?.error) {
        def errorCode = jsonResponse.error.code
        def errorMessage = jsonResponse.error.message?.value
        message.setProperty("ErrorCode", errorCode)
        message.setProperty("ErrorMessage", errorMessage)
    }

    message.setBody(responseText)  // Set response as message body
    return message
}

// Build SOAP Request
def buildSoapRequest(String action, String fw3p_id, String fw3p_key, String data) {
    // Define the SoapEnvelope template
    def soapEnvelopeTemplate = """
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
            <soapenv:Header/>
            <soapenv:Body>
                {0}
            </soapenv:Body>
        </soapenv:Envelope>"""

    // Define the SoapFunction template
    def soapFunctionTemplate = """
        <call>
            <action>{0}</action>
            <params>
                <id>
                    <fw3p_id>{1}</fw3p_id>
                    <fw3p_key>{2}</fw3p_key>
                </id>
                <data>
                    {3}
                </data>
            </params>
        </call>"""

    // Format the SoapFunction template by replacing placeholders
    def soapFunction = soapFunctionTemplate.replace("{0}", action)
                                           .replace("{1}", fw3p_id)
                                           .replace("{2}", fw3p_key)
                                           .replace("{3}", data)

    // Format the SoapEnvelope template by inserting the SoapFunction
    return soapEnvelopeTemplate.replace("{0}", soapFunction)
}

def buildLocalKeyData(Message message, itemsToProcess,String mEntity) {
    def count = 0
    def lastBatchId = message.getProperty(mEntity + "_product_last_batchid") ?: ""
    def lastKey = (message.getProperty(mEntity +"_product_last_key") ?: "0").toInteger() // Ensure numeric value
    def newBatchId = message.getProperty(mEntity +"_product_new_batchid") ?: ""

    if (newBatchId == "") {
        newBatchId = getFormattedTimestamp()
        lastKey = ""
    }

    def data = ""

    try {
        count = itemsToProcess.getLength()
        message.setProperty("getLength", itemsToProcess.getLength())
     } catch (Exception e) {
        count = itemsToProcess?.size()
        message.setProperty("getLength", itemsToProcess?.size())
    }

    if (count == 0) {
        data = """
            <record>
                <fkey>${mEntity}_product_last_batchid</fkey>
                <fvalue>${newBatchId}</fvalue>
            </record>
            <record>
                <fkey>${mEntity}_product_last_key</fkey>
                <fvalue></fvalue>
            </record>
            <record>
                <fkey>${mEntity}_product_new_batchid</fkey>
                <fvalue></fvalue>
            </record>"""
    } else {
        lastKey += count // Correct numeric addition
        data = """
            <record>
                <fkey>${mEntity}_product_last_batchid</fkey>
                <fvalue>${lastBatchId}</fvalue>
            </record>
            <record>
                <fkey>${mEntity}_product_last_key</fkey>
                <fvalue>${lastKey}</fvalue>
            </record>
            <record>
                <fkey>${mEntity}_product_new_batchid</fkey>
                <fvalue>${newBatchId}</fvalue>
            </record>"""
    }
    message.setProperty("data", data)
    return data
}
def byPassData(Message message, frecordid,fid,finput_param,ferror_msg,faction) {
   
    def data = ""
    data = """
        <frecordid>${frecordid}</frecordid>
        <fid>${fid}</fid>
        <finput_param>${finput_param}</finput_param>
        <ferror_msg>${ferror_msg}</ferror_msg>
        <faction>${faction}</faction>
        <fsource>1</fsource>"""

    def soapRequestBody = buildSoapRequest("BYPASS_RECORD", "22081685", "12345", data)

    def response = sendSoapRequest(soapRequestBody,message)

    if (response.responseCode == 200) {
        // println "Response Body: ${response.responseBody}"
        message.setProperty("byPassResponseBody", response.responseBody)

       // Parse the raw SOAP response
        def xmlParser = new XmlSlurper().parseText(response.responseBody)

        // Extract and clean the inner XML (Result)
        def fbypassFlag = xmlParser.Body.callResponse.Result.text()
            .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
        fbypassFlag = fbypassFlag.replaceAll("(?i)<\\?xml[^>]*\\?>", "")

        // Parse the cleaned XML
        def parsedInnerXml = new XmlSlurper().parseText(fbypassFlag)

        // Extract the fbypass_flag value
        def fbypassValue = parsedInnerXml.data.fbypass_flag.text()
        message.setProperty("fbypassValue", fbypassValue)


        // Return 1 if fbypass_flag is "1", otherwise 0
        return fbypassValue == "1" ? 1 : 0
    } else {
        // println "Error: ${response.responseBody}"
        message.setProperty("byPassResponseBody", response.responseBody)
    }
}

def postLog(Message message, fstatus_flag,finput_param,foutput_param,ffrom_sap,faction) {
   
    def data = ""
    data = """
        <frecordid>W3P</frecordid>
        <fstatus_flag>${fstatus_flag}</fstatus_flag>
        <finput_param>${finput_param}</finput_param>
        <foutput_param>${foutput_param}</foutput_param>
        <ffrom_sap>${ffrom_sap}</ffrom_sap>
        <faction>${faction}</faction>"""

    def soapRequestBody = buildSoapRequest("POST_LOG", "22081685", "12345", data)

    def response = sendSoapRequest(soapRequestBody,message)

    if (response.responseCode == 200) {
        // println "Response Body: ${response.responseBody}"
        message.setProperty("postLogResponseBody", response.responseBody)
    } else {
        // println "Error: ${response.responseBody}"
        message.setProperty("postLogResponseBody", response.responseBody)
    }
}
def sendSoapRequest( String soapRequestBody,message) {
    def connection = null
    def url = new URL("https://stjul2.alliancewebpos.net/appserv/app/w3p/W3PSoapServer.php")
    try {
        // Open connection
        connection = url.openConnection() as HttpURLConnection
        connection.with {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/xml")
            doOutput = true
            outputStream.withWriter("UTF-8") { writer ->
                writer << soapRequestBody
            }
        }

        // Get response
        def responseCode = connection.responseCode
        def responseStream = responseCode in 200..299 ? connection.inputStream : connection.errorStream
        def responseBody = responseStream?.getText("UTF-8")

        return [responseCode: responseCode, responseBody: responseBody]
    } catch (Exception e) {
        return [responseCode: 500, responseBody: e.message]
    } finally {
        connection?.disconnect()
    }
}

def processWebposData(Message message) {
    // Retrieve the body as a string
    def body = message.getBody(String)
    
    // Remove all XML declarations
    body = body.replaceAll("<\\?xml[^>]*\\?>", "").trim()

    // Log the body content for debugging
    def messageLog = messageLogFactory.getMessageLog(message)
    if (messageLog != null) {
        messageLog.addAttachmentAsString("Body After XML Declaration Removal", body, "text/xml")
    }

    // Decode HTML entities back to XML format
    body = body.replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&amp;", "&")

    // Remove any remaining XML declarations
    body = body.replaceAll("(?i)<\\?xml[^>]*\\?>", "")

    // Try parsing the modified XML content
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(true)
    def builder = factory.newDocumentBuilder()
    def originalInputStream = new ByteArrayInputStream(body.getBytes("UTF-8"))

    try {
        // Parse the XML document
        Document doc = builder.parse(originalInputStream)
        
        // Extract the specific elements using DOM methods
        NodeList recordNodes = doc.getElementsByTagName("record")
        def returnCodeNode = doc.getElementsByTagName("return_code").item(0)
        def returnCode = returnCodeNode?.textContent?.trim() ?: ""

        // Define variables to hold extracted values
        def lastBatchId = ""
        def lastKey = ""
        def newBatchId = ""
        def sapLastBatchId = ""
        def sapLastKey = ""
        def sapNewBatchId = ""

        // Loop through records and extract specific elements
        for (int i = 0; i < recordNodes.length; i++) {
            def record = recordNodes.item(i)
            def recordId = record.attributes.getNamedItem("id")?.nodeValue

            if (recordId == "w3p_product_last_batchid") {
                def fvalueNode = record.getElementsByTagName("fvalue").item(0)
                lastBatchId = fvalueNode?.textContent?.trim() ?: ""
            } else if (recordId == "w3p_product_last_key") {
                def fvalueNode = record.getElementsByTagName("fvalue").item(0)
                lastKey = fvalueNode?.textContent?.trim() ?: ""
            } else if (recordId == "w3p_product_new_batchid") {
                def fvalueNode = record.getElementsByTagName("fvalue").item(0)
                newBatchId = fvalueNode?.textContent?.trim() ?: ""
            } else if (recordId == "sap_product_last_batchid") {
                def fvalueNode = record.getElementsByTagName("fvalue").item(0)
                sapLastBatchId = fvalueNode?.textContent?.trim() ?: ""
            } else if (recordId == "sap_product_last_key") {
                def fvalueNode = record.getElementsByTagName("fvalue").item(0)
                sapLastKey = fvalueNode?.textContent?.trim() ?: ""
            } else if (recordId == "sap_product_new_batchid") {
                def fvalueNode = record.getElementsByTagName("fvalue").item(0)
                sapNewBatchId = fvalueNode?.textContent?.trim() ?: ""
            }
        }
        
        // The SOAP XML content as a string
        def soapXml = """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
            <soapenv:Header />
            <soapenv:Body>
                <call>
                    <action>GET_PRODUCT</action>
                    <params>
                        <id>
                            <fw3p_id>22081685</fw3p_id>
                            <fw3p_key>12345</fw3p_key>
                        </id>
                        <data>
                            <filter>
                                <fnew_batchid>${newBatchId}</fnew_batchid>
                                <flast_batchid>${lastBatchId}</flast_batchid>
                                <flast_key>${lastKey}</flast_key>
                            </filter>
                        </data>
                    </params>
                </call>
            </soapenv:Body>
        </soapenv:Envelope>"""

        // Set the SOAP XML string as the message body
        // message.setBody(soapXml)
        ByteArrayInputStream soapXmlInput = new ByteArrayInputStream(soapXml.getBytes("UTF-8"))
        message.setProperty("soapXml", soapXmlInput)
        
        // Set properties
        message.setProperty("w3p_product_last_batchid", lastBatchId)
        message.setProperty("w3p_product_last_key", lastKey)
        message.setProperty("w3p_product_new_batchid", newBatchId)
        message.setProperty("return_code", returnCode)
        message.setProperty("sap_product_last_batchid", sapLastBatchId)
        message.setProperty("sap_product_last_key", sapLastKey)
        message.setProperty("sap_product_new_batchid", sapNewBatchId)

        // Set default values for empty or null variables
        if (sapNewBatchId == "") {
            sapNewBatchId = getFormattedTimestamp()
            sapLastKey = "0"
        }


        // sapLastBatchId = sapLastBatchId ?: "20000000000000" // Example default date in ISO 8601
        // sapNewBatchId = sapNewBatchId ?: getFormattedTimestamp() 
        sapLastKey = sapLastKey?.isInteger() ? sapLastKey : "0"

        // Construct the dynamic URL
        def baseUrl = "https://saptest.alliancewebpos.com/b1s/v1/Items"
        // Ensure that the $ symbol is treated literally by concatenating the parts of the query
        def queryParams = "\$select=ItemCode,ItemName,U_fupdated_date" +
                  "&\$filter=U_fupdated_date ge '" + sapLastBatchId + "' and U_fupdated_date le '" + sapNewBatchId + "'" +
                  "&\$orderby=U_fupdated_date asc" +
                  "&\$top=100" +
                  "&\$skip=" + sapLastKey

        // Combine base URL and query parameters
        def fullUrl = baseUrl + "?" + queryParams

        // Log the constructed URL for debugging
        if (messageLog != null) {
            messageLog.addAttachmentAsString("Constructed URL", fullUrl, "text/plain")
        }

        // Set the dynamic URL in a property
        message.setProperty("baseUrl", baseUrl)
        message.setProperty("targetURL", fullUrl)
        message.setProperty("queryParams", queryParams)

    } catch (Exception e) {
        // Log error for troubleshooting
        if (messageLog != null) {
            messageLog.addAttachmentAsString("XML Parsing Error", e.getMessage(), "text/plain")
        }
        throw e
    }

    return message
}
def getFormattedTimestamp() {
    // Get the current time in Philippine Time (Asia/Manila)
    def manilaZone = ZoneId.of("Asia/Manila")
    def now = ZonedDateTime.now(manilaZone)
    
    // Define the formatter with the desired pattern
    def formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    
    // Return the formatted timestamp
    return now.format(formatter)
}
def processSapData(Message message) {
    try {
          
        if (message == null) {
            throw new IllegalArgumentException("Message object is null")
        }

        // Ensure message body exists
        def inputJson = message.getBody(String)
        if (inputJson == null || inputJson.isEmpty()) {
            throw new IllegalArgumentException("Message body is null or empty")
        }

        // Parse the JSON input
        def jsonSlurper = new JsonSlurper()
        def parsedJson = jsonSlurper.parseText(inputJson)


        message.setProperty("parsedJson", parsedJson)

        // Limit records to process
        def itemsToProcess = parsedJson.value.take(20) // Example limit
        message.setProperty("ItemsToProcess", itemsToProcess)

        
        // Define action and credentials
        def action = 'SAVE_PRODUCT'
        def fw3p_id = '22081685'
        def fw3p_key = '12345'
        def data = buildPosData(itemsToProcess)

        if (itemsToProcess?.size() > 0) {
             // Call the function to build the SOAP request
            def soapRequest = buildSoapRequest(action, fw3p_id, fw3p_key, data)
             message.setProperty("soapRequest", soapRequest)
            ByteArrayInputStream inputStream = new ByteArrayInputStream(soapRequest.getBytes("UTF-8"))
            message.setBody(inputStream)
            message.setProperty("inputStream", inputStream)
        }
         // Set SOAP XML string as the message body
       

        // Save local keys after processing
        def localKeyData = buildLocalKeyData(message, itemsToProcess,"sap")
        def localKeySoapRequest = buildSoapRequest("SAVE_LOCAL_KEY", fw3p_id, fw3p_key, localKeyData)

        // Set SOAP XML string as the message body
        ByteArrayInputStream localKeyInput = new ByteArrayInputStream(localKeySoapRequest.getBytes("UTF-8"))
        message.setBody(localKeyInput)
        message.setProperty("localKeyInput", localKeyInput)

    } catch (Exception e) {
        // Log and handle errors
        def errorMessage = "Error in transformToSoapRequest: ${e.message}"
        println errorMessage
        message.setBody(errorMessage)
        message.setProperty("ErrorDetails", e.stackTrace.toString())
    }
    return message
}
// Build POS Data for SOAP Request
def buildPosData(itemsToProcess) {
    def data = ""
    // Check if ItemsToProcess is empty and return empty data
    if (itemsToProcess?.isEmpty()) {
        return data
    }
    itemsToProcess.each { item ->
        def sanitizedItemName = sanitizeInput(item.ItemName) // Sanitize ItemName
        data += """
            <record>
                <fproductid>${item.ItemCode}</fproductid>
                <fname>${sanitizedItemName}</fname>
                <factive_flag>1</factive_flag>
                <fproduct_type>0</fproduct_type>
                <fuomid>PCS</fuomid>
            </record>"""
    }
    return data
}
// Replace &nbsp; with a space or remove it before building the SOAP request
// Sanitize input by replacing common HTML entities and escaping reserved XML characters
def sanitizeInput(String input) {
    return input?.replace("&nbsp;", " ")      // Replace non-breaking spaces
                 .replace("&ndash;", "-")    // Replace en-dash with a standard dash
                 .replace("&mdash;", "--")   // Optionally handle em-dash
                 .replace("&", "&amp;")      // Escape ampersand
                 .replace("<", "&lt;")       // Escape less than
                 .replace(">", "&gt;")       // Escape greater than
                 .replace("\"", "&quot;")    // Escape double quotes
                 .replace("'", "&apos;")     // Escape single quotes
}