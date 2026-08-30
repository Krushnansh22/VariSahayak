package com.varisahayak.feature.lostfound

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.varisahayak.R
import com.varisahayak.core.designsystem.Dimens
import com.varisahayak.core.designsystem.component.VariPrimaryButton
import com.varisahayak.core.media.PhotoCapture
import com.varisahayak.domain.model.LostFoundReport
import com.varisahayak.domain.model.MatchConfidence
import com.varisahayak.domain.model.MatchSignal
import com.varisahayak.domain.model.SignalStrength

@Composable
fun CandidateCard(
    candidate: MatchCandidate,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val score = candidate.match.score

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Dimens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
        ) {
            // Confidence as a word plus the percentage. Never a colour on its own.
            Text(
                text = stringResource(
                    when (score.confidence) {
                        MatchConfidence.HIGH -> R.string.match_confidence_high
                        MatchConfidence.MEDIUM -> R.string.match_confidence_medium
                        MatchConfidence.LOW -> R.string.match_confidence_low
                    },
                ) + " · ${score.percent}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            // Both faces at the same size, on the same line, at the top of the card.
            // Comparing two people is the one thing this screen exists for, and it was
            // asking the reviewer to do it from two text columns.
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SidePanel(
                    labelRes = R.string.match_lost_side,
                    report = candidate.lost,
                    modifier = Modifier.weight(1f),
                )
                SidePanel(
                    labelRes = R.string.match_found_side,
                    report = candidate.found,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.match_why),
                style = MaterialTheme.typography.titleSmall,
            )

            // Everything the engine looked at, including what it could not compare. A
            // volunteer needs to know a photo was missing, not merely that the score is
            // lower than they expected.
            score.signals.forEach { signal -> SignalRow(signal) }

            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
                VariPrimaryButton(
                    text = stringResource(R.string.match_confirm),
                    onClick = onConfirm,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = onReject,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.match_reject))
                }
            }
        }
    }
}

@Composable
fun SidePanel(labelRes: Int, report: LostFoundReport?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (report == null) {
            // The counterpart has not reached this device yet. Say so rather than showing
            // a blank column that reads as "no information recorded".
            Text(
                text = stringResource(R.string.state_loading),
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }

        SidePhoto(report = report)

        Text(text = report.title, style = MaterialTheme.typography.bodyMedium)

        listOfNotNull(
            report.personName,
            report.approximateAge?.let { stringResource(R.string.lostfound_age_approx, it) },
            report.clothingDescription,
            report.language,
            report.qrLocationName,
            report.custodianName?.let { stringResource(R.string.lostfound_with_custodian, it) },
        ).forEach { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SignalRow(signal: MatchSignal) {
    // A leading glyph plus the sentence. The glyph is redundant with the words on purpose
    // — it is a scanning aid, never the only carrier of meaning.
    val marker = when (signal.strength) {
        SignalStrength.SUPPORTS -> "✓"
        SignalStrength.NEUTRAL -> "·"
        SignalStrength.CONTRADICTS -> "✗"
        SignalStrength.NO_SIGNAL -> "⚠"
    }

    Text(
        text = "$marker ${signal.explanation}",
        style = MaterialTheme.typography.bodySmall,
        color = when (signal.strength) {
            SignalStrength.CONTRADICTS -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        },
    )
}

/**
 * One side's photograph, or an honest placeholder.
 *
 * Square and equal-width on both sides so the two faces are the same size — a comparison
 * where one image is larger reads as one being the better answer. A missing photo says so
 * in words rather than collapsing the column, because "no photo on this report" and "these
 * faces differ" are different facts and the reviewer has to be able to tell them apart.
 */
@Composable
private fun SidePhoto(report: LostFoundReport, modifier: Modifier = Modifier) {
    val bitmap = remember(report.photoLocalPath) {
        report.photoLocalPath?.let { PhotoCapture.thumbnail(it) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        } else {
            Text(
                text = stringResource(R.string.match_no_photo),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Dimens.SpaceXs),
            )
        }
    }
}
