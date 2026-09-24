package com.personal.autocheckin

import android.os.Build
import java.util.Locale

/** Detects the ROM vendor so the app can show the right background-management guidance. */
object Vendor {
    enum class Kind { XIAOMI, OPPO, GENERIC }

    val kind: Kind
        get() = when (Build.MANUFACTURER.lowercase(Locale.ROOT)) {
            "xiaomi", "redmi", "poco" -> Kind.XIAOMI
            "oppo", "realme", "oneplus" -> Kind.OPPO
            else -> Kind.GENERIC
        }

    val label: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Background-management guidance shown at the bottom of the main screen. */
    fun backgroundHintRes(): Int = when (kind) {
        Kind.XIAOMI -> R.string.hint_xiaomi
        Kind.OPPO -> R.string.hint_oppo
        Kind.GENERIC -> R.string.hint_generic
    }
}
