package com.torchons.app.ui.changelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torchons.app.R
import com.torchons.app.data.api.ApiService
import com.torchons.app.ui.common.ReloadOnReconnect
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrorBlock
import com.torchons.app.ui.common.UserErrors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChangelogApiEntryPoint {
    fun apiService(): ApiService
}

/**
 * Ecran qui affiche les notes de version et la roadmap de l'app.
 * Deux onglets : "Nouveautes" (changelog.md) et "Roadmap" (roadmap.md).
 * Les deux fichiers markdown sont servis par le backend via /api/changelog et /api/roadmap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apiService = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ChangelogApiEntryPoint::class.java
        ).apiService()
    }

    var selectedTab by remember { mutableStateOf(0) }
    var version by remember { mutableStateOf<String?>(null) }
    var changelogText by remember { mutableStateOf<String?>(null) }
    var roadmapText by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<UserError?>(null) }

    var reloadCount by remember { mutableStateOf(0) }
    ReloadOnReconnect(hasError = error != null) { reloadCount++ }

    LaunchedEffect(reloadCount) {
        loading = true
        error = null
        try {
            val c = withContext(Dispatchers.IO) { apiService.getChangelog() }
            val r = withContext(Dispatchers.IO) { apiService.getRoadmap() }
            if (c.isSuccessful) {
                val body = c.body()
                changelogText = body?.get("content") as? String
                version = body?.get("version") as? String
            }
            if (r.isSuccessful) {
                roadmapText = r.body()?.get("content") as? String
            }
            if (!c.isSuccessful && !r.isSuccessful) {
                error = UserErrors.fromResponse(context, c)
            }
        } catch (e: Exception) {
            error = UserErrors.from(context, e)
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (version != null)
                            stringResource(R.string.changelog_screen_title_with_version_format, version!!)
                        else stringResource(R.string.changelog_screen_title)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.changelog_back_cd))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.changelog_tab_release_notes)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.changelog_tab_roadmap)) }
                )
            }

            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }
                error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        error?.let { UserErrorBlock(it, "Changelog", textAlign = TextAlign.Center) }
                    }
                }
                else -> {
                    val content = if (selectedTab == 0) changelogText else roadmapText
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        if (content.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.changelog_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            MarkdownText(content)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Rendu minimaliste d'un texte markdown : supporte `# `, `## `, `### `,
 * les listes `- ` et le gras `**...**`. Suffit pour un changelog simple.
 */
@Composable
private fun MarkdownText(markdown: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        markdown.lines().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.isEmpty() -> Spacer(modifier = Modifier.height(6.dp))
                line.startsWith("# ") -> Text(
                    text = line.removePrefix("# "),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                line.startsWith("## ") -> Text(
                    text = line.removePrefix("## "),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                line.startsWith("### ") -> Text(
                    text = line.removePrefix("### "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                line.startsWith("- ") -> Text(
                    text = boldMarkdown("• " + line.removePrefix("- ")),
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> Text(
                    text = boldMarkdown(line),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * Transforme `**texte**` en AnnotatedString avec gras applique.
 */
private fun boldMarkdown(text: String) = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val start = text.indexOf("**", i)
        if (start < 0) {
            append(text.substring(i))
            break
        }
        append(text.substring(i, start))
        val end = text.indexOf("**", start + 2)
        if (end < 0) {
            append(text.substring(start))
            break
        }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(text.substring(start + 2, end))
        }
        i = end + 2
    }
}
