package app.flux.dj.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

object C {
    val bg = Color(0xFF07080B)
    val s1 = Color(0xFF0E1016)
    val s2 = Color(0xFF141722)
    val s3 = Color(0xFF1D2131)
    val s4 = Color(0xFF2A2F44)
    val line = Color(0x1AFFFFFF)
    val tx = Color(0xFFE8EBF2)
    val mu = Color(0xFF7D849A)
    val dim = Color(0xFF4A5068)
    val a = Color(0xFF22D3EE)
    val b = Color(0xFFF472B6)
    val fx = Color(0xFFFBBF24)
    val ok = Color(0xFF34D399)
    val bad = Color(0xFFEF4444)
    val lo = Color(0xFFF43F5E)
    val mid = Color(0xFFFBBF24)
    val hi = Color(0xFFE0F2FE)
    fun deck(i: Int) = if (i == 0) a else b
    // Surfaces and scrims (single owner for every literal the widgets used to repeat).
    val deep = Color(0xFF0A0B10)
    val panelDeep = Color(0xFF0C0E14)
    val well = Color(0xFF05060A)
    val meterOff = Color(0xFF1A1E2B)
    val scrim = Color(0xAA000000)
    val scrimSoft = Color(0x99000000)
    val scrimFaint = Color(0x66000000)
    val scrimStrong = Color(0xEE05060A)
    val cueText = Color(0xFFFF9F5A)
    val labelDim = Color(0xFF8C93AA)
    val jogBody = Color(0xFF1B2030)
    val jogDeep = Color(0xFF0A0C13)
    val jogPlatter = Color(0xFF0E111A)
    val jogHub = Color(0xFF12151F)
    val knobHi = Color(0xFF30364F)
    val knobLo = Color(0xFF151827)
    val faderHi = Color(0xFF4A5170)
    val faderLo = Color(0xFF282D43)
    val cue = listOf(
        Color(0xFF22D3EE), Color(0xFFA78BFA), Color(0xFFF472B6), Color(0xFFFBBF24),
        Color(0xFF34D399), Color(0xFFFB7185), Color(0xFF60A5FA), Color(0xFFF97316),
    )
}

object T {
    val tab = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = C.tx)
    val num = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = C.tx)
    val cap = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.sp, color = C.mu)
}
