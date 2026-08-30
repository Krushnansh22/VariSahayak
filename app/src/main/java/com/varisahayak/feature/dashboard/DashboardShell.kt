package com.varisahayak.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.varisahayak.R
import com.varisahayak.core.designsystem.Accents
import com.varisahayak.core.designsystem.Dimens
import com.varisahayak.core.designsystem.VariTheme
import com.varisahayak.core.designsystem.component.DashboardHeader
import com.varisahayak.core.designsystem.component.DashboardStatusRow
import com.varisahayak.core.designsystem.component.OfflineQueuePill
import com.varisahayak.core.designsystem.component.QuickAction
import com.varisahayak.core.designsystem.component.QuickActionGrid
import com.varisahayak.domain.model.Capabilities

/**
 * The parts of a role dashboard that never differ.
 *
 * Every role opens on the same three rows — who you are, whether you are reachable, and
 * the four things you do most — and only then diverges. Keeping that identical is the
 * point: a medical responder who covers a shift as a volunteer should not have to relearn
 * the top of the screen.
 */

/** Callbacks the shell needs, gathered so each screen signature stays readable. */
data class DashboardActions(
    val onReport: () -> Unit,
    val onMap: () -> Unit,
    val onLostFound: () -> Unit,
    val onReportFound: () -> Unit,
    val onDetail: (String) -> Unit,
    val onToggleWalkie: () -> Unit,
    val onSos: () -> Unit,
)

/**
 * Header, status row, offline pill and quick actions, as LazyColumn items.
 *
 * A `LazyListScope` extension rather than a wrapper composable so each role screen keeps
 * one flat scrolling list. Nesting a scrollable inside a scrollable to share a header is
 * how a dashboard ends up with two scroll positions fighting a single thumb.
 */
fun LazyListScope.dashboardHeaderItems(
    uiState: DashboardUiState,
    actions: DashboardActions,
    walkieChannelName: String?,
    walkieVisible: Boolean,
    onRetrySync: () -> Unit,
) {
    val profile = uiState.profile ?: return

    item(key = "header") {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            DashboardHeader(
                displayName = profile.displayName,
                role = profile.role,
                subtitle = profile.organisationName ?: profile.areaName,
                // No notifications screen exists yet, so the bell is deliberately absent
                // rather than present and inert. See DashboardHeader.onNotifications.
                onNotifications = null,
                onWalkie = actions.onToggleWalkie,
                onSos = actions.onSos,
                walkieActive = walkieVisible,
            )

            DashboardStatusRow(
                isOnline = !uiState.isOffline,
                channelName = walkieChannelName,
                onChannelClick = actions.onToggleWalkie,
            )

            if (uiState.unsyncedCount > 0) {
                OfflineQueuePill(
                    unsyncedCount = uiState.unsyncedCount,
                    isOnline = !uiState.isOffline,
                    onRetry = onRetrySync,
                )
            }
        }
    }

    item(key = "quick-actions") {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            Text(
                text = stringResource(R.string.dashboard_quick_actions),
                style = MaterialTheme.typography.titleLarge,
                color = VariTheme.colors.textPrimary,
            )
            QuickActionGrid(actions = quickActionsFor(uiState.capabilities, actions))
        }
    }
}

/**
 * The four quick actions a role gets.
 *
 * Driven by [Capabilities] rather than by role, so the grid cannot offer a control the
 * server would reject. Padded to four with the map, which every role can open — a grid
 * that changes width between roles would make the whole screen feel like a different app.
 */
@Composable
private fun quickActionsFor(
    capabilities: Capabilities,
    actions: DashboardActions,
): List<QuickAction> = buildList {
    if (capabilities.canReportIncident) {
        add(
            QuickAction(
                label = stringResource(R.string.dashboard_report_incident),
                icon = Icons.Filled.ReportProblem,
                tone = Accents.red,
                onClick = actions.onReport,
            ),
        )
    }
    add(
        QuickAction(
            label = stringResource(R.string.nav_map),
            icon = Icons.Filled.Map,
            tone = Accents.blue,
            onClick = actions.onMap,
        ),
    )
    if (capabilities.canUseLostFound) {
        add(
            QuickAction(
                label = stringResource(R.string.lostfound_report_found),
                icon = Icons.Filled.PersonSearch,
                tone = Accents.green,
                onClick = actions.onReportFound,
            ),
        )
        add(
            QuickAction(
                label = stringResource(R.string.lostfound_title),
                icon = Icons.Filled.FindInPage,
                tone = Accents.amber,
                onClick = actions.onLostFound,
            ),
        )
    }
}

/**
 * The SOS confirmation.
 *
 * Confirmed rather than fired on the first tap, because the control is deliberately large
 * and sits under a thumb that is also scrolling. A mis-raised SOS costs a responder a
 * journey, so one deliberate second is worth buying.
 */
@Composable
fun SosConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = VariTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.sos_confirm_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.sos_confirm_message),
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_confirm),
                    color = colors.critical,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        containerColor = colors.cardSurface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
    )
}
