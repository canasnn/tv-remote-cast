package com.canasnn.tvremote.ui.app

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Configuration for the customizable TV remote layout.
 *
 * Defines:
 *  1. [sectionOrder]: Order of top-level sections:
 *     - "power" (Power bar)
 *     - "top_strip" (Applications/Media shortcuts strip)
 *     - "dpad_stage" (D-pad/Touchpad stage)
 *     - "control_deck" (Volume, Channel, and Grid buttons deck)
 *  2. [leftRocker]: Control on the left side of the Control Deck ("volume", "channel", or "none").
 *  3. [rightRocker]: Control on the right side of the Control Deck ("volume", "channel", or "none").
 *  4. [gridButtons]: Ordered list of buttons inside the middle grid of the Control Deck.
 */
data class RemoteLayoutConfig(
    val sectionOrder: List<String>,
    val leftRocker: String,
    val rightRocker: String,
    val gridButtons: List<String>
) {
    companion object {
        // Section identifiers
        const val SECTION_POWER = "power"
        const val SECTION_TOP_STRIP = "top_strip"
        const val SECTION_DPAD_STAGE = "dpad_stage"
        const val SECTION_CONTROL_DECK = "control_deck"

        // Rocker types
        const val ROCKER_VOLUME = "volume"
        const val ROCKER_CHANNEL = "channel"
        const val ROCKER_NONE = "none"

        // Grid button identifiers
        const val BUTTON_KEYBOARD = "keyboard"
        const val BUTTON_HOME = "home"
        const val BUTTON_SWITCH_PAD = "switch_pad"
        const val BUTTON_MUTE = "mute"
        const val BUTTON_VOICE = "voice"
        const val BUTTON_BACK = "back"
        const val BUTTON_RECENT_APPS = "recent_apps"
        const val BUTTON_PLAY_PAUSE = "play_pause"
        const val BUTTON_POWER = "power"
        const val BUTTON_MENU = "menu"
        const val BUTTON_POWER_MINI = "power_mini"

        /** Default layout configuration. Power is in the toolbar; Menu and Play/Pause removed from deck. */
        val default = RemoteLayoutConfig(
            sectionOrder = listOf(SECTION_TOP_STRIP, SECTION_DPAD_STAGE, SECTION_CONTROL_DECK),
            leftRocker = ROCKER_VOLUME,
            rightRocker = ROCKER_CHANNEL,
            gridButtons = listOf(
                BUTTON_KEYBOARD, BUTTON_HOME, BUTTON_SWITCH_PAD,
                BUTTON_MUTE, BUTTON_VOICE, BUTTON_BACK
            )
        )

        /** Serialize configuration to a JSON string. */
        fun toJson(config: RemoteLayoutConfig): String {
            return try {
                JSONObject().apply {
                    put("sectionOrder", JSONArray(config.sectionOrder))
                    put("leftRocker", config.leftRocker)
                    put("rightRocker", config.rightRocker)
                    put("gridButtons", JSONArray(config.gridButtons))
                }.toString()
            } catch (e: Exception) {
                Timber.e(e, "Failed to serialize RemoteLayoutConfig to JSON")
                ""
            }
        }

        /** Deserialize configuration from a JSON string. Falls back to default on error. */
        fun fromJson(json: String?): RemoteLayoutConfig {
            if (json.isNullOrBlank()) return default
            return try {
                val obj = JSONObject(json)
                val sectionOrderArr = obj.getJSONArray("sectionOrder")
                val sections = (0 until sectionOrderArr.length()).map { sectionOrderArr.getString(it) }

                val gridButtonsArr = obj.getJSONArray("gridButtons")
                val buttons = (0 until gridButtonsArr.length()).map { gridButtonsArr.getString(it) }

                RemoteLayoutConfig(
                    sectionOrder = sections,
                    leftRocker = obj.optString("leftRocker", ROCKER_VOLUME),
                    rightRocker = obj.optString("rightRocker", ROCKER_CHANNEL),
                    gridButtons = buttons
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to deserialize RemoteLayoutConfig from JSON")
                default
            }
        }
    }
}
