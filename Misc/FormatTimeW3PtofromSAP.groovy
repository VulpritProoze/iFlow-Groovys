/*
 * Dependencies: None
 */

import com.sap.gateway.ip.core.customdev.util.Message
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * FormatTimeW3PtofromSAP.groovy
 * 
 * Provides utility methods to convert timestamps between 
 * SAP basic format and UTC Extended format.
 */

/**
 * Converts Basic Timestamp (yyyyMMddHHmmss) to UTC Extended Timestamp (yyyy-MM-dd'T'HH:mm:ss'Z')
 * Example: 20260219144639 -> 2026-02-19T14:46:39Z
 */
def String convertBasicToUtcExtended(String basicTimestamp) {
    if (basicTimestamp == null || basicTimestamp.trim().isEmpty()) {
        return ""
    }
    
    def formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    def utcFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    
    LocalDateTime dateTime = LocalDateTime.parse(basicTimestamp.trim(), formatter)
    return dateTime.format(utcFormatter)
}

/**
 * Converts UTC Extended Timestamp (yyyy-MM-dd'T'HH:mm:ss'Z') to Basic Timestamp (yyyyMMddHHmmss)
 * Example: 2025-01-28T00:00:00Z -> 20250128000000
 */
def String convertUtcExtendedToBasic(String utcTimestamp) {
    if (utcTimestamp == null || utcTimestamp.trim().isEmpty()) {
        return ""
    }
    
    def formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    def basicFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    
    LocalDateTime dateTime = LocalDateTime.parse(utcTimestamp.trim(), formatter)
    return dateTime.format(basicFormatter)
}
