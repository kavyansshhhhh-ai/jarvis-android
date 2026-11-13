package app.shilex.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A translucent Liquid Glass card container with specular highlight rim. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xB3141F36), // translucent dark navy/blue
                        Color(0x800B111F),
                    ),
                ),
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        Color(0x40FFFFFF), // top specular light reflection
                        Color(0x2638E5FF), // subtle cyan refractor
                        Color(0x0AFFFFFF),
                    ),
                ),
                shape,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        content = content,
    )
}

/** A titled group of rows inside a floating Liquid Glass card. */
@Composable
fun Section(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = Muted,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }
        GlassCard(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** An iOS-styled Liquid Glass switch row with smooth animated thumb and gradient track. */
@Composable
fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) { onChange(!checked) }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) PureWhite else Muted)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(14.dp))
        IosGlassSwitch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/** iOS-style Liquid Glass toggle switch. */
@Composable
fun IosGlassSwitch(
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(durationMillis = 220),
        label = "switchThumb",
    )
    val trackBgColor by animateColorAsState(
        targetValue = if (checked) ElectricCyan else Color(0x33FFFFFF),
        animationSpec = tween(durationMillis = 220),
        label = "switchTrackColor",
    )

    Box(
        modifier = Modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (checked) {
                    Brush.horizontalGradient(listOf(ElectricCyan, NeonViolet))
                } else {
                    SolidColor(Color(0x26FFFFFF))
                },
            )
            .border(
                1.dp,
                if (checked) Color(0x6638E5FF) else Color(0x26FFFFFF),
                RoundedCornerShape(16.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Glowing thumb
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(26.dp)
                .shadow(elevation = 3.dp, shape = CircleShape)
                .background(Color.White, CircleShape)
                .border(0.5.dp, Color(0x33000000), CircleShape),
        )
    }
}

/** A row of text with an optional trailing control. */
@Composable
fun InfoRow(
    title: String,
    detail: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = PureWhite)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A modern Liquid Glass pill badge with glowing dot and translucent border. */
@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    dot: Boolean = true,
) {
    Row(
        modifier
            .background(
                Brush.horizontalGradient(
                    listOf(
                        color.copy(alpha = 0.16f),
                        color.copy(alpha = 0.08f),
                    ),
                ),
                RoundedCornerShape(50),
            )
            .border(
                0.8.dp,
                Brush.horizontalGradient(
                    listOf(
                        color.copy(alpha = 0.45f),
                        color.copy(alpha = 0.15f),
                    ),
                ),
                RoundedCornerShape(50),
            )
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
                    .shadow(elevation = 2.dp, shape = CircleShape, ambientColor = color, spotColor = color),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
    }
}

/** Material microphone glyph. */
val MicIcon: ImageVector by lazy {
    ImageVector.Builder("Mic", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 14f)
            curveToRelative(1.66f, 0f, 2.99f, -1.34f, 2.99f, -3f)
            lineTo(15f, 5f)
            curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
            reflectiveCurveTo(9f, 3.34f, 9f, 5f)
            verticalLineToRelative(6f)
            curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
            close()
            moveTo(17.3f, 11f)
            curveToRelative(0f, 3f, -2.54f, 5.1f, -5.3f, 5.1f)
            reflectiveCurveTo(6.7f, 14f, 6.7f, 11f)
            lineTo(5f, 11f)
            curveToRelative(0f, 3.41f, 2.72f, 6.23f, 6f, 6.72f)
            lineTo(11f, 21f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-3.28f)
            curveToRelative(3.28f, -0.48f, 6f, -3.3f, 6f, -6.72f)
            horizontalLineToRelative(-1.7f)
            close()
        }
    }.build()
}
