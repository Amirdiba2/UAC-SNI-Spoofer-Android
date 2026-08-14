package com.uacspoofer.mobile.vpn

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UacQuickSettingsTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile(ConnectionStateStore.state.value)
    }

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            ConnectionStateStore.state.collectLatest(::updateTile)
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val action = Runnable { handleTileClick() }
        if (isLocked) unlockAndRun(action) else action.run()
    }

    override fun onDestroy() {
        stateJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleTileClick() {
        when (ConnectionStateStore.state.value) {
            ConnectionState.CONNECTED,
            ConnectionState.CONNECTING,
            -> disconnect()

            ConnectionState.DISCONNECTING -> Unit
            ConnectionState.DISCONNECTED,
            ConnectionState.ERROR,
            -> connect()
        }
    }

    private fun connect() {
        val proxyMode = AdvancedSettingsStore(this).snapshot().connectionMode == CONNECTION_MODE_PROXY
        val needsVpnPermission = !proxyMode && VpnService.prepare(this) != null
        val needsNotificationPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED

        if (needsVpnPermission || needsNotificationPermission) {
            openPermissionFlow()
            return
        }
        if (!ConnectionStateStore.tryBeginConnect()) return
        runCatching { VpnController.start(this) }
            .onFailure { ConnectionStateStore.markError() }
    }

    private fun disconnect() {
        if (!ConnectionStateStore.tryBeginDisconnect()) return
        runCatching { VpnController.stop(this) }
            .onFailure { ConnectionStateStore.markDisconnected() }
    }

    private fun openPermissionFlow() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CONNECT_FROM_QUICK_TILE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                QUICK_TILE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(state: ConnectionState) {
        val tile = qsTile ?: return
        val status = when (state) {
            ConnectionState.CONNECTED -> getString(R.string.quick_settings_tile_connected)
            ConnectionState.CONNECTING -> getString(R.string.quick_settings_tile_connecting)
            ConnectionState.DISCONNECTING -> getString(R.string.quick_settings_tile_disconnecting)
            ConnectionState.ERROR -> getString(R.string.quick_settings_tile_error)
            ConnectionState.DISCONNECTED -> getString(R.string.quick_settings_tile_disconnected)
        }
        tile.label = getString(R.string.quick_settings_tile_label)
        tile.state = when (state) {
            ConnectionState.CONNECTED,
            ConnectionState.CONNECTING,
            -> Tile.STATE_ACTIVE

            ConnectionState.DISCONNECTING -> Tile.STATE_UNAVAILABLE
            ConnectionState.DISCONNECTED,
            ConnectionState.ERROR,
            -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = status
        tile.contentDescription = "${tile.label}, $status"
        tile.updateTile()
    }

    private companion object {
        const val QUICK_TILE_REQUEST_CODE = 4107
    }
}
