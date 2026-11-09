/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.feature.messaging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.model.DeliveryReceipt
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.close
import org.meshtastic.core.resources.delivery_receipt_acked_by
import org.meshtastic.core.resources.delivery_receipt_hops
import org.meshtastic.core.resources.delivery_receipt_last_hop
import org.meshtastic.core.resources.delivery_receipt_no_signal
import org.meshtastic.core.resources.delivery_receipt_relay_suffix
import org.meshtastic.core.resources.delivery_receipt_relayed_by
import org.meshtastic.core.resources.delivery_receipt_relayed_by_unknown
import org.meshtastic.core.resources.delivery_receipt_uncertain
import org.meshtastic.core.resources.delivery_receipts_first_relay_note
import org.meshtastic.core.resources.mqtt
import org.meshtastic.core.resources.relays
import org.meshtastic.core.resources.resend
import org.meshtastic.core.resources.traceroute
import org.meshtastic.core.resources.unknown
import org.meshtastic.core.ui.component.MeshtasticDialog
import org.meshtastic.core.ui.component.Rssi
import org.meshtastic.core.ui.component.Snr

/**
 * Delivery status of an outgoing message or reaction.
 *
 * @param receipts the acks that settled it, each shown with its relayer and the signal it was heard at.
 * @param resolveRelay names the node behind a relay id byte (see [resolveRelay]).
 * @param recipientName the addressed node's name for a direct message, used for its explicit ack.
 * @param onTraceroute opens the traceroute log for the addressed node; null hides the button.
 */
@Suppress("LongParameterList")
@Composable
fun DeliveryInfo(
    title: StringResource,
    resendOption: Boolean,
    modifier: Modifier = Modifier,
    text: StringResource? = null,
    detail: StringResource? = null,
    relays: Int = 0,
    receipts: List<DeliveryReceipt> = emptyList(),
    resolveRelay: (Int) -> ResolvedRelay = { ResolvedRelay(name = null, suffix = relaySuffix(it), uncertain = false) },
    recipientName: String? = null,
    onTraceroute: (() -> Unit)? = null,
    onConfirm: (() -> Unit) = {},
    onDismiss: () -> Unit = {},
) = MeshtasticDialog(
    modifier = modifier,
    title = stringResource(title),
    onDismiss = onDismiss,
    dismissText = stringResource(Res.string.close),
    confirmText = if (resendOption) stringResource(Res.string.resend) else null,
    onConfirm = if (resendOption) onConfirm else null,
    text = {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            text?.let {
                Text(
                    text = stringResource(it),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            detail?.let {
                Text(
                    text = stringResource(it),
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (relays != 0) {
                Text(
                    text = pluralStringResource(Res.plurals.relays, relays, relays),
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (receipts.isNotEmpty()) {
                DeliveryReceiptList(receipts, resolveRelay, recipientName)
            }
            if (onTraceroute != null) {
                OutlinedButton(onClick = onTraceroute, modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(Res.string.traceroute))
                }
            }
        }
    },
)

@Composable
private fun DeliveryReceiptList(
    receipts: List<DeliveryReceipt>,
    resolveRelay: (Int) -> ResolvedRelay,
    recipientName: String?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        receipts.forEachIndexed { index, receipt ->
            if (index > 0) HorizontalDivider()
            DeliveryReceiptRow(receipt, resolveRelay, recipientName)
        }
        if (receipts.any { !it.fromRecipient }) {
            Text(
                text = stringResource(Res.string.delivery_receipts_first_relay_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeliveryReceiptRow(
    receipt: DeliveryReceipt,
    resolveRelay: (Int) -> ResolvedRelay,
    recipientName: String?,
) {
    val relayLabel = receipt.relayNode?.let { relayLabel(resolveRelay(it)) }
    Column(modifier = Modifier.fillMaxWidth()) {
        val headline =
            when {
                receipt.fromRecipient ->
                    stringResource(
                        Res.string.delivery_receipt_acked_by,
                        recipientName ?: stringResource(Res.string.unknown),
                    )

                relayLabel == null -> stringResource(Res.string.delivery_receipt_relayed_by_unknown)

                else -> stringResource(Res.string.delivery_receipt_relayed_by, relayLabel)
            }
        Text(text = headline, style = MaterialTheme.typography.bodyMedium)
        if (receipt.fromRecipient) {
            val details = buildList {
                if (receipt.hopsAway >= 0) {
                    add(pluralStringResource(Res.plurals.delivery_receipt_hops, receipt.hopsAway, receipt.hopsAway))
                }
                // A zero-hop ack came straight from the recipient, so its last hop is the recipient itself.
                if (relayLabel != null && receipt.hopsAway > 0) {
                    add(stringResource(Res.string.delivery_receipt_last_hop, relayLabel))
                }
            }
            if (details.isNotEmpty()) {
                Text(text = details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (receipt.rssi == null && receipt.snr == null) {
                Text(
                    text = stringResource(Res.string.delivery_receipt_no_signal),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Rssi(receipt.rssi)
                Snr(receipt.snr)
            }
            if (receipt.viaMqtt) {
                Text(text = stringResource(Res.string.mqtt), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun relayLabel(relay: ResolvedRelay): String {
    val base = relay.name ?: stringResource(Res.string.delivery_receipt_relay_suffix, relay.suffix)
    return if (relay.uncertain) stringResource(Res.string.delivery_receipt_uncertain, base) else base
}
