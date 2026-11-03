package com.torchons.app.ui.common

import com.torchons.app.data.model.ModuleView

/** Everything a module guide page shows. Blank fields are not rendered. */
data class ModuleGuide(
    val key: String,
    val title: String,
    val pitch: String,
    val details: String,
    val why: String,
    val how: String,
    val example: String?,
)

fun ModuleView.toGuide(): ModuleGuide = ModuleGuide(
    key = key,
    title = title,
    pitch = pitch,
    details = details,
    why = guideWhy.orEmpty(),
    how = guideHow.orEmpty(),
    example = example?.takeIf { it.isNotBlank() },
)

/**
 * Pick the guide to show at app open: the first module (registry order) whose
 * `guide_due` is set. At most one per open. Returns null while a prompt modal
 * is on screen, so the two dialogs never stack; the next open picks it up.
 */
fun pickDueGuide(modules: List<ModuleView>, promptShowing: Boolean): ModuleView? {
    if (promptShowing) return null
    return modules.filter { it.guideDue && !it.guideSeen }.minByOrNull { it.order }
}

/**
 * Guide fields carried by a DISCOVER_MODULE prompt context. `guide_why` and
 * `guide_how` are absent on prompts created before the server shipped them:
 * absence yields blank strings, never an error.
 */
fun guideFromPromptContext(moduleKey: String, ctx: Map<String, Any?>): ModuleGuide = ModuleGuide(
    key = moduleKey,
    title = ctx["title"]?.toString().orEmpty(),
    pitch = ctx["pitch"]?.toString().orEmpty(),
    details = ctx["details"]?.toString().orEmpty(),
    why = ctx["guide_why"]?.toString()?.takeIf { it != "null" }.orEmpty(),
    how = ctx["guide_how"]?.toString()?.takeIf { it != "null" }.orEmpty(),
    example = ctx["example"]?.toString()?.takeIf { it.isNotBlank() && it != "null" },
)
