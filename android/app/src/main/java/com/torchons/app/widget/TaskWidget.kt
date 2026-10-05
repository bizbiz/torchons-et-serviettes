package com.torchons.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Box
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.torchons.app.MainActivity
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.Suggestion
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The widget goes through the app's shared Retrofit client, so it carries the
 * stored token, the app-version header and the device-revocation handling.
 * It used to call the legacy `/auth/auto` route, removed on 2026-10-05.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TaskWidgetEntryPoint {
    fun apiService(): ApiService
}

private fun widgetApi(context: Context): ApiService =
    EntryPointAccessors.fromApplication(context.applicationContext, TaskWidgetEntryPoint::class.java)
        .apiService()

class TaskWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val suggestions = try {
            fetchSuggestions(context)
        } catch (e: Exception) {
            emptyList()
        }

        provideContent {
            WidgetContent(suggestions)
        }
    }

    private suspend fun fetchSuggestions(context: Context): List<Suggestion> =
        withContext(Dispatchers.IO) {
            try {
                val response = widgetApi(context).getSuggestions()
                if (response.isSuccessful) response.body()?.suggestions.orEmpty() else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
}

@Composable
private fun WidgetContent(suggestions: List<Suggestion>) {
    val size = LocalSize.current
    // Calcul dynamique : header ~22dp + padding 24dp = 46dp, item ~36dp
    val availableHeight = size.height.value - 46
    val maxItems = (availableHeight / 36).toInt().coerceIn(1, 10)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color.White)
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Torchons & Serviettes",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = ColorProvider(Color(0xFF6366F1))
                ),
                modifier = GlanceModifier.defaultWeight()
            )
        }

        Spacer(modifier = GlanceModifier.height(6.dp))

        if (suggestions.isEmpty()) {
            Text(
                text = "Tout est frais ! 🎉",
                style = TextStyle(fontSize = 13.sp, color = ColorProvider(Color(0xFF6B7280)))
            )
        } else {
            suggestions.take(maxItems).forEachIndexed { index, suggestion ->
                WidgetTaskRow(suggestion)
                if (index < suggestions.take(maxItems).size - 1) {
                    Spacer(modifier = GlanceModifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun WidgetTaskRow(suggestion: Suggestion) {
    val stateColor = when (suggestion.state) {
        "RED" -> Color(0xFFEF4444)
        "ORANGE" -> Color(0xFFF97316)
        else -> Color(0xFF10B981)
    }
    val context = androidx.glance.LocalContext.current
    val countdownLabel = com.torchons.app.util.TaskUrgencyCalculator
        .countdownLabelFromDays(context.resources, suggestion.daysUntilDue)

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Indicateur couleur etat d'urgence
        Column(
            modifier = GlanceModifier
                .width(4.dp)
                .height(32.dp)
                .cornerRadius(2.dp)
                .background(stateColor)
        ) {}

        Spacer(modifier = GlanceModifier.width(8.dp))

        // Titre + countdown
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = suggestion.title,
                style = TextStyle(
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = ColorProvider(Color(0xFF1A1A1A))
                ),
                maxLines = 1
            )
            Text(
                text = countdownLabel,
                style = TextStyle(fontSize = 10.sp, color = ColorProvider(Color(0xFF6B7280)))
            )
        }

        Spacer(modifier = GlanceModifier.width(4.dp))

        // Bouton "Fait" (✓)
        Box(
            modifier = GlanceModifier
                .width(32.dp)
                .height(32.dp)
                .cornerRadius(16.dp)
                .background(Color(0xFF10B981))
                .clickable(actionRunCallback<CompleteTaskAction>(actionParametersOf(taskIdParam to suggestion.taskId))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✓",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = ColorProvider(Color.White)
                )
            )
        }
    }
}

class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}

val taskIdParam = ActionParameters.Key<String>("task_id")

class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val taskId = parameters[taskIdParam] ?: return
        try {
            withContext(Dispatchers.IO) {
                widgetApi(context).completeTask(taskId)
            }
            // Refresh widget
            TaskWidget().update(context, glanceId)
        } catch (_: Exception) {}
    }
}
