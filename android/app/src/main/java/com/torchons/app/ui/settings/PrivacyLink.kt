package com.torchons.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.torchons.app.R

private const val LINK_MARKER = "@@"

/**
 * One-line notice shown where personal data is collected (household creation, or joining),
 * with the privacy policy label opening the browser (GDPR art. 13).
 */
@Composable
fun PrivacyNoticeLine(modifier: Modifier = Modifier, joining: Boolean = false) {
    val context = LocalContext.current
    val url = stringResource(R.string.privacy_policy_url)
    val label = stringResource(R.string.privacy_policy_link_label)
    // The translated sentence decides where the link goes: split around a marker.
    val format = stringResource(
        if (joining) R.string.privacy_notice_join_format else R.string.privacy_notice_format,
        LINK_MARKER,
    )
    val before = format.substringBefore(LINK_MARKER)
    val after = format.substringAfter(LINK_MARKER)
    val linkStyle = TextLinkStyles(
        SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
    )
    val text = buildAnnotatedString {
        append(before)
        withLink(LinkAnnotation.Clickable("privacy", linkStyle) { openUrl(context, url) }) { append(label) }
        append(after)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}
