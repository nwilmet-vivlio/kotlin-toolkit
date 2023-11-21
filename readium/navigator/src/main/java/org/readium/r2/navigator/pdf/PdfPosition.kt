package org.readium.r2.navigator.pdf

data class PdfPosition(val page:Int, val position:Int) {
    val cfi: String
        get() {
            return "/6/${page * 2}/${position}:0"
        }

    companion object {
        fun fromString(cfi: String): PdfPosition? {
            val splitted = cfi.split("/", ":")
            if (splitted.size != 5 || splitted[1].toInt() != 6 || splitted[4].toInt() != 0) return null
            return PdfPosition((splitted[2].toInt())/2, splitted[3].toInt())
        }
    }
}