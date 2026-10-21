package com.carlink.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.carlink.ui.theme.LocalCarlinkColors

/**
 * Every confirmation in the app, design "Dialog B" (2026-10-21): a 32 sp title beside an icon tile,
 * 22 sp text, and Cancel and the action as two equal 88 dp halves along the bottom edge, so both are
 * easy to hit at arm's length. It replaced stock AlertDialogs (14 sp text, 40 dp text buttons).
 *
 * [onDismiss] is Cancel, and also back or a tap outside unless [dismissible] is false. With no
 * [dismissLabel] the action fills the whole bottom edge (a notice with only OK). [extra] goes under
 * the text, such as the pairing code.
 */
@Suppress("LongParameterList") // the dialog's words, its two answers and how they look
@Composable
internal fun CarlinkDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    weight: DialogWeight = DialogWeight.NORMAL,
    dismissLabel: String? = "Cancel",
    confirmEnabled: Boolean = true,
    dismissible: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible, usePlatformDefaultWidth = false),
    ) {
        DialogSystemBars() // keep the head unit's bars hidden while this window has focus
        val palette = dialogPalette(LocalCarlinkColors.current.dark, weight)
        Surface(
            modifier = Modifier.padding(32.dp).widthIn(max = 880.dp).fillMaxWidth(),
            shape = RoundedCornerShape(36.dp),
            color = palette.fill,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, palette.border),
        ) {
            Column {
                Column(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 40.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        if (icon != null) {
                            Surface(modifier = Modifier.size(72.dp), shape = RoundedCornerShape(22.dp), color = palette.tile, contentColor = palette.onTile) {
                                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp)) }
                            }
                        }
                        Text(title, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(text, fontSize = 22.sp, lineHeight = 32.sp, color = palette.body, modifier = Modifier.padding(top = 24.dp))
                    extra?.invoke()
                }
                HorizontalDivider(color = palette.divider)
                Row(modifier = Modifier.fillMaxWidth().height(88.dp)) {
                    if (dismissLabel != null) {
                        DialogHalf(dismissLabel, onDismiss, Color.Transparent, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                        VerticalDivider(color = palette.divider)
                    }
                    DialogHalf(confirmLabel, onConfirm, palette.action, palette.onAction, Modifier.weight(1f), enabled = confirmEnabled)
                }
            }
        }
    }
}

@Suppress("LongParameterList") // one half of the button edge: label, action, colours, state
@Composable
private fun DialogHalf(
    label: String,
    onClick: () -> Unit,
    fill: Color,
    content: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxHeight(),
        shape = RectangleShape,
        color = fill,
        contentColor = if (enabled) content else content.copy(alpha = DISABLED_ALPHA),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 26.sp, fontWeight = FontWeight.Medium) }
    }
}

private const val DISABLED_ALPHA = 0.38f

private data class DialogPalette(
    val fill: Color,
    val border: Color,
    val divider: Color,
    val body: Color,
    val tile: Color,
    val onTile: Color,
    val action: Color,
    val onAction: Color,
)

/** Dialog colours per theme, with the tile and the confirm half tinted by [weight] (teal, amber, red). */
private fun dialogPalette(
    dark: Boolean,
    weight: DialogWeight,
): DialogPalette {
    // tile, onTile, action, onAction
    val tints =
        if (dark) {
            when (weight) {
                DialogWeight.NORMAL -> listOf(0xFF13303A, 0xFF5FD5ED, 0xFF16333A, 0xFF7FE3F5)
                DialogWeight.CAUTION -> listOf(0xFF3A2C10, 0xFFFFB951, 0xFF3A2C10, 0xFFFFC772)
                DialogWeight.DANGER -> listOf(0xFF3B1614, 0xFFFFB4AB, 0xFF4A1512, 0xFFFFB4AB)
            }
        } else {
            when (weight) {
                DialogWeight.NORMAL -> listOf(0xFFD3F2FA, 0xFF00566A, 0xFFD3F2FA, 0xFF004E5C)
                DialogWeight.CAUTION -> listOf(0xFFFFDDB0, 0xFF5C4300, 0xFFFFDDB0, 0xFF4D3800)
                DialogWeight.DANGER -> listOf(0xFFFFDAD6, 0xFFBA1A1A, 0xFFFFDAD6, 0xFF93000A)
            }
        }.map { Color(it) }
    return DialogPalette(
        fill = Color(if (dark) 0xFF1F2829 else 0xFFFFFFFF),
        border = Color(if (dark) 0xFF30393A else 0xFFD5DDDF),
        divider = Color(if (dark) 0xFF3F484A else 0xFFD5DDDF),
        body = Color(if (dark) 0xFFB8C3C4 else 0xFF3F484A),
        tile = tints[0],
        onTile = tints[1],
        action = tints[2],
        onAction = tints[3],
    )
}
