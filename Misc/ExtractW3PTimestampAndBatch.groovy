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