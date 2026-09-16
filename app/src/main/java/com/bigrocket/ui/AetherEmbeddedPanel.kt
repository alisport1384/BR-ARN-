package com.bigrocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.AdvancedPanel
import studio.cluvex.aether.ui.SharePanel
import studio.cluvex.aether.ui.components.ConnectionCard

@Composable
fun AetherEmbeddedPanel(
    modifier: Modifier = Modifier,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        AetherEmbeddedContent(
            profile = profile,
            onProfileChange = onProfileChange,
            enabled = enabled,
            onEnabledChange = onEnabledChange,
        )
    }
}

@Composable
private fun AetherEmbeddedContent(
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        AetherHeader(
            enabled = enabled,
            onEnabledChange = onEnabledChange,
        )

        Spacer(Modifier.height(12.dp))
        AetherConnectionCard()

        // Diagnostics & logs panel intentionally hidden - not needed in this app.
        // (DiagnosticsPanel() left unused rather than deleted, for easy re-enabling.)

        Spacer(Modifier.height(12.dp))
        AetherAdvancedPanel(
            profile = profile,
            onProfileChange = onProfileChange,
            startExpanded = true,
        )

        Spacer(Modifier.height(12.dp))
        AetherSharePanel(
            profile = profile,
            onProfileChange = onProfileChange,
        )

    }
}

@Composable
private fun AetherHeader(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val state = AetherController.state.collectAsState().value

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Aether",
                style = MaterialTheme.typography.titleLarge,
            )
            AetherStateText(state)
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
    }
}

@Composable
private fun AetherConnectionCard() {
    val state = AetherController.state.collectAsState().value
    val connectedSince = AetherController.connectedSince.collectAsState().value
    val ipInfo = AetherController.ipInfo.collectAsState().value
    val ipLoading = AetherController.ipLoading.collectAsState().value

    ConnectionCard(
        connected = state.isConnected,
        statusTitle = aetherStatusTitle(state),
        statusCaption = aetherStatusCaption(state),
        connectedSince = connectedSince,
        ipInfo = ipInfo,
        ipLoading = ipLoading,
        error = state is ConnectionState.Error,
    )
}

@Composable
private fun AetherAdvancedPanel(
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    startExpanded: Boolean,
) {
    val state = AetherController.state.collectAsState().value

    // Settings are independent of the Aether runtime switch. They must remain
    // editable before BigRocket connects; only the active connection workflow
    // temporarily locks them.
    AdvancedPanel(
        profile = profile,
        onProfileChange = onProfileChange,
        enabled = !state.isBusy,
        startExpanded = startExpanded,
    )
}

@Composable
private fun AetherSharePanel(
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
) {
    val state = AetherController.state.collectAsState().value

    SharePanel(
        state = state,
        profile = profile,
        onProfileChange = onProfileChange,
    )
}

@Composable
private fun AetherStateText(state: ConnectionState) {
    Text(
        text = aetherStatusTitle(state),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Short status label - same wording ConnectionMeta/AetherStateText used before
 * ConnectionCard replaced them, just split into title/caption since ConnectionCard
 * takes them separately.
 */
private fun aetherStatusTitle(state: ConnectionState): String = when (state) {
    ConnectionState.Idle -> "آماده"
    ConnectionState.Launching -> "در حال راه‌اندازی موتور"
    ConnectionState.Connecting -> "در حال اتصال / بررسی پورت"
    ConnectionState.Verifying -> "در حال بررسی اتصال"
    is ConnectionState.Connected -> "متصل"
    is ConnectionState.Reconnecting -> "در حال اتصال مجدد"
    ConnectionState.Disconnecting -> "در حال قطع اتصال"
    is ConnectionState.Error -> "خطا"
}

private fun aetherStatusCaption(state: ConnectionState): String = when (state) {
    is ConnectionState.Error -> state.message
    else -> ""
}
