package com.example.data.remote

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.AvatarHat
import com.example.data.model.AvatarLoadout
import com.example.data.model.AvatarModel
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedlineRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureProfile_andUpdateAvatarLoadout_succeedsForOwner() = runBlocking {
        val uid = signInTestUser("alice_avatar@test.com")
        val repo = RedlineRepository(firestore, auth)

        val profile = withTimeout(TIMEOUT_MS) { repo.ensureUserProfile("CrimsonAlice").getOrThrow() }
        assertEquals(uid, profile.userId)

        val customLoadout = AvatarLoadout(
            colorHex = "#FB7185",
            model = AvatarModel.PHANTOM.id,
            hat = AvatarHat.TACTICAL_HOOD.id
        )
        val updateResult = withTimeout(TIMEOUT_MS) { repo.updateAvatarLoadout(customLoadout) }
        assertTrue(updateResult.isSuccess)
    }

    @Test
    fun lobbyFlow_andSpectatorIsolation_enforcedEndToEnd() = runBlocking {
        val hostUid = signInTestUser("host_seeker@test.com")
        val hostRepo = RedlineRepository(firestore, auth)
        val code = withTimeout(TIMEOUT_MS) {
            hostRepo.createLobby(
                title = "Downtown Ops",
                displayName = "HostAlpha",
                maxPlayers = 8,
                hideDurationSec = 45,
                huntDurationSec = 300,
                initialZoneRadiusMeters = 250.0,
                finalZoneRadiusMeters = 25.0,
                centerLat = 37.7749,
                centerLng = -122.4194,
                voiceEnabled = true
            ).getOrThrow()
        }

        val hiderUid = signInTestUser("hider_bob@test.com")
        val hiderRepo = RedlineRepository(firestore, auth)
        val joinedCode = withTimeout(TIMEOUT_MS) {
            hiderRepo.joinLobbyByCode(
                rawCode = code,
                displayName = "BobShadow",
                lat = 37.7750,
                lng = -122.4195
            ).getOrThrow()
        }
        assertEquals(code, joinedCode)

        // Host starts match with host as seeker
        signInTestUser("host_seeker@test.com")
        val lobby = withTimeout(FLOW_TIMEOUT_MS) {
            hostRepo.observeLobby(code).first { it != null }!!
        }
        val players = withTimeout(FLOW_TIMEOUT_MS) {
            hostRepo.observeLobbyPlayers(code).first { it.size == 2 }
        }
        withTimeout(TIMEOUT_MS) {
            hostRepo.startMatch(lobby, players, hostUid).getOrThrow()
            hostRepo.transitionToHunting(code).getOrThrow()
        }

        // Hider deploys decoy & activates cloak while active
        signInTestUser("hider_bob@test.com")
        val activeLobby = withTimeout(FLOW_TIMEOUT_MS) {
            hiderRepo.observeLobby(code).first { it?.status == "hunting" }!!
        }
        assertTrue(withTimeout(TIMEOUT_MS) { hiderRepo.deployHiderDecoy(activeLobby, 37.775, -122.419).isSuccess })
        assertTrue(withTimeout(TIMEOUT_MS) { hiderRepo.activateHiderCloak(code).isSuccess })

        // Seeker tags Bob -> transitions Bob into permanent Spectator Mode
        signInTestUser("host_seeker@test.com")
        assertTrue(withTimeout(TIMEOUT_MS) { hostRepo.tagHiderToSpectator(code, hiderUid).isSuccess })

        // Spectator Bob CANNOT update GPS, send voice packets, or deploy decoys
        signInTestUser("hider_bob@test.com")
        val locAttempt = withTimeout(TIMEOUT_MS) {
            hiderRepo.updateActivePlayerState(code, 37.776, -122.420, isMicMuted = false, isSpeaking = true)
        }
        assertTrue(locAttempt.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            locAttempt.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        val voiceAttempt = withTimeout(TIMEOUT_MS) {
            hiderRepo.sendVoicePacket(activeLobby, 37.775, -122.419, "AAAA")
        }
        assertTrue(voiceAttempt.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            voiceAttempt.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        val decoyAttempt = withTimeout(TIMEOUT_MS) {
            hiderRepo.deployHiderDecoy(activeLobby, 37.775, -122.419)
        }
        assertTrue(decoyAttempt.isFailure)
    }

    @Test
    fun observeMyLobbies_unauthenticated_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = RedlineRepository(firestore, auth)

        val ex = try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeMyLobbies("unauth_uid").first()
            }
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull(ex)
        val firestoreEx = ex?.unwrapFirestoreException()
        assertNotNull(firestoreEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    private companion object {
        const val TIMEOUT_MS = 6000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
