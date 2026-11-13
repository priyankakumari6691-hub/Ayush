package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.example.data.remote.RedlineRepository
import com.example.services.GeoPoint
import com.example.services.LocationTracker
import com.example.services.ProximityVoiceEngine
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class MatchSnapshot(
    val lobby: GameLobby? = null,
    val players: List<LobbyPlayer> = emptyList(),
    val powerUps: List<ArenaPowerUp> = emptyList(),
    val decoys: List<DecoyBeacon> = emptyList(),
    val voicePackets: List<VoicePacket> = emptyList()
)

class RedlineViewModel(
    private val repository: RedlineRepository,
    private val currentUserId: String,
    private val locationTracker: LocationTracker
) : ViewModel() {

    val userProfile: StateFlow<UiState<UserProfile?>> = repository.observeUserProfile(currentUserId)
        .mapSuccessOrError("Failed to load profile")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val myLobbies: StateFlow<UiState<List<GameLobby>>> = repository.observeMyLobbies(currentUserId)
        .mapSuccessOrError("Failed to load rooms")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val _activeLobbyCode = MutableStateFlow<String?>(null)
    val activeLobbyCode: StateFlow<String?> = _activeLobbyCode.asStateFlow()

    private val _matchSnapshot = MutableStateFlow(MatchSnapshot())
    val matchSnapshot: StateFlow<MatchSnapshot> = _matchSnapshot.asStateFlow()

    private val _nowMs = MutableStateFlow(System.currentTimeMillis())
    val nowMs: StateFlow<Long> = _nowMs.asStateFlow()

    private val _localLocation = MutableStateFlow(GeoPoint(37.7749, -122.4194, hasGpsFix = false))
    val localLocation: StateFlow<GeoPoint> = _localLocation.asStateFlow()

    private val _hasLocationPermission = MutableStateFlow(false)
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()

    private val _hasAudioPermission = MutableStateFlow(false)
    val hasAudioPermission: StateFlow<Boolean> = _hasAudioPermission.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(false)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _outOfZoneSeconds = MutableStateFlow(0)
    val outOfZoneSeconds: StateFlow<Int> = _outOfZoneSeconds.asStateFlow()

    private var lobbyObserveJob: Job? = null
    private var locationJob: Job? = null
    private var lastPowerUpSpawnAtMs = 0L
    private var lastSyncedLocationAtMs = 0L
    private var recordedFinishedMatchCode: String? = null

    private val voiceEngine = ProximityVoiceEngine(
        scope = viewModelScope,
        onVoiceFrameReady = { audioBase64, _ ->
            val lobby = _matchSnapshot.value.lobby ?: return@ProximityVoiceEngine
            val me = _matchSnapshot.value.players.find { it.userId == currentUserId }
            if (me == null || me.isSpectator || !lobby.voiceEnabled) return@ProximityVoiceEngine
            val loc = _localLocation.value
            viewModelScope.launch {
                repository.sendVoicePacket(lobby, loc.lat, loc.lng, audioBase64)
            }
        },
        onSpeakingStateChanged = { speaking ->
            _isSpeaking.value = speaking
            syncActivePlayerLocationAndMic(force = true)
        }
    )

    init {
        viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                _nowMs.value = now
                tickMatchAutomation(now)
                delay(500L)
            }
        }
    }

    fun ensureProfile(defaultDisplayName: String) {
        viewModelScope.launch {
            repository.ensureUserProfile(defaultDisplayName)
                .onFailure { Log.w(TAG, "Profile ensure failed: ${it.message}") }
        }
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun showBanner(msg: String) {
        _bannerMessage.value = msg
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _hasLocationPermission.value = granted
        if (granted && locationJob == null) {
            locationJob = viewModelScope.launch {
                locationTracker.observeLocationUpdates(1500L)
                    .catch { e -> Log.w(TAG, "Location stream ended: ${e.message}") }
                    .collect { point ->
                        _localLocation.value = point
                        syncActivePlayerLocationAndMic(force = false)
                    }
            }
        }
    }

    fun onAudioPermissionResult(granted: Boolean) {
        _hasAudioPermission.value = granted
        val me = _matchSnapshot.value.players.find { it.userId == currentUserId }
        if (me?.isSpectator == true) {
            voiceEngine.setSpectatorLocked(true)
            return
        }
        voiceEngine.setMicActive(_isMicEnabled.value, granted)
    }

    fun toggleProximityMic() {
        val me = _matchSnapshot.value.players.find { it.userId == currentUserId }
        if (me?.isSpectator == true) {
            _isMicEnabled.value = false
            voiceEngine.setSpectatorLocked(true)
            _bannerMessage.value = "Spectators cannot transmit voice to active players."
            return
        }
        if (!_hasAudioPermission.value) {
            _bannerMessage.value = "Grant microphone permission to use proximity voice chat."
            return
        }
        val next = !_isMicEnabled.value
        _isMicEnabled.value = next
        voiceEngine.setMicActive(next, _hasAudioPermission.value)
        syncActivePlayerLocationAndMic(force = true)
    }

    fun openLobby(code: String) {
        if (_activeLobbyCode.value == code && lobbyObserveJob?.isActive == true) return
        _activeLobbyCode.value = code
        _outOfZoneSeconds.value = 0
        recordedFinishedMatchCode = null
        lobbyObserveJob?.cancel()
        lobbyObserveJob = viewModelScope.launch {
            combine(
                repository.observeLobby(code),
                repository.observeLobbyPlayers(code),
                repository.observePowerUps(code),
                repository.observeDecoys(code),
                repository.observeVoicePackets(code)
            ) { lobby, players, powerUps, decoys, packets ->
                MatchSnapshot(lobby, players, powerUps, decoys, packets)
            }.catch { err ->
                val fsErr = generateSequence(err) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsErr == null && err is CancellationException) throw err
                Log.w(TAG, "Lobby stream error: ${fsErr?.message ?: err.message}")
            }.collect { snap ->
                _matchSnapshot.value = snap
                val me = snap.players.find { it.userId == currentUserId }
                if (me?.isSpectator == true) {
                    if (_isMicEnabled.value) _isMicEnabled.value = false
                    voiceEngine.setSpectatorLocked(true)
                } else {
                    voiceEngine.setSpectatorLocked(false)
                }

                val lobby = snap.lobby
                if (lobby != null && (lobby.status == "hiding" || lobby.status == "hunting") && lobby.voiceEnabled) {
                    val playersByUid = snap.players.associateBy { it.userId }
                    val loc = _localLocation.value
                    voiceEngine.processIncomingPackets(
                        packets = snap.voicePackets,
                        playersByUid = playersByUid,
                        myUid = currentUserId,
                        listenerLat = loc.lat,
                        listenerLng = loc.lng
                    )
                }
            }
        }
    }

    fun closeLobbyObserver() {
        lobbyObserveJob?.cancel()
        lobbyObserveJob = null
        _activeLobbyCode.value = null
        _isMicEnabled.value = false
        voiceEngine.setMicActive(false, _hasAudioPermission.value)
        _matchSnapshot.value = MatchSnapshot()
    }

    fun createLobby(
        title: String,
        maxPlayers: Int,
        hideDurationSec: Int,
        huntDurationSec: Int,
        initialRadiusMeters: Double,
        finalRadiusMeters: Double,
        voiceEnabled: Boolean,
        onCreated: (String) -> Unit
    ) {
        val profile = (userProfile.value as? UiState.Success)?.data
        val loc = _localLocation.value
        viewModelScope.launch {
            _isBusy.value = true
            repository.createLobby(
                title = title,
                displayName = profile?.displayName ?: "Host",
                avatar = profile?.toAvatarLoadout() ?: AvatarLoadout(),
                maxPlayers = maxPlayers,
                hideDurationSec = hideDurationSec,
                huntDurationSec = huntDurationSec,
                initialZoneRadiusMeters = initialRadiusMeters,
                finalZoneRadiusMeters = finalRadiusMeters,
                centerLat = loc.lat,
                centerLng = loc.lng,
                voiceEnabled = voiceEnabled
            ).onSuccess { code ->
                _isBusy.value = false
                openLobby(code)
                onCreated(code)
            }.onFailure { err ->
                _isBusy.value = false
                _bannerMessage.value = err.message ?: "Failed to create lobby"
            }
        }
    }

    fun joinLobby(code: String, onJoined: (String) -> Unit) {
        val profile = (userProfile.value as? UiState.Success)?.data
        val loc = _localLocation.value
        viewModelScope.launch {
            _isBusy.value = true
            repository.joinLobbyByCode(
                rawCode = code,
                displayName = profile?.displayName ?: "Agent",
                avatar = profile?.toAvatarLoadout() ?: AvatarLoadout(),
                lat = loc.lat,
                lng = loc.lng
            ).onSuccess { joinedCode ->
                _isBusy.value = false
                openLobby(joinedCode)
                onJoined(joinedCode)
            }.onFailure { err ->
                _isBusy.value = false
                _bannerMessage.value = err.message ?: "Could not join room"
            }
        }
    }

    fun leaveActiveLobby(onLeft: () -> Unit) {
        val lobby = _matchSnapshot.value.lobby ?: run {
            closeLobbyObserver()
            onLeft()
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            repository.leaveLobby(lobby)
                .onSuccess {
                    _isBusy.value = false
                    closeLobbyObserver()
                    onLeft()
                }
                .onFailure {
                    _isBusy.value = false
                    closeLobbyObserver()
                    onLeft()
                }
        }
    }

    fun updateDisplayName(newName: String) {
        viewModelScope.launch {
            repository.updateDisplayName(newName)
                .onSuccess { _bannerMessage.value = "Callsign updated" }
                .onFailure { _bannerMessage.value = it.message ?: "Failed to update callsign" }
        }
    }

    fun saveAvatarLoadout(loadout: AvatarLoadout) {
        val lobbyCode = _activeLobbyCode.value?.takeIf {
            _matchSnapshot.value.lobby?.status == "waiting"
        }
        viewModelScope.launch {
            _isBusy.value = true
            repository.updateAvatarLoadout(loadout, lobbyCode)
                .onSuccess {
                    _isBusy.value = false
                    _bannerMessage.value = "Avatar loadout saved"
                }
                .onFailure {
                    _isBusy.value = false
                    _bannerMessage.value = it.message ?: "Failed to save avatar"
                }
        }
    }

    fun updateSettings(
        hideDurationSec: Int,
        huntDurationSec: Int,
        maxPlayers: Int,
        initialRadiusMeters: Double,
        finalRadiusMeters: Double,
        seekerUid: String,
        voiceEnabled: Boolean
    ) {
        val lobby = _matchSnapshot.value.lobby ?: return
        viewModelScope.launch {
            repository.updateLobbySettings(
                lobbyCode = lobby.code,
                hideDurationSec = hideDurationSec,
                huntDurationSec = huntDurationSec,
                maxPlayers = maxPlayers,
                initialZoneRadiusMeters = initialRadiusMeters,
                finalZoneRadiusMeters = finalRadiusMeters,
                seekerUid = seekerUid,
                voiceEnabled = voiceEnabled
            ).onFailure { _bannerMessage.value = it.message ?: "Failed to update settings" }
        }
    }

    fun startMatch(chosenSeekerUid: String) {
        val snap = _matchSnapshot.value
        val lobby = snap.lobby ?: return
        if (snap.players.size < 2) {
            _bannerMessage.value = "At least 2 players are needed to start a match."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            repository.startMatch(lobby, snap.players, chosenSeekerUid)
                .onSuccess {
                    _isBusy.value = false
                    lastPowerUpSpawnAtMs = System.currentTimeMillis()
                }
                .onFailure {
                    _isBusy.value = false
                    _bannerMessage.value = it.message ?: "Failed to start match"
                }
        }
    }

    fun activateHiderCloak() {
        val snap = _matchSnapshot.value
        val lobby = snap.lobby ?: return
        val me = snap.players.find { it.userId == currentUserId } ?: return
        if (me.isSpectator || me.role != "hider") return
        val now = System.currentTimeMillis()
        if (now - me.lastCloakAtMs < AbilityConfig.CLOAK_COOLDOWN_MS) return
        viewModelScope.launch {
            repository.activateHiderCloak(lobby.code)
        }
    }

    fun activateHiderSpeed() {
        val snap = _matchSnapshot.value
        val lobby = snap.lobby ?: return
        val me = snap.players.find { it.userId == currentUserId } ?: return
        if (me.isSpectator || me.role != "hider") return
        val now = System.currentTimeMillis()
        if (now - me.lastSpeedAtMs < AbilityConfig.SPEED_COOLDOWN_MS) return
        viewModelScope.launch {
            repository.activateHiderSpeed(lobby.code)
        }
    }

    fun deployHiderDecoy() {
        val snap = _matchSnapshot.value
        val lobby = snap.lobby ?: return
        val me = snap.players.find { it.userId == currentUserId } ?: return
        if (me.isSpectator || me.role != "hider") return
        val now = System.currentTimeMillis()
        if (now - me.lastDecoyAtMs < AbilityConfig.DECOY_COOLDOWN_MS) return
        val loc = _localLocation.value
        viewModelScope.launch {
            repository.deployHiderDecoy(lobby, loc.lat, loc.lng)
                .onSuccess { _bannerMessage.value = "Phantom Decoy deployed!" }
        }
    }

    fun activateSeekerReveal() {
        val snap = _matchSnapshot.value
        val lobby = snap.lobby ?: return
        val me = snap.players.find { it.userId == currentUserId } ?: return
        if (me.isSpectator || me.role != "seeker" || lobby.status != "hunting") return
        val now = System.currentTimeMillis()
        if (now - me.lastRevealAtMs < AbilityConfig.REVEAL_COOLDOWN_MS) return
        viewModelScope.launch {
            repository.activateSeekerReveal(lobby.code)
        }
    }

    fun activateSeekerSonar() {
        val snap = _matchSnapshot.value
        val lobby = snap.lobby ?: return
        val me = snap.players.find { it.userId == currentUserId } ?: return
        if (me.isSpectator || me.role != "seeker" || lobby.status != "hunting") return
        val now = System.currentTimeMillis()
        if (now - me.lastSonarAtMs < AbilityConfig.SONAR_COOLDOWN_MS) return
        viewModelScope.launch {
            repository.activateSeekerSonar(lobby.code)
        }
    }

    fun claimPowerUp(powerUp: ArenaPowerUp) {
        val snap = _matchSnapshot.value
        val lobby = snap.lobby ?: return
        val me = snap.players.find { it.userId == currentUserId } ?: return
        if (me.isSpectator || powerUp.isClaimed) return
        viewModelScope.launch {
            repository.claimPowerUp(lobby, me, powerUp)
                .onSuccess {
                    val label = when (powerUp.type) {
                        "cloak" -> "Ghost Veil activated!"
                        "sprint" -> "Adrenaline Surge activated!"
                        "decoy" -> "Decoy cooldown refreshed!"
                        "sonar_charge" -> "Sonar & Reveal recharged!"
                        else -> "Power-up claimed!"
                    }
                    _bannerMessage.value = label
                }
        }
    }

    fun tagHider(targetUid: String) {
        val snap = _matchSnapshot.value
        val lobby = snap.lobby ?: return
        val me = snap.players.find { it.userId == currentUserId } ?: return
        if (me.isSpectator || me.role != "seeker" || lobby.status != "hunting") return
        viewModelScope.launch {
            repository.tagHiderToSpectator(lobby.code, targetUid)
                .onSuccess {
                    val targetName = snap.players.find { it.userId == targetUid }?.displayName ?: "Hider"
                    _bannerMessage.value = "$targetName tagged -> Spectator Mode!"
                    val remainingHiders = snap.players.count {
                        it.userId != targetUid && it.role == "hider" && !it.isSpectator
                    }
                    if (remainingHiders == 0) {
                        repository.finishMatch(lobby.code, "seekers")
                    }
                }
                .onFailure { _bannerMessage.value = it.message ?: "Failed to tag player" }
        }
    }

    private fun syncActivePlayerLocationAndMic(force: Boolean) {
        val snap = _matchSnapshot.value
        val lobby = snap.lobby ?: return
        if (lobby.status != "hiding" && lobby.status != "hunting") return
        val me = snap.players.find { it.userId == currentUserId } ?: return
        if (me.isSpectator) return

        val now = System.currentTimeMillis()
        if (!force && now - lastSyncedLocationAtMs < 2000L) return
        lastSyncedLocationAtMs = now

        val loc = _localLocation.value
        viewModelScope.launch {
            repository.updateActivePlayerState(
                lobbyCode = lobby.code,
                lat = loc.lat,
                lng = loc.lng,
                isMicMuted = !_isMicEnabled.value,
                isSpeaking = _isSpeaking.value
            )
        }
    }

    private fun tickMatchAutomation(nowMs: Long) {
        val snap = _matchSnapshot.value
        val lobby = snap.lobby ?: return
        val players = snap.players
        val me = players.find { it.userId == currentUserId }

        if (lobby.status == "hiding") {
            val remaining = lobby.remainingPhaseSeconds(nowMs)
            if (remaining <= 0L && (lobby.hostId == currentUserId || lobby.seekerUid == currentUserId)) {
                viewModelScope.launch {
                    repository.transitionToHunting(lobby.code)
                }
            }
        } else if (lobby.status == "hunting") {
            val remaining = lobby.remainingPhaseSeconds(nowMs)
            val activeHiders = players.count { it.role == "hider" && !it.isSpectator }

            // Check win conditions (host or seeker commits outcome)
            if (lobby.hostId == currentUserId || lobby.seekerUid == currentUserId) {
                if (activeHiders == 0 && players.isNotEmpty()) {
                    viewModelScope.launch {
                        repository.finishMatch(lobby.code, "seekers")
                    }
                    return
                }
                if (remaining <= 0L) {
                    viewModelScope.launch {
                        repository.finishMatch(lobby.code, "hiders")
                    }
                    return
                }
            }

            // Host periodic power-up respawn inside shrinking zone
            if (lobby.hostId == currentUserId) {
                val activeUnclaimed = snap.powerUps.count { !it.isClaimed }
                if (activeUnclaimed < AbilityConfig.MAX_ACTIVE_POWERUPS &&
                    nowMs - lastPowerUpSpawnAtMs >= AbilityConfig.POWERUP_RESPAWN_INTERVAL_MS
                ) {
                    lastPowerUpSpawnAtMs = nowMs
                    val radius = lobby.currentZoneRadiusMeters(nowMs)
                    viewModelScope.launch {
                        repository.spawnPowerUp(lobby, radius)
                    }
                }
            }

            // Out-of-zone check for active hider
            if (me != null && me.role == "hider" && !me.isSpectator && _hasLocationPermission.value) {
                val loc = _localLocation.value
                val distFromCenter = GeoMath.distanceMeters(
                    lobby.zoneCenterLat,
                    lobby.zoneCenterLng,
                    loc.lat,
                    loc.lng
                )
                val zoneRadius = lobby.currentZoneRadiusMeters(nowMs)
                if (loc.hasGpsFix && distFromCenter > zoneRadius) {
                    val nextSec = _outOfZoneSeconds.value + 1
                    _outOfZoneSeconds.value = nextSec
                    if (nextSec >= 16) { // 16 half-second ticks = 8 seconds outside Redline
                        _outOfZoneSeconds.value = 0
                        viewModelScope.launch {
                            repository.eliminateOutOfZoneHider(lobby.code)
                        }
                    }
                } else {
                    if (_outOfZoneSeconds.value != 0) _outOfZoneSeconds.value = 0
                }
            }
        } else if (lobby.status == "finished" && recordedFinishedMatchCode != lobby.code && me != null) {
            recordedFinishedMatchCode = lobby.code
            val won = when (lobby.winnerTeam) {
                "seekers" -> me.userId == lobby.seekerUid
                "hiders" -> me.userId != lobby.seekerUid && !me.isSpectator
                else -> false
            }
            val tagsScored = if (me.userId == lobby.seekerUid) {
                players.count { it.caughtByUid == currentUserId }.toLong()
            } else 0L
            viewModelScope.launch {
                repository.recordMatchResultForSelf(won, tagsScored)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceEngine.release()
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.mapSuccessOrError(fallbackMessage: String) =
        kotlinx.coroutines.flow.flow {
            this@mapSuccessOrError
                .catch { error ->
                    val fsError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsError == null && error is CancellationException) throw error
                    val root = fsError ?: error
                    Log.w(TAG, "$fallbackMessage: ${root.message}", root)
                    emit(UiState.Error(root.message ?: fallbackMessage))
                }
                .collect { data ->
                    emit(UiState.Success(data))
                }
        }

    companion object {
        private const val TAG = "RedlineVM"

        fun create(context: Context, currentUserId: String): RedlineViewModel {
            val dbId = context.applicationContext.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            val repo = RedlineRepository(db)
            val locationTracker = LocationTracker(context.applicationContext)
            return RedlineViewModel(repo, currentUserId, locationTracker)
        }
    }
}
