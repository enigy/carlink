package com.carlink.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.carlink.ui.theme.LocalCarlinkColors

/**
 * Type and colour for the merged settings screen (design "Merged A"), sized for a head unit at
 * arm's length: 26 sp row titles, 20 sp secondary lines. Colours the theme does not carry — a
 * readable error red on the dark surface, the connected-phone tint — live here so every pane
 * agrees on them. Those colours come from [LocalCarlinkColors], so light and dark each get their own.
 */
internal object SettingsStyle {
    val PaneTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Medium)
    val RowTitle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium)
    val RowSubtitle = TextStyle(fontSize = 20.sp)
    val Body = TextStyle(fontSize = 21.sp, lineHeight = 30.sp)
    val SectionLabel = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.em)
    val Action = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium)

    /** Red that reads as text on this theme's settings surfaces (the scheme's error is too dark on dark). */
    val ErrorText: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalCarlinkColors.current.errorText
    val ConnectedFill: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalCarlinkColors.current.connectedFill
    val ConnectedBorder: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalCarlinkColors.current.connectedBorder
    val DisabledText: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalCarlinkColors.current.disabledText

    val RowMinHeight = 104.dp
    val ActionHeight = 64.dp
    val GroupShape = RoundedCornerShape(28.dp)
}

/** Pane heading with an optional one-line subtitle under it. */
@Composable
internal fun PaneHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier = modifier) {
        Text(text = title, style = SettingsStyle.PaneTitle)
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = SettingsStyle.RowSubtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Small capitals over a group: "DISPLAY MODE", "CALL MICROPHONE". */
@Composable
internal fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = SettingsStyle.SectionLabel,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp),
    )
}

/** A rounded card holding rows; put [GroupDivider] between them. */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SettingsStyle.GroupShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, LocalCarlinkColors.current.cardEdge),
    ) {
        Column(content = content)
    }
}

@Composable
internal fun GroupDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh)
}

/**
 * One row: optional leading icon, title over subtitle, then whatever [trailing] puts at the end.
 * [tint] colours the icon only (amber for a caution, red for a destructive fix).
 */
@Suppress("LongParameterList") // a slot row: words, icon, tint and trailing content
@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tint: Color = Color.Unspecified,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = SettingsStyle.RowMinHeight).padding(horizontal = 32.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (tint == Color.Unspecified) colors.onSurfaceVariant else tint,
                modifier = Modifier.size(32.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = SettingsStyle.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SettingsStyle.RowSubtitle,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        trailing()
    }
}

/** A row whose whole width toggles [checked]; the switch is the visible state. */
@Suppress("LongParameterList") // title, subtitle, state, callback, icon and the usual modifier
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        modifier = modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** How much an action disturbs: plain, cautionary (amber outline) or destructive (red fill). */
internal enum class ActionWeight { NORMAL, CAUTION, DANGER, DANGER_OUTLINE }

/** The trailing button on a row, at the head unit's touch height. */
@Composable
internal fun RowAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    weight: ActionWeight = ActionWeight.NORMAL,
) {
    val colors = MaterialTheme.colorScheme
    val extra = LocalCarlinkColors.current
    val shapeModifier = modifier.heightIn(min = SettingsStyle.ActionHeight)
    val padding = PaddingValues(horizontal = 28.dp)
    when (weight) {
        ActionWeight.NORMAL ->
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = shapeModifier,
                contentPadding = padding,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer,
                        disabledContainerColor = extra.disabledFill,
                        disabledContentColor = extra.disabledText,
                    ),
            ) { Text(label, style = SettingsStyle.Action) }
        ActionWeight.DANGER ->
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = shapeModifier,
                contentPadding = padding,
                colors = ButtonDefaults.buttonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer),
            ) { Text(label, style = SettingsStyle.Action) }
        ActionWeight.CAUTION, ActionWeight.DANGER_OUTLINE -> {
            val tint = if (weight == ActionWeight.CAUTION) colors.tertiary else SettingsStyle.ErrorText
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                modifier = shapeModifier,
                contentPadding = padding,
                border = BorderStroke(2.dp, if (enabled && weight == ActionWeight.CAUTION) tint else extra.dangerOutline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
            ) { Text(label, style = SettingsStyle.Action) }
        }
    }
}

/** A row whose choice sits under its words, so long option labels never squeeze the title. */
@Suppress("LongParameterList") // the row's words, its options and the callback
@Composable
internal fun <T : Labelled> ChoiceSetting(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    Column(modifier = modifier.padding(bottom = 24.dp)) {
        SettingRow(title = title, subtitle = subtitle, icon = icon)
        ChoiceRow(options = options, selected = selected, onSelect = onSelect, modifier = Modifier.padding(start = 88.dp, end = 32.dp))
    }
}

/** A labelled single choice as a segmented row; applies the moment it is tapped. */
@Composable
internal fun <T : Labelled> ChoiceRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.label },
) {
    val colors = MaterialTheme.colorScheme
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.heightIn(min = 72.dp),
                colors =
                    SegmentedButtonDefaults.colors(
                        activeContainerColor = colors.primaryContainer,
                        activeContentColor = colors.onPrimaryContainer,
                        inactiveContainerColor = Color.Transparent,
                    ),
            ) { Text(label(option), style = SettingsStyle.Action) }
        }
    }
}

/** "Bluetooth" with a check when the box reports it alive, a dash when not (or not yet known). */
@Composable
internal fun HealthChip(
    item: HealthItem,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val ok = item.ok == true
    Surface(modifier = modifier, shape = RoundedCornerShape(22.dp), color = colors.surface) {
        Row(
            modifier = Modifier.heightIn(min = 44.dp).padding(start = 12.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (ok) Icons.Default.Check else Icons.Default.Remove,
                contentDescription = if (ok) "running" else "not running",
                tint = if (ok) colors.primary else colors.outline,
                modifier = Modifier.size(22.dp),
            )
            Text(text = item.label, style = SettingsStyle.RowSubtitle, color = if (ok) colors.onSurface else colors.onSurfaceVariant)
        }
    }
}
