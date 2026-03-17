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
    static final String M_ENTITY = "SALES"
    static final String M_SOAP_SAVE_ACTION = "SAVE_GET_SALES"
    static final String M_SOAP_GET_ACTION = "GET_SALES"
    static final String SapEndpoint = "Invoices"
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
        def salesList = []
        def salesNodes = data.getElementsByTagName("sales")
        if (salesNodes.length > 0) {
            for (int k = 0; k < salesNodes.length; k++) {
                def sale = salesNodes.item(k)
                if(saveInvoice(message, sale) < 0) return -1
                if(savePayment(message, sale) < 0) return -1
                // saveInvoice(message, sale)
                // savePayment(message, sale)
            }
        }

    } catch (Exception ex) {
        message.setProperty("Error",ex)
        return -1
    }
    return 0
}
def getDocumentNumber(Message message, id) {
    try {
        def docnum = ""
        def queryCheck = "U_OINV_TRACK?\$filter=U_fid%20eq%20'${id}'"
        def response = callServiceLayer(queryCheck, 'GET', null, message)
        if (response?.value?.size() > 0) {
            docnum =  response.value[0]?.U_fdocument_no ?: ""
        }

        if (!docnum) {
            def queryCheckMax = "U_OINV_TRACK?\$orderby=U_fdocument_no%20desc%20&\$top=1"
            def getDocNum = callServiceLayer(queryCheckMax, 'GET', null, message)
            // if (getDocNum?.value?.size() > 0) {
            //     docnum =  getDocNum.value[0]?.U_fdocument_no ?: ""
            // }
            def maxDocNum = Integer.parseInt(getDocNum?.value?.getAt(0)?.U_fdocument_no?.toString() ?: "0")
            docnum = Math.max(maxDocNum, 1000000000) + 10

            def jsonRecord = [
                U_fid: id,
                U_fdocument_no: docnum
            ]

            callServiceLayer("U_OINV_TRACK", "POST", jsonRecord, message)
        }
         message.setProperty("docnum",docnum)
        return docnum
    } catch (Exception ex) {
        throw new Exception("API Error in getDocumentNumber: ${ex.message}")
    }
    
}
def saveInvoice(Message message, sale){
    def fkey = sale.getElementsByTagName("fkey").item(0)?.textContent
    if (fkey == "") return 0
    try {
        def retval = 0,totalGross = 0.0,siteID = "",invoiceNum = ""
        def docNum = getDocumentNumber(message,fkey)
        //No double posting check
        def queryCheck = "Invoices?\$filter=DocNum%20eq%20${docNum}&\$select=DocNum,CardCode,DocDate"
        def response = callServiceLayer(queryCheck, 'GET', null, message)
        if (response?.value?.size() > 0) {
            invoiceNum = response.value[0]?.DocNum ?: ""
        }
        if (invoiceNum != "") {
            return 0
        }
        def thirdPartyAccountId = sale.getElementsByTagName("fthirdparty_accountid").item(0)?.textContent?.trim()
        //Check configuration
        checkConfig(message)
        def salesPerAccount = message.getProperty("localKeys")?.get("sales_per_account")
        def salesPostAccountId = message.getProperty("localKeys")?.get("sales_post_faccountid")
        def postProductId = message.getProperty("localKeys")?.get("sales_post_fproductid")
        def diffProductId = message.getProperty("localKeys")?.get("sales_diff_fproductid")
       
        def cardCode = (salesPerAccount != "0" && thirdPartyAccountId) ? thirdPartyAccountId : salesPostAccountId
        def gross = sale.getElementsByTagName("fgross").item(0)?.textContent?.trim()
        def tax = sale.getElementsByTagName("ftax").item(0)?.textContent?.trim()
        def taxRate = 12
       

        //GET  Tax Product UomEntry
        queryCheck = "Items?\$filter=ItemCode%20eq%20'${taxProductId}'"
        response = callServiceLayer(queryCheck, 'GET', null, message)
        if (!response || !response.value) {
            throw new Exception("Invalid response from API Tax Product UomEntry. Check URL and credentials.")
        }
        
        queryCheck = "UnitOfMeasurementGroups?\$filter=AbsEntry%20eq%20${response?.value?.getAt(0)?.UoMGroupEntry ?: ""}"
        response = callServiceLayer(queryCheck, "GET", null, message)
        if (!response || !response.value) {
            throw new Exception("Invalid response from API Tax Product UomEntry. Check URL and credentials.")
        }

        taxProductUomEntry = response?.value?.getAt(0)?.BaseUoM
        //GET  Post Product UomEntry 
        queryCheck = "Items?\$filter=ItemCode%20eq%20'${postProductId}'"
        response = callServiceLayer(queryCheck, 'GET', null, message)
        if (!response || !response.value) {
            throw new Exception("Invalid response from API Post Product UomEntry. Check URL and credentials.")
        }
        
        queryCheck = "UnitOfMeasurementGroups?\$filter=AbsEntry%20eq%20${response?.value?.getAt(0)?.UoMGroupEntry ?: ""}"
        response = callServiceLayer(queryCheck, "GET", null, message)
        if (!response || !response.value) {
            throw new Exception("Invalid response from API Post Product UomEntry. Check URL and credentials.")
        }
        postProductUomEntry = response?.value?.getAt(0)?.BaseUoM

        def inputDateFormat = new SimpleDateFormat("yyyyMMdd") // Matches "20220913"
        def outputDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'00:00:00'Z'") // Desired format
        def docDate = sale.getElementsByTagName("fsale_date").item(0).textContent
        def parsedDate = inputDateFormat.parse(docDate) // Parse using correct format
        def formattedDate = outputDateFormat.format(parsedDate) // Convert to MM/dd/yyyy

        def jsonRecord = [:] // Initialize as a map
        jsonRecord.Lines = []
        // Processing product lines
        def productList = []
        if (message.getProperty("localKeys")?.get("sales_per_product")){
            def productNodes = sale.getElementsByTagName("product")
            if (productNodes.length > 0) {
                for (int j = 0; j < productNodes.length; j++) {
                    def product = productNodes.item(j)
                    def fqty = product.getElementsByTagName("fqty").item(0)?.textContent?.toDouble() ?: 0.0
                    def ftotal_line = product.getElementsByTagName("ftotal_line").item(0)?.textContent?.toDouble() ?: 0.0

                    if (fqty == 0 && ftotal_line == 0) continue

                    def productId = product.getElementsByTagName("fthirdparty_productid").item(0)?.textContent
                    queryCheck = "Items?\$filter=ItemCode%20eq%20'${productId}'"
                    response = callServiceLayer(queryCheck, 'GET', null, message)
                    def lotNo = ""

                    if (!response?.value || response.value.size() == 0) {
                        throw new Exception("Product Not Found: ${productId}")
                    }
                    if ((response.value[0]?.ManageSerialNumbers == "tYES" || response.value[0]?.ManageBatchNumbers == "tYES") &&  
                        product.getElementsByTagName("flotno").item(0)?.textContent?.trim() ==""){
                        lotNo = message.getProperty("localKeys")?.get("default_lotno")?.trim()
                        if (!lotNo) {
                            throw new Exception("Lot No is Required: ${productId}")
                        }
                    }

                    queryCheck = "UnitOfMeasurements?\$filter=Code%20eq%20'${product.getElementsByTagName("fbase_uom").item(0)?.textContent}'"
                    response = callServiceLayer(queryCheck, 'GET', null, message)
                    def uom = response?.value?.getAt(0)?.AbsEntry ?: ""

                    def vatGroup = ["0", "1"].contains(product.getElementsByTagName("flotno").item(0)?.textContent?.trim())
                        ? message.getProperty("localKeys")?.get("sales_tax_group")?.trim()
                        : message.getProperty("localKeys")?.get("sales_non_tax_group")?.trim()
                    def warehouseCode = product.getElementsByTagName("fthirdparty_siteid").item(0)?.textContent?.trim()
                    def qty = product.getElementsByTagName("fqty").item(0)?.textContent?.trim()
                    def totalLine = product.getElementsByTagName("ftotal_line").item(0)?.textContent?.trim()
                    def addLine = { itemCode, quantity, price, vatGroupParam, uomparam ->
                        def line = [
                            ItemCode: itemCode,
                            Quantity: quantity,
                            PriceAfterVAT: price,
                            Currency: "",
                            DiscountPercent: 0,
                            VatGroup: vatGroupParam,
                            UoMEntry: uomparam,
                            WarehouseCode: warehouseCode
                        ]

                        if (product.getElementsByTagName("ManageSerialNumbers").item(0)?.textContent?.trim() == "tYES") {
                            line.SerialNumbers = [[
                                InternalSerialNumber: flotno,
                                Quantity: quantity
                            ]]
                        }

                        if (product.getElementsByTagName("ManageBatchNumbers").item(0)?.textContent?.trim() == "tYES") {
                            line.BatchNumbers = [[
                                BatchNumber: flotno,
                                Quantity: quantity
                            ]]
                        }
                        totalGross += line.PriceAfterVAT * line.Quantity
                        productList << line
                    }
                    if (qty == 0) {
                        def sign = totalLine < 0 ? -1 : 1
                        addLine(productId,sign, totalLine * sign,vatGroup,uom)
                        addLine(productId,sign * -1, 0,vatGroup,uom)

                    } else {
                        def numericTotalLine = totalLine instanceof String ? totalLine.toBigDecimal() : totalLine
                        def numericQty = qty instanceof String ? qty.toBigDecimal() : qty
                        addLine(productId, numericQty, numericTotalLine / numericQty, vatGroup, uom)
                        // addLine(productId,qty, totalLine / qty,vatGroup,uom)
                    }
                    siteID = siteID ?: warehouseCode
                }

            }
        }else{
            def sign = gross < 0 ? -1 : 1
            def warehouseCode = (siteID != "") ? siteID : ""
            productList << addLineItems(postProductId,sign, gross * sign,nonTaxGroup,postProductUomEntry,warehouseCode)
            totalGross = gross

            if (tax != 0 && taxRate > 0) {
               productList <<  addLineItems(taxProductId,1, tax / (taxRate / 100) * ((100 + taxRate) / 100),taxGroup,warehouseCode)
               productList <<  addLineItems(taxProductId,-1, tax / (taxRate / 100) * ((100 + taxRate) / 100),nonTaxGroup,taxProductUomEntry,warehouseCode)
            }
            siteID = siteID ?: warehouseCode
        }
        def grossValue = gross instanceof String ? gross.toDouble() : gross
        def totalGrossValue = totalGross instanceof String ? totalGross.toDouble() : totalGross
        def fdiff = Math.round((grossValue - totalGrossValue) * 100) / 100.0
        //def fdiff = Math.round((gross - totalGross) * 100) / 100.0
        if (fdiff != 0) {
            def sign = (fdiff < 0) ? -1 : 1
            def warehouseCode = (siteID != "") ? siteID : ""
            productList << addLineItems(diffProductId ?: taxProductId,sign, fdiff * sign,taxGroup,taxProductUomEntry,warehouseCode)
        }
        
        jsonRecord = [
            Series: 0,
            PaymentGroupCode: "-1",
            CardCode: cardCode,
            HandWritten: "tYES",
            DocNum: docNum,
            DocDate: formattedDate,
            DocDueDate: formattedDate,
            TaxDate: formattedDate,
            DocTotal: sale.getElementsByTagName("fgross").item(0)?.textContent,
            Comments: "Posted From WebPOS - " + sale.getElementsByTagName("fkey").item(0)?.textContent,
            DocumentLines: productList
        ]
        if (message.getProperty("localKeys")?.get("multiple_branch")) {
            jsonRecord.BPL_IDAssignedToInvoice = sale.getElementsByTagName("fthirdparty_branchid").item(0)?.textContent?.trim() ?: "0"
        }
        def jsonBuilder = new JsonBuilder(jsonRecord)
        message.setProperty("InvoiceRecord",jsonRecord)
        apiResponse = callServiceLayer("${Constants.SapEndpoint}", "POST", jsonRecord, message)
        if (apiResponse?.errorCode) {
            if (byPassData(message, "INVOICE", fkey, jsonRecord, apiResponse.errorMessage, Constants.M_ENTITY + ".PushtoSAP") == 0) {
                postLog(message, "ERROR", jsonRecord, apiResponse.errorMessage, "1", Constants.M_ENTITY + ".PushtoSAP")
            }
            throw new Exception("Error: ${apiResponse.errorMessage}")
            return -1
        } 
    concludeRecord(message, "INVOICE", fkey)
    } catch (Exception e) {
        message.setProperty("InvoiceError",e)
        throw new Exception("Error Invoice: ${e}")
        return -1
    }
    
    return 0
}
def savePayment(Message message, sale){
    message.setProperty("savePayment","1")
    def fkey = sale.getElementsByTagName("fkey").item(0)?.textContent
    if (fkey == "") return 0
    try {
        def fcash = sale.getElementsByTagName("fcash").item(0)?.textContent?.toDouble() ?: 0
        def fcredit = sale.getElementsByTagName("fcredit").item(0)?.textContent?.toDouble() ?: 0
        def fcharge = sale.getElementsByTagName("fcharge").item(0)?.textContent?.toDouble() ?: 0
        message.setProperty("savePayment","2")
        message.setProperty("fcash",fcash)
        message.setProperty("fcredit",fcredit)
        message.setProperty("fcharge",fcharge)
        if (fcash == 0 && fcredit == 0 && fcharge > 0){
            message.setProperty("savePayment3","3")
            return 0
        } 
        message.setProperty("savePayment3","3")
        checkConfig(message)
        def retval = 0, total_credit = 0.0, total_charge = 0.0, total_check = 0.0,paymentNum = "",invoiceNum = ""
        def docNum = getDocumentNumber(message,fkey)
        //No double posting check
        def queryCheck = "IncomingPayments?\$filter=DocNum%20eq%20${docNum}&\$select=DocNum,CardCode,DocDate"
        def response = callServiceLayer(queryCheck, 'GET', null, message)
        if (response?.value?.size() > 0) {
            paymentNum = response.value[0]?.DocNum ?: ""
        }
        if (paymentNum != "") {
            message.setProperty("savePayment4","4")
            return 0
        }
        message.setProperty("savePayment5","5")

        queryCheck = "Invoices?\$filter=DocNum%20eq%20${docNum}&\$select=DocNum,DocEntry,CardCode,DocDate"
        response = callServiceLayer(queryCheck, 'GET', null, message)
        if (response?.value?.size() > 0) {
            invoiceNum = response.value[0]?.DocEntry ?: ""
        }
        if (invoiceNum == "") {
            throw new Exception("Error Incoming Payment: Invoice was not found: ${fkey}")
            // return -1
        }
         message.setProperty("savePayment6","6")
        def thirdPartyAccountId = sale.getElementsByTagName("fthirdparty_accountid").item(0)?.textContent?.trim()
        //Check configuration
        checkConfig(message)
        def salesPerAccount = message.getProperty("localKeys")?.get("sales_per_account")
        def salesPostAccountId = message.getProperty("localKeys")?.get("sales_post_faccountid")
        def cardCode = (salesPerAccount != "0" && thirdPartyAccountId) ? thirdPartyAccountId : salesPostAccountId
        def inputDateFormat = new SimpleDateFormat("yyyyMMdd") // Matches "20220913"
        def outputDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'00:00:00'Z'") // Desired format
        def docDate = sale.getElementsByTagName("fsale_date").item(0).textContent
        def parsedDate = inputDateFormat.parse(docDate) // Parse using correct format
        def formattedDate = outputDateFormat.format(parsedDate) // Convert to MM/dd/yyyy

        def jsonRecord = [:] // Initialize as a map
        jsonRecord.Lines = []
        // Processing payment lines
        def paymentList = [], paymentChecks = [],paymentCreditCards = []
        def paymentNodes = sale.getElementsByTagName("payment")
        if (paymentNodes.length > 0) {
            for (int j = 0; j < paymentNodes.length; j++) {
                def payment = paymentNodes.item(j)
                def ftype =  payment.getElementsByTagName("ftype").item(0)?.textContent
                def famount = payment.getElementsByTagName("famount").item(0)?.textContent?.toDouble() ?: 0.0
                if (ftype && famount != 0) {
                    switch (ftype) {
                        case "CREDIT":
                            def fexpiry = payment.getElementsByTagName("fexpiry").item(0)?.textContent ?: ""
                            fexpiry = fexpiry.length() == 4 ? "${fexpiry.take(2)}/20${fexpiry.takeRight(2)}" :
                                    fexpiry.length() == 6 ? "${fexpiry.take(2)}/${fexpiry.takeRight(4)}" : "12/2050"

                            def fapproval = payment.getElementsByTagName("fexpifapprovalry").item(0)?.textContent ?: "000"
                            def fthirdparty_creditid = payment.getElementsByTagName("fthirdparty_creditid").item(0)?.textContent ?: ""
                            def cardNumber = ("0000" + fapproval).takeRight(4)
                            def fbatchno = payment.getElementsByTagName("fbatchno").item(0)?.textContent ?: "1"
                            def salesCreditCardPaymentMethod = message.getProperty("localKeys")?.get("sales_credit_card_payment_method") ?: 1

                            paymentCreditCards << [
                                CreditType         : "cr_Regular",
                                CardValidUntil     : fexpiry,
                                ConfirmationNum    : fapproval,
                                CreditCard         : fthirdparty_creditid,
                                CreditCardNumber   : cardNumber,
                                CreditSum          : famount,
                                VoucherNum         : fbatchno,
                                PaymentMethodCode  : salesCreditCardPaymentMethod
                            ]
                            total_credit += famount
                            break

                        case "CHECK":
                            def paymentChecksList = [
                                BankCode   : payment.getElementsByTagName("fbankcode").item(0)?.textContent ?: "",
                                CheckNumber: payment.getElementsByTagName("fcheckno").item(0)?.textContent ?: "",
                                CheckSum   : famount,
                                Details    : payment.getElementsByTagName("fremarks").item(0)?.textContent ?: "",
                                DueDate    : dateFormatMMddyyyy(payment.getElementsByTagName("fexpiry").item(0)?.textContent ?: "")
                            ]
                            total_check += famount
                            paymentChecks << paymentChecksList
                            break

                        case "COUPON":
                        case "EXCESS":
                            def localKey = ftype == "COUPON" ? "sales_coupon_creditid" : "sales_excess_creditid"
                            def fthirdparty_creditid = payment.getElementsByTagName("fthirdparty_creditid").item(0)?.textContent ?: ""
                            def creditId = fthirdparty_creditid ?: message.getProperty("localKeys")?.get(localKey) ?: ""

                            paymentCreditCards << [
                                CreditType         : "cr_Regular",
                                CardValidUntil     : "12/2099",
                                ConfirmationNum    : "000",
                                CreditCard         : creditId,
                                CreditCardNumber   : "0000",
                                CreditSum          : famount,
                                VoucherNum         : "1",
                                PaymentMethodCode  : message.getProperty("localKeys")?.get("sales_credit_card_payment_method") ?: 1
                            ]
                            total_credit += famount
                            break
                    }
                }
            }

        }
        if (message.getProperty("localKeys")?.get("sales_per_account") != "0") {
            total_charge += sale.getElementsByTagName("fcharge").item(0)?.textContent?.toDouble() ?: 0.0
        }
        message.setProperty("savePayment7","7")
        def fgross = sale.getElementsByTagName("fgross").item(0)?.textContent?.toDouble() ?: 0.0
        def ftotal_excess_tender = sale.getElementsByTagName("ftotal_excess_tender").item(0)?.textContent?.toDouble() ?: 0.0
        def cash_amount = fgross - total_credit - total_charge - total_check + ftotal_excess_tender
        def sum_applied = fgross - total_charge

        def invoiceList = []
        def invoiceLine = [
            AppliedFC: 0,
            DocEntry: invoiceNum,
            InvoiceType: "it_Invoice",
            SumApplied: sum_applied > 0 ? sum_applied : 0
        ]
        invoiceList << invoiceLine
        jsonRecord = [
            CardCode: cardCode,
            HandWritten: "tYES",
            DocNum: docNum,
            DocDate: formattedDate,
            TaxDate: formattedDate,
            JournalRemarks: fkey?.take(50),
            DocRate: 0,
            DocRate: 0,
            DocTypte: "rCustomer",
            LocalCurrency: "tYES",
            Remarks:  "Posted From WebPOS - " + fkey,
            PaymentInvoices: invoiceList,
            CashAccount: cashAccount,
            CashSum: cash_amount > 0 ? cash_amount : 0,
            PaymentChecks: paymentChecks,
            PaymentCreditCards: paymentCreditCards

        ]
        def jsonBuilder = new JsonBuilder(jsonRecord)
        message.setProperty("IncomingPaymentRecord",jsonRecord)
        apiResponse = callServiceLayer("IncomingPayments", "POST", jsonRecord, message)

    } catch (Exception e) {
        throw new Exception("Error Payment: ${e.message}")
        //return -1
    }
    return 0

}
def checkConfig(Message message) {
    try {
        def localKeys = message.getProperty("localKeys") ?: [:]
        
        nonTaxGroup = localKeys.get("sales_non_tax_group")?.trim() ?: ""
        taxGroup = localKeys.get("sales_tax_group")?.trim() ?: ""
        taxProductId = localKeys.get("sales_tax_fproductid") ?: ""
        cashAccount = localKeys.get("sales_cash_account") ?: ""

        if (!nonTaxGroup || !taxGroup || !taxProductId || !cashAccount) {
            throw new Exception("Missing configuration: nonTaxGroup=$nonTaxGroup, taxGroup=$taxGroup, taxProductId=$taxProductId, cashAccount=$cashAccount")
        }
    } catch (Exception e) {
        throw new Exception("Configuration Error: ${e.message}")
    }
}
def addLineItems(itemCode,quantity, price,vatGroup,uom,warehouseCode) {
    def line = [
        ItemCode: itemCode,
        Quantity: quantity,
        PriceAfterVAT: price,
        Currency: "",
        DiscountPercent: 0,
        VatGroup: vatGroup,
        UoMEntry: uom,
        WarehouseCode: warehouseCode
    ]
    return line;
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
        
        def saleCutOffFrom = localKey['sales_cutoff_from'] ?: "20190101"
        def saleCutOffTo = localKey['sales_cutoff_to'] ?: ""
        def perCustomerFlag = localKey['sales_per_account'] ?: "0"
        def params = """
            <filter>
                <flast_batchid>${lastBatchId}</flast_batchid>
                <flast_key>${lastKey}</flast_key>
                <fnew_batchid>${newBatchId}</fnew_batchid>
                <ffrom>${saleCutOffFrom}</ffrom>
                <fto>${saleCutOffTo}</fto>
                <fper_customer_flag>${perCustomerFlag}</fper_customer_flag>
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

def concludeRecord(Message message, frecordid,fid) {
   
    def data = ""
    data = """
        <frecordid>${frecordid}</frecordid>
        <fid>${fid}</fid>fid"""
    def soapRequestBody = buildSoapRequest("CONCLUDE_RECORD", message.getProperty("W3P_ID"), message.getProperty("W3P_KEY"), data)
    def response = sendSoapRequest(soapRequestBody,message)
    if (response.responseCode == 200) {
        // println "Response Body: ${response.responseBody}"
        message.setProperty("concludeResponseBody", response.responseBody)
    } else {
        // println "Error: ${response.responseBody}"
        message.setProperty("concludeResponseBody", response.responseBody)
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
def dateFormatMMddyyyy(date) {
    def inputDateFormat = new SimpleDateFormat("yyyyMMdd") // Matches "20220913"
    def outputDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'00:00:00'Z'") // Desired format

    if (date) {
        try {
            def parsedDate = inputDateFormat.parse(date) // Parse date
            return outputDateFormat.format(date) // Format to output format
        } catch (Exception e) {
            return null // Handle parsing errors gracefully
        }
    }
    return null // Return null if docDate is empty
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
                // if (byPassData(message, Constants.M_ENTITY, payload?.Code ?: "", payload, errorMessage, Constants.M_ENTITY + ".PushtoSAP") == 0) {
                //     postLog(message, "ERROR", payload, errorMessage, "1", Constants.M_ENTITY + ".PushtoSAP")
                // }
            }
            return [errorCode: errorCode, errorMessage: errorMessage]
        }
    }catch(Exception ex){
        // throw new Exception("Invalid response from API callServiceLayer",ex)
    }
    return apiResponse 
}

