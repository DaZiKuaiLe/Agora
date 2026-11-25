package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R

@Composable
fun DocumentationFab(docPath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val baseUrl = "https://newo-ether.github.io/Agora/"
    val langPrefix = if (locale.language == "zh") "zh/" else ""

    AnimatedActionFab(
        label = stringResource(R.string.documentation),
        icon = Icons.AutoMirrored.Filled.MenuBook,
        onClick = {
            val page = docPath.removeSuffix(".md") + "/"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl$langPrefix$page"))
            context.startActivity(intent)
        },
        modifier = modifier,
    )
}
