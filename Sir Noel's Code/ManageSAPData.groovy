import com.sap.gateway.ip.core.customdev.util.Message;
import groovy.json.JsonSlurper;

def Message processData(Message message) {
    // Parse the response from Service Layer
    def body = message.getBody(String);
    def jsonSlurper = new JsonSlurper();
    def jsonResponse = jsonSlurper.parseText(body);

    // Extract the session ID token
    def sessionId = jsonResponse.SessionId;

    // Store the session ID in message headers or properties for further requests
    message.setHeader("ServiceLayerToken", sessionId);
    message.setProperty("ServiceLayerToken", sessionId);


    
    return message;
}

def selectProductFromSAP(Message message, String sessionId) {
    // Construct the URL for the SAP B1 Service Layer endpoint
    def baseUrl = "https://c8d734f2trial-trial.integrationsuitetrial-apim.ap21.hana.ondemand.com/c8d734f2trial/b1s/v1/Items"
    def queryParams = "?$filter=UpdateDate+ge+'2022-01-01T00:00:00Z'+" +
                      "+and+UpdateDate+le+'2024-11-20'+" +
                      "&$orderby=UpdateDate+asc" +
                      "&$select=ItemCode,ItemName,UpdateDate" +
                      "&$skip=0" +
                      "&$top=100"
    def fullUrl = baseUrl + queryParams

    // Prepare the HTTP request to SAP B1 Service Layer
    def client = new AhcHttpClient(new AhcHttpClientConfiguration())
    def request = client.createRequest(fullUrl, "GET")

    // Add the session ID as a Cookie for authentication
    request.addHeader("Cookie", "B1SESSION=${sessionId}")

    try {
        // Execute the HTTP request and get the response
        def response = client.execute(request)

        // Return the response body
        return response.getBody().toString()
    } catch (AhcOperationFailedException e) {
        // Handle errors gracefully
        def errorMessage = "Error calling SAP Service Layer: ${e.message}"
        // Log error details
        def messageLog = messageLogFactory.getMessageLog(message)
        if (messageLog != null) {
            messageLog.addAttachmentAsString("SAP Service Layer Error", errorMessage, "text/plain")
        }
        throw new RuntimeException(errorMessage)
    }
}
