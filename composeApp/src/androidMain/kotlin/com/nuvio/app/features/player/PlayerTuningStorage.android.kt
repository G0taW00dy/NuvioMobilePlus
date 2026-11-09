package com.nuvio.app.features.player

import android.content.Context

internal actual object PlayerTuningStorage {
    private const val PREFERENCES_NAME = "nuvio_cybervulcan_tuning"
    private const val KEY = "player_tuning_json"

    private fun preferences() =
        PlayerSettingsStorage.applicationContext()
            ?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    actual fun load(): String? = preferences()?.getString(KEY, null)

    actual fun save(json: String) {
        preferences()?.edit()?.putString(KEY, json)?.apply()
    }
}
