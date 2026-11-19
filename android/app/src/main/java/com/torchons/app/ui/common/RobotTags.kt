package com.torchons.app.ui.common

/**
 * Stable test tags for the Google Play pre-launch robot.
 *
 * MainActivity sets `testTagsAsResourceId = true` at the root, so each tag below is exposed to
 * UiAutomator as a resource id: the Robo script uploaded in the Play Console finds the restore
 * flow by these ids (Welcome "Find my household" -> code field -> Verify -> Confirm) and signs
 * in to the dedicated test household instead of creating a new household in production on every
 * uploaded bundle. Renaming a tag breaks that script: change both together.
 */
object RobotTags {
    const val RESTORE_OPEN = "restore_open"
    const val RESTORE_CODE = "restore_code"
    const val RESTORE_VERIFY = "restore_verify"
    const val RESTORE_CONFIRM = "restore_confirm"
}
