package dev.bedrocklanbridge.bridge;

import dev.bedrocklanbridge.bridge.auth.AuthPolicy;
import dev.bedrocklanbridge.bridge.auth.AuthResult;
import dev.bedrocklanbridge.bridge.auth.BedrockIdentityStore;
import dev.bedrocklanbridge.bridge.auth.BedrockProfileMapper;
import dev.bedrocklanbridge.bridge.auth.ClientDataSummary;
import dev.bedrocklanbridge.bridge.auth.IdentityLookup;
import dev.bedrocklanbridge.bridge.auth.MappedBedrockProfile;
import org.cloudburstmc.netty.util.nethernet.TransportIdentityBinding;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests verifying the complete {@link BedrockProfileMapper} and {@link BedrockIdentityStore}
 * contract before integrating real WebRTC sessions into World PLAY.
 */
final class BedrockProfileMapperUnitTest {

    private static final Pattern STRICT_JAVA_NAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");
    private static final PublicKey DUMMY_PUBLIC_KEY = generateDummyKey();

    @TempDir
    Path tempDir;

    @Test
    void xuidDrivesDeterministicUuidAcrossRestarts() {
        Path storeFile = tempDir.resolve("identities.json");

        BedrockIdentityStore firstRunStore = BedrockIdentityStore.open(storeFile);
        BedrockProfileMapper firstMapper = new BedrockProfileMapper(firstRunStore);
        AuthResult auth = fullMicrosoftAuth("2535400000001001", "Abzak Play 2026");

        MappedBedrockProfile first = firstMapper.resolve(auth, AuthPolicy.STRICT_MICROSOFT, IdentityLookup.empty());

        BedrockIdentityStore secondRunStore = BedrockIdentityStore.open(storeFile);
        BedrockProfileMapper secondMapper = new BedrockProfileMapper(secondRunStore);
        MappedBedrockProfile second = secondMapper.resolve(auth, AuthPolicy.STRICT_MICROSOFT, IdentityLookup.empty());

        assertEquals("2535400000001001", first.xuid());
        assertEquals(first.bedrockUuid(), second.bedrockUuid());
        assertEquals(first.bedrockUuid(), BedrockProfileMapper.deterministicBedrockUuid("2535400000001001"));
        assertEquals(first.javaProfile().name(), second.javaProfile().name());
        assertEquals("Abzak Play 2026", second.originalGamertag());
        assertTrue(first.sanitized());
    }

    @Test
    void gamertagRenamePreservesUuidAndBoundProfile() {
        Path storeFile = tempDir.resolve("rename-identities.json");
        BedrockIdentityStore store1 = BedrockIdentityStore.open(storeFile);
        BedrockProfileMapper mapper1 = new BedrockProfileMapper(store1);

        MappedBedrockProfile initial = mapper1.resolve(
                fullMicrosoftAuth("2535400000001002", "PlayerOne"),
                AuthPolicy.STRICT_MICROSOFT,
                IdentityLookup.empty()
        );
        assertEquals("PlayerOne", initial.javaProfile().name());
        assertFalse(initial.sanitized());

        BedrockIdentityStore store2 = BedrockIdentityStore.open(storeFile);
        BedrockProfileMapper mapper2 = new BedrockProfileMapper(store2);
        MappedBedrockProfile renamed = mapper2.resolve(
                fullMicrosoftAuth("2535400000001002", "New Fancy Tag ✨"),
                AuthPolicy.STRICT_MICROSOFT,
                IdentityLookup.empty()
        );

        assertEquals(initial.bedrockUuid(), renamed.bedrockUuid());
        assertEquals("PlayerOne", renamed.javaProfile().name());
        assertEquals("New Fancy Tag ✨", renamed.originalGamertag());
        assertTrue(renamed.sanitized());
    }

    @Test
    void sanitizesLongUnicodeEmojiAndShortNamesToValidJavaPattern() {
        List<String> trickyGamertags = List.of(
                "",
                "   ",
                "A",
                "ab",
                "🔥✨",
                "Игрок Мир 🔥",
                "SuperLongXboxGamertagExceedingSixteenChars",
                "Łukasz.Café-2026",
                "---___---"
        );

        for (int i = 0; i < trickyGamertags.size(); i++) {
            String tag = trickyGamertags.get(i);
            String xuid = "25354000000020" + i;
            BedrockProfileMapper mapperA = new BedrockProfileMapper(BedrockIdentityStore.inMemory());
            BedrockProfileMapper mapperB = new BedrockProfileMapper(BedrockIdentityStore.inMemory());

            MappedBedrockProfile resA = mapperA.resolve(
                    fullMicrosoftAuth(xuid, tag), AuthPolicy.STRICT_MICROSOFT, IdentityLookup.empty());
            MappedBedrockProfile resB = mapperB.resolve(
                    fullMicrosoftAuth(xuid, tag), AuthPolicy.STRICT_MICROSOFT, IdentityLookup.empty());

            String javaName = resA.javaProfile().name();
            assertTrue(STRICT_JAVA_NAME.matcher(javaName).matches(),
                    "Invalid Java name '" + javaName + "' for gamertag '" + tag + "'");
            assertEquals(javaName, resB.javaProfile().name(), "Sanitization must be deterministic");
            assertEquals(resA.bedrockUuid(), resB.bedrockUuid());
            assertEquals(tag, resA.originalGamertag(), "Original gamertag must be preserved verbatim");
        }
    }

    @Test
    void resolvesCollisionWithOnlineAndCachedJavaPlayerCaseInsensitively() {
        UUID onlineJavaAlexUuid = UUID.randomUUID();
        UUID cachedJavaAbzakUuid = UUID.randomUUID();
        IdentityLookup serverLookup = new IdentityLookup() {
            @Override
            public Optional<UUID> findOnlineJavaUuidByNameIgnoreCase(String name) {
                return "Alex".equalsIgnoreCase(name) ? Optional.of(onlineJavaAlexUuid) : Optional.empty();
            }

            @Override
            public Optional<UUID> findCachedJavaUuidByNameIgnoreCase(String name) {
                return "Abzakcc".equalsIgnoreCase(name) ? Optional.of(cachedJavaAbzakUuid) : Optional.empty();
            }

            @Override
            public Optional<String> findInFlightBedrockXuidByNameIgnoreCase(String name) {
                return Optional.empty();
            }
        };

        BedrockProfileMapper mapper = new BedrockProfileMapper(BedrockIdentityStore.inMemory());

        MappedBedrockProfile alexBedrock = mapper.resolve(
                fullMicrosoftAuth("2535400000003001", "alex"),
                AuthPolicy.STRICT_MICROSOFT,
                serverLookup
        );
        assertNotEquals("alex", alexBedrock.javaProfile().name().toLowerCase(Locale.ROOT));
        assertNotEquals(onlineJavaAlexUuid, alexBedrock.bedrockUuid());
        assertTrue(alexBedrock.collisionResolved());
        assertTrue(STRICT_JAVA_NAME.matcher(alexBedrock.javaProfile().name()).matches());
        assertEquals("alex", alexBedrock.originalGamertag());

        MappedBedrockProfile abzakBedrock = mapper.resolve(
                fullMicrosoftAuth("2535400000003002", "Abzakcc"),
                AuthPolicy.STRICT_MICROSOFT,
                serverLookup
        );
        assertNotEquals("abzakcc", abzakBedrock.javaProfile().name().toLowerCase(Locale.ROOT));
        assertNotEquals(cachedJavaAbzakUuid, abzakBedrock.bedrockUuid());
        assertTrue(abzakBedrock.collisionResolved());
        assertTrue(STRICT_JAVA_NAME.matcher(abzakBedrock.javaProfile().name()).matches());
    }

    @Test
    void resolvesCollisionBetweenTwoBedrockPlayersAndEscapes4CharLimits() {
        // Synthetic strategy forcing exact identical baseCandidate AND identical attempts 0, 1, 2
        // across two different XUIDs, only diverging cleanly on attempt 3 -> proves no reliance on 4-char hashes.
        Map<Integer, String> sharedForcedPrefix = Map.of(
                0, "Coll_a1b2",
                1, "Coll_c3d4",
                2, "Coll_e5f6"
        );
        BedrockProfileMapper.NameCandidateStrategy collisionTestStrategy =
                new BedrockProfileMapper.NameCandidateStrategy() {
                    @Override
                    public String baseCandidate(String canonicalXuid, String originalGamertag) {
                        return "Twin_1234";
                    }

                    @Override
                    public String collisionCandidate(String canonicalXuid, String originalGamertag, int attempt) {
                        if (sharedForcedPrefix.containsKey(attempt)) {
                            return sharedForcedPrefix.get(attempt);
                        }
                        return BedrockProfileMapper.DEFAULT_STRATEGY
                                .collisionCandidate(canonicalXuid, originalGamertag, attempt);
                    }
                };

        BedrockIdentityStore store = BedrockIdentityStore.inMemory();
        BedrockProfileMapper mapper = new BedrockProfileMapper(store, collisionTestStrategy);

        MappedBedrockProfile p1 = mapper.resolve(
                fullMicrosoftAuth("2535400000004001", "Player A"),
                AuthPolicy.STRICT_MICROSOFT,
                IdentityLookup.empty()
        );
        assertEquals("Twin_1234", p1.javaProfile().name());

        MappedBedrockProfile p2 = mapper.resolve(
                fullMicrosoftAuth("2535400000004002", "Player B"),
                AuthPolicy.STRICT_MICROSOFT,
                IdentityLookup.empty()
        );
        assertEquals("Coll_a1b2", p2.javaProfile().name());

        MappedBedrockProfile p3 = mapper.resolve(
                fullMicrosoftAuth("2535400000004003", "Player C"),
                AuthPolicy.STRICT_MICROSOFT,
                IdentityLookup.empty()
        );
        assertEquals("Coll_c3d4", p3.javaProfile().name());

        MappedBedrockProfile p4 = mapper.resolve(
                fullMicrosoftAuth("2535400000004004", "Player D"),
                AuthPolicy.STRICT_MICROSOFT,
                IdentityLookup.empty()
        );
        assertEquals("Coll_e5f6", p4.javaProfile().name());

        MappedBedrockProfile p5 = mapper.resolve(
                fullMicrosoftAuth("2535400000004005", "Player E"),
                AuthPolicy.STRICT_MICROSOFT,
                IdentityLookup.empty()
        );
        assertTrue(STRICT_JAVA_NAME.matcher(p5.javaProfile().name()).matches());
        assertNotEquals(p1.javaProfile().name().toLowerCase(Locale.ROOT), p5.javaProfile().name().toLowerCase(Locale.ROOT));
        assertNotEquals(p2.javaProfile().name().toLowerCase(Locale.ROOT), p5.javaProfile().name().toLowerCase(Locale.ROOT));
        assertNotEquals(p3.javaProfile().name().toLowerCase(Locale.ROOT), p5.javaProfile().name().toLowerCase(Locale.ROOT));
        assertNotEquals(p4.javaProfile().name().toLowerCase(Locale.ROOT), p5.javaProfile().name().toLowerCase(Locale.ROOT));
        assertTrue(p5.collisionResolved());
    }

    @Test
    void preventsXuidSpoofingViaGamertagImpersonation() {
        BedrockIdentityStore store = BedrockIdentityStore.inMemory();
        BedrockProfileMapper mapper = new BedrockProfileMapper(store);

        // Victim joins first with spaced gamertag -> bound sanitized name
        MappedBedrockProfile victim = mapper.resolve(
                fullMicrosoftAuth("2535400000005001", "Abzak Play 2026"),
                AuthPolicy.STRICT_MICROSOFT,
                IdentityLookup.empty()
        );

        // Attacker with different XUID changes Xbox Gamertag literally to victim's sanitized Java name!
        MappedBedrockProfile attacker = mapper.resolve(
                fullMicrosoftAuth("2535400000005002", victim.javaProfile().name()),
                AuthPolicy.STRICT_MICROSOFT,
                IdentityLookup.empty()
        );
        assertNotEquals(victim.bedrockUuid(), attacker.bedrockUuid());
        assertNotEquals(
                victim.javaProfile().name().toLowerCase(Locale.ROOT),
                attacker.javaProfile().name().toLowerCase(Locale.ROOT)
        );
        assertTrue(attacker.collisionResolved());

        // Second impersonation vector: name held by an in-flight Login/Config session for another XUID
        IdentityLookup inFlightLookup = new IdentityLookup() {
            @Override
            public Optional<UUID> findOnlineJavaUuidByNameIgnoreCase(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<UUID> findCachedJavaUuidByNameIgnoreCase(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<String> findInFlightBedrockXuidByNameIgnoreCase(String name) {
                return "InFlightHero".equalsIgnoreCase(name)
                        ? Optional.of("2535400000005999")
                        : Optional.empty();
            }
        };
        MappedBedrockProfile lateSpoofer = mapper.resolve(
                fullMicrosoftAuth("2535400000005003", "InFlightHero"),
                AuthPolicy.STRICT_MICROSOFT,
                inFlightLookup
        );
        assertNotEquals("inflighthero", lateSpoofer.javaProfile().name().toLowerCase(Locale.ROOT));
        assertTrue(lateSpoofer.collisionResolved());
    }

    @Test
    void rejectsMissingOrInvalidXuidUnderStrictAuth() {
        BedrockProfileMapper mapper = new BedrockProfileMapper(BedrockIdentityStore.inMemory());

        assertThrows(SecurityException.class, () -> mapper.resolve(
                fullMicrosoftAuth("", "NoXuid"), AuthPolicy.STRICT_MICROSOFT, IdentityLookup.empty()));
        assertThrows(SecurityException.class, () -> mapper.resolve(
                fullMicrosoftAuth(null, "NullXuid"), AuthPolicy.STRICT_MICROSOFT, IdentityLookup.empty()));
        assertThrows(SecurityException.class, () -> mapper.resolve(
                fullMicrosoftAuth("abc-123", "AlphaXuid"), AuthPolicy.STRICT_MICROSOFT, IdentityLookup.empty()));

        AuthResult selfSigned = new AuthResult(
                AuthType.SELF_SIGNED,
                false,
                "SelfSignedPlayer",
                UUID.randomUUID(),
                "2535400000006001",
                "",
                DUMMY_PUBLIC_KEY,
                TransportIdentityBinding.State.ACCEPTED,
                "test",
                new ClientDataSummary("26.66", "Test", 1, "en_US", "", "")
        );
        assertThrows(SecurityException.class, () -> mapper.resolve(
                selfSigned, AuthPolicy.STRICT_MICROSOFT, IdentityLookup.empty()));
    }

    @Test
    void concurrentLoginResolutionIsAtomicAndCollisionFree() throws Exception {
        BedrockIdentityStore store = BedrockIdentityStore.inMemory();
        BedrockProfileMapper mapper = new BedrockProfileMapper(store);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<MappedBedrockProfile> task1 = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return mapper.resolve(
                        fullMicrosoftAuth("2535400000007001", "SameName"),
                        AuthPolicy.STRICT_MICROSOFT,
                        IdentityLookup.empty()
                );
            };
            Callable<MappedBedrockProfile> task2 = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return mapper.resolve(
                        fullMicrosoftAuth("2535400000007002", "samename"),
                        AuthPolicy.STRICT_MICROSOFT,
                        IdentityLookup.empty()
                );
            };

            Future<MappedBedrockProfile> f1 = pool.submit(task1);
            Future<MappedBedrockProfile> f2 = pool.submit(task2);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            MappedBedrockProfile r1 = f1.get(5, TimeUnit.SECONDS);
            MappedBedrockProfile r2 = f2.get(5, TimeUnit.SECONDS);

            assertNotEquals(r1.bedrockUuid(), r2.bedrockUuid());
            assertNotEquals(
                    r1.javaProfile().name().toLowerCase(Locale.ROOT),
                    r2.javaProfile().name().toLowerCase(Locale.ROOT)
            );
            assertTrue(STRICT_JAVA_NAME.matcher(r1.javaProfile().name()).matches());
            assertTrue(STRICT_JAVA_NAME.matcher(r2.javaProfile().name()).matches());
            assertEquals(2, store.snapshot().size());
        } finally {
            pool.shutdownNow();
        }
    }

    private static AuthResult fullMicrosoftAuth(String xuid, String displayName) {
        return new AuthResult(
                AuthType.FULL,
                true,
                displayName,
                UUID.nameUUIDFromBytes(("Xbox:" + displayName).getBytes()),
                xuid,
                "mc-id",
                DUMMY_PUBLIC_KEY,
                TransportIdentityBinding.State.ACCEPTED,
                "test-sdp-match",
                new ClientDataSummary("26.66", "Honor 200", 1, "ru_RU", "", "")
        );
    }

    private static PublicKey generateDummyKey() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp384r1"));
            KeyPair kp = kpg.generateKeyPair();
            return kp.getPublic();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
