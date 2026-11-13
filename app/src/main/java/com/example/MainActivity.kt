package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.auth.AuthScreen
import com.example.ui.game.GameScreen
import com.example.ui.home.HomeScreen
import com.example.ui.lobby.LobbyRoomScreen
import com.example.ui.theme.RedlineTheme
import com.example.ui.viewmodel.RedlineViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ROUTE_HOME = "home"
const val ROUTE_LOBBY = "lobby/{code}"
const val ROUTE_GAME = "game/{code}"

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RedlineTheme(darkTheme = true) {
                RedlineAppRoot()
            }
        }
    }
}

@Composable
fun RedlineAppRoot(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = { /* authStateFlow updates automatically */ })
    } else {
        androidx.compose.runtime.key(user.uid) {
            AuthenticatedApp(user = user)
        }
    }
}

@Composable
private fun AuthenticatedApp(user: FirebaseUser) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModel: RedlineViewModel = viewModel(
        key = user.uid,
        factory = viewModelFactory {
            initializer {
                RedlineViewModel.create(context.applicationContext, user.uid)
            }
        }
    )

    val profileState by viewModel.userProfile.collectAsStateWithLifecycle()
    val lobbiesState by viewModel.myLobbies.collectAsStateWithLifecycle()
    val matchSnap by viewModel.matchSnapshot.collectAsStateWithLifecycle()
    val nowMs by viewModel.nowMs.collectAsStateWithLifecycle()
    val localLoc by viewModel.localLocation.collectAsStateWithLifecycle()
    val hasLocPerm by viewModel.hasLocationPermission.collectAsStateWithLifecycle()
    val isMicEnabled by viewModel.isMicEnabled.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()
    val outOfZoneTicks by viewModel.outOfZoneSeconds.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fine = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val audio = results[Manifest.permission.RECORD_AUDIO] == true
        viewModel.onLocationPermissionResult(fine || coarse)
        viewModel.onAudioPermissionResult(audio)
    }

    LaunchedEffect(user.uid) {
        viewModel.ensureProfile(user.displayName ?: "Agent")
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasAudio = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        viewModel.onLocationPermissionResult(hasFine || hasCoarse)
        viewModel.onAudioPermissionResult(hasAudio)

        if (!hasFine && !hasCoarse || !hasAudio) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearBanner()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { _ ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_HOME,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(ROUTE_HOME) {
                HomeScreen(
                    profileState = profileState,
                    lobbiesState = lobbiesState,
                    isBusy = isBusy,
                    hasLocationPermission = hasLocPerm,
                    onRequestLocation = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.RECORD_AUDIO
                            )
                        )
                    },
                    onCreateLobby = { title, maxP, hideS, huntS, initR, finalR, voice ->
                        viewModel.createLobby(title, maxP, hideS, huntS, initR, finalR, voice) { code ->
                            navController.navigate("lobby/$code")
                        }
                    },
                    onJoinLobby = { code ->
                        viewModel.joinLobby(code) { joinedCode ->
                            navController.navigate("lobby/$joinedCode")
                        }
                    },
                    onOpenLobby = { lobby ->
                        viewModel.openLobby(lobby.code)
                        if (lobby.status == "waiting") {
                            navController.navigate("lobby/${lobby.code}")
                        } else {
                            navController.navigate("game/${lobby.code}")
                        }
                    },
                    onSaveAvatar = { loadout -> viewModel.saveAvatarLoadout(loadout) },
                    onUpdateCallsign = { newName -> viewModel.updateDisplayName(newName) },
                    onSignOut = {
                        viewModel.closeLobbyObserver()
                        scope.launch {
                            withContext(NonCancellable) {
                                try {
                                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                                } catch (e: Exception) {
                                    Log.w("Auth", "Clear credential failed: ${e.message}")
                                } finally {
                                    Firebase.auth.signOut()
                                }
                            }
                        }
                    }
                )
            }

            composable(ROUTE_LOBBY) { backStackEntry ->
                val code = backStackEntry.arguments?.getString("code").orEmpty()
                LaunchedEffect(code) {
                    if (code.isNotBlank()) viewModel.openLobby(code)
                }

                val lobby = matchSnap.lobby
                LaunchedEffect(lobby?.status) {
                    if (lobby != null && (lobby.status == "hiding" || lobby.status == "hunting")) {
                        navController.navigate("game/${lobby.code}") {
                            popUpTo("lobby/${lobby.code}") { inclusive = true }
                        }
                    }
                }

                BackHandler {
                    viewModel.leaveActiveLobby {
                        navController.popBackStack(ROUTE_HOME, inclusive = false)
                    }
                }

                if (lobby == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LobbyRoomScreen(
                        lobby = lobby,
                        players = matchSnap.players,
                        currentUserId = user.uid,
                        isBusy = isBusy,
                        onCopyCode = { copied -> viewModel.showBanner("Copied room code $copied") },
                        onSaveAvatar = { loadout -> viewModel.saveAvatarLoadout(loadout) },
                        onSelectSeeker = { seekerUid ->
                            viewModel.updateSettings(
                                hideDurationSec = lobby.hideDurationSec.toInt(),
                                huntDurationSec = lobby.huntDurationSec.toInt(),
                                maxPlayers = lobby.maxPlayers.toInt(),
                                initialRadiusMeters = lobby.initialZoneRadiusMeters,
                                finalRadiusMeters = lobby.finalZoneRadiusMeters,
                                seekerUid = seekerUid,
                                voiceEnabled = lobby.voiceEnabled
                            )
                        },
                        onStartMatch = { seekerUid -> viewModel.startMatch(seekerUid) },
                        onLeaveLobby = {
                            viewModel.leaveActiveLobby {
                                navController.popBackStack(ROUTE_HOME, inclusive = false)
                            }
                        }
                    )
                }
            }

            composable(ROUTE_GAME) { backStackEntry ->
                val code = backStackEntry.arguments?.getString("code").orEmpty()
                LaunchedEffect(code) {
                    if (code.isNotBlank()) viewModel.openLobby(code)
                }

                BackHandler {
                    viewModel.leaveActiveLobby {
                        navController.popBackStack(ROUTE_HOME, inclusive = false)
                    }
                }

                val lobby = matchSnap.lobby
                if (lobby == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    GameScreen(
                        lobby = lobby,
                        players = matchSnap.players,
                        powerUps = matchSnap.powerUps,
                        decoys = matchSnap.decoys,
                        currentUserId = user.uid,
                        localLocation = localLoc,
                        nowMs = nowMs,
                        isMicEnabled = isMicEnabled,
                        outOfZoneTicks = outOfZoneTicks,
                        onToggleMic = { viewModel.toggleProximityMic() },
                        onActivateCloak = { viewModel.activateHiderCloak() },
                        onActivateSpeed = { viewModel.activateHiderSpeed() },
                        onDeployDecoy = { viewModel.deployHiderDecoy() },
                        onActivateReveal = { viewModel.activateSeekerReveal() },
                        onActivateSonar = { viewModel.activateSeekerSonar() },
                        onClaimPowerUp = { pu -> viewModel.claimPowerUp(pu) },
                        onTagHider = { targetUid -> viewModel.tagHider(targetUid) },
                        onLeaveMatch = {
                            viewModel.leaveActiveLobby {
                                navController.popBackStack(ROUTE_HOME, inclusive = false)
                            }
                        }
                    )
                }
            }
        }
    }
}
