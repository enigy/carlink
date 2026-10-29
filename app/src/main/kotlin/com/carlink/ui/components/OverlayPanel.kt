package com.carlink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.carlink.ui.theme.AutomotiveDimens

// The connecting screen's panels (the adapter's one-file install, a stock adapter's conversion): the
// plan page's mockup cards, over the scene where the status words otherwise go.

private val PanelText = Color(0xFFDDE4E5) // the overlay's fixed light text colour
private val WarnText = Color(0xFFF0A63A)

/** How a panel button looks: the main answer, a quiet one, or the way back. */
internal enum class PanelActionLook { PRIMARY, QUIET, DANGER }

internal data class PanelAction(
    val label: String,
    val look: PanelActionLook = PanelActionLook.PRIMARY,
    val onClick: () -> Unit,
)

/** What a panel says: a small [meta] line over the title, a spinner while [busy], a [progress] bar, a [note] and its buttons. */
internal data class PanelContent(
    val title: String,
    val body: String,
    val meta: String? = null,
    val note: String? = null,
    val busy: Boolean = false,
    val progress: Float? = null,
    val actions: List<PanelAction> = emptyList(),
)

@Composable
internal fun OverlayPanel(content: PanelContent) {
    Column(
        modifier =
            Modifier
                .widthIn(max = 960.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(24.dp))
                .padding(32.dp),
    ) {
        content.meta?.let {
            Text(text = it, style = MaterialTheme.typography.titleMedium, color = PanelText.copy(alpha = META_ALPHA))
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (content.busy) {
                LoadingSpinner(size = 36.dp, color = PanelText)
                Spacer(modifier = Modifier.width(16.dp))
            }
            Text(text = content.title, style = MaterialTheme.typography.headlineMedium, color = PanelText)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = content.body, style = MaterialTheme.typography.bodyLarge, color = PanelText)
        content.progress?.let { done ->
            Spacer(modifier = Modifier.height(20.dp))
            LinearProgressIndicator(
                progress = { done.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = PanelText,
                trackColor = PanelText.copy(alpha = TRACK_ALPHA),
                drawStopIndicator = {},
            )
        }
        content.note?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = it, style = MaterialTheme.typography.bodyLarge, color = WarnText)
        }
        if (content.actions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                content.actions.forEach { PanelButton(it) }
            }
        }
    }
}

@Composable
internal fun PanelButton(action: PanelAction) {
    val padding = PaddingValues(horizontal = AutomotiveDimens.ButtonPaddingHorizontal, vertical = AutomotiveDimens.ButtonPaddingVertical)
    val modifier = Modifier.height(AutomotiveDimens.ButtonMinHeight)
    val style = MaterialTheme.typography.titleLarge
    when (action.look) {
        PanelActionLook.PRIMARY ->
            FilledTonalButton(onClick = action.onClick, modifier = modifier, contentPadding = padding) {
                Text(text = action.label, style = style)
            }
        PanelActionLook.QUIET ->
            OutlinedButton(onClick = action.onClick, modifier = modifier, contentPadding = padding) {
                Text(text = action.label, style = style, color = PanelText)
            }
        PanelActionLook.DANGER ->
            OutlinedButton(onClick = action.onClick, modifier = modifier, contentPadding = padding) {
                Text(text = action.label, style = style, color = WarnText)
            }
    }
}

private const val META_ALPHA = 0.7f
private const val TRACK_ALPHA = 0.25f
