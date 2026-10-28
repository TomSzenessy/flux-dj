package app.flux.dj.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
    val gridLine = Color(0x22FFFFFF)
    val gridStrong = Color(0x66FFFFFF)
    val tick = Color(0x33FFFFFF)
    val tickStrong = Color(0x55FFFFFF)
    val previewOff = Color(0xFF4A5068)
    val previewPending = Color(0xFF1D2131)
    val loadingScrim = Color(0xCC000000)
    val pastDim = Color(0x99090A0F)
    val mainCue = Color(0xFFFF8A3D)
    val scratchFlash = Color(0x11FFFFFF)
    val overviewBand = Color(0xFF3B4158)
    val toolbarScrim = Color(0xE6000000)
    val padEmpty = Color(0xFF0F121B)
    val padBorder = Color(0x22FFFFFF)
    val jogRimTrack = Color(0x14FFFFFF)
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

object D {
    val r1 = 1.dp
    val r2 = 2.dp
    val r3 = 3.dp
    val r5 = 5.dp
    val r6 = 6.dp
    val r7 = 7.dp
    val r8 = 8.dp
    val r9 = 9.dp
    val r10 = 10.dp
    val r12 = 12.dp
    val r14 = 14.dp
    val r16 = 16.dp
    val r18 = 18.dp
    val r20 = 20.dp
    val r22 = 22.dp
    val r24 = 24.dp
}

object F {
    val f6_5 = 6.5.sp
    val f7 = 7.sp
    val f7_5 = 7.5.sp
    val f8 = 8.sp
    val f8_5 = 8.5.sp
    val f9 = 9.sp
    val f10 = 10.sp
    val f11 = 11.sp
    val f12 = 12.sp
    val f13 = 13.sp
    val f14 = 14.sp
    val f15 = 15.sp
    val f16 = 16.sp
    val f17 = 17.sp
    val f19 = 19.sp
    val f20 = 20.sp
    val f22 = 22.sp
}
