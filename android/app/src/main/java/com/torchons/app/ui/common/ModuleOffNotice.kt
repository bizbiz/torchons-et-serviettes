package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.ModuleView
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** How long a "turned off by another member" notice stays eligible. */
const val MODULE_OFF_NOTICE_DAYS = 30L

/** Key under which a notice is acknowledged: a new change of the module gives a new key. */
fun moduleOffNoticeKey(foyerId: String, module: ModuleView): String =
    "$foyerId:${module.key}:${module.changedAt}"

/**
 * Modules to announce on Home: collective, turned OFF, last changed by ANOTHER member
 * (the server only fills `changed_by_name` in that case) in the last
 * [MODULE_OFF_NOTICE_DAYS] days, and not acknowledged yet. Registry order.
 * Turning a module ON never yields a notice: the guide (`guide_due`) already tells it.
 */
fun pickModuleOffNotices(
    modules: List<ModuleView>,
    foyerId: String,
    acknowledged: Set<String>,
    now: Instant,
): List<ModuleView> = modules
    .filter { it.isCollective && !it.enabled && !it.changedByName.isNullOrBlank() }
    .filter { isRecent(it.changedAt, now) }
    .filter { moduleOffNoticeKey(foyerId, it) !in acknowledged }
    .sortedBy { it.order }

private fun isRecent(iso: String?, now: Instant): Boolean {
    val at = parseInstantOrNull(iso) ?: return false
    // A negative age (clock skew between server and phone) counts as recent.
    return Duration.between(at, now) <= Duration.ofDays(MODULE_OFF_NOTICE_DAYS)
}

private fun parseInstantOrNull(iso: String?): Instant? {
    if (iso.isNullOrBlank()) return null
    return runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .recoverCatching { Instant.parse("${iso}Z") }
        .getOrNull()
}

/**
 * Home card(s): "<name> turned off <module> for the household". "See" opens the
 * household screen, "OK" acknowledges. Renders nothing when there is nothing to say.
 */
@Composable
fun ModuleOffNoticeHost(onSee: () -> Unit) {
    val vm: ModuleGuideViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val notices by vm.offNotices.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        notices.forEach { module ->
            ModuleOffNoticeCard(
                module = module,
                onSee = { vm.acknowledgeOffNotice(module); onSee() },
                onOk = { vm.acknowledgeOffNotice(module) },
            )
        }
    }
}

@Composable
private fun ModuleOffNoticeCard(module: ModuleView, onSee: () -> Unit, onOk: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(
                text = stringResource(
                    R.string.home_module_off_notice_format,
                    module.changedByName.orEmpty(),
                    module.title,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSee) { Text(stringResource(R.string.home_module_off_notice_see)) }
                TextButton(onClick = onOk) { Text(stringResource(R.string.common_ok)) }
            }
        }
    }
}
