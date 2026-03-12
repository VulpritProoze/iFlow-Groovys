/**
 * FormatBatchResponse.groovy
 * 
 * Dependencies:
 * - None
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Reusable utility to parse OData $batch multipart responses and convert them into a 
 * single, human-readable JSON array of results.
 * 
 * <p>Example usage in another script:</p>
 * <pre>
 * {@code
 *  String rawResponse = conn.post(batchRequest).toJson()
 *  String formattedJson = formatBatchResponse(rawResponse)
 *  println(formattedJson)
 * }
 * </pre>
 * 
 * @param body The raw multipart/mixed string from the OData response
 * @return String A prettified JSON string containing success counts and per-item details
 */
def String formatBatchResponse(String body) {
    def results = []

    if (!body || !body.contains("--batchresponse")) {
        return body // Not a batch response, return as is
    }

    try {
        // 1. Split by boundary (find the first boundary to determine the pattern)
        String boundary = ""
        def boundaryMatcher = (body =~ /--batchresponse_[a-zA-Z0-9-]+/)
        if (boundaryMatcher.find()) {
            boundary = boundaryMatcher.group()
        }

        if (!boundary) return body

        // 2. Extract individual response parts
        def parts = body.split(java.util.regex.Pattern.quote(boundary))
        
        parts.each { part ->
            if (part.contains("HTTP/1.1")) {
                def result = [:]
                
                // Extract HTTP Status Code (e.g., 201 Created or 400 Bad Request)
                def statusMatcher = (part =~ /HTTP\/1\.1 (\d{3}) .*/)
                if (statusMatcher.find()) {
                    result.statusCode = statusMatcher.group(1).toInteger()
                }

                // Extract JSON Body if exists
                if (part.contains("{")) {
                    int jsonStart = part.indexOf("{")
                    int jsonEnd = part.lastIndexOf("}") + 1
                    String jsonString = part.substring(jsonStart, jsonEnd)
                    
                    try {
                        result.body = new JsonSlurper().parseText(jsonString)
                    } catch (Exception e) {
                        result.body = jsonString // Fallback to raw text
                    }
                }
                
                if (result.statusCode) results << result
            }
        }

        // 3. Format the final output as a pretty JSON string
        return JsonOutput.prettyPrint(JsonOutput.toJson([
            batchSummary: [
                totalItems: results.size(),
                successCount: results.count { it.statusCode >= 200 && it.statusCode < 300 },
                errorCount: results.count { it.statusCode >= 400 }
            ],
            details: results
        ]))
        
    } catch (Exception e) {
        return "Batch Parsing Error: ${e.message}\n\nOriginal Body:\n${body}"
    }
}

