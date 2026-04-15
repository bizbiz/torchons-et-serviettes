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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.torchons.app.MainActivity
import com.torchons.app.data.model.Suggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class TaskWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val suggestions = try {
            fetchSuggestions()
        } catch (e: Exception) {
            emptyList()
        }

        provideContent {
            WidgetContent(suggestions)
        }
    }

    private suspend fun fetchSuggestions(): List<Suggestion> = withContext(Dispatchers.IO) {
        try {
            val authConn = (URL("${com.torchons.app.BuildConfig.API_BASE_URL}/auth/auto").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
            }
            val authJson = authConn.inputStream.bufferedReader().readText()
            authConn.disconnect()
            val authMap = Gson().fromJson(authJson, Map::class.java) as Map<*, *>
            val token = authMap["token"] as? String ?: return@withContext emptyList()

            val conn = (URL("${com.torchons.app.BuildConfig.API_BASE_URL}/suggestions").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Authorization", "Bearer $token")
            }
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            Gson().fromJson(json, object : TypeToken<List<Suggestion>>() {}.type)
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
    val countdownLabel = com.torchons.app.util.TaskUrgencyCalculator.countdownLabelFromDays(suggestion.daysUntilDue)

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
                // Auto-login
                val authConn = (URL("${com.torchons.app.BuildConfig.API_BASE_URL}/auth/auto").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                }
                val authJson = authConn.inputStream.bufferedReader().readText()
                authConn.disconnect()
                val token = (Gson().fromJson(authJson, Map::class.java) as Map<*, *>)["token"] as? String ?: return@withContext

                // Complete task
                val conn = (URL("${com.torchons.app.BuildConfig.API_BASE_URL}/tasks/$taskId/complete").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    outputStream.write("{}".toByteArray())
                }
                conn.responseCode
                conn.disconnect()
            }
            // Refresh widget
            TaskWidget().update(context, glanceId)
        } catch (_: Exception) {}
    }
}
