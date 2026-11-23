package local.ha.todayearnings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 等宽数字，金额跳动时不抖。 */
val TabularNums = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun mutedColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

@Composable
fun secondaryColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

/** 圆角卡片：浅灰底 + 细边框。 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

@Composable
fun CardTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        color = mutedColor(),
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
fun StatusDot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

/** 一行：左标签，右侧内容。 */
@Composable
fun LabeledRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 14.sp, color = secondaryColor())
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** 小徽标：班 / 假 / 加。filled = 实心（这一项有钱），否则空心（没钱）。 */
@Composable
fun Badge(text: String, color: Color, filled: Boolean, alpha: Float = 1f) {
    Surface(
        modifier = Modifier.height(15.dp),
        shape = RoundedCornerShape(50),
        color = if (filled) color.copy(alpha = alpha) else Color.Transparent,
        border = BorderStroke(0.8.dp, if (filled) Color.Transparent else color.copy(alpha = 0.55f * alpha)),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            maxLines = 1,
            softWrap = false,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) Color.White else color.copy(alpha = 0.75f * alpha),
            modifier = Modifier.padding(horizontal = 5.dp),
        )
      }
    }
}
