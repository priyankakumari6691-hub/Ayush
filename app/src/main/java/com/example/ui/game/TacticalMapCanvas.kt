package com.example.ui.game

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.example.data.model.AbilityConfig
import com.example.data.model.ArenaPowerUp
import com.example.data.model.DecoyBeacon
import com.example.data.model.GameLobby
import com.example.data.model.GeoMath
import com.example.data.model.LobbyPlayer
import com.example.ui.avatar.drawMapAvatarToken
import com.example.ui.theme.AmberTactical
import com.example.ui.theme.CrimsonBright
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SafeEmerald
import com.example.ui.theme.SpectatorCyan
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TacticalMapCanvas(
    lobby: GameLobby,
    players: List<LobbyPlayer>,
    powerUps: List<ArenaPowerUp>,
    decoys: List<DecoyBeacon>,
    myPlayer: LobbyPlayer?,
    myLat: Double,
    myLng: Double,
    nowMs: Long,
    cameraCenterXMeters: Float,
    cameraCenterYMeters: Float,
    cameraZoom: Float,
    allowFreeRoamGestures: Boolean,
    onCameraTransform: (panMetersX: Float, panMetersY: Float, zoomFactor: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val infiniteTransition = rememberInfiniteTransition(label = "mapAnim")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    val seeker = players.find { it.userId == lobby.seekerUid }
    val currentZoneRadius = lobby.currentZoneRadiusMeters(nowMs).toFloat()
    val isSpectator = myPlayer?.isSpectator == true
    val isSeeker = myPlayer?.role == "seeker" && !isSpectator

    val gestureModifier = if (allowFreeRoamGestures) {
        Modifier.pointerInput(cameraZoom, lobby.initialZoneRadiusMeters) {
            detectTransformGestures { _, pan, zoom, _ ->
                val minDim = minOf(size.width, size.height).toFloat().coerceAtLeast(1f)
                val basePxPerMeter = (minDim / (lobby.initialZoneRadiusMeters.toFloat() * 2.5f))
                val pxPerMeter = (basePxPerMeter * cameraZoom).coerceAtLeast(0.05f)
                // Dragging right moves camera left in world meters; dragging down moves camera up (+north)
                onCameraTransform(
                    -pan.x / pxPerMeter,
                    pan.y / pxPerMeter,
                    zoom
                )
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .background(ObsidianBg)
            .then(gestureModifier)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val basePxPerMeter = (size.minDimension / (lobby.initialZoneRadiusMeters.toFloat() * 2.5f))
            val pxPerMeter = basePxPerMeter * cameraZoom

            fun worldToScreen(wx: Float, wy: Float): Offset {
                val sx = size.width / 2f + (wx - cameraCenterXMeters) * pxPerMeter
                val sy = size.height / 2f - (wy - cameraCenterYMeters) * pxPerMeter
                return Offset(sx, sy)
            }

            // 1. Tactical Metric Grid
            val gridStepMeters = 50f
            val visibleHalfWidthM = (size.width / 2f) / pxPerMeter
            val visibleHalfHeightM = (size.height / 2f) / pxPerMeter
            val minGx = (((cameraCenterXMeters - visibleHalfWidthM) / gridStepMeters).toInt() - 1) * gridStepMeters
            val maxGx = (((cameraCenterXMeters + visibleHalfWidthM) / gridStepMeters).toInt() + 1) * gridStepMeters
            val minGy = (((cameraCenterYMeters - visibleHalfHeightM) / gridStepMeters).toInt() - 1) * gridStepMeters
            val maxGy = (((cameraCenterYMeters + visibleHalfHeightM) / gridStepMeters).toInt() + 1) * gridStepMeters

            var gx = minGx
            while (gx <= maxGx) {
                val sx = worldToScreen(gx, 0f).x
                drawLine(
                    color = Color(0xFF1D171B),
                    start = Offset(sx, 0f),
                    end = Offset(sx, size.height),
                    strokeWidth = 1f
                )
                gx += gridStepMeters
            }
            var gy = minGy
            while (gy <= maxGy) {
                val sy = worldToScreen(0f, gy).y
                drawLine(
                    color = Color(0xFF1D171B),
                    start = Offset(0f, sy),
                    end = Offset(size.width, sy),
                    strokeWidth = 1f
                )
                gy += gridStepMeters
            }

            // 2. Arena Center & Shrinking Redline Safe Zone
            val centerScreen = worldToScreen(0f, 0f)
            val zoneRadiusPx = currentZoneRadius * pxPerMeter
            val finalRadiusPx = lobby.finalZoneRadiusMeters.toFloat() * pxPerMeter

            // Danger outer ring glow
            drawCircle(
                color = CrimsonPrimary.copy(alpha = 0.08f),
                radius = zoneRadiusPx + 600f,
                center = centerScreen
            )
            // Safe interior fill
            drawCircle(
                color = ObsidianBg,
                radius = zoneRadiusPx,
                center = centerScreen
            )
            // Subtle concentric radar rings inside safe zone
            listOf(0.33f, 0.66f).forEach { frac ->
                drawCircle(
                    color = CrimsonPrimary.copy(alpha = 0.14f),
                    radius = zoneRadiusPx * frac,
                    center = centerScreen,
                    style = Stroke(width = 1.5f)
                )
            }
            // Final shrunk target ring
            drawCircle(
                color = Color.White.copy(alpha = 0.25f),
                radius = finalRadiusPx,
                center = centerScreen,
                style = Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                )
            )
            // Active Shrinking Redline Border
            drawCircle(
                color = CrimsonBright,
                radius = zoneRadiusPx,
                center = centerScreen,
                style = Stroke(width = 4.5f)
            )

            // 3. Seeker Sonar & Reveal Visual Waves
            if (seeker != null && lobby.status == "hunting") {
                val (sxM, syM) = GeoMath.toLocalMeters(
                    lobby.zoneCenterLat,
                    lobby.zoneCenterLng,
                    seeker.lat,
                    seeker.lng
                )
                val seekerScreen = worldToScreen(sxM, syM)

                if (seeker.isSonarActive(nowMs)) {
                    val sonarElapsed = ((nowMs - seeker.lastSonarAtMs).toFloat() / AbilityConfig.SONAR_DURATION_MS)
                        .coerceIn(0f, 1f)
                    val sonarR = (AbilityConfig.SONAR_RADIUS_METERS.toFloat() * sonarElapsed) * pxPerMeter
                    drawCircle(
                        color = CrimsonBright.copy(alpha = (1f - sonarElapsed) * 0.85f),
                        radius = sonarR,
                        center = seekerScreen,
                        style = Stroke(width = 5f)
                    )
                }
                if (seeker.isRevealActive(nowMs)) {
                    val revealR = AbilityConfig.REVEAL_RADIUS_METERS.toFloat() * pxPerMeter
                    drawCircle(
                        color = AmberTactical.copy(alpha = 0.12f),
                        radius = revealR,
                        center = seekerScreen
                    )
                    drawCircle(
                        color = AmberTactical.copy(alpha = 0.65f),
                        radius = revealR,
                        center = seekerScreen,
                        style = Stroke(width = 3f)
                    )
                    val rad = Math.toRadians(sweepAngle.toDouble())
                    drawLine(
                        color = AmberTactical.copy(alpha = 0.85f),
                        start = seekerScreen,
                        end = Offset(
                            seekerScreen.x + (revealR * cos(rad)).toFloat(),
                            seekerScreen.y + (revealR * sin(rad)).toFloat()
                        ),
                        strokeWidth = 3f
                    )
                }
            }

            // 4. Collectible Arena Power-Up Drops
            powerUps.filter { !it.isClaimed }.forEach { pu ->
                val (pxM, pyM) = GeoMath.toLocalMeters(
                    lobby.zoneCenterLat,
                    lobby.zoneCenterLng,
                    pu.lat,
                    pu.lng
                )
                val puScreen = worldToScreen(pxM, pyM)
                val puColor = when (pu.type) {
                    "cloak" -> SpectatorCyan
                    "sprint" -> SafeEmerald
                    "decoy" -> AmberTactical
                    else -> CrimsonBright
                }
                val puLabel = when (pu.type) {
                    "cloak" -> "VEIL"
                    "sprint" -> "SURGE"
                    "decoy" -> "DECOY"
                    else -> "SONAR"
                }
                drawCircle(
                    color = puColor.copy(alpha = 0.25f * (1f - pulseProgress * 0.5f)),
                    radius = 20f + pulseProgress * 8f,
                    center = puScreen
                )
                drawCircle(
                    color = puColor,
                    radius = 10f,
                    center = puScreen
                )
                drawLabel(textMeasurer, puLabel, puScreen + Offset(0f, 14f), puColor)
            }

            // 5. Phantom Decoys (Appear as Hider blips to Seeker; marked DECOY for Hiders & Spectators)
            decoys.filter { it.expiresAtMs > nowMs }.forEach { decoy ->
                val (dxM, dyM) = GeoMath.toLocalMeters(
                    lobby.zoneCenterLat,
                    lobby.zoneCenterLng,
                    decoy.lat,
                    decoy.lng
                )
                val decoyScreen = worldToScreen(dxM, dyM)
                drawCircle(
                    color = AmberTactical.copy(alpha = 0.35f),
                    radius = 22f + pulseProgress * 12f,
                    center = decoyScreen,
                    style = Stroke(width = 2.5f)
                )
                drawCircle(
                    color = AmberTactical,
                    radius = 13f,
                    center = decoyScreen
                )
                val label = if (isSeeker) "SIGNAL" else "DECOY"
                drawLabel(textMeasurer, label, decoyScreen + Offset(0f, 18f), AmberTactical)
            }

            // 6. Active Players (with Customized Avatar Tokens!)
            players.filter { !it.isSpectator }.forEach { player ->
                val isSelf = player.userId == myPlayer?.userId
                val pLat = if (isSelf) myLat else player.lat
                val pLng = if (isSelf) myLng else player.lng

                val distToSeeker = if (seeker != null) {
                    GeoMath.distanceMeters(seeker.lat, seeker.lng, pLat, pLng)
                } else Double.MAX_VALUE

                val isCloaked = player.isCloaked(nowMs)
                val isSpeed = player.isSpeedBoosted(nowMs)

                // Determine visibility on map
                val visibleToViewer = when {
                    isSpectator -> true // Spectators see everyone in free-roam!
                    isSelf -> true
                    player.role == "seeker" -> {
                        val seekerDistToMe = GeoMath.distanceMeters(myLat, myLng, pLat, pLng)
                        lobby.status == "hiding" || seekerDistToMe <= 75.0 || player.isSonarActive(nowMs)
                    }
                    isSeeker -> {
                        if (isCloaked) false
                        else {
                            val revealHit = seeker?.isRevealActive(nowMs) == true &&
                                distToSeeker <= AbilityConfig.REVEAL_RADIUS_METERS
                            val sonarHit = seeker?.isSonarActive(nowMs) == true &&
                                distToSeeker <= AbilityConfig.SONAR_RADIUS_METERS
                            val closeSight = distToSeeker <= 35.0
                            val outsideZone = GeoMath.distanceMeters(
                                lobby.zoneCenterLat,
                                lobby.zoneCenterLng,
                                pLat,
                                pLng
                            ) > currentZoneRadius
                            revealHit || sonarHit || closeSight || outsideZone
                        }
                    }
                    else -> true // Fellow hiders see allies
                }

                if (!visibleToViewer) return@forEach

                val (pxM, pyM) = GeoMath.toLocalMeters(lobby.zoneCenterLat, lobby.zoneCenterLng, pLat, pLng)
                val pos = worldToScreen(pxM, pyM)
                val roleColor = if (player.role == "seeker") CrimsonBright else AmberTactical
                val alpha = if (isCloaked) 0.42f else 1f

                // Proximity Voice speaking wave
                if (player.isSpeaking && !player.isMicMuted) {
                    drawCircle(
                        color = SafeEmerald.copy(alpha = 0.55f * (1f - pulseProgress)),
                        radius = 24f + pulseProgress * 22f,
                        center = pos,
                        style = Stroke(width = 3f)
                    )
                }

                // Speed boost aura
                if (isSpeed) {
                    drawCircle(
                        color = SafeEmerald.copy(alpha = 0.35f),
                        radius = 28f,
                        center = pos,
                        style = Stroke(width = 3f)
                    )
                }

                drawMapAvatarToken(
                    center = pos,
                    radius = if (isSelf) 21f else 17f,
                    loadout = player.toAvatarLoadout(),
                    roleRingColor = roleColor,
                    alpha = alpha
                )

                val tagSuffix = buildString {
                    append(player.displayName)
                    if (player.role == "seeker") append(" [SEEKER]")
                    if (isCloaked) append(" (CLOAKED)")
                }
                drawLabel(textMeasurer, tagSuffix, pos + Offset(0f, 24f), roleColor.copy(alpha = alpha))
            }

            // 7. Free-Roam Spectator Crosshair at Camera Center
            if (isSpectator) {
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    color = SpectatorCyan.copy(alpha = 0.45f),
                    radius = 18f,
                    center = c,
                    style = Stroke(width = 2f)
                )
                drawLine(
                    color = SpectatorCyan.copy(alpha = 0.6f),
                    start = c - Offset(24f, 0f),
                    end = c + Offset(24f, 0f),
                    strokeWidth = 1.5f
                )
                drawLine(
                    color = SpectatorCyan.copy(alpha = 0.6f),
                    start = c - Offset(0f, 24f),
                    end = c + Offset(0f, 24f),
                    strokeWidth = 1.5f
                )
            }
        }
    }
}

private fun DrawScope.drawLabel(
    textMeasurer: TextMeasurer,
    text: String,
    topCenter: Offset,
    color: Color
) {
    val result = textMeasurer.measure(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    )
    val x = (topCenter.x - result.size.width / 2f).coerceIn(4f, size.width - result.size.width - 4f)
    val y = topCenter.y.coerceIn(4f, size.height - result.size.height - 4f)
    drawText(result, topLeft = Offset(x, y))
}
