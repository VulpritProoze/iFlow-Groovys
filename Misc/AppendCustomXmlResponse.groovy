/**
 * AppendCustomXmlResponse.groovy
 * Helper utilities to build and append XML response fragments into the current
 * iFlow `message` body. These helpers are library functions (no processData).
 *
 * Two exported functions:
 *  - appendCustomXmlResponseToBody(message, uniqueId, xmlResponse)
 *      -> appends the built <response> into a top-level <responses> root in the
 *         message body, sets message property `W3P_Key` to uniqueId and returns
 *         a result Map similar to buildCustomXmlResponse.
 *
 * Behavior notes:
 * - Response XML is preserved inside a CDATA in the <value> element.
 * - Existing body handling:
 *   - If body already contains a <responses>...</responses> root, the new
 *     <response> is inserted before the closing tag.
 *   - If body is non-empty but not wrapped, the existing body is wrapped as the
 *     first <response> element (key taken from existing `W3P_Key` property if present).
 *   - Empty body => creates <responses> with a single <response> element.
 * - Functions never throw; errors return a result map with status 0.
 */

import groovy.json.JsonOutput


/**
 * Append a built <response> element into the `message` body under a
 * <responses> root. Sets message property `W3P_Key` to uniqueId on success.
 * Returns a result Map: status/message/payload (payload contains the new body).
 */
def appendCustomXmlResponseToBody(def msg, String uniqueId, String xmlResponse) {
    try {
        if (!msg) {
            return [status: 0, message: 'appendCustomXmlResponseToBody: message is required', payload: null]
        }

        def buildRes = buildCustomXmlResponse(uniqueId, xmlResponse)
        if (buildRes.status != 1) {
            return [status: 0, message: "Failed to build response: ${buildRes.message}", payload: buildRes.payload]
        }

        String element = buildRes.payload.toString()
        String uid = uniqueId ?: java.util.UUID.randomUUID().toString()

        String current = msg.getBody(java.lang.String) ?: ''

        if (current?.trim()) {
            // Insert into existing <responses> root if present
            if (current.contains('<responses') && current.contains('</responses>')) {
                int idx = current.lastIndexOf('</responses>')
                if (idx >= 0) {
                    String newBody = current.substring(0, idx) + element + current.substring(idx)
                    msg.setBody(newBody)
                    return [status: 1, message: 'Appended to existing <responses>', payload: newBody]
                }
            }

            // Wrap existing body as the first <response>
            String existingNoDecl = stripXmlDeclaration(current)
            String safeExisting = existingNoDecl.replace(']]>', ']]]]><![CDATA[>')
            String existingKey = msg.getProperty('W3P_Key') ?: 'existing'
            String firstResponse = "<response><key>${escapeXml(existingKey)}</key><value><![CDATA[${safeExisting}]]></value></response>"
            String newBody = "<responses>${firstResponse}${element}</responses>"
            msg.setBody(newBody)
            return [status: 1, message: 'Wrapped existing body and appended new response', payload: newBody]
        } else {
            // Empty body -> create new responses root
            String newBody = "<responses>${element}</responses>"
            msg.setBody(newBody)
            return [status: 1, message: 'Created <responses> and added response', payload: newBody]
        }
    } catch (Exception e) {
        return [status: 0, message: "appendCustomXmlResponseToBody error: ${e.message}", payload: e?.stackTrace?.take(5)?.collect{ it.toString() }]
    }
}

private String stripXmlDeclaration(String s) {
    if (!s) return ''
    return s.replaceFirst(/^\s*<\?xml.*?\?>\s*/, '')
}

private String escapeXml(String s) {
    if (s == null) return ''
    return s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;').replace("'", '&apos;')
}

/* Example usage:
   def responseXml = soapResult.payload?.toString() ?: ''
   def uid = java.util.UUID.randomUUID().toString()
   def res = appendCustomXmlResponseToBody(message, uid, responseXml)
   // check res.status and proceed
*/
