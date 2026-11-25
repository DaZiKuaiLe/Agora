package com.newoether.agora.ui.files

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.R
import com.newoether.agora.sandbox.SandboxSharedStorageAccess
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.util.LocalFileBrowser
import com.newoether.agora.util.LocalFileEntry
import com.newoether.agora.util.LocalFileRoot
import com.newoether.agora.util.LocalFileSort
import com.newoether.agora.util.VideoThumbnailCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen path-based picker. Icon toggles multi-select (files and directories);
 * name opens a directory or previews image/video. Bottom action references all selections.
 *
 * [showHidden] includes names starting with `.` (needed for `.ssh` private keys).
 * [singleFile] picks a file immediately on tap instead of accumulating a selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFilePickerDialog(
    onPathsPicked: (absolutePaths: List<String>) -> Unit,
    onDismiss: () -> Unit,
    showHidden: Boolean = false,
    singleFile: Boolean = false,
    title: String? = null,
    confirmLabel: String? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val videoThumbs = remember { VideoThumbnailCache(context) }
    val resolvedTitle = title ?: stringResource(R.string.local_file_picker_title)
    val resolvedConfirm = confirmLabel ?: stringResource(R.string.local_file_picker_confirm)

    var sharedGranted by remember {
        mutableStateOf(SandboxSharedStorageAccess.isGranted(context))
    }
    var roots by remember { mutableStateOf(LocalFileBrowser.roots(context)) }
    var activeRoot by remember { mutableStateOf(LocalFileBrowser.defaultRoot(context)) }
    var currentDir by remember { mutableStateOf(activeRoot.directory) }
    var entries by remember { mutableStateOf<List<LocalFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sort by rememberSaveable { mutableStateOf(LocalFileSort.NAME_ASC.name) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<LocalFileEntry>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchTruncated by remember { mutableStateOf(false) }
    var previewEntry by remember { mutableStateOf<LocalFileEntry?>(null) }
    var listJob by remember { mutableStateOf<Job?>(null) }

    val sortEnum = remember(sort) {
        runCatching { LocalFileSort.valueOf(sort) }.getOrDefault(LocalFileSort.NAME_ASC)
    }
    val searchingMode = searchQuery.trim().length >= 2

    fun refreshRoots(keepPath: Boolean) {
        sharedGranted = SandboxSharedStorageAccess.isGranted(context)
        roots = LocalFileBrowser.roots(context)
        val nextRoot = roots.firstOrNull { it.id == activeRoot.id }
            ?: LocalFileBrowser.defaultRoot(context)
        activeRoot = nextRoot
        if (!keepPath || !LocalFileBrowser.isUnderRoot(currentDir, nextRoot.directory)) {
            currentDir = nextRoot.directory
        }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshRoots(keepPath = true) }
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshRoots(keepPath = true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshRoots(keepPath = true)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun loadDirectory(dir: File, sortOrder: LocalFileSort) {
        listJob?.cancel()
        loading = true
        errorMessage = null
        listJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                LocalFileBrowser.listEntries(dir, sort = sortOrder, showHidden = showHidden)
            }
            ensureActive()
            loading = false
            result.fold(
                onSuccess = {
                    entries = it
                    errorMessage = null
                },
                onFailure = {
                    entries = emptyList()
                    errorMessage = context.getString(R.string.local_file_picker_unreadable)
                },
            )
        }
    }

    LaunchedEffect(currentDir, sortEnum, showHidden) {
        if (!searchingMode) loadDirectory(currentDir, sortEnum)
    }

    LaunchedEffect(searchQuery, currentDir, sortEnum, searchingMode, showHidden) {
        if (!searchingMode) {
            searchResults = emptyList()
            searching = false
            searchTruncated = false
            return@LaunchedEffect
        }
        searching = true
        delay(280)
        val q = searchQuery.trim()
        val result = withContext(Dispatchers.IO) {
            LocalFileBrowser.searchUnder(
                directory = currentDir,
                query = q,
                sort = sortEnum,
                maxResults = 500,
                showHidden = showHidden,
            )
        }
        searching = false
        result.fold(
            onSuccess = { found ->
                searchResults = found
                searchTruncated = found.size >= 500
                errorMessage = null
            },
            onFailure = {
                searchResults = emptyList()
                errorMessage = context.getString(R.string.local_file_picker_unreadable)
            },
        )
    }

    fun selectRoot(root: LocalFileRoot) {
        activeRoot = root
        currentDir = root.directory
        searchQuery = ""
    }

    fun navigateUp() {
        val parent = LocalFileBrowser.parentWithinRoot(currentDir, activeRoot.directory)
            ?: return
        currentDir = parent
        searchQuery = ""
    }

    fun pickFile(path: String) {
        onPathsPicked(listOf(path))
    }

    fun toggleSelected(entry: LocalFileEntry) {
        val path = entry.file.absolutePath
        if (singleFile) {
            if (entry.isDirectory) {
                currentDir = entry.file
                searchQuery = ""
            } else {
                pickFile(path)
            }
            return
        }
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }

    fun onNameClick(entry: LocalFileEntry) {
        when {
            entry.isDirectory -> {
                currentDir = entry.file
                searchQuery = ""
            }
            singleFile -> pickFile(entry.file.absolutePath)
            LocalFileBrowser.isPreviewable(entry) -> previewEntry = entry
            else -> Unit
        }
    }

    val canGoUp = LocalFileBrowser.parentWithinRoot(currentDir, activeRoot.directory) != null

    fun handleSystemBack() {
        when {
            sortMenuOpen -> sortMenuOpen = false
            previewEntry != null -> previewEntry = null
            searchingMode -> searchQuery = ""
            canGoUp -> navigateUp()
            else -> onDismiss()
        }
    }

    val visibleEntries = if (searchingMode) searchResults else entries
    val rootPath = remember(activeRoot, currentDir) {
        runCatching { currentDir.canonicalFile.path }.getOrDefault(currentDir.path)
    }

    Dialog(
        onDismissRequest = ::handleSystemBack,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // Back is handled inside (BackHandler) so we can go up a directory
            // instead of always dismissing; keep Dialog from auto-closing.
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        // Must live inside Dialog: the dialog window owns the back dispatcher
        // (hardware key + gesture navigation).
        BackHandler(onBack = ::handleSystemBack)
        DialogWindowEdgeToEdge()
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    resolvedTitle,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    currentDir.absolutePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cancel),
                                )
                            }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { sortMenuOpen = true }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Sort,
                                        contentDescription = stringResource(
                                            R.string.local_file_picker_sort,
                                        ),
                                    )
                                }
                                DropdownMenu(
                                    expanded = sortMenuOpen,
                                    onDismissRequest = { sortMenuOpen = false },
                                ) {
                                    LocalFileSort.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(sortLabel(option)) },
                                            onClick = {
                                                sort = option.name
                                                sortMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                            if (canGoUp) {
                                IconButton(onClick = ::navigateUp) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(
                                            R.string.local_file_picker_up,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (singleFile) {
                                    stringResource(R.string.local_file_picker_tap_file)
                                } else if (selectedPaths.isEmpty()) {
                                    stringResource(R.string.local_file_picker_none_selected)
                                } else {
                                    stringResource(
                                        R.string.local_file_picker_selected_count,
                                        selectedPaths.size,
                                    )
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = onDismiss) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Spacer(Modifier.width(8.dp))
                                if (!singleFile) {
                                    Button(
                                        enabled = selectedPaths.isNotEmpty(),
                                        onClick = {
                                            onPathsPicked(selectedPaths.toList())
                                        },
                                    ) {
                                        Text(resolvedConfirm)
                                    }
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    if (!sharedGranted) {
                        SharedStorageGrantBanner(
                            onGrant = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    SandboxSharedStorageAccess.settingsIntent(context)
                                        ?.let(settingsLauncher::launch)
                                } else {
                                    legacyLauncher.launch(
                                        SandboxSharedStorageAccess.legacyPermissions,
                                    )
                                }
                            },
                        )
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(roots, key = { it.id }) { root ->
                            val selected = root.id == activeRoot.id
                            TextButton(onClick = { selectRoot(root) }) {
                                Text(
                                    stringResource(root.labelRes),
                                    fontWeight = if (selected) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = 0.55f,
                            ),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = 0.45f,
                            ),
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = 0.35f,
                            ),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        placeholder = {
                            Text(stringResource(R.string.local_file_picker_search_hint))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.cancel),
                                    )
                                }
                            }
                        },
                    )
                    if (searchTruncated) {
                        Text(
                            stringResource(R.string.local_file_picker_search_truncated),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        when {
                            loading || searching -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                            errorMessage != null && visibleEntries.isEmpty() -> {
                                Text(
                                    errorMessage!!,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            visibleEntries.isEmpty() -> {
                                Text(
                                    if (searchingMode) {
                                        stringResource(R.string.local_file_picker_search_empty)
                                    } else {
                                        stringResource(R.string.local_file_picker_empty)
                                    },
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(
                                        visibleEntries,
                                        key = { it.file.absolutePath },
                                    ) { entry ->
                                        val path = entry.file.absolutePath
                                        val relative = if (searchingMode) {
                                            relativePathHint(entry.file, rootPath)
                                        } else {
                                            null
                                        }
                                        LocalFilePickerEntryRow(
                                            entry = entry,
                                            selected = path in selectedPaths,
                                            videoThumbs = videoThumbs,
                                            relativeHint = relative,
                                            onToggleSelect = { toggleSelected(entry) },
                                            onOpenName = { onNameClick(entry) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    previewEntry?.let { entry ->
        LocalFileMediaPreviewDialog(
            file = entry.file,
            mediaKind = entry.mediaKind,
            onDismiss = { previewEntry = null },
        )
    }
}

@Composable
private fun sortLabel(sort: LocalFileSort): String = when (sort) {
    LocalFileSort.NAME_ASC -> stringResource(R.string.local_file_picker_sort_name_asc)
    LocalFileSort.NAME_DESC -> stringResource(R.string.local_file_picker_sort_name_desc)
    LocalFileSort.MODIFIED_DESC -> stringResource(R.string.local_file_picker_sort_modified_desc)
    LocalFileSort.MODIFIED_ASC -> stringResource(R.string.local_file_picker_sort_modified_asc)
    LocalFileSort.SIZE_DESC -> stringResource(R.string.local_file_picker_sort_size_desc)
    LocalFileSort.SIZE_ASC -> stringResource(R.string.local_file_picker_sort_size_asc)
}

private fun relativePathHint(file: File, rootPath: String): String? {
    val path = runCatching { file.canonicalFile.path }.getOrElse { return null }
    if (!path.startsWith(rootPath)) return file.parent
    val rest = path.removePrefix(rootPath).trimStart('/')
    val parentRel = rest.substringBeforeLast('/', missingDelimiterValue = "")
    return parentRel.ifBlank { null }
}

@Composable
private fun SharedStorageGrantBanner(onGrant: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.local_file_picker_grant_hint),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onGrant) {
                Text(stringResource(R.string.shell_all_files_grant))
            }
        }
    }
}
