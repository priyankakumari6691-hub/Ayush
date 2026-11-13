package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object AbilityConfig {
    const val CLOAK_DURATION_MS = 10_000L
    const val CLOAK_COOLDOWN_MS = 45_000L

    const val SPEED_DURATION_MS = 8_000L
    const val SPEED_COOLDOWN_MS = 35_000L

    const val DECOY_DURATION_MS = 20_000L
    const val DECOY_COOLDOWN_MS = 50_000L

    const val REVEAL_DURATION_MS = 6_000L
    const val REVEAL_COOLDOWN_MS = 40_000L
    const val REVEAL_RADIUS_METERS = 120.0

    const val SONAR_DURATION_MS = 4_000L
    const val SONAR_COOLDOWN_MS = 25_000L
    const val SONAR_RADIUS_METERS = 180.0

    const val POWERUP_PICKUP_RADIUS_METERS = 22.0
    const val POWERUP_RESPAWN_INTERVAL_MS = 30_000L
    const val MAX_ACTIVE_POWERUPS = 6

    const val PROXIMITY_VOICE_RADIUS_METERS = 45.0
    const val TAG_RADIUS_METERS = 18.0
}

enum class AvatarModel(val id: String, val label: String) {
    STRIKER("striker", "Striker"),
    PHANTOM("phantom", "Phantom"),
    VANGUARD("vanguard", "Vanguard"),
    CIPHER("cipher", "Cipher");

    companion object {
        fun fromId(id: String): AvatarModel = entries.find { it.id == id } ?: STRIKER
    }
}

enum class AvatarHat(val id: String, val label: String) {
    NONE("none", "None"),
    CAP("cap", "Tactical Cap"),
    BEANIE("beanie", "Redline Beanie"),
    TACTICAL_HOOD("tactical_hood", "Stealth Hood"),
    VISOR_HELM("visor_helm", "Apex Helm");

    companion object {
        fun fromId(id: String): AvatarHat = entries.find { it.id == id } ?: NONE
    }
}

enum class AvatarShirt(val id: String, val label: String) {
    CRIMSON_TEE("crimson_tee", "Crimson Tee"),
    OBSIDIAN_HOODIE("obsidian_hoodie", "Obsidian Hoodie"),
    SCARLET_JACKET("scarlet_jacket", "Scarlet Jacket"),
    STEALTH_VEST("stealth_vest", "Stealth Vest");

    companion object {
        fun fromId(id: String): AvatarShirt = entries.find { it.id == id } ?: CRIMSON_TEE
    }
}

enum class AvatarPants(val id: String, val label: String) {
    CHARCOAL_JOGGERS("charcoal_joggers", "Charcoal Joggers"),
    TACTICAL_CARGOS("tactical_cargos", "Tactical Cargos"),
    CRIMSON_TRACK("crimson_track", "Crimson Track"),
    OBSIDIAN_SLIM("obsidian_slim", "Obsidian Slim");

    companion object {
        fun fromId(id: String): AvatarPants = entries.find { it.id == id } ?: CHARCOAL_JOGGERS
    }
}

enum class AvatarAccessory(val id: String, val label: String) {
    NONE("none", "None"),
    RED_VISOR("red_visor", "Laser Visor"),
    COMMS_HEADSET("comms_headset", "Comms Rig"),
    SCARF("scarf", "Crimson Scarf"),
    RADAR_PACK("radar_pack", "Radar Pack");

    companion object {
        fun fromId(id: String): AvatarAccessory = entries.find { it.id == id } ?: NONE
    }
}

data class AvatarLoadout(
    val colorHex: String = "#E11D48",
    val model: String = AvatarModel.STRIKER.id,
    val hat: String = AvatarHat.NONE.id,
    val shirt: String = AvatarShirt.CRIMSON_TEE.id,
    val pants: String = AvatarPants.CHARCOAL_JOGGERS.id,
    val accessory: String = AvatarAccessory.NONE.id
)

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "Agent",
    val avatarColorHex: String = "#E11D48",
    val avatarModel: String = "striker",
    val avatarHat: String = "none",
    val avatarShirt: String = "crimson_tee",
    val avatarPants: String = "charcoal_joggers",
    val avatarAccessory: String = "none",
    val gamesPlayed: Long = 0L,
    val gamesWon: Long = 0L,
    val hidersTagged: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toAvatarLoadout(): AvatarLoadout = AvatarLoadout(
        colorHex = avatarColorHex,
        model = avatarModel,
        hat = avatarHat,
        shirt = avatarShirt,
        pants = avatarPants,
        accessory = avatarAccessory
    )
}

@IgnoreExtraProperties
data class GameLobby(
    @DocumentId val id: String = "",
    val hostId: String = "",
    val code: String = "",
    val title: String = "Crimson Sector",
    val status: String = "waiting", // waiting, hiding, hunting, finished
    val maxPlayers: Long = 10L,
    val hideDurationSec: Long = 60L, // 30..120
    val huntDurationSec: Long = 300L, // 120..1800
    val initialZoneRadiusMeters: Double = 250.0,
    val finalZoneRadiusMeters: Double = 25.0,
    val zoneCenterLat: Double = 37.7749,
    val zoneCenterLng: Double = -122.4194,
    val seekerUid: String = "",
    val winnerTeam: String = "", // "", "seekers", "hiders"
    val phaseStartedAtMs: Long = 0L,
    val memberUids: List<String> = emptyList(),
    val voiceEnabled: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun currentZoneRadiusMeters(nowMs: Long): Double {
        if (status != "hunting" || phaseStartedAtMs <= 0L) return initialZoneRadiusMeters
        val elapsedSec = ((nowMs - phaseStartedAtMs).coerceAtLeast(0L)) / 1000.0
        val progress = (elapsedSec / huntDurationSec.coerceAtLeast(1L)).coerceIn(0.0, 1.0)
        return initialZoneRadiusMeters - (initialZoneRadiusMeters - finalZoneRadiusMeters) * progress
    }

    fun remainingPhaseSeconds(nowMs: Long): Long {
        if (phaseStartedAtMs <= 0L) return if (status == "hiding") hideDurationSec else huntDurationSec
        val durationSec = when (status) {
            "hiding" -> hideDurationSec
            "hunting" -> huntDurationSec
            else -> 0L
        }
        val elapsedSec = ((nowMs - phaseStartedAtMs).coerceAtLeast(0L)) / 1000L
        return (durationSec - elapsedSec).coerceAtLeast(0L)
    }
}

@IgnoreExtraProperties
data class LobbyPlayer(
    @DocumentId val id: String = "",
    val userId: String = "",
    val lobbyId: String = "",
    val displayName: String = "Agent",
    val avatarColorHex: String = "#E11D48",
    val avatarModel: String = "striker",
    val avatarHat: String = "none",
    val avatarShirt: String = "crimson_tee",
    val avatarPants: String = "charcoal_joggers",
    val avatarAccessory: String = "none",
    val role: String = "hider", // hider, seeker, spectator
    val status: String = "ready", // ready, active, caught, spectating
    val lat: Double = 37.7749,
    val lng: Double = -122.4194,
    val isMicMuted: Boolean = true,
    val isSpeaking: Boolean = false,
    val cloakedUntilMs: Long = 0L,
    val speedBoostUntilMs: Long = 0L,
    val lastSonarAtMs: Long = 0L,
    val lastRevealAtMs: Long = 0L,
    val lastCloakAtMs: Long = 0L,
    val lastSpeedAtMs: Long = 0L,
    val lastDecoyAtMs: Long = 0L,
    val bonusCharges: Long = 0L,
    val joinedAtMs: Long = 0L,
    val caughtAtMs: Long = 0L,
    val caughtByUid: String = "",
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val isSpectator: Boolean
        get() = role == "spectator" || status == "spectating"

    fun toAvatarLoadout(): AvatarLoadout = AvatarLoadout(
        colorHex = avatarColorHex,
        model = avatarModel,
        hat = avatarHat,
        shirt = avatarShirt,
        pants = avatarPants,
        accessory = avatarAccessory
    )

    fun isCloaked(nowMs: Long): Boolean = cloakedUntilMs > nowMs && !isSpectator
    fun isSpeedBoosted(nowMs: Long): Boolean = speedBoostUntilMs > nowMs && !isSpectator
    fun isSonarActive(nowMs: Long): Boolean =
        lastSonarAtMs > 0L && (nowMs - lastSonarAtMs) < AbilityConfig.SONAR_DURATION_MS
    fun isRevealActive(nowMs: Long): Boolean =
        lastRevealAtMs > 0L && (nowMs - lastRevealAtMs) < AbilityConfig.REVEAL_DURATION_MS
}

@IgnoreExtraProperties
data class ArenaPowerUp(
    @DocumentId val id: String = "",
    val lobbyId: String = "",
    val type: String = "cloak", // cloak, sprint, decoy, sonar_charge
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val spawnedAtMs: Long = 0L,
    val claimedByUid: String = "",
    val claimedAtMs: Long = 0L,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    val isClaimed: Boolean
        get() = claimedByUid.isNotBlank()
}

@IgnoreExtraProperties
data class DecoyBeacon(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val lobbyId: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val expiresAtMs: Long = 0L,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class VoicePacket(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val lobbyId: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val audioBase64: String = "",
    val sentAtMs: Long = 0L,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
)

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(rLat1) * cos(rLat2) * sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Projects (lat, lng) into local metric (eastXMeters, northYMeters) relative to center.
     */
    fun toLocalMeters(
        centerLat: Double,
        centerLng: Double,
        targetLat: Double,
        targetLng: Double
    ): Pair<Float, Float> {
        val northMeters = Math.toRadians(targetLat - centerLat) * EARTH_RADIUS_METERS
        val eastMeters = Math.toRadians(targetLng - centerLng) *
            EARTH_RADIUS_METERS * cos(Math.toRadians(centerLat))
        return eastMeters.toFloat() to northMeters.toFloat()
    }

    /**
     * Offsets a GPS coordinate by distanceMeters along bearingRad (0 = North, PI/2 = East).
     */
    fun offsetCoordinate(
        centerLat: Double,
        centerLng: Double,
        distanceMeters: Double,
        bearingRad: Double
    ): Pair<Double, Double> {
        val northMeters = distanceMeters * cos(bearingRad)
        val eastMeters = distanceMeters * sin(bearingRad)
        val dLat = Math.toDegrees(northMeters / EARTH_RADIUS_METERS)
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)
        val dLng = Math.toDegrees(eastMeters / (EARTH_RADIUS_METERS * cosLat))
        return (centerLat + dLat).coerceIn(-89.9, 89.9) to
            (centerLng + dLng).coerceIn(-179.9, 179.9)
    }
}
