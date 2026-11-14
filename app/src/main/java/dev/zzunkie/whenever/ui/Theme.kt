package dev.zzunkie.whenever.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 밝은 바탕, 큰 글씨(본문 30sp, 가장 작은 글씨 28sp), 터치 대상 96dp 이상.
object C {
    val bg = Color(0xFFFFFFFF)
    val rail = Color(0xFFF1F4F8)
    val ink = Color(0xFF14181F)
    val sub = Color(0xFF5B6472)
    val line = Color(0xFFE1E6ED)
    val primary = Color(0xFF1F5EEB)
    val primarySoft = Color(0xFFE3ECFF)
    val ok = Color(0xFF14804A)
    val wait = Color(0xFFB25E00)
    val bad = Color(0xFFC62828)
    val off = Color(0xFF8A929E)
}

object T {
    val title = TextStyle(fontSize = 46.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold, color = C.ink)
    val head = TextStyle(fontSize = 36.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, color = C.ink)
    val body = TextStyle(fontSize = 32.sp, lineHeight = 44.sp, color = C.ink)
    val small = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, color = C.sub)
    val button = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
}

val TOUCH: Dp = 104.dp

@Composable
fun WheneverTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.primary, onPrimary = Color.White, background = C.bg, surface = C.bg,
            onBackground = C.ink, onSurface = C.ink, secondaryContainer = C.primarySoft,
        ),
        typography = Typography(bodyLarge = T.body),
        content = content,
    )
}

/** 마이크 모양 (아이콘 라이브러리 없이 그림) */
@Composable
fun MicGlyph(color: Color, size: Dp = 48.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.09f
        drawRoundRect(color, topLeft = Offset(w * 0.34f, h * 0.06f), size = Size(w * 0.32f, h * 0.52f), cornerRadius = CornerRadius(w * 0.16f))
        drawArc(color, 0f, 180f, false, topLeft = Offset(w * 0.2f, h * 0.22f), size = Size(w * 0.6f, h * 0.5f),
            style = Stroke(stroke, cap = StrokeCap.Round))
        drawLine(color, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.9f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.34f, h * 0.92f), Offset(w * 0.66f, h * 0.92f), stroke, StrokeCap.Round)
    }
}
