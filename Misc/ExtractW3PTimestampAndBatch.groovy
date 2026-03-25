/**
 * Extracts W3P timestamp and batch fields from the provided XML. 
 * Fields extracted: fnew_batchid, flast_batchid, flast_key, fdone.
 *
 * Returns a result Map: [status:1,message:'Success',payload:[...]] or error map.
 */
def extractW3PTimestampAndBatch(String xml) {
    try {
        if (!xml) return [status: 0, message: 'No XML provided', payload: null]

        def parser = new XmlSlurper()
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false)

        def root = parser.parseText(xml)

        // If the payload is a SOAP envelope with an escaped inner XML inside <Result> then
        // unescape and reparse the inner XML so we can find the fnew/flast/fkey/fdone nodes.
        def resultNode = root.'**'.find { it.name() == 'Result' }
        if (resultNode) {
            def innerText = resultNode.text()
            if (innerText) {
                def unescapeXml = { String s ->
                    if (s == null) return ''
                    def x = s
                    x = x.replaceAll('&lt;', '<')
                    x = x.replaceAll('&gt;', '>')
                    x = x.replaceAll('&quot;', '"')
                    x = x.replaceAll('&apos;', "'")
                    x = x.replaceAll('&amp;', '&')
                    return x
                }

                // If innerText appears escaped (contains &lt;), unescape and try to parse
                if (innerText.contains('&lt;')) {
                    try {
                        def innerUnescaped = unescapeXml(innerText)
                        root = parser.parseText(innerUnescaped)
                    } catch (e) {
                        // ignore and keep original root
                    }
                } else {
                    // Not escaped; maybe already raw XML string — attempt to parse
                    try { root = parser.parseText(innerText) } catch (e) { /* ignore */ }
                }
            }
        }

        def findFirst = { String name ->
            def node = root.'**'.find { it.name() == name }
            return node ? node.text() : null
        }

        def fnew = findFirst('fnew_batchid')
        def flast = findFirst('flast_batchid')
        def fkey = findFirst('flast_key')
        def fdone = findFirst('fdone')

        return [status: 1, message: 'Success', payload: [fnew: fnew, flast: flast, fkey: fkey, fdone: fdone]]
    } catch (Exception e) {
        return [status: 0, message: "extractW3PTimestampAndBatch error: ${e?.message}", payload: null]
    }
}