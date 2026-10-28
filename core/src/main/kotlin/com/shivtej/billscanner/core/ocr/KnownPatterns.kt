package com.shivtej.billscanner.core.ocr

/**
 * Bill pattern codes that come with the app: every code made for a supplier is also put here, so after an update
 * (or a new install) it is already in the app and never has to be pasted again.
 */
object KnownPatterns {
    val CODES = listOf(
        // Patel Trading Corporation, Barshi (EBase): rate with GST, net rate, MRP 0.00 = local brand by rate only.
        "SBP1:eyJ2IjoxLCJuIjoiUGF0ZWwgVHJhZGluZyBDb3Jwb3JhdGlvbiIsImciOlsiMjdBQldQUDI0MzJKMVo1Il0sInMiOlsiUEFURUxUUkFESU5HQ09SUE9SQVRJT04iXSwiaCI6eyJoc25zYWMiOiJIU04iLCJoc25pc2FjIjoiSFNOIiwicXVhbnRpdHkiOiJRVFkiLCJxdWFudHR5IjoiUVRZIiwibXJwIjoiTVJQIiwicmF0ZSI6IlJBVEVfR1NUIiwibmV0cmF0ZSI6IlJBVEUiLCJnc3QiOiJHU1RfUENUIiwiYW1vdW50IjoiVE9UQUwifSwibyI6W10sIm0iOm51bGwsInQiOiJNUlAgMCA9IHJhdGUgb25seSAobG9jYWwgYnJhbmQpIn0",
        // Venkateshwara Corporation (26-27), Dharashiv: Amount = pairs × rate before GST, GST at the bottom; "Amount" often cut at the photo edge.
        "SBP1:eyJ2IjoxLCJuIjoiVmVua2F0ZXNod2FyYSBDb3Jwb3JhdGlvbiIsImciOlsiMjdBQVZGVjczOTJLMVowIl0sInMiOlsiVkVOS0FURVNIV0FSQUNPUlBPUkFUSU9OIiwiVkVOS0FURVNIV0FSQSJdLCJoIjp7InNybm8iOiJTUiIsIml0ZW1kZXNjcmlwdGlvbiI6IkRFU0MiLCJoc24iOiJIU04iLCJnc3QlIjoiR1NUX1BDVCIsImdzdCI6IkdTVF9QQ1QiLCJzaXplIjoiU0laRSIsIm1ycCI6Ik1SUCIsInF0eSI6IlFUWSIsInJhdGUiOiJSQVRFIiwiZGlzYyI6IkRJU0NfUENUIiwiYW1vdW50IjoiVEFYQUJMRSIsImFtb3VuIjoiVEFYQUJMRSIsImFtb3UiOiJUQVhBQkxFIiwiYW1vIjoiVEFYQUJMRSIsImFtdCI6IlRBWEFCTEUifSwibyI6WyJTUiIsIkRFU0MiLCJIU04iLCJHU1RfUENUIiwiU0laRSIsIk1SUCIsIlFUWSIsIlJBVEUiLCJESVNDX1BDVCIsIlRBWEFCTEUiXSwibSI6bnVsbCwidCI6IkFtb3VudCA9IHF0eSB4IHJhdGUsIEdTVCBhdCB0aGUgYm90dG9tOyBEaXNjIHVzdWFsbHkgZW1wdHkifQ",
    )

    val ALL: List<BillPattern> by lazy { CODES.mapNotNull { BillPattern.decode(it) } }

    /** The owner's own codes first (newest wins), then the ones that come with the app. */
    fun withOwn(own: List<BillPattern>): List<BillPattern> = (own + ALL).distinctBy { it.name.uppercase() }
}
