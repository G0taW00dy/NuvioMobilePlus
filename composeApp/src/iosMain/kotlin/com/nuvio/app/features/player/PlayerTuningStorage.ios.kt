package com.nuvio.app.features.player

import platform.Foundation.NSUserDefaults

internal actual object PlayerTuningStorage {
    private const val KEY = "cybervulcan_player_tuning_json"

    actual fun load(): String? = NSUserDefaults.standardUserDefaults.stringForKey(KEY)

    actual fun save(json: String) {
        NSUserDefaults.standardUserDefaults.setObject(json, forKey = KEY)
    }
}

internal actual object NextEpisodePrefetcher {
    actual fun prefetch(url: String, headers: Map<String, String>, bytes: Long) = Unit

    actual fun cancel() = Unit
}
