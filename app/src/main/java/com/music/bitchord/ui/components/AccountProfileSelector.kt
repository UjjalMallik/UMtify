package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.auth.GoogleAccountSession
import com.music.bitchord.auth.YouTubeProfile
import com.music.bitchord.data.settings.AppSettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/** Shared compact selector for every visible YouTube account avatar. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AccountProfileSelector(
    accounts: List<GoogleAccountSession>,
    activeAccountId: String?,
    activeProfileId: String?,
    hazeState: HazeState,
    onSelect: (GoogleAccountSession, YouTubeProfile) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (GoogleAccountSession) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    bottomPadding: Dp = 100.dp,
    maxWidth: Dp = FLOATING_BAR_MAX_WIDTH,
    modifier: Modifier = Modifier,
) {
    var managing by remember { mutableStateOf(false) }
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(28.dp)
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val borderBrush = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = 0.18f),
            Color.White.copy(alpha = 0.05f),
        ),
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .48f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth(),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Surface(
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = shape,
                modifier = Modifier
                    .padding(start = 24.dp, end = 16.dp, bottom = bottomPadding)
                    .widthIn(max = 330.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .then(
                        when {
                            useGlass && !reduceDynamicBlur -> Modifier.liquidGlass(shape = shape)
                            reduceDynamicBlur -> Modifier.background(MaterialTheme.colorScheme.surface)
                            else -> Modifier
                                .optimizedHazeEffect(
                                    state = hazeState,
                                    style = HazeMaterials.regular(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                                )
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                        },
                    )
                    .border(1.dp, borderBrush, shape)
                    .clickable(onClick = {}),
            ) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                ) {
                    item(key = "offline_mode_toggle", contentType = "header") {
                        val offlineMode by AppSettings.offlineModeEnabled.collectAsStateWithLifecycle()
                        val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
                        val toggleShape = RoundedCornerShape(18.dp)
                        val toggleBorder = Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.35f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                Color.White.copy(alpha = 0.08f),
                            ),
                        )
                        val toggleBg = if (isLight) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.60f)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .clip(toggleShape)
                                .background(toggleBg)
                                .border(1.dp, toggleBorder, toggleShape)
                                .clickable { AppSettings.setOfflineModeEnabled(!offlineMode) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (offlineMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudOff,
                                    contentDescription = null,
                                    tint = if (offlineMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.offline_mode),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                                Text(
                                    text = "Downloads, cached & local only",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = offlineMode,
                                onCheckedChange = AppSettings::setOfflineModeEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                    accounts.forEach { account ->
                        item(key = "account_label_${account.accountId}", contentType = "account_label") {
                            Text(
                                account.email.ifBlank { account.name.ifBlank { stringResource(R.string.accounts) } },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 4.dp),
                            )
                        }
                        items(
                            count = account.profiles.size,
                            key = { index -> "profile_${account.accountId}_${account.profiles[index].profileId}" },
                            contentType = { "profile_row" },
                        ) { index ->
                            val profile = account.profiles[index]
                            ProfileRow(
                                profile,
                                account.accountId == activeAccountId && profile.profileId == activeProfileId,
                                managing,
                                { onSelect(account, profile); onDismiss() },
                                { onRemoveAccount(account) },
                            )
                        }
                    }
                    item(key = "add_account", contentType = "action") {
                        SelectorAction(Icons.Rounded.Add, stringResource(R.string.add_account), onAddAccount)
                    }
                    item(key = "manage_accounts", contentType = "action") {
                        SelectorAction(Icons.Rounded.ManageAccounts, stringResource(R.string.manage_accounts)) { managing = !managing }
                    }
                    item(key = "settings", contentType = "action") {
                        SelectorAction(Icons.Rounded.Settings, stringResource(R.string.settings), onOpenSettings)
                    }
                }
            }
        }
    }
}

@Composable private fun ProfileRow(profile: YouTubeProfile, selected: Boolean, managing: Boolean,
    onClick: () -> Unit, onRemove: () -> Unit) {
    val description = stringResource(if (selected) R.string.selected_account else R.string.switch_account)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.RadioButton, onClick = if (managing) onRemove else onClick)
        .semantics { contentDescription = description }
        .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (profile.avatar != null) AsyncImage(profile.avatar, null, Modifier.size(38.dp).clip(MaterialTheme.shapes.extraLarge))
        else Icon(Icons.Rounded.Person, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(profile.handle.ifBlank { stringResource(if (profile.isBrandAccount) R.string.brand_account else R.string.personal) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected && !managing) Icon(Icons.Rounded.Check, stringResource(R.string.selected_account), tint = MaterialTheme.colorScheme.primary)
        if (managing) Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun SelectorAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null); Spacer(Modifier.width(16.dp)); Text(label)
    }
}
