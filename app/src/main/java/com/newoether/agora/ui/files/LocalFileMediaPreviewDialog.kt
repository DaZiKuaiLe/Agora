package com.newoether.agora.ui.files

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.newoether.agora.R
import com.newoether.agora.ui.chat.VideoPlayer
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.util.LocalFileMediaKind
import java.io.File

@Composable
internal fun LocalFileMediaPreviewDialog(
    file: File,
    mediaKind: LocalFileMediaKind,
    onDismiss: () -> Unit,
) {
    if (mediaKind == LocalFileMediaKind.NONE) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        DialogWindowEdgeToEdge()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            when (mediaKind) {
                LocalFileMediaKind.IMAGE -> {
                    AsyncImage(
                        model = file,
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    BackHandler(onBack = onDismiss)
                }
                LocalFileMediaKind.VIDEO -> {
                    VideoPlayer(
                        uri = Uri.fromFile(file).toString(),
                        onClose = onDismiss,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                LocalFileMediaKind.NONE -> Unit
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = Color.White,
                )
            }
        }
    }
}
