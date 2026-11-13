const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const HOST_UID = "host_alice";
const HIDER_UID = "hider_bob";
const OUTSIDER_UID = "outsider_eve";
const LOBBY_CODE = "RED742";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function nowTimestamp() {
  return new Date(Date.now() - 1000);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function lobbyPayload(overrides = {}) {
  const ts = nowTimestamp();
  return {
    hostId: HOST_UID,
    code: LOBBY_CODE,
    title: "Crimson Alley",
    status: "waiting",
    maxPlayers: 10,
    hideDurationSec: 60,
    huntDurationSec: 300,
    initialZoneRadiusMeters: 250.0,
    finalZoneRadiusMeters: 25.0,
    zoneCenterLat: 37.7749,
    zoneCenterLng: -122.4194,
    seekerUid: HOST_UID,
    winnerTeam: "",
    phaseStartedAtMs: 0,
    memberUids: [HOST_UID],
    voiceEnabled: true,
    createdAt: ts,
    updatedAt: ts,
    ...overrides,
  };
}

function playerPayload(uid, overrides = {}) {
  const ts = nowTimestamp();
  return {
    userId: uid,
    lobbyId: LOBBY_CODE,
    displayName: "Agent",
    avatarColorHex: "#E11D48",
    avatarModel: "striker",
    avatarHat: "none",
    avatarShirt: "crimson_tee",
    avatarPants: "charcoal_joggers",
    avatarAccessory: "none",
    role: "hider",
    status: "ready",
    lat: 37.7749,
    lng: -122.4194,
    isMicMuted: false,
    isSpeaking: false,
    cloakedUntilMs: 0,
    speedBoostUntilMs: 0,
    lastSonarAtMs: 0,
    lastRevealAtMs: 0,
    lastCloakAtMs: 0,
    lastSpeedAtMs: 0,
    lastDecoyAtMs: 0,
    bonusCharges: 0,
    joinedAtMs: 1700000000000,
    caughtAtMs: 0,
    caughtByUid: "",
    memberUids: [HOST_UID],
    createdAt: ts,
    updatedAt: ts,
    ...overrides,
  };
}

test("Unauthenticated user cannot read or list lobbies", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("lobbies").doc(LOBBY_CODE).get());
  await assertFails(unauthDb.collection("lobbies").get());
});

test("User profile enforces owner-only access, avatar schema, and rejects ghost fields", async () => {
  const aliceDb = testEnv.authenticatedContext(HOST_UID).firestore();
  const bobDb = testEnv.authenticatedContext(HIDER_UID).firestore();
  const ts = nowTimestamp();

  await assertSucceeds(
    aliceDb.collection("users").doc(HOST_UID).set({
      userId: HOST_UID,
      displayName: "Alice",
      avatarColorHex: "#E11D48",
      avatarModel: "phantom",
      avatarHat: "tactical_hood",
      avatarShirt: "scarlet_jacket",
      avatarPants: "crimson_track",
      avatarAccessory: "red_visor",
      gamesPlayed: 0,
      gamesWon: 0,
      hidersTagged: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(bobDb.collection("users").doc(HOST_UID).get());
  await assertFails(
    aliceDb.collection("users").doc(HOST_UID).update({
      avatarHat: "invalid_hat_hack",
      updatedAt: nowTimestamp(),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(HOST_UID).update({
      isAdmin: true,
      updatedAt: nowTimestamp(),
    })
  );
});

test("Host can create lobby and list own lobbies, outsider list without filter fails", async () => {
  const aliceDb = testEnv.authenticatedContext(HOST_UID).firestore();
  const eveDb = testEnv.authenticatedContext(OUTSIDER_UID).firestore();

  await assertSucceeds(aliceDb.collection("lobbies").doc(LOBBY_CODE).set(lobbyPayload()));
  await assertSucceeds(
    aliceDb.collection("lobbies").where("memberUids", "array-contains", HOST_UID).get()
  );
  await assertFails(eveDb.collection("lobbies").get());
});

test("Active hider can deploy decoy and claim powerUp; spectator cannot interact in any way", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await adminDb.collection("lobbies").doc(LOBBY_CODE).set(
      lobbyPayload({
        status: "hunting",
        memberUids: [HOST_UID, HIDER_UID],
        phaseStartedAtMs: 1700000100000,
      })
    );
    await adminDb.collection("lobbies").doc(LOBBY_CODE).collection("players").doc(HOST_UID).set(
      playerPayload(HOST_UID, {
        role: "seeker",
        status: "active",
        memberUids: [HOST_UID, HIDER_UID],
      })
    );
    await adminDb.collection("lobbies").doc(LOBBY_CODE).collection("players").doc(HIDER_UID).set(
      playerPayload(HIDER_UID, {
        role: "hider",
        status: "active",
        memberUids: [HOST_UID, HIDER_UID],
      })
    );
    await adminDb.collection("lobbies").doc(LOBBY_CODE).collection("powerUps").doc("pu_1").set({
      lobbyId: LOBBY_CODE,
      type: "cloak",
      lat: 37.775,
      lng: -122.419,
      spawnedAtMs: 1700000100000,
      claimedByUid: "",
      claimedAtMs: 0,
      memberUids: [HOST_UID, HIDER_UID],
      createdAt: nowTimestamp(),
    });
    await adminDb.collection("lobbies").doc(LOBBY_CODE).collection("powerUps").doc("pu_2").set({
      lobbyId: LOBBY_CODE,
      type: "sprint",
      lat: 37.7751,
      lng: -122.4191,
      spawnedAtMs: 1700000100000,
      claimedByUid: "",
      claimedAtMs: 0,
      memberUids: [HOST_UID, HIDER_UID],
      createdAt: nowTimestamp(),
    });
  });

  const seekerDb = testEnv.authenticatedContext(HOST_UID).firestore();
  const bobDb = testEnv.authenticatedContext(HIDER_UID).firestore();

  await assertSucceeds(
    bobDb.collection("lobbies").doc(LOBBY_CODE).collection("decoys").doc("decoy_1").set({
      ownerUid: HIDER_UID,
      lobbyId: LOBBY_CODE,
      lat: 37.7749,
      lng: -122.4194,
      expiresAtMs: 1700000200000,
      memberUids: [HOST_UID, HIDER_UID],
      createdAt: nowTimestamp(),
    })
  );
  await assertSucceeds(
    bobDb.collection("lobbies").doc(LOBBY_CODE).collection("powerUps").doc("pu_1").update({
      claimedByUid: HIDER_UID,
      claimedAtMs: 1700000150000,
    })
  );

  // Seeker tags Bob -> transitions Bob into Spectator Mode with mic muted
  await assertSucceeds(
    seekerDb.collection("lobbies").doc(LOBBY_CODE).collection("players").doc(HIDER_UID).update({
      role: "spectator",
      status: "spectating",
      isMicMuted: true,
      isSpeaking: false,
      cloakedUntilMs: 0,
      speedBoostUntilMs: 0,
      caughtAtMs: 1700000200000,
      caughtByUid: HOST_UID,
      updatedAt: nowTimestamp(),
    })
  );

  // Spectator Bob CANNOT update active GPS coordinates or abilities
  await assertFails(
    bobDb.collection("lobbies").doc(LOBBY_CODE).collection("players").doc(HIDER_UID).update({
      lat: 37.7755,
      lng: -122.4199,
      updatedAt: nowTimestamp(),
    })
  );
  await assertFails(
    bobDb.collection("lobbies").doc(LOBBY_CODE).collection("players").doc(HIDER_UID).update({
      cloakedUntilMs: 1700000300000,
      updatedAt: nowTimestamp(),
    })
  );

  // Spectator Bob CANNOT unmute mic or send voice packets
  await assertFails(
    bobDb.collection("lobbies").doc(LOBBY_CODE).collection("players").doc(HIDER_UID).update({
      isMicMuted: false,
      isSpeaking: true,
      updatedAt: nowTimestamp(),
    })
  );
  await assertFails(
    bobDb.collection("lobbies").doc(LOBBY_CODE).collection("voicePackets").doc("pkt_1").set({
      senderUid: HIDER_UID,
      lobbyId: LOBBY_CODE,
      lat: 37.7749,
      lng: -122.4194,
      audioBase64: "AAAA",
      sentAtMs: 1700000205000,
      memberUids: [HOST_UID, HIDER_UID],
      createdAt: nowTimestamp(),
    })
  );

  // Spectator Bob CANNOT deploy decoy or claim pu_2
  await assertFails(
    bobDb.collection("lobbies").doc(LOBBY_CODE).collection("decoys").doc("decoy_2").set({
      ownerUid: HIDER_UID,
      lobbyId: LOBBY_CODE,
      lat: 37.7749,
      lng: -122.4194,
      expiresAtMs: 1700000300000,
      memberUids: [HOST_UID, HIDER_UID],
      createdAt: nowTimestamp(),
    })
  );
  await assertFails(
    bobDb.collection("lobbies").doc(LOBBY_CODE).collection("powerUps").doc("pu_2").update({
      claimedByUid: HIDER_UID,
      claimedAtMs: 1700000210000,
    })
  );

  // Spectator Bob CAN observe lobby, players, and powerUps
  await assertSucceeds(bobDb.collection("lobbies").doc(LOBBY_CODE).get());
  await assertSucceeds(
    bobDb.collection("lobbies").doc(LOBBY_CODE).collection("players")
      .where("memberUids", "array-contains", HIDER_UID)
      .get()
  );
  await assertSucceeds(
    bobDb.collection("lobbies").doc(LOBBY_CODE).collection("powerUps")
      .where("memberUids", "array-contains", HIDER_UID)
      .get()
  );
});
