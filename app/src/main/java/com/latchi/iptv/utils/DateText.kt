package com.latchi.iptv.utils

object DateText {
    fun shortDate(raw: String): String {
        if (raw.isBlank()) return "Not available"
        val normalized = DigitNormalizer.normalizeDigits(raw)
        val regex = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
        val m = regex.find(normalized)
        if (m != null) {
            val (y, mo, d) = m.destructured
            return "$d/$mo/$y"
        }
        val parts = normalized.split(" ")
        // Example: Sat Dec 26 2026 ...
        if (parts.size >= 4) return "${parts[2]}/${monthNumber(parts[1])}/${parts[3]}"
        return normalized.take(16)
    }

    private fun monthNumber(m: String): String = when (m.lowercase().take(3)) {
        "jan" -> "01"; "feb" -> "02"; "mar" -> "03"; "apr" -> "04"; "may" -> "05"; "jun" -> "06"
        "jul" -> "07"; "aug" -> "08"; "sep" -> "09"; "oct" -> "10"; "nov" -> "11"; "dec" -> "12"
        else -> "--"
    }
}
