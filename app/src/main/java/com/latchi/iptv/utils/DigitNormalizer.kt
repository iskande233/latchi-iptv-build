package com.latchi.iptv.utils

object DigitNormalizer {
    /**
     * Normalizes Eastern Arabic (٠١٢٣٤٥٦٧٨٩) and Persian (۰۱۲۳۴۵۶۷۸۹) digits
     * into standard ASCII (English) digits (0123456789).
     */
    fun normalizeDigits(text: String?): String {
        if (text == null) return ""
        val sb = StringBuilder(text.length)
        for (char in text) {
            when (char) {
                // Eastern Arabic Numerals
                '٠' -> sb.append('0')
                '١' -> sb.append('1')
                '٢' -> sb.append('2')
                '٣' -> sb.append('3')
                '٤' -> sb.append('4')
                '٥' -> sb.append('5')
                '٦' -> sb.append('6')
                '٧' -> sb.append('7')
                '٨' -> sb.append('8')
                '٩' -> sb.append('9')
                // Persian Numerals
                '۰' -> sb.append('0')
                '۱' -> sb.append('1')
                '۲' -> sb.append('2')
                '۳' -> sb.append('3')
                '۴' -> sb.append('4')
                '۵' -> sb.append('5')
                '۶' -> sb.append('6')
                '۷' -> sb.append('7')
                '۸' -> sb.append('8')
                '۹' -> sb.append('9')
                else -> sb.append(char)
            }
        }
        return sb.toString()
    }
}
