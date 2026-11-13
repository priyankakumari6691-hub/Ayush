package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.data.model.AbilityConfig
import com.example.data.model.ArenaPowerUp
import com.example.data.model.AvatarLoadout
import com.example.data.model.DecoyBeacon
import com.example.data.model.GameLobby
import com.example.data.model.GeoMath
import com.example.data.model.LobbyPlayer
import com.example.data.model.UserProfile
import com.example.data.model.VoicePacket
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID
import kotlin.math.PI
import kotlin.random.Random

class RedlineRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun <T> Flow<T>.withFirestoreErrorHandling(
        operationType: OperationType,
        path: String,
        uid: String
    ): Flow<T> = catch { error ->
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
        throw error
    }

    fun observeUserProfile(uid: String): Flow<UserProfile?> = flow {
        val docRef = db.collection("users").document(uid)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(UserProfile::class.java, ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .withFirestoreErrorHandling(OperationType.GET, docRef.path, uid)
        )
    }

    fun observeMyLobbies(uid: String): Flow<List<GameLobby>> = flow {
        val path = "lobbies"
        emitAll(
            db.collection(path)
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(GameLobby::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .withFirestoreErrorHandling(OperationType.LIST, path, uid)
        )
    }

    fun observeLobby(lobbyId: String): Flow<GameLobby?> = flow {
        val uid = requireUserId()
        val docRef = db.collection("lobbies").document(lobbyId)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(GameLobby::class.java, ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .withFirestoreErrorHandling(OperationType.GET, docRef.path, uid)
        )
    }

    fun observeLobbyPlayers(lobbyId: String): Flow<List<LobbyPlayer>> = flow {
        val uid = requireUserId()
        val path = "lobbies/$lobbyId/players"
        emitAll(
            db.collection("lobbies").document(lobbyId).collection("players")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(LobbyPlayer::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.joinedAtMs }
                }
                .withFirestoreErrorHandling(OperationType.LIST, path, uid)
        )
    }

    fun observePowerUps(lobbyId: String): Flow<List<ArenaPowerUp>> = flow {
        val uid = requireUserId()
        val path = "lobbies/$lobbyId/powerUps"
        emitAll(
            db.collection("lobbies").document(lobbyId).collection("powerUps")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(ArenaPowerUp::class.java, ServerTimestampBehavior.ESTIMATE)
                }
                .withFirestoreErrorHandling(OperationType.LIST, path, uid)
        )
    }

    fun observeDecoys(lobbyId: String): Flow<List<DecoyBeacon>> = flow {
        val uid = requireUserId()
        val path = "lobbies/$lobbyId/decoys"
        emitAll(
            db.collection("lobbies").document(lobbyId).collection("decoys")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(DecoyBeacon::class.java, ServerTimestampBehavior.ESTIMATE)
                }
                .withFirestoreErrorHandling(OperationType.LIST, path, uid)
        )
    }

    fun observeVoicePackets(lobbyId: String): Flow<List<VoicePacket>> = flow {
        val uid = requireUserId()
        val path = "lobbies/$lobbyId/voicePackets"
        emitAll(
            db.collection("lobbies").document(lobbyId).collection("voicePackets")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(VoicePacket::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.sentAtMs }
                }
                .withFirestoreErrorHandling(OperationType.LIST, path, uid)
        )
    }

    suspend fun ensureUserProfile(defaultName: String): Result<UserProfile> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val snap = try {
            docRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
        if (snap.exists()) {
            snap.toObject(UserProfile::class.java, ServerTimestampBehavior.ESTIMATE)
                ?: error("Failed to parse UserProfile")
        } else {
            val cleanName = defaultName.trim().take(30).ifBlank { "Agent_${uid.take(4)}" }
            val payload = mapOf(
                "userId" to uid,
                "displayName" to cleanName,
                "avatarColorHex" to "#E11D48",
                "avatarModel" to "striker",
                "avatarHat" to "none",
                "avatarShirt" to "crimson_tee",
                "avatarPants" to "charcoal_joggers",
                "avatarAccessory" to "none",
                "gamesPlayed" to 0L,
                "gamesWon" to 0L,
                "hidersTagged" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            try {
                docRef.set(payload).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, docRef.path)
                throw e
            }
            UserProfile(id = uid, userId = uid, displayName = cleanName)
        }
    }

    suspend fun updateDisplayName(newName: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val cleanName = newName.trim().take(30)
        require(cleanName.isNotEmpty()) { "Callsign cannot be empty" }
        val docRef = db.collection("users").document(uid)
        try {
            docRef.update(
                mapOf(
                    "displayName" to cleanName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun updateAvatarLoadout(
        loadout: AvatarLoadout,
        activeLobbyCode: String? = null
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        val fields = mapOf(
            "avatarColorHex" to loadout.colorHex,
            "avatarModel" to loadout.model,
            "avatarHat" to loadout.hat,
            "avatarShirt" to loadout.shirt,
            "avatarPants" to loadout.pants,
            "avatarAccessory" to loadout.accessory,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            val batch = db.batch()
            batch.update(userRef, fields)
            if (!activeLobbyCode.isNullOrBlank()) {
                val playerRef = db.collection("lobbies").document(activeLobbyCode)
                    .collection("players").document(uid)
                batch.update(playerRef, fields)
            }
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path)
            throw e
        }
    }

    suspend fun createLobby(
        title: String,
        displayName: String,
        avatar: AvatarLoadout = AvatarLoadout(),
        maxPlayers: Int,
        hideDurationSec: Int,
        huntDurationSec: Int,
        initialZoneRadiusMeters: Double,
        finalZoneRadiusMeters: Double,
        centerLat: Double,
        centerLng: Double,
        voiceEnabled: Boolean
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val code = generateLobbyCode()
        val lobbyRef = db.collection("lobbies").document(code)
        val playerRef = lobbyRef.collection("players").document(uid)

        val cleanTitle = title.trim().take(40).ifBlank { "Crimson Sector" }
        val cleanName = displayName.trim().take(30).ifBlank { "Host" }
        val clampedMax = maxPlayers.coerceIn(2, 20).toLong()
        val clampedHide = hideDurationSec.coerceIn(30, 120).toLong()
        val clampedHunt = huntDurationSec.coerceIn(120, 1800).toLong()
        val clampedInitRadius = initialZoneRadiusMeters.coerceIn(50.0, 2000.0)
        val clampedFinalRadius = finalZoneRadiusMeters.coerceIn(10.0, 200.0)
            .coerceAtMost(clampedInitRadius)

        val lobbyPayload = mapOf(
            "hostId" to uid,
            "code" to code,
            "title" to cleanTitle,
            "status" to "waiting",
            "maxPlayers" to clampedMax,
            "hideDurationSec" to clampedHide,
            "huntDurationSec" to clampedHunt,
            "initialZoneRadiusMeters" to clampedInitRadius,
            "finalZoneRadiusMeters" to clampedFinalRadius,
            "zoneCenterLat" to centerLat.coerceIn(-89.9, 89.9),
            "zoneCenterLng" to centerLng.coerceIn(-179.9, 179.9),
            "seekerUid" to uid,
            "winnerTeam" to "",
            "phaseStartedAtMs" to 0L,
            "memberUids" to listOf(uid),
            "voiceEnabled" to voiceEnabled,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        val playerPayload = newPlayerPayload(
            uid = uid,
            lobbyId = code,
            displayName = cleanName,
            avatar = avatar,
            lat = centerLat.coerceIn(-89.9, 89.9),
            lng = centerLng.coerceIn(-179.9, 179.9),
            memberUids = listOf(uid)
        )

        try {
            db.batch()
                .set(lobbyRef, lobbyPayload)
                .set(playerRef, playerPayload)
                .commit()
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, lobbyRef.path)
            throw e
        }
        code
    }

    suspend fun joinLobbyByCode(
        rawCode: String,
        displayName: String,
        avatar: AvatarLoadout = AvatarLoadout(),
        lat: Double,
        lng: Double
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val code = rawCode.trim().uppercase().replace("[^A-Z0-9]".toRegex(), "")
        require(code.length in 4..12) { "Enter a valid 6-character room code." }

        val lobbyRef = db.collection("lobbies").document(code)
        val lobbySnap = try {
            lobbyRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, lobbyRef.path)
            throw e
        }
        if (!lobbySnap.exists()) {
            error("Lobby $code not found. Check the code and try again.")
        }
        val lobby = lobbySnap.toObject(GameLobby::class.java, ServerTimestampBehavior.ESTIMATE)
            ?: error("Unable to read lobby $code.")

        if (uid in lobby.memberUids) {
            return@runCatching code
        }
        if (lobby.status != "waiting") {
            error("Match in $code has already started.")
        }
        if (lobby.memberUids.size >= lobby.maxPlayers) {
            error("Lobby $code is full (${lobby.maxPlayers}/${lobby.maxPlayers}).")
        }

        val updatedMembers = lobby.memberUids + uid
        val cleanName = displayName.trim().take(30).ifBlank { "Agent" }
        val playerPayload = newPlayerPayload(
            uid = uid,
            lobbyId = code,
            displayName = cleanName,
            avatar = avatar,
            lat = lat.coerceIn(-89.9, 89.9),
            lng = lng.coerceIn(-179.9, 179.9),
            memberUids = updatedMembers
        )

        try {
            val batch = db.batch()
            batch.update(
                lobbyRef,
                mapOf(
                    "memberUids" to updatedMembers,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(lobbyRef.collection("players").document(uid), playerPayload)
            for (existingUid in lobby.memberUids) {
                batch.update(
                    lobbyRef.collection("players").document(existingUid),
                    mapOf(
                        "memberUids" to updatedMembers,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, lobbyRef.path)
            throw e
        }
        code
    }

    suspend fun leaveLobby(lobby: GameLobby): Result<Unit> = runCatching {
        val uid = requireUserId()
        val lobbyRef = db.collection("lobbies").document(lobby.code)
        try {
            if (lobby.hostId == uid) {
                lobbyRef.delete().await()
            } else {
                val updatedMembers = lobby.memberUids.filter { it != uid }
                val batch = db.batch()
                if (lobby.status != "finished" && updatedMembers.isNotEmpty()) {
                    batch.update(
                        lobbyRef,
                        mapOf(
                            "memberUids" to updatedMembers,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.delete(lobbyRef.collection("players").document(uid))
                batch.commit().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, lobbyRef.path)
            throw e
        }
    }

    suspend fun updateLobbySettings(
        lobbyCode: String,
        hideDurationSec: Int,
        huntDurationSec: Int,
        maxPlayers: Int,
        initialZoneRadiusMeters: Double,
        finalZoneRadiusMeters: Double,
        seekerUid: String,
        voiceEnabled: Boolean
    ): Result<Unit> = runCatching {
        requireUserId()
        val lobbyRef = db.collection("lobbies").document(lobbyCode)
        val clampedInit = initialZoneRadiusMeters.coerceIn(50.0, 2000.0)
        val clampedFinal = finalZoneRadiusMeters.coerceIn(10.0, 200.0).coerceAtMost(clampedInit)
        try {
            lobbyRef.update(
                mapOf(
                    "hideDurationSec" to hideDurationSec.coerceIn(30, 120).toLong(),
                    "huntDurationSec" to huntDurationSec.coerceIn(120, 1800).toLong(),
                    "maxPlayers" to maxPlayers.coerceIn(2, 20).toLong(),
                    "initialZoneRadiusMeters" to clampedInit,
                    "finalZoneRadiusMeters" to clampedFinal,
                    "seekerUid" to seekerUid,
                    "voiceEnabled" to voiceEnabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, lobbyRef.path)
            throw e
        }
    }

    suspend fun startMatch(
        lobby: GameLobby,
        players: List<LobbyPlayer>,
        chosenSeekerUid: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(lobby.hostId == uid) { "Only the host can start the match." }
        require(players.size >= 2) { "At least 2 players are required to start." }

        val nowMs = System.currentTimeMillis()
        val seeker = chosenSeekerUid.ifBlank { players.random().userId }
        val memberUids = players.map { it.userId }.distinct()
        val lobbyRef = db.collection("lobbies").document(lobby.code)

        try {
            val batch = db.batch()
            batch.update(
                lobbyRef,
                mapOf(
                    "status" to "hiding",
                    "seekerUid" to seeker,
                    "winnerTeam" to "",
                    "phaseStartedAtMs" to nowMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            for (player in players) {
                val role = if (player.userId == seeker) "seeker" else "hider"
                batch.update(
                    lobbyRef.collection("players").document(player.userId),
                    mapOf(
                        "role" to role,
                        "status" to "active",
                        "isMicMuted" to !lobby.voiceEnabled,
                        "isSpeaking" to false,
                        "cloakedUntilMs" to 0L,
                        "speedBoostUntilMs" to 0L,
                        "lastSonarAtMs" to 0L,
                        "lastRevealAtMs" to 0L,
                        "lastCloakAtMs" to 0L,
                        "lastSpeedAtMs" to 0L,
                        "lastDecoyAtMs" to 0L,
                        "bonusCharges" to 0L,
                        "caughtAtMs" to 0L,
                        "caughtByUid" to "",
                        "memberUids" to memberUids,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            // Spawn initial 5 tactical power-ups inside the arena
            val types = listOf("cloak", "sprint", "decoy", "sonar_charge", "sprint")
            for ((index, type) in types.withIndex()) {
                val angle = (2.0 * PI / types.size) * index + Random.nextDouble(-0.3, 0.3)
                val dist = lobby.initialZoneRadiusMeters * Random.nextDouble(0.35, 0.72)
                val (pLat, pLng) = GeoMath.offsetCoordinate(
                    lobby.zoneCenterLat,
                    lobby.zoneCenterLng,
                    dist,
                    angle
                )
                val puId = "pu_${nowMs}_$index"
                batch.set(
                    lobbyRef.collection("powerUps").document(puId),
                    mapOf(
                        "lobbyId" to lobby.code,
                        "type" to type,
                        "lat" to pLat,
                        "lng" to pLng,
                        "spawnedAtMs" to nowMs,
                        "claimedByUid" to "",
                        "claimedAtMs" to 0L,
                        "memberUids" to memberUids,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, lobbyRef.path)
            throw e
        }
    }

    suspend fun spawnPowerUp(lobby: GameLobby, currentRadiusMeters: Double): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(lobby.hostId == uid)
        val nowMs = System.currentTimeMillis()
        val types = listOf("cloak", "sprint", "decoy", "sonar_charge")
        val type = types.random()
        val angle = Random.nextDouble(0.0, 2.0 * PI)
        val dist = currentRadiusMeters * Random.nextDouble(0.2, 0.75)
        val (pLat, pLng) = GeoMath.offsetCoordinate(lobby.zoneCenterLat, lobby.zoneCenterLng, dist, angle)
        val puRef = db.collection("lobbies").document(lobby.code)
            .collection("powerUps").document("pu_${UUID.randomUUID().toString().take(8)}")
        try {
            puRef.set(
                mapOf(
                    "lobbyId" to lobby.code,
                    "type" to type,
                    "lat" to pLat,
                    "lng" to pLng,
                    "spawnedAtMs" to nowMs,
                    "claimedByUid" to "",
                    "claimedAtMs" to 0L,
                    "memberUids" to lobby.memberUids,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, puRef.path)
            throw e
        }
    }

    suspend fun transitionToHunting(lobbyCode: String): Result<Unit> = runCatching {
        requireUserId()
        val lobbyRef = db.collection("lobbies").document(lobbyCode)
        try {
            lobbyRef.update(
                mapOf(
                    "status" to "hunting",
                    "phaseStartedAtMs" to System.currentTimeMillis(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, lobbyRef.path)
            throw e
        }
    }

    suspend fun updateActivePlayerState(
        lobbyCode: String,
        lat: Double,
        lng: Double,
        isMicMuted: Boolean,
        isSpeaking: Boolean
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val playerRef = db.collection("lobbies").document(lobbyCode)
            .collection("players").document(uid)
        try {
            playerRef.update(
                mapOf(
                    "lat" to lat.coerceIn(-89.9, 89.9),
                    "lng" to lng.coerceIn(-179.9, 179.9),
                    "isMicMuted" to isMicMuted,
                    "isSpeaking" to isSpeaking,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, playerRef.path)
            throw e
        }
    }

    suspend fun activateHiderCloak(lobbyCode: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val nowMs = System.currentTimeMillis()
        val playerRef = db.collection("lobbies").document(lobbyCode)
            .collection("players").document(uid)
        try {
            playerRef.update(
                mapOf(
                    "cloakedUntilMs" to nowMs + AbilityConfig.CLOAK_DURATION_MS,
                    "lastCloakAtMs" to nowMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, playerRef.path)
            throw e
        }
    }

    suspend fun activateHiderSpeed(lobbyCode: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val nowMs = System.currentTimeMillis()
        val playerRef = db.collection("lobbies").document(lobbyCode)
            .collection("players").document(uid)
        try {
            playerRef.update(
                mapOf(
                    "speedBoostUntilMs" to nowMs + AbilityConfig.SPEED_DURATION_MS,
                    "lastSpeedAtMs" to nowMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, playerRef.path)
            throw e
        }
    }

    suspend fun deployHiderDecoy(
        lobby: GameLobby,
        lat: Double,
        lng: Double
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val nowMs = System.currentTimeMillis()
        val lobbyRef = db.collection("lobbies").document(lobby.code)
        val decoyRef = lobbyRef.collection("decoys").document("decoy_${UUID.randomUUID().toString().take(8)}")
        val playerRef = lobbyRef.collection("players").document(uid)
        val (dLat, dLng) = GeoMath.offsetCoordinate(lat, lng, 8.0, Random.nextDouble(0.0, 2 * PI))
        try {
            db.batch()
                .set(
                    decoyRef,
                    mapOf(
                        "ownerUid" to uid,
                        "lobbyId" to lobby.code,
                        "lat" to dLat,
                        "lng" to dLng,
                        "expiresAtMs" to nowMs + AbilityConfig.DECOY_DURATION_MS,
                        "memberUids" to lobby.memberUids,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                .update(
                    playerRef,
                    mapOf(
                        "lastDecoyAtMs" to nowMs,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .commit()
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, decoyRef.path)
            throw e
        }
    }

    suspend fun activateSeekerReveal(lobbyCode: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val nowMs = System.currentTimeMillis()
        val playerRef = db.collection("lobbies").document(lobbyCode)
            .collection("players").document(uid)
        try {
            playerRef.update(
                mapOf(
                    "lastRevealAtMs" to nowMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, playerRef.path)
            throw e
        }
    }

    suspend fun activateSeekerSonar(lobbyCode: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val nowMs = System.currentTimeMillis()
        val playerRef = db.collection("lobbies").document(lobbyCode)
            .collection("players").document(uid)
        try {
            playerRef.update(
                mapOf(
                    "lastSonarAtMs" to nowMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, playerRef.path)
            throw e
        }
    }

    suspend fun claimPowerUp(
        lobby: GameLobby,
        player: LobbyPlayer,
        powerUp: ArenaPowerUp
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(!player.isSpectator) { "Spectators cannot claim power-ups." }
        val nowMs = System.currentTimeMillis()
        val lobbyRef = db.collection("lobbies").document(lobby.code)
        val puRef = lobbyRef.collection("powerUps").document(powerUp.id)
        val playerRef = lobbyRef.collection("players").document(uid)

        val playerUpdates = mutableMapOf<String, Any>(
            "bonusCharges" to (player.bonusCharges + 1L).coerceAtMost(10L),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        when (powerUp.type) {
            "cloak" -> if (player.role == "hider") {
                playerUpdates["cloakedUntilMs"] = nowMs + AbilityConfig.CLOAK_DURATION_MS
            }
            "sprint" -> {
                playerUpdates["speedBoostUntilMs"] = nowMs + AbilityConfig.SPEED_DURATION_MS
            }
            "decoy" -> if (player.role == "hider") {
                playerUpdates["lastDecoyAtMs"] = 0L // Resets decoy cooldown immediately
            }
            "sonar_charge" -> if (player.role == "seeker") {
                playerUpdates["lastSonarAtMs"] = 0L // Resets sonar cooldown immediately
                playerUpdates["lastRevealAtMs"] = 0L
            }
        }

        try {
            db.batch()
                .update(
                    puRef,
                    mapOf(
                        "claimedByUid" to uid,
                        "claimedAtMs" to nowMs
                    )
                )
                .update(playerRef, playerUpdates)
                .commit()
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, puRef.path)
            throw e
        }
    }

    /**
     * Seeker tags an active hider -> immediately transitions them to permanent Spectator Mode until match end.
     */
    suspend fun tagHiderToSpectator(lobbyCode: String, targetUid: String): Result<Unit> = runCatching {
        val seekerUid = requireUserId()
        val nowMs = System.currentTimeMillis()
        val targetRef = db.collection("lobbies").document(lobbyCode)
            .collection("players").document(targetUid)
        try {
            targetRef.update(
                mapOf(
                    "role" to "spectator",
                    "status" to "spectating",
                    "isMicMuted" to true,
                    "isSpeaking" to false,
                    "cloakedUntilMs" to 0L,
                    "speedBoostUntilMs" to 0L,
                    "caughtAtMs" to nowMs,
                    "caughtByUid" to seekerUid,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, targetRef.path)
            throw e
        }
    }

    suspend fun eliminateOutOfZoneHider(lobbyCode: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val nowMs = System.currentTimeMillis()
        val playerRef = db.collection("lobbies").document(lobbyCode)
            .collection("players").document(uid)
        try {
            playerRef.update(
                mapOf(
                    "role" to "spectator",
                    "status" to "spectating",
                    "isMicMuted" to true,
                    "isSpeaking" to false,
                    "cloakedUntilMs" to 0L,
                    "speedBoostUntilMs" to 0L,
                    "caughtAtMs" to nowMs,
                    "caughtByUid" to "zone",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, playerRef.path)
            throw e
        }
    }

    suspend fun finishMatch(lobbyCode: String, winnerTeam: String): Result<Unit> = runCatching {
        requireUserId()
        val lobbyRef = db.collection("lobbies").document(lobbyCode)
        try {
            lobbyRef.update(
                mapOf(
                    "status" to "finished",
                    "winnerTeam" to winnerTeam,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, lobbyRef.path)
            throw e
        }
    }

    suspend fun recordMatchResultForSelf(won: Boolean, tagsScored: Long): Result<Unit> = runCatching {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        try {
            val snap = userRef.get().await()
            val current = snap.toObject(UserProfile::class.java, ServerTimestampBehavior.ESTIMATE)
            if (current != null) {
                userRef.update(
                    mapOf(
                        "gamesPlayed" to (current.gamesPlayed + 1L),
                        "gamesWon" to (current.gamesWon + if (won) 1L else 0L),
                        "hidersTagged" to (current.hidersTagged + tagsScored),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path)
            throw e
        }
    }

    suspend fun sendVoicePacket(
        lobby: GameLobby,
        lat: Double,
        lng: Double,
        audioBase64: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val pktRef = db.collection("lobbies").document(lobby.code)
            .collection("voicePackets").document("pkt_${UUID.randomUUID().toString().take(8)}")
        try {
            pktRef.set(
                mapOf(
                    "senderUid" to uid,
                    "lobbyId" to lobby.code,
                    "lat" to lat.coerceIn(-89.9, 89.9),
                    "lng" to lng.coerceIn(-179.9, 179.9),
                    "audioBase64" to audioBase64,
                    "sentAtMs" to System.currentTimeMillis(),
                    "memberUids" to lobby.memberUids,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, pktRef.path)
            throw e
        }
    }

    private fun newPlayerPayload(
        uid: String,
        lobbyId: String,
        displayName: String,
        avatar: AvatarLoadout,
        lat: Double,
        lng: Double,
        memberUids: List<String>
    ): Map<String, Any> = mapOf(
        "userId" to uid,
        "lobbyId" to lobbyId,
        "displayName" to displayName,
        "avatarColorHex" to avatar.colorHex,
        "avatarModel" to avatar.model,
        "avatarHat" to avatar.hat,
        "avatarShirt" to avatar.shirt,
        "avatarPants" to avatar.pants,
        "avatarAccessory" to avatar.accessory,
        "role" to "hider",
        "status" to "ready",
        "lat" to lat,
        "lng" to lng,
        "isMicMuted" to true,
        "isSpeaking" to false,
        "cloakedUntilMs" to 0L,
        "speedBoostUntilMs" to 0L,
        "lastSonarAtMs" to 0L,
        "lastRevealAtMs" to 0L,
        "lastCloakAtMs" to 0L,
        "lastSpeedAtMs" to 0L,
        "lastDecoyAtMs" to 0L,
        "bonusCharges" to 0L,
        "joinedAtMs" to System.currentTimeMillis(),
        "caughtAtMs" to 0L,
        "caughtByUid" to "",
        "memberUids" to memberUids,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )

    private fun generateLobbyCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString {
            append("RL")
            repeat(4) { append(chars[Random.nextInt(chars.length)]) }
        }
    }
}
