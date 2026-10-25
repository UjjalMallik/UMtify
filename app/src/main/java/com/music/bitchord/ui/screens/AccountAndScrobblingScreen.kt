package com.music.bitchord.ui.screens

import com.music.bitchord.auth.clearSpotifyWebSession
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.spotify_logo
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.settings.AppSettings

@Composable
fun AccountAndScrobblingScreen(
    signedIn: Boolean,
    account: Account?,
    channelName: String?,
    onSignIn: () -> Unit,
    onSwitchChannel: () -> Unit,
    onSignOut: () -> Unit,
    onOpenListenBrainzLogin: () -> Unit,
    onOpenDiscord: () -> Unit,
    onOpenSpotify: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val listenBrainzEnabled by AppSettings.listenBrainzEnabled.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()
    val listenBrainzPrimaryArtistOnly by AppSettings.listenBrainzPrimaryArtistOnly.collectAsStateWithLifecycle()
    val discordToken by AppSettings.discordToken.collectAsStateWithLifecycle()
    val discordUsername by AppSettings.discordUsername.collectAsStateWithLifecycle()
    val discordRpcEnabled by AppSettings.discordRpcEnabled.collectAsStateWithLifecycle()
    val spotifyConnected by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Text(
            text = stringResource(R.string.account_integrations),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )

        // One card: who is signed in, which profile is listening, and the way
        // out, with the explanation of profiles underneath.
        SettingsGroup(
            footer = if (signedIn) stringResource(R.string.account_profiles_help) else null,
            topSpacing = 0.dp,
        ) {
            AccountCard(
                signedIn = signedIn,
                account = account,
                onSignIn = onSignIn,
                onClick = onSwitchChannel,
                grouped = true,
            )
            if (signedIn) {
                FullWidthDivider()
                SettingsRow(
                    icon = Icons.Rounded.SwitchAccount,
                    title = stringResource(R.string.listen_as),
                    subtitle = channelName ?: stringResource(R.string.default_youtube_profile),
                    onClick = onSwitchChannel,
                )
                FullWidthDivider()
                DestructiveRow(label = stringResource(R.string.sign_out), onClick = onSignOut)
            }
        }

        SettingsGroup(
            header = stringResource(R.string.spotify),
            footer = stringResource(R.string.spotify_connect_subtitle),
        ) {
            SettingsRow(
                iconPainter = painterResource(Res.drawable.spotify_logo),
                title = stringResource(R.string.spotify),
                subtitle = stringResource(
                    if (spotifyConnected.isNotBlank()) R.string.connected else R.string.tap_to_connect,
                ),
                onClick = onOpenSpotify,
            )
            if (spotifyConnected.isNotBlank()) {
                FullWidthDivider()
                DestructiveRow(
                    label = stringResource(R.string.spotify_disconnect),
                    onClick = {
                        clearSpotifyWebSession()
                        AppSettings.setSpotifySpdcToken("")
                    },
                )
            }
        }

        SettingsGroup(
            header = stringResource(R.string.rich_presence),
            footer = stringResource(R.string.rich_presence_footer),
        ) {
            SettingsRow(
                icon = ImageVector.vectorResource(R.drawable.ic_discord),
                title = "Discord",
                subtitle = when {
                    discordToken.isEmpty() -> stringResource(R.string.tap_to_connect)
                    !discordRpcEnabled -> stringResource(R.string.connected_presence_off)
                    discordUsername.isNotEmpty() -> stringResource(R.string.sharing_as, discordUsername)
                    else -> stringResource(R.string.sharing_listens)
                },
                onClick = onOpenDiscord,
            )
        }

        if (AppSettings.scrobblingAvailable) {
            SettingsGroup(
                header = stringResource(R.string.scrobbling),
                footer = stringResource(R.string.scrobbling_footer),
            ) {
                SettingsRow(
                    icon = Icons.Rounded.Cloud,
                    title = "ListenBrainz",
                    subtitle = if (listenBrainzEnabled && listenBrainzToken.isNotBlank()) {
                        stringResource(R.string.connected)
                    } else {
                        stringResource(R.string.enter_token_to_enable)
                    },
                    trailing = {
                        Switch(
                            checked = listenBrainzEnabled,
                            onCheckedChange = { checked ->
                                if (checked && listenBrainzToken.isBlank()) {
                                    onOpenListenBrainzLogin()
                                } else {
                                    AppSettings.setListenBrainzEnabled(checked)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = onOpenListenBrainzLogin,
                )
                if (listenBrainzEnabled && listenBrainzToken.isNotBlank()) {
                    RowDivider()
                    SettingsRow(
                        icon = Icons.Rounded.GraphicEq,
                        title = stringResource(R.string.scrobble_primary_artist_only),
                        subtitle = stringResource(R.string.scrobble_primary_artist_only_subtitle),
                        trailing = {
                            Switch(
                                checked = listenBrainzPrimaryArtistOnly,
                                onCheckedChange = AppSettings::setListenBrainzPrimaryArtistOnly,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setListenBrainzPrimaryArtistOnly(!listenBrainzPrimaryArtistOnly) },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** A hairline across the whole card, for setting a destructive row apart from the rows above it. */
@Composable
private fun FullWidthDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
}
