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

class Constants {
    static final String M_ENTITY = "PRODUCT"
    static final String M_SOAP_SAVE_ACTION = "SAVE_PRODUCT"
    static final String M_SOAP_GET_ACTION = "GET_PRODUCT"
    static final String SapEndpoint = "Items"
    static String getServiceLayerToken(def message) {
        return message.getProperty("ServiceLayerToken")
    }

}
def getLoginToken(Message message) {
    // Parse the response from Service Layer
    def body = message.getBody(String);
    def jsonSlurper = new JsonSlurper();
    def jsonResponse = jsonSlurper.parseText(body);
    // Extract the session ID token
    def sessionId = jsonResponse.SessionId;
    message.setProperty("ServiceLayerToken", sessionId);
    message.setProperty("ServiceLayerCookie", "B1SESSION=" + sessionId);
    return message;
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

def pullDataFromWebpos(Message message) {
    try {
        
        def field = "w3p_" + Constants.M_ENTITY.toLowerCase()
        def localKey = processLocalKey(message)   

        def lastBatchId = localKey[field + '_last_batchid'] ?: "" //message.getProperty("localKeys")?.get(field+ "_last_batchid") 
        def lastKey = localKey[field + '_last_key'] ?: ""
        def newBatchId = localKey[field + '_new_batchid'] ?: ""
        def xmlParser,parsed, resultRaw = ""
        while (true) {
            if (requestWebPOS(lastBatchId, lastKey, newBatchId,message) < 0) return message
            try {

                xmlParser = new XmlSlurper().parseText(message.getProperty("doRequestReturn"))
                resultRaw = xmlParser.Body.callResponse.Result.text()
                    .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                resultRaw = resultRaw.replaceAll("(?i)<\\?xml[^>]*\\?>", "")
                parsed = new XmlSlurper().parseText(resultRaw)
                newBatchId = parsed.data.fnew_batchid.text() ?: ""
                lastKey = parsed.data.flast_key.text() ?: ""

                if (processDataFromWebPOS(message,message.getProperty("doRequestReturn")) < 0) return message
                if (parsed.data.fdone.text() == "1") {
                    def localKeyData = buildLocalKeyData(newBatchId,"","","w3p_"+Constants.M_ENTITY.toLowerCase())
                    def localKeySoapRequest = buildSoapRequest("SAVE_LOCAL_KEY", message.getProperty("W3P_ID"), message.getProperty("W3P_KEY"), localKeyData)
                    def response = sendSoapRequest(localKeySoapRequest,message)
                    break
                }
                def localKeyData = buildLocalKeyData(lastBatchId,lastKey,newBatchId,"w3p_"+Constants.M_ENTITY.toLowerCase())
                def localKeySoapRequest = buildSoapRequest("SAVE_LOCAL_KEY", message.getProperty("W3P_ID"), message.getProperty("W3P_KEY"), localKeyData)
                def response = sendSoapRequest(localKeySoapRequest,message)

            } catch (Exception ex) {
                postLog(message,"ERROR", message.getProperty("doRequestReturn"), ex.toString(), "1", Constants.M_ENTITY.toLowerCase() + ".PullDataFromWebPOS")
                return -1
            }
        }
    } catch (Exception e) {
       return message
    }
    return message
}
def pullDataFromSAP(Message message) {
    try {
        def field = "sap_" + Constants.M_ENTITY.toLowerCase()
        def localKey = processLocalKey(message)   

        def lastBatchId = localKey[field + '_last_batchid'] ?: "" 
        def lastKey = localKey[field + '_last_key'] ?: 0
        def newBatchId = localKey[field + '_new_batchid'] ?: ""

        if (newBatchId == "") {
            newBatchId = getFormattedTimestamp()
            lastKey = 0
        }
        while (true) {
            try {
                def list = getSAPRecord(message,lastBatchId,lastKey,newBatchId)
                message.setProperty("listsize", list?.value?.size())
                if (list?.value?.size() > 0) {
                    message.setProperty("list", list)
                    def itemsToProcess = list.value.take(20) // Example limit
                    message.setProperty("itemsToProcess", itemsToProcess)
                    def action = Constants.M_SOAP_SAVE_ACTION
                    def data = mapSAP2WebPos(itemsToProcess,message)
                    message.setProperty("data", data)
                    if (itemsToProcess?.size() > 0) {
                        lastKey += (lastKey as Integer) + (itemsToProcess?.size() ?: 0)
                        if (doRequest(action, data,message) < 0) return message
                    }else{
                        def localKeyData = buildLocalKeyData(newBatchId,"","","sap_"+Constants.M_ENTITY.toLowerCase())
                        def localKeySoapRequest = buildSoapRequest("SAVE_LOCAL_KEY", message.getProperty("W3P_ID"), message.getProperty("W3P_KEY"), localKeyData)
                        response = sendSoapRequest(localKeySoapRequest,message)
                        message.setProperty("response", response)
                        break
                    }
                } else {
                    def localKeyData = buildLocalKeyData(newBatchId,"","","sap_"+Constants.M_ENTITY.toLowerCase())
                    def localKeySoapRequest = buildSoapRequest("SAVE_LOCAL_KEY", message.getProperty("W3P_ID"), message.getProperty("W3P_KEY"), localKeyData)
                    response = sendSoapRequest(localKeySoapRequest,message)
                    message.setProperty("response", response)
                    break
                }
                def localKeyData = buildLocalKeyData(lastBatchId,lastKey,newBatchId,"sap_"+Constants.M_ENTITY.toLowerCase())
                def localKeySoapRequest = buildSoapRequest("SAVE_LOCAL_KEY", message.getProperty("W3P_ID"), message.getProperty("W3P_KEY"), localKeyData)
                response = sendSoapRequest(localKeySoapRequest,message)
            } catch (Exception ex) {
                postLog(message,"ERROR", list, ex.toString(), "1", Constants.M_ENTITY.toLowerCase() + ".PullDataFromSAP")
                return message
            }
        }
    } catch (Exception e) {
       return message
    }
    return message
}

int processDataFromWebPOS(Message message,doc) {
    try {
        if (sanitizeInput(message,doc) == ""){
            return -1
        }
        Document document = sanitizeInput(message,doc)
        NodeList recordNodes = document.getElementsByTagName("record")
        for (int i = 0; i < recordNodes.getLength(); i++) {
            def item = recordNodes.item(i)
            if(pushToSAP(message,item) < 0) return -1
        }
        return 0
    } catch (Exception e) {
        return -1
    }
}
int pushToSAP(Message message, data) {
    try {
        def jsonRecords = []
        def item = data
        def activeFlag = (item.getElementsByTagName("factive_flag").item(0).textContent == "1") ? "tNO" : "tYES"
        def ftaxType = item.getElementsByTagName("ftaxType").item(0)?.textContent ? "tYES" : "tNO"
        def fSalesItem = (item.getElementsByTagName("fsale_flag").item(0).textContent == "1") ? "tYES" : "tNO"
        def fInventoryItem = item.getElementsByTagName("fproduct_type").item(0)?.textContent ? "tYES" : "tNO"
        def fManageSerial = ""
        def fManageBatch = ""
        def fActiveFlag = (item.getElementsByTagName("factive_flag").item(0).textContent == "1") ? "tYES" : "tNO"

        def costMapping = [
            "FIFO": "bis_FIFO",
            "AVERAGE": "bis_MovingAverage",
            "SNB": "bis_SNB",
            "STANDARD": "bis_Standard"
        ]
        def fCostAccountingMethod = costMapping[message.getProperty("productcostingmethod")] ?: ""

        if (fInventoryItem == "tYES" && 
            message.getProperty("productlotno") == "1" &&
            message.getProperty("inventorycontrol") == "1") {

            def isSingleLot = item.getElementsByTagName("fsingle_lot_flag").item(0)?.textContent == "1"
            fManageSerial = isSingleLot ? "tYES" : "tNO"
            fManageBatch = isSingleLot ? "tNO" : "tYES"
        }

        def jsonRecord = [
            ItemCode: item.getElementsByTagName("fthirdpartyid").item(0).textContent,
            ItemName: item.getElementsByTagName("fname").item(0).textContent,
            BarCode: item.getElementsByTagName("fbarcode1").item(0).textContent,
            VatLiable: ftaxType,
            SalesItem: fSalesItem,
            InventoryItem: fInventoryItem,
            SupplierCatalogNo: item.getElementsByTagName("falternate1").item(0)?.textContent?.take(50),
            ManageSerialNumbers: fManageSerial ?: "tNO",
            ManageBatchNumbers: fManageBatch ?: "tNO",
            Valid: fActiveFlag,
            AvgStdPrice: item.getElementsByTagName("fstnd_cost").item(0).textContent,
            CostAccountingMethod: fCostAccountingMethod,
            ProdStdCost: item.getElementsByTagName("fstnd_cost").item(0).textContent
        ]
        jsonRecords << jsonRecord

        for (def record : jsonRecords) {
            def apiResponse 
            try {
                def requiredFields = ["ItemCode","ItemName", "Valid", "Frozen", "SalesItem", "BarCode", "VatLiable", "SupplierCatalogNo"]
                // Filter record to contain only required fields for updating
                def filteredRecord = record.findAll { key, value -> requiredFields.contains(key) }
                 message.setProperty("filteredRecord", filteredRecord)
                def getData = callServiceLayer("${Constants.SapEndpoint}('${record.ItemCode}')", "GET", null, message)
                if (getData && getData instanceof Map && !getData.containsKey("error")) {
                    if (!isRecordEqual(getData, record)) {
                        message.setProperty("record", filteredRecord)
                        apiResponse = callServiceLayer("${Constants.SapEndpoint}('${record.ItemCode}')", "PATCH", filteredRecord, message)
                        message.setProperty("apiResponse", apiResponse)
                    } 
                } else {
                    message.setProperty("record", record)
                    apiResponse = callServiceLayer("${Constants.SapEndpoint}", "POST", record, message)
                    message.setProperty("apiResponse", apiResponse)
                }
                if (apiResponse?.errorCode) {
                    return -1
                }
            } catch (Exception e) {
                // message.setProperty("errorresponse", e)
                // return -1
                break
            }
        }

    } catch (Exception ex) {
        postLog(message, "ERROR", record.toString(), ex.toString(), "0", ".PushToSAP")
        return -1
    }
    return 0
}
int requestWebPOS(String lastBatchId, String lastKey, String newBatchId,message) {
    try {
        def params = """
            <filter>
                <fsearch_delete_log>1</fsearch_delete_log>
                <flast_batchid>${lastBatchId}</flast_batchid>
                <flast_key>${lastKey}</flast_key>
                <fnew_batchid>${newBatchId}</fnew_batchid>
            </filter>
        """
        return doRequest(Constants.M_SOAP_GET_ACTION, params,message)
    } catch (Exception ex) {
        return -1
    }
}
int doRequest(String action, String params, Message message) {
    try {
        def mError = ""
        boolean isPostLog = (action == "POST_LOG")
        def soapRequestBody = buildSoapRequest(action, message.getProperty("W3P_ID"), message.getProperty("W3P_KEY"), params)
        def response = sendSoapRequest(soapRequestBody, message)
        def retVal = ""
        if (response.responseCode == 200) {
            retVal = response.responseBody
            message.setProperty("retVal", retVal)
        }
        if (retVal == "") {
            if (!isPostLog) {
                mError = "Soap returned an empty data error on " + action
                postLog(message, "ERROR", params, mError, "0", action)
            }
            return -1
        }
        if (!isPostLog) {
            mRawData = retVal
        }
        if (sanitizeInput(message,retVal) == ""){
            return -1
        }
        def doc = sanitizeInput(message, retVal)
        def returnCode = doc.getElementsByTagName("return_code").item(0)?.textContent

        message.setProperty("doRequestReturn", retVal)

        if (returnCode != "0") {
            if (!isPostLog) {
                mError = doc.getElementsByTagName("messages").item(0)?.textContent
                postLog(message, "ERROR", params, mError, "0", action)
            }
            return -1
        }

        return 0

    } catch (Exception ex) {
        if (!isPostLog) {
            mError = ex.toString()
            postLog(message, "ERROR", params, mError, "0", action)
        }
        return -1
    }
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

def buildLocalKeyData(lastBatchId, lastKey,newBatchId,mEntity) {
    data = """
        <record>
            <fkey>${mEntity}_last_batchid</fkey>
            <fvalue>${lastBatchId}</fvalue>
        </record>
        <record>
            <fkey>${mEntity}_last_key</fkey>
            <fvalue>${lastKey}</fvalue>
        </record>
        <record>
            <fkey>${mEntity}_new_batchid</fkey>
            <fvalue>${newBatchId}</fvalue>
        </record>"""
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
    def soapRequestBody = buildSoapRequest("BYPASS_RECORD", message.getProperty("W3P_ID"), message.getProperty("W3P_KEY"), data)
    def response = sendSoapRequest(soapRequestBody,message)
    if (response.responseCode == 200) {
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
    def soapRequestBody = buildSoapRequest("POST_LOG", message.getProperty("W3P_ID"), message.getProperty("W3P_KEY"), data)
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
    def url = new URL(message.getProperty("WebposUrl") + "/appserv/app/w3p/W3PSoapServer.php")
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

def processLocalKey(Message message) {
    try {
        // Parse the XML document
        Document doc = sanitizeInput(message,"")
        NodeList recordNodes = doc.getElementsByTagName("data")
        def localKeys = [:]
        if (recordNodes.length > 0) {
            def dataNode = recordNodes.item(0)
            NodeList dataNodes = dataNode.getElementsByTagName("record")

            for (int i = 0; i < dataNodes.length; i++) {
                def recordNode = dataNodes.item(i)

                def keyNode = recordNode.getElementsByTagName("fkey").item(0)
                def valueNode = recordNode.getElementsByTagName("fvalue").item(0)

                def key = keyNode?.textContent?.trim() ?: ""
                def value = valueNode?.textContent?.trim() ?: ""

                localKeys[key] = value
            }
        }
        message.setProperty("localKeys", localKeys)
        return localKeys
    } catch (Exception e) {
        throw new Exception("Error while processing ${Constants.M_ENTITY} data to SAP: ${e.message}")
    }
   
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
def getSAPRecord(Message message,lastBatchId,lastKey,newBatchId){
    def response = [:]
    def queryParams = "${Constants.SapEndpoint}?\$select=ItemCode,ItemName,Valid,Frozen,SalesItem,PurchaseItem,InventoryItem,BarCode,SupplierCatalogNo,VatLiable,CostAccountingMethod,ProdStdCost,AvgStdPrice,MovingAveragePrice,ManageSerialNumbers,ManageBatchNumbers,InventoryUOM,UoMGroupEntry,U_fupdated_date" +
            "&\$filter=U_fupdated_date%20ge%20'" + lastBatchId + "'%20and%20U_fupdated_date%20le%20'" + newBatchId + "'" +
            "&\$orderby=U_fupdated_date%20asc" +
                "&\$top=100" +
                "&\$skip=" + (lastKey?.toString() ?: "0")
    response = callServiceLayer(queryParams, 'GET', null, message)
    return response
}
// Build Product Data for SOAP Request
def mapSAP2WebPos(itemsToProcess,Message message) {
    def data = ""
    // Check if ItemsToProcess is empty and return empty data
    if (itemsToProcess?.isEmpty()) {
        return data
    }
   
    itemsToProcess.collect { item ->
        def sanitizedItemName = sanitizeString(item.ItemName) 
        def activeFlag = (item.Valid == "tYES") ? "1" : "0"
        def salesFlag = (item.SalesItem == "tYES") ? "1" : "0"
        def purchaseFlag = (item.PurchaseItem == "tYES") ? "1" : "0"
        def vatLiable = (item.VatLiable == "tYES") ? "0" : "2"
        def barCode = item.BarCode ?: ""
        def supplierCatalogNo = item.SupplierCatalogNo ?: ""
        
        // Fetch UoM AbsEntry and UoM Group in parallel
        def uomAbsEntry = callServiceLayer(
            "UnitOfMeasurements?\$filter=Code%20eq%20'${item.InventoryUOM}'", 
            "GET", null, message
        )?.value?.getAt(0)?.AbsEntry ?: 0

        def uomGroup = callServiceLayer(
            "UnitOfMeasurementGroups?\$filter=AbsEntry%20eq%20${item.UoMGroupEntry}", 
            "GET", null, message
        )?.value?.getAt(0)

        // Extract Code and UoM conversion details
        def ugpCode = uomGroup?.Code ?: "PCS" // Default to "PCS" if null
        message.setProperty("ugpCode", ugpCode)
        def matchingUoM = uomGroup?.UoMGroupDefinitionCollection?.find { 
            it.AlternateUoM == uomAbsEntry 
        }

        def invConv = (matchingUoM?.AlternateQuantity ?: 1) / (matchingUoM?.BaseQuantity ?: 1)
        def standardCost = (item.CostAccountingMethod == "bis_Standard") ? 
                            item.ProdStdCost : invConv

        // Set productType and singleLotFlag if inventory controlled
        def (productType, singleLotFlag) = ["", ""]
        productType = (item.InventoryItem  == "tYES") ? "0" : "S" 
        if (item.InventoryItem == "tYES" && 
        (item.ManageSerialNumbers == "tYES" || item.ManageBatchNumbers == "tYES")) {
            productType = "1"
            singleLotFlag = (item.ManageSerialNumbers == "tYES") ? "1" : "0"
        }
    

        // Generate XML record
        data += """
        <record>
            <fthirdpartyid>${item.ItemCode}</fthirdpartyid>
            <fname>${sanitizedItemName}</fname>
            <factive_flag>${activeFlag}</factive_flag>
            <fsale_flag>${salesFlag}</fsale_flag>
            <new_fmemo>Ported From SAP</new_fmemo>
            <fpurchase_flag>${purchaseFlag}</fpurchase_flag>
            <fbarcode1>${barCode}</fbarcode1>
            <falternate1>${supplierCatalogNo}</falternate1>
            <new_ftax_type>${vatLiable}</new_ftax_type>
            <new_fuomid>${ugpCode}</new_fuomid>
            <new_fuom>${ugpCode}</new_fuom>
            <new_fproduct_type>${productType}</new_fproduct_type>
            <new_fsingle_lot_flag>${singleLotFlag}</new_fsingle_lot_flag>
            <fstnd_cost>${standardCost}</fstnd_cost>
            <new_fstnd_cost>${standardCost}</new_fstnd_cost>
           
        </record>
        """
        
            
    }
    message.setProperty("data", data)
    return data
}
// Replace &nbsp; with a space or remove it before building the SOAP request
// Sanitize input by replacing common HTML entities and escaping reserved XML characters
def sanitizeInput(Message message,responseBody) {
   
    try{
        def body = responseBody ?: message.getBody(String)
   
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
        Document doc = builder.parse(inputStream)
        return doc
    }catch(Exception ex){
        return ""
    }
} 
def sanitizeString(String input) {
    return input?.replace("&nbsp;", " ")      // Replace non-breaking spaces
                 .replace("&ndash;", "-")    // Replace en-dash with a standard dash
                 .replace("&mdash;", "--")   // Optionally handle em-dash
                 .replace("&", "&amp;")      // Escape ampersand
                 .replace("<", "&lt;")       // Escape less than
                 .replace(">", "&gt;")       // Escape greater than
                 .replace("\"", "&quot;")    // Escape double quotes
                 .replace("'", "&apos;")     // Escape single quotes
}
boolean isRecordEqual(def data1, def data2) {
    def fieldsToCompare = ["ItemCode", "ItemName", "Valid", "SalesItem", "BarCode", "VatLiable", "SupplierCatalogNo"] // Add more fields if needed

    return fieldsToCompare.every { field -> 
        data1?."$field" == data2?."$field"
    }
}
def callServiceLayer(endpoint, method , payload = null,Message message) {
    def apiResponse = [:]
    try{
        disableSSLVerification()
        def url = new URL("${message.getProperty("ServiceLayerUrl")}/${endpoint}")
        message.setProperty("url", url)
        def connection = url.openConnection()
        if (method == 'PATCH') {
            connection = url.openConnection()
            connection.setRequestMethod("POST")
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH") // Override method
        }else{
            connection.setRequestMethod(method)
        }
        connection.setRequestProperty("Cookie", "B1SESSION=" + Constants.getServiceLayerToken(message))
        connection.setRequestProperty('Content-Type', 'application/json')

        if (payload) {
            connection.doOutput = true
            connection.outputStream.write(JsonOutput.toJson(payload).getBytes('UTF-8'))
        }
        // Handle response safely
        def responseText
        try {
            responseText = connection.inputStream?.text
        } catch (IOException e) {
            responseText = connection.errorStream?.text // Handle HTTP error responses
        }
        // Ensure responseText is not null or empty before parsing
        if (!responseText || responseText.trim().isEmpty()) {
            return [:] // Return empty map instead of parsing null/empty text
        }
        apiResponse = new JsonSlurper().parseText(responseText)
        if (apiResponse?.error) {
            def errorCode = apiResponse.error.code
            def errorMessage = apiResponse.error.message?.value
            
            if (method == 'GET'){
                message.setProperty("test", false)
                return false
            }else{
                if (byPassData(message, Constants.M_ENTITY, payload?.Code ?: "", payload, errorMessage, Constants.M_ENTITY + ".PushtoSAP") == 0) {
                    postLog(message, "ERROR", payload, errorMessage, "1", Constants.M_ENTITY + ".PushtoSAP")
                }
            }
            return [errorCode: errorCode, errorMessage: errorMessage]
        }
    }catch(Exception ex){
        // throw new Exception("Invalid response from API callServiceLayer",ex)
    }
    return apiResponse 
}

