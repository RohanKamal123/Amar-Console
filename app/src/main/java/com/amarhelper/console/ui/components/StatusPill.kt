package com.amarhelper.console.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.amarhelper.console.domain.model.HealthState
import com.amarhelper.console.domain.model.TaskState
import com.amarhelper.console.ui.theme.Amber100
import com.amarhelper.console.ui.theme.Amber400
import com.amarhelper.console.ui.theme.Amber700
import com.amarhelper.console.ui.theme.Amber900
import com.amarhelper.console.ui.theme.Emerald100
import com.amarhelper.console.ui.theme.Emerald400
import com.amarhelper.console.ui.theme.Emerald700
import com.amarhelper.console.ui.theme.Emerald900
import com.amarhelper.console.ui.theme.Rose100
import com.amarhelper.console.ui.theme.Rose400
import com.amarhelper.console.ui.theme.Rose700
import com.amarhelper.console.ui.theme.Rose900
import com.amarhelper.console.ui.theme.Slate100
import com.amarhelper.console.ui.theme.Slate400
import com.amarhelper.console.ui.theme.Slate600
import com.amarhelper.console.ui.theme.Slate900

/**
 * A compact state badge.
 *
 * Every pill carries an icon *and* a word, so the state survives greyscale, colour
 * blindness and a screen reader. The whole pill reads as one label to TalkBack.
 */
@Composable
fun StatusPill(
    label: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = label }
            .background(container, RoundedCornerShape(percent = 50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
fun TaskStatePill(state: TaskState, modifier: Modifier = Modifier, dark: Boolean = isDarkTheme()) {
    val (icon, container, content) = when (state) {
        TaskState.QUEUED -> Triple(Icons.Filled.Schedule, slateContainer(dark), slateContent(dark))
        TaskState.RUNNING -> Triple(Icons.Filled.Bolt, amberContainer(dark), amberContent(dark))
        TaskState.WAITING -> Triple(Icons.Filled.HourglassEmpty, amberContainer(dark), amberContent(dark))
        TaskState.COMPLETED -> Triple(Icons.Filled.CheckCircle, emeraldContainer(dark), emeraldContent(dark))
        TaskState.STOPPED -> Triple(Icons.Filled.RemoveCircleOutline, slateContainer(dark), slateContent(dark))
        TaskState.FAILED -> Triple(Icons.Filled.ErrorOutline, roseContainer(dark), roseContent(dark))
        TaskState.CANCELLED -> Triple(Icons.Filled.RemoveCircleOutline, slateContainer(dark), slateContent(dark))
        TaskState.UNKNOWN -> Triple(Icons.AutoMirrored.Filled.HelpOutline, slateContainer(dark), slateContent(dark))
    }
    StatusPill(state.name, icon, container, content, modifier)
}

@Composable
fun HealthPill(state: HealthState, modifier: Modifier = Modifier, dark: Boolean = isDarkTheme()) {
    val (label, icon, colors) = when (state) {
        HealthState.ONLINE -> Triple("Online", Icons.Filled.CheckCircle, emeraldContainer(dark) to emeraldContent(dark))
        HealthState.DEGRADED -> Triple("Degraded", Icons.Filled.ErrorOutline, amberContainer(dark) to amberContent(dark))
        HealthState.OFFLINE -> Triple("Offline", Icons.Filled.Circle, roseContainer(dark) to roseContent(dark))
        HealthState.NOT_CONFIGURED -> Triple("Not set up", Icons.Filled.RemoveCircleOutline, slateContainer(dark) to slateContent(dark))
        HealthState.UNKNOWN -> Triple("Unknown", Icons.AutoMirrored.Filled.HelpOutline, slateContainer(dark) to slateContent(dark))
    }
    StatusPill(label, icon, colors.first, colors.second, modifier)
}

@Composable
private fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminanceIsDark()

private fun Color.luminanceIsDark(): Boolean = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f

private fun emeraldContainer(dark: Boolean) = if (dark) Emerald900 else Emerald100
private fun emeraldContent(dark: Boolean) = if (dark) Emerald400 else Emerald700
private fun amberContainer(dark: Boolean) = if (dark) Amber900 else Amber100
private fun amberContent(dark: Boolean) = if (dark) Amber400 else Amber700
private fun roseContainer(dark: Boolean) = if (dark) Rose900 else Rose100
private fun roseContent(dark: Boolean) = if (dark) Rose400 else Rose700
private fun slateContainer(dark: Boolean) = if (dark) Slate900 else Slate100
private fun slateContent(dark: Boolean) = if (dark) Slate400 else Slate600
