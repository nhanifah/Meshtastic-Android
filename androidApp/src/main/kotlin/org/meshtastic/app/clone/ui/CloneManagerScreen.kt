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
package org.meshtastic.app.clone.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.app.clone.CloneIcons
import org.meshtastic.app.clone.CloneLauncher
import org.meshtastic.app.clone.CloneProfile
import org.meshtastic.app.clone.CloneRegistry
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.clone_actions
import org.meshtastic.core.resources.clone_add
import org.meshtastic.core.resources.clone_add_to_home
import org.meshtastic.core.resources.clone_change_color
import org.meshtastic.core.resources.clone_choose_logo
import org.meshtastic.core.resources.clone_color_option
import org.meshtastic.core.resources.clone_default_name
import org.meshtastic.core.resources.clone_delete_message
import org.meshtastic.core.resources.clone_delete_title
import org.meshtastic.core.resources.clone_manager_explainer
import org.meshtastic.core.resources.clone_manager_title
import org.meshtastic.core.resources.clone_open
import org.meshtastic.core.resources.clone_rename
import org.meshtastic.core.resources.clone_reset_logo
import org.meshtastic.core.resources.clone_running
import org.meshtastic.core.resources.clone_slot_label
import org.meshtastic.core.resources.clone_slots_full
import org.meshtastic.core.resources.delete
import org.meshtastic.core.resources.name
import org.meshtastic.core.resources.save
import org.meshtastic.core.ui.component.MainAppBar
import org.meshtastic.core.ui.component.MeshtasticDialog
import org.meshtastic.core.ui.icon.Add
import org.meshtastic.core.ui.icon.Check
import org.meshtastic.core.ui.icon.Delete
import org.meshtastic.core.ui.icon.Edit
import org.meshtastic.core.ui.icon.FormatPaint
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.More
import org.meshtastic.core.ui.icon.PhoneAndroid
import org.meshtastic.core.ui.icon.PhotoCamera
import org.meshtastic.core.ui.icon.RestartAlt

private const val REFRESH_INTERVAL_MS = 2_000L
private const val ICON_RENDER_PX = 192
private const val MAX_NAME_LENGTH = 40
private val ICON_SIZE = 48.dp
private val SWATCH_SIZE = 44.dp

/** What the manager shows: the clones, which of them are running, and the next slot a new clone would take. */
private data class CloneSnapshot(
    val clones: List<CloneProfile> = emptyList(),
    val running: Set<Int> = emptySet(),
    val nextFreeSlot: Int? = null,
)

private fun loadSnapshot(context: Context): CloneSnapshot {
    val clones = CloneRegistry.read(context)
    return CloneSnapshot(
        clones = clones,
        running = clones.map { it.slot }.filter { CloneLauncher.isRunning(context, it) }.toSet(),
        nextFreeSlot = CloneRegistry.nextFreeSlot(context),
    )
}

/** Where the user adds, customizes, opens and deletes app clones. Only reachable from the main app. */
@Suppress("LongMethod")
@Composable
fun CloneManagerScreen(onNavigateUp: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(CloneSnapshot()) }
    // Bumped when a custom icon file changes, since the profile itself may stay equal.
    var iconVersion by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<CloneProfile?>(null) }
    var coloring by remember { mutableStateOf<CloneProfile?>(null) }
    var deleting by remember { mutableStateOf<CloneProfile?>(null) }
    var logoSlot by rememberSaveable { mutableStateOf<Int?>(null) }

    suspend fun refresh() {
        snapshot = withContext(Dispatchers.IO) { loadSnapshot(context) }
    }

    /** Runs [block] off the main thread, then reloads the list. */
    fun mutate(block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { block() }
            refresh()
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            refresh()
            delay(REFRESH_INTERVAL_MS)
        }
    }

    val logoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val slot = logoSlot
            logoSlot = null
            if (uri == null || slot == null) return@rememberLauncherForActivityResult
            mutate {
                val profile = CloneRegistry.find(context, slot) ?: return@mutate
                if (CloneIcons.saveCustom(context, slot, uri)) {
                    val updated = profile.copy(hasCustomIcon = true)
                    CloneRegistry.upsert(context, updated)
                    CloneLauncher.updateShortcut(context, updated)
                    iconVersion++
                }
            }
        }

    Scaffold(
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.clone_manager_title),
                canNavigateUp = true,
                onNavigateUp = onNavigateUp,
                ourNode = null,
                showNodeChip = false,
                actions = {},
                onClickChip = {},
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(Res.string.clone_manager_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(snapshot.clones, key = { it.slot }) { profile ->
                CloneCard(
                    profile = profile,
                    iconVersion = iconVersion,
                    running = profile.slot in snapshot.running,
                    onOpen = { CloneLauncher.launch(context, profile.slot) },
                    onRename = { renaming = profile },
                    onChangeColor = { coloring = profile },
                    onChooseLogo = {
                        logoSlot = profile.slot
                        logoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onResetLogo = {
                        mutate {
                            CloneRegistry.customIconFile(context, profile.slot).delete()
                            val updated = profile.copy(hasCustomIcon = false)
                            CloneRegistry.upsert(context, updated)
                            CloneLauncher.updateShortcut(context, updated)
                            iconVersion++
                        }
                    },
                    onAddToHome = { CloneLauncher.requestPinShortcut(context, profile) },
                    onDelete = { deleting = profile },
                )
            }
            item {
                val nextSlot = snapshot.nextFreeSlot
                Button(
                    onClick = {
                        val slot = nextSlot ?: return@Button
                        scope.launch {
                            val name = getString(Res.string.clone_default_name, slot + 1)
                            mutate {
                                val profile =
                                    CloneProfile(
                                        slot = slot,
                                        name = name,
                                        color = CloneIcons.defaultColorFor(slot),
                                        createdAtMillis = nowMillis,
                                    )
                                CloneRegistry.upsert(context, profile)
                            }
                        }
                    },
                    enabled = nextSlot != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(MeshtasticIcons.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(Res.string.clone_add))
                }
                if (nextSlot == null) {
                    Text(
                        text = stringResource(Res.string.clone_slots_full, CloneRegistry.maxSlots),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    renaming?.let { profile ->
        RenameDialog(
            initial = profile.name,
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                renaming = null
                mutate {
                    val updated = profile.copy(name = newName)
                    CloneRegistry.upsert(context, updated)
                    CloneLauncher.updateShortcut(context, updated)
                }
            },
        )
    }

    coloring?.let { profile ->
        ColorDialog(
            selected = profile.color,
            onDismiss = { coloring = null },
            onSelect = { color ->
                coloring = null
                mutate {
                    val updated = profile.copy(color = color)
                    CloneRegistry.upsert(context, updated)
                    CloneLauncher.updateShortcut(context, updated)
                }
            },
        )
    }

    deleting?.let { profile ->
        MeshtasticDialog(
            title = stringResource(Res.string.clone_delete_title, profile.name),
            message = stringResource(Res.string.clone_delete_message),
            confirmText = stringResource(Res.string.delete),
            onConfirm = {
                deleting = null
                mutate { CloneLauncher.delete(context, profile.slot) }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun CloneCard(
    profile: CloneProfile,
    iconVersion: Int,
    running: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onChangeColor: () -> Unit,
    onChooseLogo: () -> Unit,
    onResetLogo: () -> Unit,
    onAddToHome: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val icon = remember(profile, iconVersion) { CloneIcons.render(context, profile, ICON_RENDER_PX).asImageBitmap() }
    var menuOpen by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(ICON_SIZE).clip(CircleShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val slotLabel = stringResource(Res.string.clone_slot_label, profile.slot)
                Text(
                    text = if (running) "$slotLabel · ${stringResource(Res.string.clone_running)}" else slotLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                    if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onOpen) { Text(stringResource(Res.string.clone_open)) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(MeshtasticIcons.More, contentDescription = stringResource(Res.string.clone_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuItem(stringResource(Res.string.clone_rename), MeshtasticIcons.Edit) {
                        menuOpen = false
                        onRename()
                    }
                    MenuItem(stringResource(Res.string.clone_change_color), MeshtasticIcons.FormatPaint) {
                        menuOpen = false
                        onChangeColor()
                    }
                    MenuItem(stringResource(Res.string.clone_choose_logo), MeshtasticIcons.PhotoCamera) {
                        menuOpen = false
                        onChooseLogo()
                    }
                    if (profile.hasCustomIcon) {
                        MenuItem(stringResource(Res.string.clone_reset_logo), MeshtasticIcons.RestartAlt) {
                            menuOpen = false
                            onResetLogo()
                        }
                    }
                    MenuItem(stringResource(Res.string.clone_add_to_home), MeshtasticIcons.PhoneAndroid) {
                        menuOpen = false
                        onAddToHome()
                    }
                    MenuItem(
                        text = stringResource(Res.string.delete),
                        icon = MeshtasticIcons.Delete,
                        tint = MaterialTheme.colorScheme.error,
                    ) {
                        menuOpen = false
                        onDelete()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = tint) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint) },
        onClick = onClick,
    )
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    MeshtasticDialog(
        title = stringResource(Res.string.clone_rename),
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_NAME_LENGTH) },
                label = { Text(stringResource(Res.string.name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmText = stringResource(Res.string.save),
        onConfirm = {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) onDismiss() else onConfirm(trimmed)
        },
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDialog(selected: Long, onDismiss: () -> Unit, onSelect: (Long) -> Unit) {
    MeshtasticDialog(
        title = stringResource(Res.string.clone_change_color),
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CloneIcons.defaultColors.forEachIndexed { index, color ->
                    val label = stringResource(Res.string.clone_color_option, index + 1)
                    Box(
                        modifier =
                        Modifier.size(SWATCH_SIZE)
                            .clip(CircleShape)
                            .drawBehind { drawRect(Color(color.toInt())) }
                            .selectable(selected = color == selected, role = Role.RadioButton) { onSelect(color) }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (color == selected) {
                            Icon(MeshtasticIcons.Check, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            }
        },
        onDismiss = onDismiss,
    )
}
