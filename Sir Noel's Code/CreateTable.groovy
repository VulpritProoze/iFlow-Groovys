import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URL

def serviceLayerUrl = "https://<YOUR_SAP_B1_SERVER>:50000/b1s/v1/"

// Retrieve credentials from Integration Suite Security Material
def getCredentials(Message message) {
    def credentials = message.getHeaders().get("SAP_Credentials") // Set this in the iFlow
    def parsedCredentials = new JsonSlurper().parseText(credentials)
    return [
        username  : parsedCredentials.username,
        password  : parsedCredentials.password,
        companyDB : parsedCredentials.companyDB
    ]
}

// Function to get Service Layer session token
def getSessionToken(credentials) {
    def url = new URL(serviceLayerUrl + "Login")
    HttpURLConnection conn = url.openConnection() as HttpURLConnection
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setDoOutput(true)

    def payload = JsonOutput.toJson([
        "UserName": credentials.username,
        "Password": credentials.password,
        "CompanyDB": credentials.companyDB
    ])
    conn.outputStream.write(payload.bytes)
    conn.outputStream.flush()
    conn.outputStream.close()

    if (conn.responseCode == 200) {
        def response = new JsonSlurper().parse(conn.inputStream)
        return response.SessionId
    } else {
        return null
    }
}

// Function to check if a UDT exists
def checkUDTExists(sessionToken, tableName) {
    def url = new URL(serviceLayerUrl + "UserTablesMD?\$filter=TableName eq '${tableName}'")
    HttpURLConnection conn = url.openConnection() as HttpURLConnection
    conn.setRequestMethod("GET")
    conn.setRequestProperty("Cookie", "B1SESSION=" + sessionToken)

    if (conn.responseCode == 200) {
        def response = new JsonSlurper().parse(conn.inputStream)
        return response.value.size() > 0
    }
    return false
}

// Function to create a UDT (User-Defined Table)
def createUDT(sessionToken, tableName) {
    if (checkUDTExists(sessionToken, tableName)) {
        return "UDT '${tableName}' already exists, skipping creation."
    }

    def url = new URL(serviceLayerUrl + "UserTablesMD")
    HttpURLConnection conn = url.openConnection() as HttpURLConnection
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("Cookie", "B1SESSION=" + sessionToken)
    conn.setDoOutput(true)

    def payload = JsonOutput.toJson([
        "TableName": tableName,
        "TableDescription": "Inventory Stock Status",
        "TableType": "bott_NoObject"
    ])
    conn.outputStream.write(payload.bytes)
    conn.outputStream.close()

    return conn.responseCode == 201 ? "UDT Created Successfully" : "Failed to Create UDT"
}

// Function to create UDFs (User-Defined Fields)
def createUDF(sessionToken, fieldName, fieldDesc, fieldType, size) {
    def url = new URL(serviceLayerUrl + "UserFieldsMD")
    HttpURLConnection conn = url.openConnection() as HttpURLConnection
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("Cookie", "B1SESSION=" + sessionToken)
    conn.setDoOutput(true)

    def payload = JsonOutput.toJson([
        "TableName": "@INV_STOCK_STATUS",
        "Name": fieldName,
        "Description": fieldDesc,
        "Type": fieldType,  // "db_Alpha" for nvarchar, "db_Numeric" for numeric
        "Size": size
    ])
    conn.outputStream.write(payload.bytes)
    conn.outputStream.close()

    return conn.responseCode == 201 ? "UDF ${fieldName} Created Successfully" : "Failed to Create UDF ${fieldName}"
}

// Function to create an Index
def createIndex(sessionToken) {
    def url = new URL(serviceLayerUrl + "UserKeysMD")
    HttpURLConnection conn = url.openConnection() as HttpURLConnection
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("Cookie", "B1SESSION=" + sessionToken)
    conn.setDoOutput(true)

    def payload = JsonOutput.toJson([
        "TableName": "@INV_STOCK_STATUS",
        "KeyName": "INV_STOCK_STATUS_1",
        "Unique": "tNO",
        "Elements": [
            [ "ColumnName": "FUPDATED_DATE", "Order": "rlnAscending" ]
        ]
    ])
    conn.outputStream.write(payload.bytes)
    conn.outputStream.close()

    return conn.responseCode == 201 ? "Index Created Successfully" : "Failed to Create Index"
}

// Main function to execute in SAP Integration Suite
def processData(Message message) {
    def credentials = getCredentials(message)
    def sessionToken = getSessionToken(credentials)
    def tableName = "INV_STOCK_STATUS"

    if (sessionToken) {
        def udtCreationMessage = createUDT(sessionToken, tableName)
        message.setProperty("udtCreationStatus", udtCreationMessage)

        if (!udtCreationMessage.contains("already exists")) {
            def fields = [
                ["FPRODUCTID", "Product ID", "db_Alpha", 50],
                ["FSITEID", "Site ID", "db_Alpha", 8],
                ["FLOTNO", "Lot Number", "db_Alpha", 36],
                ["FSORT_KEY", "Sort Key", "db_Alpha", 100],
                ["FQTY", "Quantity", "db_Numeric", 19], // Numeric(19,6)
                ["FUPDATED_DATE", "Updated Date", "db_Alpha", 14]
            ]

            fields.each { field ->
                def udfMessage = createUDF(sessionToken, field[0], field[1], field[2], field[3])
                message.setProperty("UDF_${field[0]}_Status", udfMessage)
            }

            def indexMessage = createIndex(sessionToken)
            message.setProperty("indexCreationStatus", indexMessage)
        } else {
            message.setProperty("indexCreationStatus", "Skipping UDF and Index creation as UDT already exists.")
        }

        message.setBody("SAP B1 UDT/UDF/Index process completed.")
    } else {
        message.setProperty("udtCreationStatus", "Authentication Failed")
        message.setBody("Authentication Failed")
    }

    return message
}
