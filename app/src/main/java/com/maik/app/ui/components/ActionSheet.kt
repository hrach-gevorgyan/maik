package com.maik.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maik.app.ui.theme.muted
import com.maik.app.ui.theme.tap

/** One line in an [ActionSheet]. [destructive] paints it in the error colour. */
@androidx.compose.runtime.Immutable
data class SheetAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * The menu that comes up on a long press: a sheet from the bottom, where a thumb
 * already is, rather than a dialog in the middle of the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    title: String? = null,
    subtitle: String? = null,
    actions: List<SheetAction>,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val buzz = tap()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceVariant,
        contentColor = scheme.onSurface
    ) {
        Column(Modifier.padding(bottom = 28.dp)) {
            if (title != null) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.muted
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            actions.forEach { action ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable {
                            buzz()
                            action.onClick()
                        }
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        action.label,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight(600)),
                        color = if (action.destructive) scheme.error else scheme.onSurface
                    )
                }
            }
        }
    }
}
