package com.example.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.model.AbilityConfig
import com.example.data.model.ArenaPowerUp
import com.example.data.model.DecoyBeacon
import com.example.data.model.GameLobby
import com.example.data.model.GeoMath
import com.example.data.model.LobbyPlayer
import com.example.services.GeoPoint
import com.example.ui.avatar.AvatarMiniBadge
import com.example.ui.theme.AmberTactical
import com.example.ui.theme.CrimsonBright
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.SafeEmerald
import com.example.ui.theme.SpectatorCyan
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun GameScreen(
    lobby: GameLobby,
    players: List<LobbyPlayer>,
    powerUps: List<ArenaPowerUp>,
    decoys: List<DecoyBeacon>,
    currentUserId: String,
    localLocation: GeoPoint,
    nowMs: Long,
    isMicEnabled: Boolean,
    outOfZoneTicks: Int,
    onToggleMic: () -> Unit,
    onActivateCloak: () -> Unit,
    onActivateSpeed: () -> Unit,
    onDeployDecoy: () -> Unit,
    onActivateReveal: () -> Unit,
    onActivateSonar: () -> Unit,
    onClaimPowerUp: (ArenaPowerUp) -> Unit,
    onTagHider: (String) -> Unit,
    onLeaveMatch: () -> Unit
) {
    val me = players.find { it.userId == currentUserId }
    val isSpectator = me?.isSpectator == true
    val isSeeker = me?.role == "seeker" && !isSpectator
    val isHider = me?.role == "hider" && !isSpectator

    // Spectator Free-Roam Camera State
    var freeRoamX by remember { mutableFloatStateOf(0f) }
    var freeRoamY by remember { mutableFloatStateOf(0f) }
    var cameraZoom by remember { mutableFloatStateOf(1f) }
    var followedPlayerUid by remember { mutableStateOf<String?>(null) }

    // Transition toast when a player first enters Spectator Mode
    var showSpectatorTransitionBanner by remember { mutableStateOf(false) }
    LaunchedEffect(isSpectator) {
        if (isSpectator) {
            showSpectatorTransitionBanner = true
            delay(4200L)
            showSpectatorTransitionBanner = false
        }
    }

    // Compute camera center in metric world coordinates
    val (cameraX, cameraY) = if (isSpectator) {
        val target = players.find { it.userId == followedPlayerUid && !it.isSpectator }
        if (target != null) {
            val (tx, ty) = GeoMath.toLocalMeters(lobby.zoneCenterLat, lobby.zoneCenterLng, target.lat, target.lng)
            freeRoamX = tx
            freeRoamY = ty
            tx to ty
        } else {
            freeRoamX to freeRoamY
        }
    } else {
        val (mx, my) = GeoMath.toLocalMeters(lobby.zoneCenterLat, lobby.zoneCenterLng, localLocation.lat, localLocation.lng)
        mx to my
    }

    val remainingSec = lobby.remainingPhaseSeconds(nowMs)
    val currentZoneRadius = lobby.currentZoneRadiusMeters(nowMs)
    val activeHiders = players.filter { it.role == "hider" && !it.isSpectator }
    val spectatorsCount = players.count { it.isSpectator }

    // Nearby claimable power-up for active players
    val nearbyPowerUp = if (!isSpectator) {
        powerUps.firstOrNull { pu ->
            !pu.isClaimed &&
                GeoMath.distanceMeters(localLocation.lat, localLocation.lng, pu.lat, pu.lng) <=
                AbilityConfig.POWERUP_PICKUP_RADIUS_METERS
        }
    } else null

    // Tag-able hider for active Seeker during Hunt Phase
    val taggableHider = if (isSeeker && lobby.status == "hunting") {
        activeHiders.firstOrNull { hider ->
            !hider.isCloaked(nowMs) &&
                GeoMath.distanceMeters(localLocation.lat, localLocation.lng, hider.lat, hider.lng) <=
                AbilityConfig.TAG_RADIUS_METERS
        }
    } else null

    Box(modifier = Modifier.fillMaxSize().background(ObsidianBg)) {
        // 1. Live Tactical Map
        TacticalMapCanvas(
            lobby = lobby,
            players = players,
            powerUps = powerUps,
            decoys = decoys,
            myPlayer = me,
            myLat = localLocation.lat,
            myLng = localLocation.lng,
            nowMs = nowMs,
            cameraCenterXMeters = cameraX,
            cameraCenterYMeters = cameraY,
            cameraZoom = cameraZoom,
            allowFreeRoamGestures = isSpectator,
            onCameraTransform = { panX, panY, zoomFactor ->
                if (isSpectator) {
                    followedPlayerUid = null
                    freeRoamX = (freeRoamX + panX).coerceIn(-1200f, 1200f)
                    freeRoamY = (freeRoamY + panY).coerceIn(-1200f, 1200f)
                    cameraZoom = (cameraZoom * zoomFactor).coerceIn(0.45f, 4.0f)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Tactical HUD Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = ObsidianSurface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (isSpectator) SpectatorCyan.copy(alpha = 0.7f) else CrimsonPrimary.copy(alpha = 0.5f),
                        RoundedCornerShape(18.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        val phaseTitle = when {
                            isSpectator -> "SPECTATOR · FREE-ROAM"
                            lobby.status == "hiding" -> "HIDE HEAD-START"
                            lobby.status == "hunting" -> "HUNT IN PROGRESS"
                            else -> "MATCH OVER"
                        }
                        Text(
                            text = phaseTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSpectator) SpectatorCyan else CrimsonBright,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatTimer(remainingSec),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HudBadge(label = "HIDERS", value = "${activeHiders.size}", color = AmberTactical)
                        HudBadge(label = "SPEC", value = "$spectatorsCount", color = SpectatorCyan)
                        HudBadge(label = "ZONE", value = "${currentZoneRadius.roundToInt()}m", color = CrimsonBright)
                        IconButton(
                            onClick = onLeaveMatch,
                            modifier = Modifier.testTag("leave_match_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "Leave match",
                                tint = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // Spectator Non-Interference Lock Pill
            if (isSpectator) {
                Surface(
                    color = Color(0xFF0A2A33).copy(alpha = 0.94f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SpectatorCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = SpectatorCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SPECTATOR MODE · Read-only free camera. Cannot interact with active players.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFCFFAFE)
                        )
                    }
                }
            }

            // Out-of-zone warning for active hiders
            if (isHider && outOfZoneTicks > 0) {
                Surface(
                    color = CrimsonPrimary,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "OUTSIDE REDLINE ZONE! Return within ${(8 - outOfZoneTicks / 2).coerceAtLeast(1)}s!",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // 3. Seeker Head-Start Wait Overlay (30–120s)
        AnimatedVisibility(
            visible = isSeeker && lobby.status == "hiding",
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = ObsidianBg.copy(alpha = 0.94f),
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 400.dp)
                    .border(2.dp, CrimsonPrimary, RoundedCornerShape(26.dp))
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = CrimsonBright,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "YOU ARE THE SEEKER",
                        style = MaterialTheme.typography.labelMedium,
                        color = CrimsonBright
                    )
                    Text(
                        text = formatTimer(remainingSec),
                        style = MaterialTheme.typography.displayLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Hold position while hiders scatter. Hunt begins automatically when the timer reaches 00:00.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // 4. Found -> Spectator Transition Banner
        AnimatedVisibility(
            visible = showSpectatorTransitionBanner,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color(0xEE18080D),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .padding(24.dp)
                    .border(2.dp, SpectatorCyan, RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Visibility,
                        contentDescription = null,
                        tint = SpectatorCyan,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = "YOU WERE FOUND",
                        style = MaterialTheme.typography.headlineMedium,
                        color = CrimsonBright,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Free-Roam Spectator Mode Active Until Match End.\nDrag & pinch map to explore freely.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // 5. Bottom Role Controls Dock
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Active Player Contextual Prompts (Claim Power-Up / Tag Hider)
            if (nearbyPowerUp != null && !isSpectator) {
                Button(
                    onClick = { onClaimPowerUp(nearbyPowerUp) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SafeEmerald),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Collect ${nearbyPowerUp.type.uppercase().replace('_', ' ')} Power-Up",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (taggableHider != null && isSeeker) {
                Button(
                    onClick = { onTagHider(taggableHider.userId) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBright),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("tag_hider_button")
                ) {
                    Icon(Icons.Filled.TouchApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TAG ${taggableHider.displayName.uppercase()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Role-Specific Control Surface
            Surface(
                color = ObsidianSurface.copy(alpha = 0.94f),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (isSpectator) SpectatorCyan.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(22.dp)
                    )
            ) {
                if (isSpectator) {
                    SpectatorControlDock(
                        players = players.filter { !it.isSpectator },
                        followedUid = followedPlayerUid,
                        onSelectFollow = { uid -> followedPlayerUid = uid },
                        onZoomIn = { cameraZoom = (cameraZoom * 1.25f).coerceAtMost(4.0f) },
                        onZoomOut = { cameraZoom = (cameraZoom * 0.8f).coerceAtLeast(0.45f) },
                        onResetCamera = {
                            followedPlayerUid = null
                            freeRoamX = 0f
                            freeRoamY = 0f
                            cameraZoom = 1f
                        }
                    )
                } else if (me != null) {
                    ActivePlayerControlDock(
                        player = me,
                        lobby = lobby,
                        nowMs = nowMs,
                        isMicEnabled = isMicEnabled,
                        onToggleMic = onToggleMic,
                        onActivateCloak = onActivateCloak,
                        onActivateSpeed = onActivateSpeed,
                        onDeployDecoy = onDeployDecoy,
                        onActivateReveal = onActivateReveal,
                        onActivateSonar = onActivateSonar
                    )
                }
            }
        }

        // 6. Match Finished Overlay
        if (lobby.status == "finished") {
            MatchFinishedOverlay(
                lobby = lobby,
                players = players,
                onExitLobby = onLeaveMatch
            )
        }
    }
}

@Composable
private fun ActivePlayerControlDock(
    player: LobbyPlayer,
    lobby: GameLobby,
    nowMs: Long,
    isMicEnabled: Boolean,
    onToggleMic: () -> Unit,
    onActivateCloak: () -> Unit,
    onActivateSpeed: () -> Unit,
    onDeployDecoy: () -> Unit,
    onActivateReveal: () -> Unit,
    onActivateSonar: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Proximity Mic Toggle
        Surface(
            onClick = onToggleMic,
            enabled = lobby.voiceEnabled,
            color = if (isMicEnabled) SafeEmerald.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .size(width = 64.dp, height = 64.dp)
                .border(
                    1.dp,
                    if (isMicEnabled) SafeEmerald else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(16.dp)
                )
                .testTag("mic_toggle_button")
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isMicEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = "Toggle proximity voice",
                    tint = if (isMicEnabled) SafeEmerald else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = if (isMicEnabled) "LIVE" else "MUTED",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMicEnabled) SafeEmerald else Color.White.copy(alpha = 0.7f)
                )
            }
        }

        if (player.role == "hider") {
            AbilityButton(
                label = "Veil",
                icon = Icons.Filled.VisibilityOff,
                cooldownRemainingSec = cooldownSec(nowMs, player.lastCloakAtMs, AbilityConfig.CLOAK_COOLDOWN_MS),
                isActive = player.isCloaked(nowMs),
                accent = SpectatorCyan,
                onClick = onActivateCloak,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ability_cloak_button")
            )
            AbilityButton(
                label = "Surge",
                icon = Icons.AutoMirrored.Filled.DirectionsRun,
                cooldownRemainingSec = cooldownSec(nowMs, player.lastSpeedAtMs, AbilityConfig.SPEED_COOLDOWN_MS),
                isActive = player.isSpeedBoosted(nowMs),
                accent = SafeEmerald,
                onClick = onActivateSpeed,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ability_speed_button")
            )
            AbilityButton(
                label = "Decoy",
                icon = Icons.Filled.FlashOn,
                cooldownRemainingSec = cooldownSec(nowMs, player.lastDecoyAtMs, AbilityConfig.DECOY_COOLDOWN_MS),
                isActive = false,
                accent = AmberTactical,
                onClick = onDeployDecoy,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ability_decoy_button")
            )
        } else {
            val huntLive = lobby.status == "hunting"
            AbilityButton(
                label = "Reveal",
                icon = Icons.Filled.Visibility,
                cooldownRemainingSec = cooldownSec(nowMs, player.lastRevealAtMs, AbilityConfig.REVEAL_COOLDOWN_MS),
                isActive = player.isRevealActive(nowMs),
                enabled = huntLive,
                accent = AmberTactical,
                onClick = onActivateReveal,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ability_reveal_button")
            )
            AbilityButton(
                label = "Sonar",
                icon = Icons.Filled.Radar,
                cooldownRemainingSec = cooldownSec(nowMs, player.lastSonarAtMs, AbilityConfig.SONAR_COOLDOWN_MS),
                isActive = player.isSonarActive(nowMs),
                enabled = huntLive,
                accent = CrimsonBright,
                onClick = onActivateSonar,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ability_sonar_button")
            )
        }
    }
}

@Composable
private fun SpectatorControlDock(
    players: List<LobbyPlayer>,
    followedUid: String?,
    onSelectFollow: (String?) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetCamera: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.MicOff,
                    contentDescription = null,
                    tint = SpectatorCyan,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "FREE-ROAM CAMERA · MIC LOCKED",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpectatorCyan
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onZoomOut, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Remove, contentDescription = "Zoom out", tint = Color.White)
                }
                IconButton(onClick = onZoomIn, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = "Zoom in", tint = Color.White)
                }
                IconButton(
                    onClick = onResetCamera,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("spectator_reset_camera_button")
                ) {
                    Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Reset free-roam camera", tint = SpectatorCyan)
                }
            }
        }

        // Camera Focus Selector: Free Roam or Lock Onto Any Active Player
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = followedUid == null,
                onClick = { onSelectFollow(null) },
                label = { Text("Free Roam") },
                leadingIcon = {
                    Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SpectatorCyan.copy(alpha = 0.25f),
                    selectedLabelColor = SpectatorCyan
                )
            )
            players.forEach { p ->
                FilterChip(
                    selected = followedUid == p.userId,
                    onClick = { onSelectFollow(p.userId) },
                    label = {
                        Text(
                            text = p.displayName + if (p.role == "seeker") " (S)" else "",
                            maxLines = 1
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CrimsonPrimary.copy(alpha = 0.28f),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }
    }
}

@Composable
private fun AbilityButton(
    label: String,
    icon: ImageVector,
    cooldownRemainingSec: Long,
    isActive: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val ready = cooldownRemainingSec == 0L && enabled
    Surface(
        onClick = onClick,
        enabled = ready,
        color = when {
            isActive -> accent.copy(alpha = 0.28f)
            ready -> MaterialTheme.colorScheme.surfaceVariant
            else -> ObsidianBg.copy(alpha = 0.7f)
        },
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .height(64.dp)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive || ready) accent.copy(alpha = 0.85f) else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (ready || isActive) accent else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = when {
                    isActive -> "ACTIVE"
                    cooldownRemainingSec > 0 -> "${cooldownRemainingSec}s"
                    else -> label
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (ready || isActive) Color.White else Color.White.copy(alpha = 0.45f),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun HudBadge(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f))
    }
}

@Composable
private fun MatchFinishedOverlay(
    lobby: GameLobby,
    players: List<LobbyPlayer>,
    onExitLobby: () -> Unit
) {
    val seekersWon = lobby.winnerTeam == "seekers"
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = ObsidianSurface,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 440.dp)
                .border(2.dp, if (seekersWon) CrimsonBright else SafeEmerald, RoundedCornerShape(28.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = if (seekersWon) "SEEKER VICTORY" else "HIDERS SURVIVED",
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (seekersWon) CrimsonBright else SafeEmerald,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (seekersWon) {
                        "All hiders were found and transitioned to Spectator Mode."
                    } else {
                        "Time ran out before the Seeker could clear the Redline zone."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    players.forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(ObsidianBg)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AvatarMiniBadge(loadout = p.toAvatarLoadout(), badgeSize = 36.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = p.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = when {
                                    p.userId == lobby.seekerUid -> "SEEKER"
                                    p.isSpectator -> "FOUND"
                                    else -> "SURVIVED"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    p.userId == lobby.seekerUid -> CrimsonBright
                                    p.isSpectator -> SpectatorCyan
                                    else -> SafeEmerald
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Button(
                    onClick = onExitLobby,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text("Return to Home", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun cooldownSec(nowMs: Long, lastUsedMs: Long, cooldownMs: Long): Long {
    if (lastUsedMs <= 0L) return 0L
    val rem = cooldownMs - (nowMs - lastUsedMs)
    return if (rem <= 0L) 0L else (rem / 1000L) + 1L
}

private fun formatTimer(totalSec: Long): String {
    val m = totalSec / 60L
    val s = totalSec % 60L
    return "%02d:%02d".format(m, s)
}
