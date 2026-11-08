package com.roywatson.lunar

import androidx.compose.ui.graphics.Color

/** The app's colour tokens. */
internal object LunarColors {
    val Space = Color(0xFF05070D)
    val Moonlight = Color(0xFFF0ECE0)
    val Muted = Color(0xFF9AA8BD)
    val Accent = Color(0xFF92CBFF)

    // The about card: dark ink on moonlight paper, so the logo's black lettering stays legible.
    val Paper = Moonlight
    val Ink = Color(0xFF1B2230)
    val InkMuted = Color(0xFF5A6474)
    val Link = Color(0xFF3346B8)
}
