package com.shivtej.billscanner.core.ocr

/**
 * Bill pattern codes that come with the app: every code made for a supplier is also put here, so after an update
 * (or a new install) it is already in the app and never has to be pasted again.
 */
object KnownPatterns {
    val CODES = listOf(
        // Patel Trading Corporation, Barshi (EBase): rate with GST, net rate, MRP 0.00 = local brand by rate only.
        "SBP1:eyJ2IjoxLCJuIjoiUGF0ZWwgVHJhZGluZyBDb3Jwb3JhdGlvbiIsImciOlsiMjdBQldQUDI0MzJKMVo1Il0sInMiOlsiUEFURUxUUkFESU5HQ09SUE9SQVRJT04iXSwiaCI6eyJoc25zYWMiOiJIU04iLCJoc25pc2FjIjoiSFNOIiwicXVhbnRpdHkiOiJRVFkiLCJxdWFudHR5IjoiUVRZIiwibXJwIjoiTVJQIiwicmF0ZSI6IlJBVEVfR1NUIiwibmV0cmF0ZSI6IlJBVEUiLCJnc3QiOiJHU1RfUENUIiwiYW1vdW50IjoiVE9UQUwifSwibyI6W10sIm0iOm51bGwsInQiOiJNUlAgMCA9IHJhdGUgb25seSAobG9jYWwgYnJhbmQpIn0",
    )

    val ALL: List<BillPattern> by lazy { CODES.mapNotNull { BillPattern.decode(it) } }

    /** The owner's own codes first (newest wins), then the ones that come with the app. */
    fun withOwn(own: List<BillPattern>): List<BillPattern> = (own + ALL).distinctBy { it.name.uppercase() }
}
