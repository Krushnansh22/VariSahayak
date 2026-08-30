package com.varisahayak.feature.lostfound

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.varisahayak.R
import com.varisahayak.core.common.AppError
import com.varisahayak.core.designsystem.Dimens
import com.varisahayak.core.designsystem.VariTheme
import com.varisahayak.core.designsystem.component.EmptyState
import com.varisahayak.core.designsystem.component.SyncBadge
import com.varisahayak.core.designsystem.component.VariSecondaryButton
import com.varisahayak.core.media.PhotoCapture
import com.varisahayak.core.permissions.AppPermissions
import com.varisahayak.core.permissions.rememberPermissionController
import com.varisahayak.domain.model.LostFoundKind
import com.varisahayak.domain.model.LostFoundReport
import com.varisahayak.domain.model.LostFoundStatus
import java.io.File

/**
 * The Lost & Found board.
 *
 * Two actions rather than one, and both prominent. §7.15 asks for "Found Person" to be a
 * first-class action, because the volunteer holding a lost child is the one under the most
 * pressure and has the least patience for navigating a form hierarchy.
 */
@Composable
fun LostFoundScreen(
    modifier: Modifier = Modifier,
    onOpenMatches: () -> Unit = {},
    onReportDetail: (String) -> Unit = {},
    viewModel: LostFoundViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(800)) + slideInVertically(tween(800)) { 20 }
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
        ) {
            // Candidates first. A pending match is somebody waiting to be reunited, and it
            // outranks everything else on this screen.
            // Candidates first.
            if (uiState.candidateCount > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = VariTheme.colors.brandSubtle.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(Dimens.CornerCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(Dimens.SpaceMd),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PersonSearch,
                                contentDescription = null,
                                tint = VariTheme.colors.brandSolid,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(Dimens.SpaceSm))
                            Text(
                                text = stringResource(
                                    R.string.lostfound_pending_matches,
                                    uiState.candidateCount,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = VariTheme.colors.textPrimary
                            )
                        }
                        TextButton(
                            onClick = onOpenMatches,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(
                                stringResource(R.string.lostfound_review_matches),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            ActionButtonsRow(
                onReportLost = { viewModel.openReport(LostFoundKind.LOST) },
                onFoundPerson = { viewModel.openReport(LostFoundKind.FOUND) }
            )

            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChanged,
                label = { Text(stringResource(R.string.lostfound_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = Dimens.MinTouchTarget),
                shape = RoundedCornerShape(Dimens.CornerMd)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
            ) {
                BoardFilter.entries.forEach { side ->
                    FilterChip(
                        selected = uiState.filter == side,
                        onClick = { viewModel.onFilterChanged(side) },
                        label = { Text(stringResource(side.labelRes())) },
                        modifier = Modifier.defaultMinSize(minHeight = Dimens.MinTouchTarget),
                    )
                }
            }

            if (reports.isEmpty()) {
                EmptyState(message = stringResource(R.string.lostfound_empty))
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(reports, key = { _, report -> report.clientId }) { index, report ->
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(tween(600, delayMillis = 100 + index * 50)) +
                                    slideInVertically(tween(600, delayMillis = 100 + index * 50)) { 20 }
                        ) {
                            LostFoundRow(
                                report = report,
                                onClick = { onReportDetail(report.clientId) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (uiState.isReportOpen) {
        ReportDialog(
            state = uiState,
            onChange = viewModel::updateForm,
            onPhotoCaptured = viewModel::onPhotoCaptured,
            onClearPhoto = viewModel::clearPhoto,
            onSubmit = viewModel::submitReport,
            onDismiss = viewModel::closeReport,
        )
    }

    uiState.justReportedClientId?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(stringResource(R.string.lostfound_title)) },
            text = {
                Text(
                    when {
                        // The photo verdict has not come back yet. Said plainly rather than
                        // shown as a spinner: the report is already saved, and the volunteer
                        // is free to close this and carry on.
                        uiState.isProcessingPhoto ->
                            stringResource(R.string.lostfound_photo_processing)
                        // A photo notice replaces the generic confirmation — it is the only
                        // thing on this dialog the volunteer might act on.
                        uiState.photoNotice != null -> uiState.photoNotice!!
                        else -> stringResource(R.string.report_saved_offline)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissConfirmation) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

private fun BoardFilter.labelRes(): Int = when (this) {
    BoardFilter.ALL -> R.string.filter_all
    BoardFilter.LOST -> R.string.lostfound_side_lost
    BoardFilter.FOUND -> R.string.lostfound_side_found
}

/**
 * The two ways onto the board, weighted equally.
 *
 * Colour carries the side — red for a person missing, green for a person found — but the
 * label and the icon carry it too, so the pair is still distinguishable without colour.
 */
@Composable
private fun ActionButtonsRow(
    onReportLost: () -> Unit,
    onFoundPerson: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
    ) {
        EnhancedActionButton(
            text = stringResource(R.string.lostfound_report_lost),
            onClick = onReportLost,
            containerColor = VariTheme.colors.criticalContainer,
            contentColor = VariTheme.colors.critical,
            icon = Icons.Default.AddAPhoto,
            modifier = Modifier.weight(1f)
        )
        EnhancedActionButton(
            text = stringResource(R.string.lostfound_report_found),
            onClick = onFoundPerson,
            containerColor = VariTheme.colors.successContainer,
            contentColor = VariTheme.colors.success,
            icon = Icons.Default.PersonSearch,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun EnhancedActionButton(
    text: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(100),
        label = "button_scale"
    )

    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        shape = RoundedCornerShape(Dimens.CornerMd),
        modifier = modifier
            .height(64.dp)
            .scale(scale),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 0.dp
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * One report on the board.
 *
 * The thumbnail leads, because a face is what a volunteer scanning the list actually
 * recognises. Tapping the row opens the report — the only route to a report's detail.
 */
@Composable
private fun LostFoundRow(
    report: LostFoundReport,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.CornerCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .padding(Dimens.SpaceMd)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Decoded small and off the full image: this runs during composition, and a
            // list of full-size JPEGs is a visible stutter on the phones this app targets.
            val photoPath = report.photoLocalPath
            val bitmap = remember(photoPath) {
                photoPath?.let { PhotoCapture.thumbnail(it, maxEdgePx = 160) }
            }

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(Dimens.CornerMd))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        // Labelled by the row, not described. Nothing here should try to
                        // characterise a photograph of a missing person.
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = if (report.kind == LostFoundKind.LOST) {
                            Icons.Default.Search
                        } else {
                            Icons.Default.PersonSearch
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(Dimens.SpaceMd))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = report.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    SyncBadge(syncState = report.syncState)
                }

                // The attributes a volunteer actually scans a list for.
                val summary = listOfNotNull(
                    report.approximateAge?.let {
                        stringResource(R.string.lostfound_age_approx, it)
                    },
                    report.clothingDescription,
                    report.qrLocationName,
                ).joinToString(" · ")

                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Who is holding this person right now — the question a frantic parent asks.
                if (report.kind == LostFoundKind.FOUND && report.custodianName != null) {
                    Text(
                        text = stringResource(R.string.lostfound_with_custodian, report.custodianName),
                        style = MaterialTheme.typography.labelSmall,
                        color = VariTheme.colors.info,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(Dimens.SpaceXs))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Side and status as words, never colour alone.
                    val kindColor = if (report.kind == LostFoundKind.LOST) {
                        VariTheme.colors.critical
                    } else {
                        VariTheme.colors.success
                    }

                    Surface(
                        color = kindColor.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(Dimens.CornerPill)
                    ) {
                        Text(
                            text = stringResource(
                                when (report.kind) {
                                    LostFoundKind.LOST -> R.string.lostfound_side_lost
                                    LostFoundKind.FOUND -> R.string.lostfound_side_found
                                },
                            ).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = kindColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = stringResource(
                            when (report.status) {
                                LostFoundStatus.OPEN -> R.string.lostfound_status_open
                                LostFoundStatus.MATCHED -> R.string.lostfound_status_matched
                                LostFoundStatus.REUNITED -> R.string.lostfound_status_reunited
                                LostFoundStatus.CLOSED -> R.string.lostfound_status_closed
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * The report form.
 *
 * Rebuilt around the two things that actually matter when somebody is standing in front of
 * you: a photograph, and a handful of attributes that can be tapped rather than typed.
 *
 * It used to be twelve free-text boxes behind a mandatory title. That cost a volunteer a
 * minute of one-handed typing per report, and it cost the matching engine more than that —
 * gender and language are compared by exact string equality, so hand-typed values almost
 * never agreed between two people describing the same child.
 *
 * What survived but is rarely decisive now lives behind "More details". Nothing is
 * mandatory: the report can be filed with a photograph and nothing else.
 */
@Composable
private fun ReportDialog(
    state: LostFoundUiState,
    onChange: ((ReportFormState) -> ReportFormState) -> Unit,
    onPhotoCaptured: (String) -> Unit,
    onClearPhoto: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isFound = state.form.kind == LostFoundKind.FOUND

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isFound) R.string.lostfound_report_found else R.string.lostfound_report_lost,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
            ) {
                // Which side of the board this report belongs to, changeable here.
                //
                // Every entry point fixes the kind before the dialog opens — the two board
                // buttons, the dashboard's Found Person action, a QR scan. A volunteer who
                // tapped the wrong one had no way back except cancelling and re-entering
                // everything, and the form gave no sign the other side existed at all.
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
                    LostFoundKind.entries.forEach { kind ->
                        FilterChip(
                            selected = state.form.kind == kind,
                            onClick = {
                                onChange { form ->
                                    if (form.kind == kind) {
                                        form
                                    } else {
                                        // Each side asks a question the other does not, and
                                        // the answers are not interchangeable: a condition
                                        // nobody assessed, or a guardian's phone number on a
                                        // report about the guardian, would both be filed
                                        // silently because the field is hidden by then.
                                        form.copy(
                                            kind = kind,
                                            condition = null,
                                            guardianName = "",
                                            guardianPhone = "",
                                        )
                                    }
                                }
                            },
                            label = {
                                Text(
                                    stringResource(
                                        when (kind) {
                                            LostFoundKind.LOST -> R.string.lostfound_side_lost
                                            LostFoundKind.FOUND -> R.string.lostfound_side_found
                                        },
                                    ),
                                )
                            },
                            modifier = Modifier.defaultMinSize(minHeight = Dimens.MinTouchTarget),
                        )
                    }
                }

                state.scannedLocation?.let { location ->
                    Text(
                        text = stringResource(R.string.lostfound_at_location, location.locationName),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // First, and deliberately. A photograph is the most valuable thing on this
                // form — it is the only input that feeds face matching — and putting it
                // anywhere but the top makes it the thing people skip.
                PhotoPicker(
                    photoPath = state.form.photoLocalPath,
                    onPhotoCaptured = onPhotoCaptured,
                    onClear = onClearPhoto,
                )

                Field(
                    value = state.form.personName,
                    onValueChange = { v -> onChange { it.copy(personName = v) } },
                    labelRes = R.string.lostfound_field_name,
                )

                AgeSelector(
                    age = state.form.approximateAge,
                    onAgeChange = { v -> onChange { it.copy(approximateAge = v) } },
                )

                ChoiceRow(
                    labelRes = R.string.lostfound_field_gender,
                    options = GenderOption.entries,
                    selected = state.form.gender,
                    onSelect = { option -> onChange { it.copy(gender = option) } },
                )

                ClothingSelector(
                    colours = state.form.clothingColours,
                    detail = state.form.clothingDetail,
                    onToggleColour = { colour ->
                        onChange { form ->
                            val next = form.clothingColours.toMutableSet()
                            if (!next.add(colour)) next.remove(colour)
                            form.copy(clothingColours = next)
                        }
                    },
                    onDetailChange = { v -> onChange { it.copy(clothingDetail = v) } },
                )

                ChoiceRow(
                    labelRes = R.string.lostfound_field_language,
                    options = LanguageOption.entries,
                    selected = state.form.language,
                    onSelect = { option -> onChange { it.copy(language = option) } },
                )

                if (isFound) {
                    // Triage for whoever comes to help. Found side only — nobody filing a
                    // missing-person report can say how the person is right now.
                    ChoiceRow(
                        labelRes = R.string.lostfound_field_condition,
                        options = ConditionOption.entries,
                        selected = state.form.condition,
                        onSelect = { option -> onChange { it.copy(condition = option) } },
                    )
                } else {
                    // The single most valuable field for actually reuniting anybody, so it
                    // stays on the fast path rather than going behind the expander.
                    Field(
                        value = state.form.guardianPhone,
                        onValueChange = { v -> onChange { it.copy(guardianPhone = v) } },
                        labelRes = R.string.lostfound_field_guardian_phone,
                        keyboardType = KeyboardType.Phone,
                    )
                }

                MoreDetails(
                    expanded = state.form.isExpanded,
                    onToggle = { onChange { it.copy(isExpanded = !it.isExpanded) } },
                ) {
                    if (!isFound) {
                        Field(
                            value = state.form.guardianName,
                            onValueChange = { v -> onChange { it.copy(guardianName = v) } },
                            labelRes = R.string.lostfound_field_guardian,
                        )
                    }
                    Field(
                        value = state.form.physicalDescription,
                        onValueChange = { v -> onChange { it.copy(physicalDescription = v) } },
                        labelRes = R.string.lostfound_field_physical,
                    )
                    Field(
                        value = state.form.additionalNotes,
                        onValueChange = { v -> onChange { it.copy(additionalNotes = v) } },
                        labelRes = R.string.lostfound_field_notes,
                    )
                }

                (state.error as? AppError.Validation)?.let { error ->
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = !state.isSubmitting && state.form.canSubmit,
            ) {
                Text(stringResource(R.string.report_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Take a photo, or choose one, or neither.
 *
 * Both routes matter. A volunteer standing with a found child takes the picture; a parent
 * reporting a missing one almost always has a photograph already on their phone, and asking
 * them to photograph their own phone screen would be absurd.
 *
 * Capture writes straight into the app's private storage through a FileProvider, so the
 * image never reaches the device gallery — see [PhotoCapture].
 */
@Composable
private fun PhotoPicker(
    photoPath: String?,
    onPhotoCaptured: (String) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current

    // Held across the launcher round trip: the result callback is told only whether the
    // capture succeeded, not where it was written.
    var pendingCapture by remember { mutableStateOf<File?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val file = pendingCapture
        pendingCapture = null

        if (saved && file != null && PhotoCapture.normalise(file)) {
            onPhotoCaptured(file.absolutePath)
        } else {
            // A cancelled capture leaves a zero-byte file behind. Removing it here is what
            // stops private storage filling with empties over a day on the route.
            file?.delete()
        }
    }

    fun launchCamera() {
        val (file, uri) = PhotoCapture.newCaptureTarget(context)
        pendingCapture = file
        cameraLauncher.launch(uri)
    }

    val cameraPermission = rememberPermissionController(AppPermissions.CAMERA) { result ->
        if (result.values.any { it }) launchCamera()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        // Imported rather than referenced: a picker grant does not survive a process
        // restart, and a report filed offline may not upload for hours.
        if (uri != null) {
            PhotoCapture.importFromUri(context, uri)?.let { path ->
                onPhotoCaptured(path)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        Text(
            text = stringResource(R.string.lostfound_photo_label),
            style = MaterialTheme.typography.titleSmall,
        )

        if (photoPath != null) {
            PhotoPreview(path = photoPath, onClear = onClear)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            VariSecondaryButton(
                text = stringResource(
                    if (photoPath == null) {
                        R.string.lostfound_photo_take
                    } else {
                        R.string.lostfound_photo_retake
                    },
                ),
                onClick = {
                    when {
                        cameraPermission.state.isAnyGranted -> launchCamera()
                        // Re-requesting after "don't ask again" is silently dropped by the
                        // system, so the only honest route left is app settings.
                        cameraPermission.isPermanentlyDenied -> cameraPermission.openAppSettings()
                        else -> cameraPermission.request()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            VariSecondaryButton(
                text = stringResource(R.string.lostfound_photo_choose),
                onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = stringResource(R.string.lostfound_photo_optional),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The attached photograph, with a way to remove it.
 *
 * Decoded at a sample size rather than in full: this runs during composition, and decoding
 * a 1280px JPEG on the main thread is a visible stutter on the phones this app targets.
 */
@Composable
private fun PhotoPreview(path: String, onClear: () -> Unit) {
    val bitmap = remember(path) { PhotoCapture.thumbnail(path) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                // Labelled for a screen reader, but not described. Nothing here should try
                // to characterise a photograph of a missing child.
                contentDescription = stringResource(R.string.lostfound_photo_label),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(PHOTO_PREVIEW_SIZE)
                    .clip(MaterialTheme.shapes.medium),
            )
        }
        TextButton(onClick = onClear) {
            Text(stringResource(R.string.lostfound_photo_remove))
        }
    }
}

/**
 * Age, by tap or by keyboard.
 *
 * The presets write a representative number rather than a range, because the engine scores
 * age numerically with a ±2 tolerance and a range would throw that away. A volunteer who
 * knows the exact age types it; one who only knows "a small child" taps once and still
 * contributes a signal that two reports can agree on.
 */
@Composable
private fun AgeSelector(age: String, onAgeChange: (String) -> Unit) {
    val selected = remember(age) { AgePreset.forAge(age.toIntOrNull()) }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        Text(
            text = stringResource(R.string.lostfound_field_age),
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            AgePreset.entries.forEach { preset ->
                FilterChip(
                    selected = selected == preset,
                    onClick = {
                        // Tapping the active band clears it, so a mis-tap costs one tap to
                        // undo rather than a trip to the keyboard.
                        onAgeChange(
                            if (selected == preset) "" else preset.representativeAge.toString(),
                        )
                    },
                    label = { Text(stringResource(preset.labelRes)) },
                    modifier = Modifier.defaultMinSize(minHeight = Dimens.MinTouchTarget),
                )
            }
        }
        Field(
            value = age,
            onValueChange = { v -> onAgeChange(v.filter(Char::isDigit).take(3)) },
            labelRes = R.string.lostfound_field_age_exact,
            keyboardType = KeyboardType.Number,
        )
    }
}

/**
 * Clothing: tapped colours plus optional detail.
 *
 * The engine compares clothing as a bag of words with Jaccard overlap, so the tapped colours
 * are what make two hurried descriptions agree — "mustard kurta" and "yellow top" overlap on
 * nothing. The free text is what distinguishes one child in a yellow shirt from the next.
 */
@Composable
private fun ClothingSelector(
    colours: Set<ClothingColour>,
    detail: String,
    onToggleColour: (ClothingColour) -> Unit,
    onDetailChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        Text(
            text = stringResource(R.string.lostfound_field_clothing),
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            ClothingColour.entries.forEach { colour ->
                FilterChip(
                    selected = colour in colours,
                    onClick = { onToggleColour(colour) },
                    label = { Text(stringResource(colour.labelRes)) },
                    modifier = Modifier.defaultMinSize(minHeight = Dimens.MinTouchTarget),
                )
            }
        }
        Field(
            value = detail,
            onValueChange = onDetailChange,
            labelRes = R.string.lostfound_field_clothing_detail,
        )
    }
}

/** A labelled row of single-choice chips over any [ReportOption] set. */
@Composable
private fun <T : ReportOption> ChoiceRow(
    labelRes: Int,
    options: List<T>,
    selected: T?,
    onSelect: (T?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    // Re-tapping clears. A volunteer who mis-taps must not be stuck with a
                    // wrong attribute on a report that will be matched against.
                    onClick = { onSelect(if (selected == option) null else option) },
                    label = { Text(stringResource(option.labelRes)) },
                    modifier = Modifier.defaultMinSize(minHeight = Dimens.MinTouchTarget),
                )
            }
        }
    }
}

/**
 * The fields worth having and rarely worth waiting for.
 *
 * Collapsed by default. They still feed the matching engine when filled in — this is about
 * what a volunteer is asked for while somebody is standing in front of them, not about what
 * the report is able to hold.
 */
@Composable
private fun MoreDetails(
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
        TextButton(onClick = onToggle) {
            Text(
                stringResource(
                    if (expanded) {
                        R.string.lostfound_fewer_details
                    } else {
                        R.string.lostfound_more_details
                    },
                ),
            )
        }
        if (expanded) content()
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Dimens.MinTouchTarget),
    )
}

private val PHOTO_PREVIEW_SIZE = 96.dp
