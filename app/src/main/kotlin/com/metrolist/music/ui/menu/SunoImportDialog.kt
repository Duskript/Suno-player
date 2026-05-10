/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.metrolist.music.R
import com.metrolist.music.viewmodels.SunoImportViewModel

@Composable
fun SunoImportDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    viewModel: SunoImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val url by viewModel.urlInput.collectAsState()

    if (!isVisible) return

    // Auto-dismiss on success
    LaunchedEffect(state.success) {
        if (state.success) {
            kotlinx.coroutines.delay(1500)
            viewModel.reset()
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!state.isImporting) {
                viewModel.reset()
                onDismiss()
            }
        },
        icon = {
            Icon(
                painter = painterResource(R.drawable.add),
                contentDescription = null,
            )
        },
        title = {
            Text(text = "Import from Suno")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isImporting) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Importing...",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(
                            text = "This may take a moment for playlist imports",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else if (state.success) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "✅ Imported",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = state.importedSongTitle ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { viewModel.updateUrl(it) },
                        label = { Text("Suno URL") },
                        placeholder = { Text("https://suno.com/s/... or playlist URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = { viewModel.importFromUrl() },
                        ),
                        enabled = !state.isImporting,
                    )

                    if (state.error != null) {
                        Text(
                            text = state.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    Text(
                        text = "Supports single songs (suno.com/s/...) and playlists (suno.com/playlist/...)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (!state.success && !state.isImporting) {
                TextButton(
                    onClick = { viewModel.importFromUrl() },
                    enabled = url.isNotBlank(),
                ) {
                    Text("Import")
                }
            }
        },
        dismissButton = {
            if (!state.isImporting) {
                TextButton(
                    onClick = {
                        viewModel.reset()
                        onDismiss()
                    },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}
