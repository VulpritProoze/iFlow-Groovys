/*
 * Dependencies: None
 */

import com.sap.gateway.ip.core.customdev.util.Message
import java.time.LocalDateTime
import java.time.LocalDate
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
    def ts = basicTimestamp.trim()
    def utcFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

    try {
        if (ts ==~ /^\\d{8}$/) {
            // date-only basic format yyyyMMdd -> set time to 00:00:00Z
            def date = LocalDate.parse(ts, DateTimeFormatter.ofPattern("yyyyMMdd"))
            return date.atStartOfDay().format(utcFormatter)
        } else if (ts ==~ /^\\d{14}$/) {
            def formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            def dateTime = LocalDateTime.parse(ts, formatter)
            return dateTime.format(utcFormatter)
        } else {
            // unsupported format
            return ""
        }
    } catch (Exception e) {
        return ""
    }
}

/**
 * Converts UTC Extended Timestamp (yyyy-MM-dd'T'HH:mm:ss'Z') to Basic Timestamp (yyyyMMddHHmmss)
 * Example: 2025-01-28T00:00:00Z -> 20250128000000
 */
def String convertUtcExtendedToBasic(String utcTimestamp) {
    if (utcTimestamp == null || utcTimestamp.trim().isEmpty()) {
        return ""
    }
    def ts = utcTimestamp.trim()
    try {
        if (ts ==~ /^\\d{4}-\\d{2}-\\d{2}$/) {
            // date-only ISO -> yyyyMMdd + 000000
            def date = LocalDate.parse(ts, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            return date.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "000000"
        } else if (ts ==~ /^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$/) {
            def formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            def basicFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            def dateTime = LocalDateTime.parse(ts, formatter)
            return dateTime.format(basicFormatter)
        } else {
            // unsupported/unknown format
            return ""
        }
    } catch (Exception e) {
        return ""
    }
}
