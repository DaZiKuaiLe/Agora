package com.newoether.agora.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.R
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.sandbox.SandboxSharedStorageAccess
import com.newoether.agora.ui.files.LocalFilePickerDialog
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.util.SshAuth
import com.newoether.agora.util.readSshPrivateKeyFile
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsShellPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val shellEnabled by viewModel.settings.shellEnabled.collectAsState()
    val shellConfirmEnabled by viewModel.settings.shellConfirmEnabled.collectAsState()
    val shellDevices by viewModel.settings.shellDevices.collectAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var newlyAddedDeviceId by remember { mutableStateOf<String?>(null) }
    var deleteConfirmDeviceId by remember { mutableStateOf<String?>(null) }
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    var allFilesGranted by remember { mutableStateOf(SandboxSharedStorageAccess.isGranted(context)) }
    val allFilesSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        allFilesGranted = SandboxSharedStorageAccess.isGranted(context)
    }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        allFilesGranted = SandboxSharedStorageAccess.isGranted(context)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesGranted = SandboxSharedStorageAccess.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SandboxSharedStorageAccess.settingsIntent(context)
                ?.let(allFilesSettingsLauncher::launch)
        } else {
            legacyStoragePermissionLauncher.launch(SandboxSharedStorageAccess.legacyPermissions)
        }
    }

    val scrollState = rememberScrollState()
    CollapsingSettingsScaffold(
        title = stringResource(R.string.shell_title),
        onBack = onBack,
        scrollState = scrollState,
        floatingActionButton = { if (showDocFab) DocumentationFab("shell.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.shell_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.shell_enable)) },
                        supportingContent = { Text(stringResource(R.string.shell_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = shellEnabled, onCheckedChange = { viewModel.settings.setShellEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setShellEnabled(!shellEnabled) }
                    )
                }
                if (shellEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.shell_confirm_setting)) },
                            supportingContent = { Text(stringResource(R.string.shell_confirm_setting_desc)) },
                            leadingContent = { Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Switch(checked = shellConfirmEnabled, onCheckedChange = { viewModel.setShellConfirmEnabled(it) }) },
                            modifier = Modifier.clickable { viewModel.setShellConfirmEnabled(!shellConfirmEnabled) }
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.shell_all_files_title)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (allFilesGranted) R.string.shell_all_files_granted
                                        else R.string.shell_all_files_desc,
                                    )
                                )
                            },
                            leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                if (!allFilesGranted) {
                                    TextButton(onClick = { requestAllFilesAccess() }) {
                                        Text(stringResource(R.string.shell_all_files_grant))
                                    }
                                }
                            },
                            modifier = Modifier.clickable { if (!allFilesGranted) requestAllFilesAccess() },
                        )
                    }
                }
            })

            if (shellEnabled) {
                // ── Remote Devices ──────────────────────────
                SettingsGroup(title = stringResource(R.string.shell_devices), items = buildList {
                    if (shellDevices.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.shell_no_devices), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                leadingContent = { Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                                modifier = Modifier.heightIn(min = 64.dp)
                            )
                        }
                    } else {
                        shellDevices.forEach { device -> add { DeviceEditor(viewModel, device, scrollState, density, newlyAddedDeviceId, onNewDeviceId = { newlyAddedDeviceId = it }, onDeleteConfirm = { deleteConfirmDeviceId = it }) } }
                    }
                    add {
                        SettingsAddItem(
                            label = stringResource(R.string.shell_add_device),
                            onClick = {
                                val newId = UUID.randomUUID().toString()
                                newlyAddedDeviceId = newId
                                viewModel.addShellDevice(ShellDeviceConfig(id = newId, name = "", description = "", type = "ssh"))
                            },
                        )
                    }
                })
            }
        }

        // ── Delete confirm dialog ──
        deleteConfirmDeviceId?.let { deviceId ->
            val device = shellDevices.find { it.id == deviceId }
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onDismissRequest = { deleteConfirmDeviceId = null },
                title = { Text(stringResource(R.string.shell_delete_confirm_title), fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.shell_delete_confirm_message, device?.name?.ifBlank { stringResource(R.string.search_untitled) } ?: "")) },
                confirmButton = {
                    TextButton(onClick = { viewModel.settings.removeShellDevice(deviceId); deleteConfirmDeviceId = null },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = { TextButton(onClick = { deleteConfirmDeviceId = null }) { Text(stringResource(R.string.cancel)) } }
            )
        }

        if (showDocFab) Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════
// Device Editor (extracted for readability)
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceEditor(
    viewModel: ChatViewModel,
    device: ShellDeviceConfig,
    scrollState: androidx.compose.foundation.ScrollState,
    density: androidx.compose.ui.unit.Density,
    newlyAddedDeviceId: String?,
    onNewDeviceId: (String?) -> Unit,
    onDeleteConfirm: (String?) -> Unit
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val isNewlyAdded = device.id == newlyAddedDeviceId
    var expanded by remember(device.id) { mutableStateOf(false) }
    var nameInput by remember(device.id) { mutableStateOf(device.name) }
    var descInput by remember(device.id) { mutableStateOf(device.description) }
    var sshHostInput by remember(device.id) { mutableStateOf(device.sshHost) }
    var sshPortInput by remember(device.id) { mutableStateOf(device.sshPort.toString()) }
    var sshUserInput by remember(device.id) { mutableStateOf(device.sshUser) }
    var sshPwInput by remember(device.id) { mutableStateOf(device.sshPassword) }
    var usePublicKey by remember(device.id) { mutableStateOf(device.sshPrivateKey.isNotBlank()) }
    var sshPrivateKeyInput by remember(device.id) { mutableStateOf(device.sshPrivateKey) }
    var sshKeyPassInput by remember(device.id) { mutableStateOf(device.sshKeyPassphrase) }
    var sshKeyLabelInput by remember(device.id) { mutableStateOf(device.sshKeyLabel) }
    var keyImportError by remember(device.id) { mutableStateOf<String?>(null) }
    var showKeyPicker by remember(device.id) { mutableStateOf(false) }
    var sshHostKeyInput by remember(device.id) { mutableStateOf(device.sshHostKey) }
    var sshShellInput by remember(device.id) { mutableStateOf(device.sshShell.ifBlank { "auto" }) }
    var sshShellMenuExpanded by remember(device.id) { mutableStateOf(false) }
    val nameFocusRequester = remember { FocusRequester() }
    val sshHostFocusRequester = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val editorContext = LocalContext.current
    fun importSshPrivateKey(path: String) {
        scope.launch {
            val file = File(path)
            val parsed = withContext(Dispatchers.IO) { readSshPrivateKeyFile(file) }
            parsed.fold(
                onSuccess = { text ->
                    sshPrivateKeyInput = text
                    sshKeyLabelInput = file.name.ifBlank { "id_ed25519" }
                    keyImportError = null
                },
                onFailure = { error ->
                    keyImportError = editorContext.getString(
                        when (error.message) {
                            SshAuth.IMPORT_TOO_LARGE -> R.string.shell_ssh_key_too_large
                            SshAuth.IMPORT_UNRECOGNIZED -> R.string.shell_ssh_key_invalid
                            else -> R.string.shell_ssh_key_read_failed
                        },
                    )
                },
            )
        }
    }
    var verifying by remember(device.id) { mutableStateOf(false) }
    // Captured (key, fingerprint) awaiting the user's trust decision.
    var pendingVerify by remember(device.id) { mutableStateOf<Pair<String, String>?>(null) }
    var verifyError by remember(device.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(device) {
        nameInput = device.name; descInput = device.description
        sshHostInput = device.sshHost; sshPortInput = device.sshPort.toString()
        sshUserInput = device.sshUser; sshPwInput = device.sshPassword
        usePublicKey = device.sshPrivateKey.isNotBlank()
        sshPrivateKeyInput = device.sshPrivateKey
        sshKeyPassInput = device.sshKeyPassphrase
        sshKeyLabelInput = device.sshKeyLabel
        keyImportError = null
        sshHostKeyInput = device.sshHostKey
        sshShellInput = device.sshShell.ifBlank { "auto" }
    }

    LaunchedEffect(isNewlyAdded) {
        if (isNewlyAdded) {
            expanded = true; delay(50); sshHostFocusRequester.requestFocus()
            val target = scrollState.maxValue + (250 * density.density).toInt()
            if (motionPolicy.allowProgrammaticScrollMotion) {
                scrollState.animateScrollTo(target, tween(500))
            } else {
                scrollState.scrollTo(target)
            }
            onNewDeviceId(null)
        }
    }

    Column {
        SettingsItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name.ifBlank { stringResource(R.string.search_untitled) }, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text("SSH",
                            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            },
            supportingContent = {
                if (device.description.isNotBlank()) Text(device.description)
                else if (sshHostInput.isNotBlank()) Text("$sshUserInput@$sshHostInput:$sshPortInput")
            },
            leadingContent = { Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, stringResource(R.string.edit))
                }
            },
            modifier = Modifier.clickable { expanded = !expanded }
        )

        AnimatedVisibility(
            visible = expanded,
            enter = if (motionPolicy.allowSpatialTransitions) {
                expandVertically()
            } else {
                fadeIn()
            },
            exit = if (motionPolicy.allowSpatialTransitions) {
                shrinkVertically()
            } else {
                fadeOut()
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(value = sshHostInput, onValueChange = { sshHostInput = it }, label = { Text(stringResource(R.string.shell_device_host)) },
                    placeholder = { Text(stringResource(R.string.shell_device_host_hint)) }, leadingIcon = { Icon(Icons.Default.Dns, null) },
                    singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().focusRequester(sshHostFocusRequester))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sshPortInput, onValueChange = { sshPortInput = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.shell_device_port)) }, leadingIcon = { Icon(Icons.Default.SettingsEthernet, null) },
                        singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(0.4f))
                    OutlinedTextField(value = sshUserInput, onValueChange = { sshUserInput = it },
                        label = { Text(stringResource(R.string.shell_device_user)) }, leadingIcon = { Icon(Icons.Default.Person, null) },
                        singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(0.6f))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.shell_ssh_auth_method),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !usePublicKey,
                        onClick = { usePublicKey = false },
                        label = { Text(stringResource(R.string.shell_ssh_auth_password)) },
                        leadingIcon = { Icon(Icons.Default.Password, null, Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        selected = usePublicKey,
                        onClick = { usePublicKey = true },
                        label = { Text(stringResource(R.string.shell_ssh_auth_private_key)) },
                        leadingIcon = { Icon(Icons.Default.Key, null, Modifier.size(18.dp)) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (!usePublicKey) {
                    OutlinedTextField(
                        value = sshPwInput,
                        onValueChange = { sshPwInput = it },
                        label = { Text(stringResource(R.string.shell_device_password)) },
                        leadingIcon = { Icon(Icons.Default.Password, null) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    if (sshPrivateKeyInput.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Default.Key,
                                null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    sshKeyLabelInput.ifBlank {
                                        stringResource(R.string.shell_ssh_key_unnamed)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stringResource(R.string.shell_ssh_key_imported),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                sshPrivateKeyInput = ""
                                sshKeyLabelInput = ""
                                sshKeyPassInput = ""
                                keyImportError = null
                            }) { Text(stringResource(R.string.shell_ssh_clear_key)) }
                        }
                    } else {
                        Text(
                            stringResource(R.string.shell_ssh_key_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showKeyPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.shell_ssh_import_key))
                    }
                    keyImportError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = sshKeyPassInput,
                        onValueChange = { sshKeyPassInput = it },
                        label = { Text(stringResource(R.string.shell_ssh_key_passphrase)) },
                        leadingIcon = { Icon(Icons.Default.Password, null) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ── Host-key pinning (TOFU) ──
                Spacer(Modifier.height(10.dp))
                val pinned = sshHostKeyInput.isNotBlank()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        if (pinned) Icons.Default.VerifiedUser else Icons.Default.GppMaybe,
                        null, modifier = Modifier.size(18.dp),
                        tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (pinned) R.string.shell_ssh_host_key_pinned else R.string.shell_ssh_host_key_not_pinned),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pinned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                        )
                        if (pinned) {
                            Text(
                                com.newoether.agora.util.SshClient.fingerprintSha256(sshHostKeyInput),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (pinned) {
                        TextButton(onClick = { sshHostKeyInput = "" }) { Text(stringResource(R.string.shell_ssh_unpin)) }
                    }
                }
                verifyError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        verifyError = null; verifying = true
                        scope.launch {
                            val result = viewModel.verifySshHostKey(
                                sshHostInput.trim(), sshPortInput.toIntOrNull() ?: 22,
                                sshUserInput.trim().ifBlank { "root" },
                                SshAuth.exclusive(
                                    usePublicKey = usePublicKey,
                                    password = sshPwInput,
                                    privateKey = sshPrivateKeyInput,
                                    passphrase = sshKeyPassInput,
                                ),
                            )
                            verifying = false
                            result.fold(
                                onSuccess = { pendingVerify = it },
                                onFailure = { verifyError = it.message }
                            )
                        }
                    },
                    enabled = !verifying && sshHostInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (verifying) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.shell_ssh_verifying))
                    } else {
                        Icon(Icons.Default.Shield, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.shell_ssh_verify_host_key))
                    }
                }
                Spacer(Modifier.height(10.dp))
                val shellLabel = when (sshShellInput) {
                    "posix" -> stringResource(R.string.shell_ssh_shell_posix)
                    "cmd" -> stringResource(R.string.shell_ssh_shell_cmd)
                    "powershell" -> stringResource(R.string.shell_ssh_shell_powershell)
                    else -> stringResource(R.string.shell_ssh_shell_auto)
                }
                ExposedDropdownMenuBox(expanded = sshShellMenuExpanded, onExpandedChange = { sshShellMenuExpanded = it }) {
                    OutlinedTextField(
                        value = shellLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.shell_ssh_command_shell)) },
                        supportingText = {
                            val detected = device.sshDetectedShell
                            if (sshShellInput == "auto" && detected.isNotBlank()) {
                                val detectedLabel = when (detected) {
                                    "posix" -> stringResource(R.string.shell_ssh_shell_posix)
                                    "cmd" -> stringResource(R.string.shell_ssh_shell_cmd)
                                    "powershell" -> stringResource(R.string.shell_ssh_shell_powershell)
                                    else -> detected
                                }
                                Text(stringResource(R.string.shell_ssh_shell_detected, detectedLabel))
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.Terminal, null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sshShellMenuExpanded) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = sshShellMenuExpanded,
                        onDismissRequest = { sshShellMenuExpanded = false },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shell_ssh_shell_auto)) },
                            onClick = { sshShellInput = "auto"; sshShellMenuExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shell_ssh_shell_posix)) },
                            onClick = { sshShellInput = "posix"; sshShellMenuExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shell_ssh_shell_cmd)) },
                            onClick = { sshShellInput = "cmd"; sshShellMenuExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shell_ssh_shell_powershell)) },
                            onClick = { sshShellInput = "powershell"; sshShellMenuExpanded = false },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text(stringResource(R.string.shell_device_name)) },
                    placeholder = { Text(stringResource(R.string.shell_device_name_hint)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                    singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().focusRequester(nameFocusRequester))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = descInput, onValueChange = { descInput = it }, label = { Text(stringResource(R.string.shell_device_desc)) },
                    placeholder = { Text(stringResource(R.string.shell_device_desc_hint)) }, leadingIcon = { Icon(Icons.Default.Description, null) },
                    singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onDeleteConfirm(device.id) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.shell_remove_device))
                    }
                    Button(onClick = {
                        viewModel.updateShellDevice(device.copy(
                            name = nameInput.trim(),
                            description = descInput.trim(),
                            type = "ssh",
                            serverUrl = "",
                            apiKey = "",
                            sshHost = sshHostInput.trim(),
                            sshPort = sshPortInput.toIntOrNull() ?: 22,
                            sshUser = sshUserInput.trim().ifBlank { "root" },
                            sshHostKey = sshHostKeyInput,
                            sshShell = sshShellInput,
                            sshDetectedShell = device.sshDetectedShell,
                        ).withExclusiveSshAuth(
                            usePublicKey = usePublicKey,
                            password = sshPwInput,
                            privateKey = sshPrivateKeyInput,
                            passphrase = sshKeyPassInput,
                            keyLabel = sshKeyLabelInput,
                        )); expanded = false
                    }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save)) }
                }
            }
        }

        if (showKeyPicker) {
            LocalFilePickerDialog(
                onPathsPicked = { paths ->
                    showKeyPicker = false
                    paths.firstOrNull()?.let(::importSshPrivateKey)
                },
                onDismiss = { showKeyPicker = false },
                showHidden = true,
                singleFile = true,
                title = stringResource(R.string.shell_ssh_import_key),
            )
        }

        // ── Host-key fingerprint confirmation ──
        pendingVerify?.let { (key, fingerprint) ->
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onDismissRequest = { pendingVerify = null },
                icon = { Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text(stringResource(R.string.shell_ssh_verify_title), fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(stringResource(R.string.shell_ssh_verify_message))
                        Spacer(Modifier.height(12.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                fingerprint,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { sshHostKeyInput = key; pendingVerify = null }) {
                        Text(stringResource(R.string.shell_ssh_trust))
                    }
                },
                dismissButton = { TextButton(onClick = { pendingVerify = null }) { Text(stringResource(R.string.cancel)) } }
            )
        }
    }
}
