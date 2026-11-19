package com.torchons.app.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.torchons.app.BuildConfig
import com.torchons.app.R
import java.text.DateFormat
import java.util.Date

/**
 * Opens the mail app on a draft addressed to the support address
 * (`BuildConfig.SUPPORT_EMAIL`, set in `local.properties`). Used by the
 * "Write to me" button of Settings and by the error report button.
 */
fun sendSupportEmail(
    context: Context,
    subject: String = context.getString(R.string.support_email_subject),
    body: String? = null,
) {
    // Subject and body go into the mailto URI itself: some mail apps (Gmail among them)
    // ignore EXTRA_SUBJECT / EXTRA_TEXT on an ACTION_SENDTO intent, seen on 2026-11-19.
    // The extras stay for the apps that read them instead.
    val query = buildString {
        append("?subject=").append(Uri.encode(subject))
        if (body != null) append("&body=").append(Uri.encode(body))
    }
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:${BuildConfig.SUPPORT_EMAIL}$query")
        putExtra(Intent.EXTRA_SUBJECT, subject)
        if (body != null) putExtra(Intent.EXTRA_TEXT, body)
    }
    runCatching { context.startActivity(intent) }
}

/**
 * Pre-filled error mail: the code when there is one, the app version (plus
 * the beta build marker), the screen name, the date, and a hint to attach a
 * screenshot. No token, session id or personal data.
 */
fun sendErrorReport(context: Context, error: UserError, screen: String) {
    val subject = error.errorId
        ?.let { context.getString(R.string.user_error_mail_subject_code, it) }
        ?: context.getString(R.string.user_error_mail_subject_generic)
    sendSupportEmail(context, subject, errorReportBody(context, error, screen))
}

private fun errorReportBody(context: Context, error: UserError, screen: String): String {
    val version = BuildConfig.VERSION_NAME +
        BuildConfig.BUILD_INFO.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
    val date = DateFormat.getDateTimeInstance().format(Date())
    return buildList {
        error.errorId?.let { add(context.getString(R.string.user_error_mail_line_code, it)) }
        add(context.getString(R.string.user_error_mail_line_version, version))
        add(context.getString(R.string.user_error_mail_line_screen, screen))
        add(context.getString(R.string.user_error_mail_line_date, date))
        add("")
        add(context.getString(R.string.user_error_mail_line_screenshot))
    }.joinToString("\n")
}
