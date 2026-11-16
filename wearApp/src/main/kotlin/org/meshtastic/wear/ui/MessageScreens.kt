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
package org.meshtastic.wear.ui

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.model.effectiveLongName
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.messages
import org.meshtastic.core.resources.reply
import org.meshtastic.core.resources.type_a_message
import org.meshtastic.core.resources.wear_keyboard
import org.meshtastic.core.resources.wear_no_conversations
import org.meshtastic.core.resources.wear_quick_copy
import org.meshtastic.core.resources.wear_quick_no
import org.meshtastic.core.resources.wear_quick_ok
import org.meshtastic.core.resources.wear_quick_on_my_way
import org.meshtastic.core.resources.wear_quick_yes
import org.meshtastic.core.resources.wear_voice

@Composable
fun ContactsScreen(onOpen: (String) -> Unit) {
    val viewModel: ContactsViewModel = koinViewModel()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    ListScreen(title = stringResource(Res.string.messages)) {
        if (contacts.isEmpty()) item { InfoText(stringResource(Res.string.wear_no_conversations)) }
        contacts.forEach { contact ->
            item(key = contact.key) {
                val prefix = if (contact.unread > 0) "(${contact.unread}) " else ""
                RowButton(
                    label = prefix + contact.name,
                    secondary = contact.lastText?.let { "${agoShort(contact.lastTime)} · $it" },
                    selected = contact.unread > 0,
                    onClick = { onOpen(contact.key) },
                )
            }
        }
    }
}

@Composable
fun ConversationScreen(contactKey: String) {
    val viewModel: ConversationViewModel = koinViewModel(key = contactKey) { parametersOf(contactKey) }
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val quickChats by viewModel.quickChats.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()

    DisposableEffect(contactKey) {
        viewModel.onShown()
        onDispose { viewModel.onHidden() }
    }
    LaunchedEffect(messages.lastOrNull()?.uuid) { messages.lastOrNull()?.let(viewModel::markRead) }

    val typeLabel = stringResource(Res.string.type_a_message)
    val keyboardLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@rememberLauncherForActivityResult
            RemoteInput.getResultsFromIntent(data)?.getCharSequence(REPLY_KEY)?.let { viewModel.send(it.toString()) }
        }
    val voiceLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(viewModel::send)
        }

    val defaults =
        listOf(
            stringResource(Res.string.wear_quick_ok),
            stringResource(Res.string.wear_quick_on_my_way),
            stringResource(Res.string.wear_quick_copy),
            stringResource(Res.string.wear_quick_yes),
            stringResource(Res.string.wear_quick_no),
        )
    val quickReplies = (quickChats + defaults).distinct()

    ListScreen(title = title) {
        messages.forEach { message -> item(key = message.uuid) { MessageBubble(message) } }
        item {
            RowButton(
                label = stringResource(Res.string.wear_voice),
                selected = true,
                onClick = {
                    voiceLauncher.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, typeLabel),
                    )
                },
            )
        }
        item {
            RowButton(
                label = stringResource(Res.string.wear_keyboard),
                onClick = {
                    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                    RemoteInputIntentHelper.putRemoteInputsExtra(
                        intent,
                        listOf(RemoteInput.Builder(REPLY_KEY).setLabel(typeLabel).build()),
                    )
                    keyboardLauncher.launch(intent)
                },
            )
        }
        item { InfoText(stringResource(Res.string.reply)) }
        quickReplies.forEach { text ->
            item(key = "quick:$text") { RowButton(label = text, onClick = { viewModel.send(text) }) }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column {
            val sender = if (message.fromLocal) "" else message.node.effectiveLongName
            val status =
                when {
                    !message.fromLocal -> ""
                    message.status == MessageStatus.DELIVERED -> " ✓"
                    message.status == MessageStatus.ERROR -> " !"
                    message.status == MessageStatus.QUEUED || message.status == MessageStatus.ENROUTE -> " …"
                    else -> ""
                }
            Text(
                "$sender ${agoShort(message.receivedTime)}$status".trim(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val REPLY_KEY = "meshtastic_reply"
