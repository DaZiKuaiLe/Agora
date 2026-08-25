package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.WebSearchPrompts
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsWebSearchPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val webSearchEnabled by viewModel.settings.webSearchEnabled.collectAsState()
    val webSearchProvider by viewModel.settings.webSearchProvider.collectAsState()
    val webSearchApiKeys by viewModel.settings.webSearchApiKeys.collectAsState()
    val webSearchBaseUrl by viewModel.settings.webSearchBaseUrl.collectAsState()
    var showProviderDialog by remember { mutableStateOf(false) }
    var apiKeyText by remember(webSearchProvider) { mutableStateOf(webSearchApiKeys[webSearchProvider] ?: "") }
    LaunchedEffect(webSearchProvider) { apiKeyText = webSearchApiKeys[webSearchProvider] ?: "" }

    // No-op bring-into-view to prevent auto-scrolling on text field focus
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.web_search_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("web-search.md") }
    ) {
            SettingsGroupColumn {
                SettingsGroup(title = stringResource(R.string.web_search_title), items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.web_search_enable)) },
                            supportingContent = { Text(stringResource(R.string.web_search_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = webSearchEnabled, onCheckedChange = { viewModel.settings.setWebSearchEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setWebSearchEnabled(!webSearchEnabled) }
                        )
                    }

                    if (webSearchEnabled) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.web_search_provider_label)) },
                                supportingContent = {
                                    Text(
                                        when (webSearchProvider) {
                                            "baidu" -> stringResource(R.string.web_search_baidu)
                                            "zhipu" -> stringResource(R.string.web_search_zhipu)
                                            "searxng" -> stringResource(R.string.web_search_searxng)
                                            "kagi" -> stringResource(R.string.web_search_kagi)
                                            "serper" -> stringResource(R.string.web_search_serper)
                                            "tavily" -> stringResource(R.string.web_search_tavily)
                                            "duckduckgo" -> stringResource(R.string.web_search_duckduckgo)
                                            else -> stringResource(R.string.web_search_brave)
                                        }
                                    )
                                },
                                leadingContent = { Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { showProviderDialog = true }
                            )
                        }

                        if (webSearchProvider != "searxng" && webSearchProvider != "duckduckgo") {
                            add {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                stringResource(
                                                    when (webSearchProvider) {
                                                        "baidu" -> R.string.web_search_baidu_key
                                                        "zhipu" -> R.string.web_search_zhipu_key
                                                        "kagi" -> R.string.web_search_kagi_key
                                                        "serper" -> R.string.web_search_serper_key
                                                        "tavily" -> R.string.web_search_tavily_key
                                                        else -> R.string.web_search_brave_key
                                                    }
                                                ),
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                                                OutlinedTextField(
                                                    value = apiKeyText,
                                                    onValueChange = { apiKeyText = it; viewModel.settings.setWebSearchApiKey(webSearchProvider, it) },
                                                    placeholder = {
                                                        Text(
                                                            stringResource(
                                                                when (webSearchProvider) {
                                                                    "baidu" -> R.string.web_search_baidu_key_hint
                                                                    "zhipu" -> R.string.web_search_zhipu_key_hint
                                                                    "kagi" -> R.string.web_search_kagi_key_hint
                                                                    "serper" -> R.string.web_search_serper_key_hint
                                                                    "tavily" -> R.string.web_search_tavily_key_hint
                                                                    else -> R.string.web_search_brave_key_hint
                                                                }
                                                            )
                                                        )
                                                    },
                                                    visualTransformation = PasswordVisualTransformation(),
                                                    shape = RoundedCornerShape(16.dp),
                                                    modifier = Modifier.fillMaxWidth(),
                                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (webSearchProvider == "searxng") {
                            add {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Icon(painter = painterResource(id = com.newoether.agora.R.drawable.link_24), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(stringResource(R.string.web_search_searxng_url), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                                            // Don't key on webSearchBaseUrl — that causes TextFieldState to be
                                            // recreated every time the debounced save writes to DataStore.
                                            val urlState = remember { TextFieldState(webSearchBaseUrl) }
                                            // Sync external changes (e.g. import) back into the text field.
                                            LaunchedEffect(webSearchBaseUrl) {
                                                val cur = urlState.text.toString()
                                                if (webSearchBaseUrl.isNotEmpty() && webSearchBaseUrl != cur) {
                                                    urlState.edit { replace(0, length, webSearchBaseUrl) }
                                                }
                                            }
                                            // Save user input with 500ms debounce.
                                            LaunchedEffect(urlState.text) {
                                                delay(500)
                                                viewModel.settings.setWebSearchBaseUrl(urlState.text.toString())
                                            }
                                            Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                                                OutlinedTextField(
                                                    state = urlState,
                                                    placeholder = { Text(stringResource(R.string.web_search_searxng_url_hint)) },
                                                    shape = RoundedCornerShape(16.dp),
                                                    modifier = Modifier.fillMaxWidth(),
                                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                )
                                            }
                                            Text(
                                                stringResource(R.string.web_search_searxng_fallback_desc),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                modifier = Modifier.padding(top = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        add {
                            WebSearchPromptPreview(provider = webSearchProvider)
                        }
                    }
                })
            }

            if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }

    if (showProviderDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showProviderDialog = false },
            title = { Text(stringResource(R.string.web_search_select_provider), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val providers = listOf(
                        "zhipu" to R.string.web_search_zhipu,
                        "baidu" to R.string.web_search_baidu,
                        "duckduckgo" to R.string.web_search_duckduckgo,
                        "brave" to R.string.web_search_brave,
                        "kagi" to R.string.web_search_kagi,
                        "serper" to R.string.web_search_serper,
                        "tavily" to R.string.web_search_tavily,
                        "searxng" to R.string.web_search_searxng
                    )
                    providers.forEach { (key, labelRes) ->
                        SettingsItem(
                            headlineContent = { Text(stringResource(labelRes), fontWeight = if (webSearchProvider == key) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        when (key) {
                                            "baidu" -> R.string.web_search_baidu_desc
                                            "zhipu" -> R.string.web_search_zhipu_desc
                                            "brave" -> R.string.web_search_brave_desc
                                            "kagi" -> R.string.web_search_kagi_desc
                                            "serper" -> R.string.web_search_serper_desc
                                            "tavily" -> R.string.web_search_tavily_desc
                                            "searxng" -> R.string.web_search_searxng_desc
                                            "duckduckgo" -> R.string.web_search_duckduckgo_desc
                                            else -> R.string.web_search_brave_desc
                                        }
                                    )
                                )
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = webSearchProvider == key,
                                    onClick = {
                                        viewModel.settings.setWebSearchProvider(key)
                                        showProviderDialog = false
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setWebSearchProvider(key)
                                showProviderDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProviderDialog = false }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }
}

/**
 * read-only preview of the exact prompt wording that gets shown to the model for the web_search
 * tool with the currently selected provider. Because the wording is plain text (numbered bullets +
 * parameter notes, no markdown), it is rendered as plain text.
 */
@Composable
private fun WebSearchPromptPreview(provider: String) {
    val text = buildString {
        append(WebSearchPrompts.DEFAULT_TOOL_DESCRIPTION)
        WebSearchPrompts.DEFAULT_PROVIDER_CONVENTIONS[provider]?.let {
            append("\n\n").append(it)
        }
        WebSearchPrompts.DEFAULT_PROVIDER_PARAMS[provider]?.forEach { (key, desc) ->
            append("\n\n").append("- $key：$desc")
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "发送给模型的搜索提示词预览",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
