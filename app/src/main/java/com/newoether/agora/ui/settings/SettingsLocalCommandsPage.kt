package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

@Composable
fun SettingsLocalCommandsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val localCommandEnabled by viewModel.settings.localCommandEnabled.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    val scrollState = rememberScrollState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.local_command_title),
        onBack = onBack,
        scrollState = scrollState,
        floatingActionButton = { if (showDocFab) DocumentationFab("local-commands.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.local_command_title), items = listOf {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.local_command_enable)) },
                    supportingContent = { Text(stringResource(R.string.local_command_enable_desc)) },
                    leadingContent = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Switch(checked = localCommandEnabled, onCheckedChange = { viewModel.settings.setLocalCommandEnabled(it) }) },
                    modifier = Modifier.clickable { viewModel.settings.setLocalCommandEnabled(!localCommandEnabled) }
                )
            })
        }

        if (showDocFab) Spacer(Modifier.height(80.dp))
    }
}
