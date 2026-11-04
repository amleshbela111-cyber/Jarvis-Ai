package com.jarvis.ai.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jarvis.ai.core.JarvisCore
import com.jarvis.ai.voice.ListeningService

/**
 * Quick Settings tile. One tap starts Jarvis listening, and another tap stops it.
 * Nothing opens on screen, so this is the quick way to start Jarvis from any app.
 */
class JarvisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val listening = JarvisCore.snapshot.value.listening
        ListeningService.start(
            applicationContext,
            if (listening) ListeningService.ACTION_PAUSE else ListeningService.ACTION_RESUME
        )
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        tile.label = "Jarvis"
        tile.state = if (JarvisCore.snapshot.value.listening) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
