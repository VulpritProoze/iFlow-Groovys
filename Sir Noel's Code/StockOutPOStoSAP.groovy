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
import java.text.SimpleDateFormat

class Constants {
    static final String M_ENTITY = "ADJOUT"
    static final String M_SOAP_SAVE_ACTION = "SAVE_STOCK_ADJUSTMENT"
    static final String M_SOAP_GET_ACTION = "GET_STOCK_ADJUSTMENT"
    static final String SapEndpoint = "InventoryGenExits"
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
            if (requestWebPOS(lastBatchId, lastKey, newBatchId,localKey,message) < 0) return message
            try {

                //def doc = sanitizeInput(message, retVal)
                xmlParser = new XmlSlurper().parseText(message.getProperty("doRequestReturn"))
                resultRaw = xmlParser.Body.callResponse.Result.text()
                    .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                resultRaw = resultRaw.replaceAll("(?i)<\\?xml[^>]*\\?>", "")
                resultRaw = replaceSpecialChars(resultRaw)
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
                message.setProperty("ErrorDetails",ex)
                return message
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
                    def data = mapSAP2WebPos(itemsToProcess)
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
        // message.setProperty("gothere","gothere")
     
        def jsonRecords = []
        def item = data
        def fkey = item.getElementsByTagName("fkey").item(0)?.textContent
        
        def queryCheck = "InventoryGenExits?\$filter=U_fkey%20eq%20'${fkey}'"
        def response = callServiceLayer(queryCheck, 'GET', null, message)
        if (!response.containsKey("value") || response.value == null || response.value.isEmpty()) {
            message.setProperty("docEntry","")
        }else{
            def docEntry = response.value[0]?.DocEntry ?: 0
            if (docEntry) {
                message.setProperty("docEntry",docEntry)
                return 0 // Skip to the next record
            }
        }
       
        message.setProperty("queryCheck",queryCheck)
        message.setProperty("response",response)
        def inputDateFormat = new SimpleDateFormat("yyyyMMdd") // Matches "20220913"
        def outputDateFormat = new SimpleDateFormat("MM/dd/yyyy") // Desired format

        def docDate = item.getElementsByTagName("ftrxdate").item(0).textContent
        def parsedDate = inputDateFormat.parse(docDate) // Parse using correct format

        // def inputDateFormat = new SimpleDateFormat("yyyyMMdd") // Matches "20220913"
        // def outputDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'00:00:00'Z'") // Desired format
        // def docDate = sale.getElementsByTagName("ftrxdate").item(0).textContent
        // def parsedDate = inputDateFormat.parse(docDate) // Parse using correct format
        // def formattedDate = outputDateFormat.format(parsedDate) // Convert to MM/dd/yyyy

        def formattedDate = outputDateFormat.format(parsedDate) // Convert to MM/dd/yyyy
        message.setProperty("formattedDate",formattedDate)
        
        def memo = replaceSpecialChars("Posted From WebPOS - Ref# ${item.getElementsByTagName("fdocument_no").item(0)?.textContent} / ${item.getElementsByTagName("fmemo").item(0)?.textContent}")
        def jMemo = item.getElementsByTagName("fkey").item(0)?.textContent?.take(50) ?: ""

        // Get Branch Info
        def branch = getDefaultBranch(
            item.getElementsByTagName("fthirdparty_siteid").item(0)?.textContent,
            item.getElementsByTagName("fthirdparty_officeid").item(0)?.textContent,
            message
        )
       
        def productList = []
        NodeList productNodes = item.getElementsByTagName("product")
        if (productNodes.length == 0) {
            return 0
        }
        for (int j = 0; j < productNodes.getLength(); j++) {
            
            def product = productNodes.item(j)
            // Retrieve Item Code & Management Type
            def itemCode = product.getElementsByTagName("fthirdparty_productid").item(0)?.textContent
            def query = "Items?\$filter=ItemCode%20eq%20'${itemCode}'"
            def resItem = callServiceLayer(query, 'GET', null, message)

            if (!resItem?.value || resItem.value.isEmpty()) {
                message.setProperty("ErrorDetails", "Product Not Found: ${itemCode}")
                // throw new Exception("Process terminated due to an error")
            }

            def itemData = resItem.value[0]
            def manSerNum = itemData.ManageSerialNumbers
            def manBtchNum = itemData.ManageBatchNumbers

            // Validate Lot Number Requirement
            if (!manSerNum && !manBtchNum && product.getElementsByTagName("flotno").item(0)?.textContent) {
                message.setProperty("ErrorDetails", "Lot No is Required: ${itemCode}")
                // throw new Exception("Process terminated due to an error")
            }

            //GET UOM Details
            def uomQuery = "UnitOfMeasurements?\$filter=Code%20eq%20'${itemData.ItemCode}'"
            def uomAbsEntry = callServiceLayer(uomQuery, "GET", null, message)?.value?.getAt(0)?.AbsEntry ?: 0
            
            //GET UGP Details
            def uomGroupQuery = "UnitOfMeasurementGroups?\$filter=AbsEntry%20eq%20${itemData.UoMGroupEntry}"
            def uomGroup = callServiceLayer(uomGroupQuery, "GET", null, message)?.value?.getAt(0)

            def matchingUoM = uomGroup?.UoMGroupDefinitionCollection?.find { it.AlternateUoM == uomAbsEntry }
            def invConv = (matchingUoM?.AlternateQuantity ?: 1) / (matchingUoM?.BaseQuantity ?: 1)
            def qty = product.getElementsByTagName("fqty").item(0)?.textContent?.toBigDecimal() ?: 0
            def uomQty = product.getElementsByTagName("fuomqty").item(0)?.textContent?.toBigDecimal() ?: 1
            def totalQty = qty * uomQty * invConv

            def lastPuPriceQuery = "SQLQueries('GetLastPurchasePrice')/List?ItemCode%20=%20'${itemCode}'"
            message.setProperty("lastPuPriceQuery", lastPuPriceQuery)
            def purPrice = callServiceLayer(lastPuPriceQuery, "GET", null, message)?.value?.getAt(0)

            def price = 0
            def unitPrice = product.getElementsByTagName("funitprice").item(0)?.textContent?.toBigDecimal()
            def useCost = message.getProperty("localKeys")?.get("inv_use_cost") ?: 0 
            def paymentGroupCode = message.getProperty("localKeys")?.get("inv_payment_group_code") ?: 0
            def lastPurchasePrice = purPrice.LastPurPrc ?: 0
            def lastEvaluatedPrice = purPrice.LstEvlPric ?: 0

            if ((unitPrice > 0 && useCost) ||
                (paymentGroupCode == -1 && lastPurchasePrice == 0) ||
                (paymentGroupCode == -2 && lastEvaluatedPrice == 0)) {
                price = unitPrice * invConv
            }

            
            def productData = [
                ItemCode: product.getElementsByTagName("fthirdparty_productid").item(0)?.textContent,
                Quantity: totalQty,
                WarehouseCode: item.getElementsByTagName("fthirdparty_siteid").item(0)?.textContent,
                UnitPrice: price,
                SerialNumbers: [],
                BatchNumbers: []
                
            ]
            if (manSerNum) {
                productData.SerialNumbers << [
                    InternalSerialNumber: product.getElementsByTagName("flotno").item(0)?.textContent,
                    Quantity: qty
                ]
            }
            if (manBtchNum) {
                productData.BatchNumbers << [
                    BatchNumber: product.getElementsByTagName("flotno").item(0)?.textContent,
                    Quantity: qty
                ]
            }
            productList << productData
        }
        
        def jsonRecord = [
            DocDate: formattedDate,
            DocDueDate: formattedDate,
            TaxDate: formattedDate,
            PaymentGroupCode: message.getProperty("inv_payment_group_code"),
            BPL_IDAssignedToInvoice: branch,
            Comments: memo,
            JournalMemo: jMemo,
            U_fkey: fkey,
            DocumentLines: productList
        ]
        jsonRecords << jsonRecord
        

        for (def record : jsonRecords) {
            def apiResponse 
            try {
                message.setProperty("record", record)
                apiResponse = callServiceLayer("${Constants.SapEndpoint}", "POST", record, message)
                message.setProperty("apiResponse", apiResponse)
                if (apiResponse?.errorCode) {
                    results << [record: record, status: "Error", errorMessage: apiResponse.errorMessage]
                    message.setProperty("errorFound", "true")

                    if (byPassData(message, Constants.M_ENTITY, fkey, record, apiResponse.errorMessage, Constants.M_ENTITY + ".PushtoSAP") == 0) {
                        postLog(message, "ERROR", record, apiResponse.errorMessage, "1", Constants.M_ENTITY + ".PushtoSAP")
                        return -1
                    }
                    break
                }
            } catch (Exception e) {
                break
            }
        }
        message.setProperty("GotHere","GotHere")

    } catch (Exception ex) {
        postLog(message, "ERROR", record.toString(), ex.toString(), "0", "${Constants.M_ENTITY}.PushToSAP")
        return -1
    }
    return 0
}
def getDefaultBranch(fsiteid,fofficeid,Message message){
    def query = "BusinessPlaces?\$filter=DefaultWarehouseID%20eq%20'${fsiteid}'"
    def response = callServiceLayer(query, 'GET', null, message)
    def BPLID = ""
    if (response?.value?.size() > 0) {
            BPLID = response.value[0].BPLID
    }

    return BPLID == "" ? fofficeid : BPLID
    //BPLID
}
int requestWebPOS(String lastBatchId, String lastKey, String newBatchId,localKey,message) {
    try {
        
        def invCutOffFrom = localKey['inv_cutoff_from'] ?: "20190101"
        def invCutOffTo = localKey['inv_cutoff_to'] ?: ""
        def params = """
            <filter>
                <flast_batchid>${lastBatchId}</flast_batchid>
                <flast_key>${lastKey}</flast_key>
                <fnew_batchid>${newBatchId}</fnew_batchid>
                <ftrxtype>1</ftrxtype>
                <ffrom>${invCutOffFrom}</ffrom>
                <fto>${invCutOffTo}</fto>
            </filter>
        """
        message.setProperty("params", params)
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
    def queryParams = "${Constants.SapEndpoint}?\$select=WarehouseCode,WarehouseName,Inactive,U_fupdated_date" +
            "&\$filter=U_fupdated_date%20ge%20'" + lastBatchId + "'%20and%20U_fupdated_date%20le%20'" + newBatchId + "'" +
            "&\$orderby=U_fupdated_date%20asc" +
                "&\$top=100" +
                "&\$skip=" + (lastKey?.toString() ?: "0")
    response = callServiceLayer(queryParams, 'GET', null, message)
    return response
}
// Build Product Data for SOAP Request
def mapSAP2WebPos(itemsToProcess) {
    def data = ""
    // Check if ItemsToProcess is empty and return empty data
    if (itemsToProcess?.isEmpty()) {
        return data
    }
    itemsToProcess.each { item ->
        def activeFlag = (item.Inactive == "tNO") ? "1" : "0"
        data += """
            <record>
                <fthirdpartyid>${item.WarehouseCode}</fthirdpartyid>
                <fname>${item.WarehouseName}</fname>
                <factive_flag>${activeFlag}</factive_flag>a
                <new_fmemo>Ported from SAP</new_fmemo>
            </record>""" 
    }
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
        body = replaceSpecialChars(body)

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

def replaceSpecialChars(String input) {
    def specialCharsMap = [
        "&Ntilde;": "Ñ", "&ntilde;": "ñ", "&ldquo;": "\"",
        "&Aacute;": "Á", "&aacute;": "á", "&Acirc;": "Â",
        "&acirc;": "â", "&Atilde;": "Ã", "&atilde;": "ã",
        "&Iacute;": "Í", "&iacute;": "í", "&Icirc;": "Î",
        "&icirc;": "î", "&#296;": "Ĩ", "&#297;": "ĩ",
        "&Uacute;": "Ú", "&ugrave;": "ù", "&Ucirc;": "Û",
        "&ucirc;": "û", "&Uacute": "Ú", "&uacute;": "ù",
        "&#360;": "Ũ", "&#361;": "ũ", "&#312;": "ĸ",
        "&euro;": "€", "&trade;": "™", "&pound;": "£",
        "&laquo;": "«", "&raquo;": "»", "&bull;": "•",
        "&dagger;": "†", "&copy;": "©", "&reg;": "®",
        "&deg;": "°", "&micro;": "µ", "&middot;": "·",
        "&ndash;": "–", "&mdash;": "—", "&#8470;": "№",
        "&lsquo;": "‘", "&rsquo;": "’", "&oelig;": "œ",
        "&cent;": "¢"
    ]

    specialCharsMap.each { entity, character ->
        input = input.replace(entity, character)
    }
    return input
}
boolean isRecordEqual(def data1, def data2) {
    return data1.WarehouseCode == data2.WarehouseCode &&
           data1.WarehouseName == data2.WarehouseName &&
           data1.Inactive == data2.Inactive // Add other fields if needed
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

