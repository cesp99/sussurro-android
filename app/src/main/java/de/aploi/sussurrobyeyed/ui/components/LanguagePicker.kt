package de.aploi.sussurrobyeyed.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.aploi.sussurrobyeyed.R
import de.aploi.sussurrobyeyed.data.WhisperLanguages

/**
 * A clickable settings row that opens an [AlertDialog] with a radio-button list
 * of every Whisper language.
 *
 * The previous implementation used [androidx.compose.material3.DropdownMenu]
 * anchored to a `FilledTonalButton` inside a [Row]. On the current
 * `material3:1.5.0-alpha20` the menu could open with no visible items
 * (off-screen anchor / zero-height popup), making the picker effectively
 * invisible. A dialog with radio buttons has none of those layout pitfalls
 * and matches the standard Android "pick one" pattern.
 */
@Composable
internal fun LanguagePicker(
    currentCode: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    val currentName = WhisperLanguages.nameFor(currentCode)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { dialogOpen = true }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_language_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.settings_language_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = currentName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }

    if (dialogOpen) {
        LanguagePickerDialog(
            currentCode = currentCode,
            onSelect = {
                onSelect(it)
                dialogOpen = false
            },
            onDismiss = { dialogOpen = false },
        )
    }
}

@Composable
private fun LanguagePickerDialog(
    currentCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_section_language)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                WhisperLanguages.all.forEach { lang ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = lang.code == currentCode,
                                onClick = { onSelect(lang.code) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = lang.code == currentCode,
                            onClick = null, // handled by the row's selectable
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = lang.name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_close))
            }
        },
    )
}
