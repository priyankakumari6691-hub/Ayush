package com.example.ui.avatar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.model.AvatarAccessory
import com.example.data.model.AvatarHat
import com.example.data.model.AvatarLoadout
import com.example.data.model.AvatarModel
import com.example.data.model.AvatarPants
import com.example.data.model.AvatarShirt

fun parseHexColor(hex: String, fallback: Color = Color(0xFFE11D48)): Color {
    return try {
        val clean = hex.removePrefix("#")
        val parsed = clean.toLong(16)
        when (clean.length) {
            6 -> Color(0xFF000000L or parsed)
            8 -> Color(parsed)
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}

/**
 * Full-body minimalist tactical avatar renderer used in Avatar Studio and Pre-Game Lobby.
 */
@Composable
fun AvatarPreviewCanvas(
    loadout: AvatarLoadout,
    modifier: Modifier = Modifier,
    roleTagColor: Color? = null
) {
    val accent = parseHexColor(loadout.colorHex)
    val model = AvatarModel.fromId(loadout.model)
    val hat = AvatarHat.fromId(loadout.hat)
    val shirt = AvatarShirt.fromId(loadout.shirt)
    val pants = AvatarPants.fromId(loadout.pants)
    val accessory = AvatarAccessory.fromId(loadout.accessory)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // Floor tactical ring
        val ringColor = roleTagColor ?: accent
        drawOval(
            color = ringColor.copy(alpha = 0.16f),
            topLeft = Offset(cx - w * 0.28f, h * 0.85f),
            size = Size(w * 0.56f, h * 0.10f)
        )
        drawOval(
            color = ringColor.copy(alpha = 0.7f),
            topLeft = Offset(cx - w * 0.25f, h * 0.865f),
            size = Size(w * 0.50f, h * 0.07f),
            style = Stroke(width = 2.5f)
        )

        // Radar Pack accessory (drawn behind torso)
        if (accessory == AvatarAccessory.RADAR_PACK) {
            drawRoundRect(
                color = Color(0xFF272226),
                topLeft = Offset(cx - w * 0.22f, h * 0.36f),
                size = Size(w * 0.44f, h * 0.24f),
                cornerRadius = CornerRadius(8f, 8f)
            )
            // Antenna
            drawLine(
                color = accent,
                start = Offset(cx + w * 0.17f, h * 0.36f),
                end = Offset(cx + w * 0.19f, h * 0.20f),
                strokeWidth = 4f
            )
            drawCircle(
                color = accent,
                radius = 4.5f,
                center = Offset(cx + w * 0.19f, h * 0.20f)
            )
        }

        // Body proportions vary by Character Model
        val shoulderWidth = when (model) {
            AvatarModel.STRIKER -> w * 0.32f
            AvatarModel.PHANTOM -> w * 0.28f
            AvatarModel.VANGUARD -> w * 0.39f
            AvatarModel.CIPHER -> w * 0.30f
        }
        val waistWidth = when (model) {
            AvatarModel.VANGUARD -> w * 0.30f
            AvatarModel.CIPHER -> w * 0.22f
            else -> w * 0.25f
        }

        // Pants / Legs
        val pantsColor = when (pants) {
            AvatarPants.CHARCOAL_JOGGERS -> Color(0xFF2A262B)
            AvatarPants.TACTICAL_CARGOS -> Color(0xFF1F1C21)
            AvatarPants.CRIMSON_TRACK -> Color(0xFF7F1D2E)
            AvatarPants.OBSIDIAN_SLIM -> Color(0xFF121014)
        }
        val legWidth = waistWidth * 0.42f
        val legTop = h * 0.60f
        val legHeight = h * 0.27f

        // Left & right legs
        drawRoundRect(
            color = pantsColor,
            topLeft = Offset(cx - waistWidth * 0.48f, legTop),
            size = Size(legWidth, legHeight),
            cornerRadius = CornerRadius(10f, 10f)
        )
        drawRoundRect(
            color = pantsColor,
            topLeft = Offset(cx + waistWidth * 0.48f - legWidth, legTop),
            size = Size(legWidth, legHeight),
            cornerRadius = CornerRadius(10f, 10f)
        )
        // Track stripe or cargo pocket detail
        if (pants == AvatarPants.CRIMSON_TRACK) {
            drawLine(
                color = accent,
                start = Offset(cx - waistWidth * 0.46f, legTop + 6f),
                end = Offset(cx - waistWidth * 0.46f, legTop + legHeight - 6f),
                strokeWidth = 3f
            )
            drawLine(
                color = accent,
                start = Offset(cx + waistWidth * 0.46f, legTop + 6f),
                end = Offset(cx + waistWidth * 0.46f, legTop + legHeight - 6f),
                strokeWidth = 3f
            )
        } else if (pants == AvatarPants.TACTICAL_CARGOS) {
            drawRoundRect(
                color = accent.copy(alpha = 0.65f),
                topLeft = Offset(cx - waistWidth * 0.52f, legTop + legHeight * 0.35f),
                size = Size(legWidth * 0.55f, legHeight * 0.25f),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }

        // Shirt / Torso
        val shirtColor = when (shirt) {
            AvatarShirt.CRIMSON_TEE -> accent
            AvatarShirt.OBSIDIAN_HOODIE -> Color(0xFF1E1A20)
            AvatarShirt.SCARLET_JACKET -> Color(0xFF9F1239)
            AvatarShirt.STEALTH_VEST -> Color(0xFF282229)
        }
        val torsoTop = h * 0.35f
        val torsoBottom = h * 0.62f
        val torsoPath = Path().apply {
            moveTo(cx - shoulderWidth / 2f, torsoTop)
            lineTo(cx + shoulderWidth / 2f, torsoTop)
            lineTo(cx + waistWidth / 2f, torsoBottom)
            lineTo(cx - waistWidth / 2f, torsoBottom)
            close()
        }
        drawPath(path = torsoPath, color = shirtColor)
        drawPath(path = torsoPath, color = Color.White.copy(alpha = 0.14f), style = Stroke(width = 2f))

        // Shirt accents
        when (shirt) {
            AvatarShirt.SCARLET_JACKET -> {
                // Center zipper & collar stripe
                drawLine(
                    color = Color.White.copy(alpha = 0.85f),
                    start = Offset(cx, torsoTop),
                    end = Offset(cx, torsoBottom),
                    strokeWidth = 2.5f
                )
            }
            AvatarShirt.STEALTH_VEST -> {
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(cx - waistWidth * 0.36f, torsoTop + (torsoBottom - torsoTop) * 0.22f),
                    size = Size(waistWidth * 0.72f, (torsoBottom - torsoTop) * 0.45f),
                    cornerRadius = CornerRadius(6f, 6f),
                    style = Stroke(width = 2.5f)
                )
            }
            AvatarShirt.OBSIDIAN_HOODIE -> {
                drawCircle(
                    color = accent,
                    radius = w * 0.032f,
                    center = Offset(cx, torsoTop + (torsoBottom - torsoTop) * 0.38f)
                )
            }
            AvatarShirt.CRIMSON_TEE -> {
                drawLine(
                    color = Color.White.copy(alpha = 0.7f),
                    start = Offset(cx - shoulderWidth * 0.22f, torsoTop + 14f),
                    end = Offset(cx + shoulderWidth * 0.22f, torsoTop + 14f),
                    strokeWidth = 2.5f
                )
            }
        }

        // Arms
        val armColor = if (shirt == AvatarShirt.CRIMSON_TEE) Color(0xFFD6C2BA) else shirtColor
        drawRoundRect(
            color = armColor,
            topLeft = Offset(cx - shoulderWidth / 2f - w * 0.08f, torsoTop + 2f),
            size = Size(w * 0.07f, h * 0.22f),
            cornerRadius = CornerRadius(8f, 8f)
        )
        drawRoundRect(
            color = armColor,
            topLeft = Offset(cx + shoulderWidth / 2f + w * 0.01f, torsoTop + 2f),
            size = Size(w * 0.07f, h * 0.22f),
            cornerRadius = CornerRadius(8f, 8f)
        )

        // Scarf accessory
        if (accessory == AvatarAccessory.SCARF) {
            drawRoundRect(
                color = accent,
                topLeft = Offset(cx - waistWidth * 0.42f, h * 0.32f),
                size = Size(waistWidth * 0.84f, h * 0.06f),
                cornerRadius = CornerRadius(8f, 8f)
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.9f),
                topLeft = Offset(cx + waistWidth * 0.10f, h * 0.36f),
                size = Size(w * 0.07f, h * 0.14f),
                cornerRadius = CornerRadius(6f, 6f)
            )
        }

        // Head
        val headRadius = when (model) {
            AvatarModel.CIPHER -> w * 0.11f
            AvatarModel.VANGUARD -> w * 0.125f
            else -> w * 0.118f
        }
        val headCenter = Offset(cx, h * 0.24f)

        // Phantom cloak hood aura
        if (model == AvatarModel.PHANTOM || hat == AvatarHat.TACTICAL_HOOD) {
            drawCircle(
                color = Color(0xFF1A161C),
                radius = headRadius * 1.24f,
                center = headCenter
            )
            drawCircle(
                color = accent,
                radius = headRadius * 1.24f,
                center = headCenter,
                style = Stroke(width = 2.5f)
            )
        }

        drawCircle(
            color = Color(0xFFE7D6CF),
            radius = headRadius,
            center = headCenter
        )

        // Eyes / Visor accessory
        if (accessory == AvatarAccessory.RED_VISOR) {
            drawRoundRect(
                color = accent,
                topLeft = Offset(cx - headRadius * 0.82f, headCenter.y - headRadius * 0.28f),
                size = Size(headRadius * 1.64f, headRadius * 0.46f),
                cornerRadius = CornerRadius(6f, 6f)
            )
            drawLine(
                color = Color.White.copy(alpha = 0.85f),
                start = Offset(cx - headRadius * 0.6f, headCenter.y - headRadius * 0.08f),
                end = Offset(cx + headRadius * 0.3f, headCenter.y - headRadius * 0.08f),
                strokeWidth = 2f
            )
        } else {
            drawCircle(
                color = Color(0xFF151216),
                radius = headRadius * 0.14f,
                center = Offset(cx - headRadius * 0.34f, headCenter.y - headRadius * 0.05f)
            )
            drawCircle(
                color = Color(0xFF151216),
                radius = headRadius * 0.14f,
                center = Offset(cx + headRadius * 0.34f, headCenter.y - headRadius * 0.05f)
            )
        }

        // Comms headset accessory
        if (accessory == AvatarAccessory.COMMS_HEADSET) {
            drawRoundRect(
                color = accent,
                topLeft = Offset(cx - headRadius * 1.18f, headCenter.y - headRadius * 0.35f),
                size = Size(headRadius * 0.28f, headRadius * 0.7f),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawLine(
                color = Color.White,
                start = Offset(cx - headRadius * 1.0f, headCenter.y + headRadius * 0.25f),
                end = Offset(cx - headRadius * 0.3f, headCenter.y + headRadius * 0.55f),
                strokeWidth = 2.5f
            )
        }

        // Hats
        drawAvatarHat(hat, headCenter, headRadius, accent)
    }
}

private fun DrawScope.drawAvatarHat(
    hat: AvatarHat,
    headCenter: Offset,
    headRadius: Float,
    accent: Color
) {
    when (hat) {
        AvatarHat.NONE -> Unit
        AvatarHat.CAP -> {
            drawArc(
                color = accent,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(headCenter.x - headRadius * 1.05f, headCenter.y - headRadius * 1.15f),
                size = Size(headRadius * 2.1f, headRadius * 1.4f)
            )
            // Brim
            drawRoundRect(
                color = Color(0xFF1B171D),
                topLeft = Offset(headCenter.x - headRadius * 0.2f, headCenter.y - headRadius * 0.52f),
                size = Size(headRadius * 1.45f, headRadius * 0.24f),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
        AvatarHat.BEANIE -> {
            drawArc(
                color = Color(0xFF9F1239),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(headCenter.x - headRadius * 1.04f, headCenter.y - headRadius * 1.25f),
                size = Size(headRadius * 2.08f, headRadius * 1.55f)
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(headCenter.x - headRadius * 1.06f, headCenter.y - headRadius * 0.62f),
                size = Size(headRadius * 2.12f, headRadius * 0.30f),
                cornerRadius = CornerRadius(6f, 6f)
            )
        }
        AvatarHat.TACTICAL_HOOD -> {
            drawArc(
                color = accent.copy(alpha = 0.9f),
                startAngle = 170f,
                sweepAngle = 200f,
                useCenter = false,
                topLeft = Offset(headCenter.x - headRadius * 1.25f, headCenter.y - headRadius * 1.25f),
                size = Size(headRadius * 2.5f, headRadius * 2.3f),
                style = Stroke(width = 5f)
            )
        }
        AvatarHat.VISOR_HELM -> {
            drawRoundRect(
                color = Color(0xFF201B23),
                topLeft = Offset(headCenter.x - headRadius * 1.15f, headCenter.y - headRadius * 1.18f),
                size = Size(headRadius * 2.3f, headRadius * 1.55f),
                cornerRadius = CornerRadius(headRadius * 0.8f, headRadius * 0.8f)
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(headCenter.x - headRadius * 0.95f, headCenter.y - headRadius * 0.38f),
                size = Size(headRadius * 1.9f, headRadius * 0.42f),
                cornerRadius = CornerRadius(6f, 6f)
            )
        }
    }
}

/**
 * Compact head-and-shoulders badge rendered on player roster rows and in-game HUD chips.
 */
@Composable
fun AvatarMiniBadge(
    loadout: AvatarLoadout,
    badgeSize: Dp = 42.dp,
    ringColor: Color = Color(0xFFE11D48)
) {
    AvatarPreviewCanvas(
        loadout = loadout,
        roleTagColor = ringColor,
        modifier = Modifier.size(badgeSize)
    )
}

/**
 * DrawScope helper so customized avatars are visually represented directly on the Live Tactical Map Canvas!
 */
fun DrawScope.drawMapAvatarToken(
    center: Offset,
    radius: Float,
    loadout: AvatarLoadout,
    roleRingColor: Color,
    alpha: Float = 1f
) {
    val accent = parseHexColor(loadout.colorHex)
    val shirtColor = when (AvatarShirt.fromId(loadout.shirt)) {
        AvatarShirt.CRIMSON_TEE -> accent
        AvatarShirt.OBSIDIAN_HOODIE -> Color(0xFF241F26)
        AvatarShirt.SCARLET_JACKET -> Color(0xFF9F1239)
        AvatarShirt.STEALTH_VEST -> Color(0xFF332B34)
    }

    // Outer role ring
    drawCircle(
        color = roleRingColor.copy(alpha = 0.26f * alpha),
        radius = radius * 1.45f,
        center = center
    )
    drawCircle(
        color = shirtColor.copy(alpha = alpha),
        radius = radius,
        center = center
    )
    drawCircle(
        color = roleRingColor.copy(alpha = alpha),
        radius = radius,
        center = center,
        style = Stroke(width = 3f)
    )

    // Mini head + hat/visor silhouette inside the map token
    val headR = radius * 0.42f
    val headCenter = Offset(center.x, center.y - radius * 0.08f)
    drawCircle(
        color = Color(0xFFE7D6CF).copy(alpha = alpha),
        radius = headR,
        center = headCenter
    )
    if (AvatarAccessory.fromId(loadout.accessory) == AvatarAccessory.RED_VISOR) {
        drawRoundRect(
            color = accent.copy(alpha = alpha),
            topLeft = Offset(headCenter.x - headR * 0.8f, headCenter.y - headR * 0.25f),
            size = Size(headR * 1.6f, headR * 0.42f),
            cornerRadius = CornerRadius(3f, 3f)
        )
    }
    when (AvatarHat.fromId(loadout.hat)) {
        AvatarHat.CAP, AvatarHat.BEANIE -> {
            drawArc(
                color = accent.copy(alpha = alpha),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(headCenter.x - headR * 1.05f, headCenter.y - headR * 1.1f),
                size = Size(headR * 2.1f, headR * 1.3f)
            )
        }
        AvatarHat.VISOR_HELM -> {
            drawCircle(
                color = Color(0xFF1B171D).copy(alpha = alpha),
                radius = headR * 1.15f,
                center = headCenter,
                style = Stroke(width = 2.5f)
            )
        }
        AvatarHat.TACTICAL_HOOD -> {
            drawCircle(
                color = accent.copy(alpha = alpha),
                radius = headR * 1.2f,
                center = headCenter,
                style = Stroke(width = 2.5f)
            )
        }
        AvatarHat.NONE -> Unit
    }
}
