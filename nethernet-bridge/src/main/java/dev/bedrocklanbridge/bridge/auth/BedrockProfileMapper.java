package dev.bedrocklanbridge.bridge.auth;

import com.mojang.authlib.GameProfile;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deterministic XUID-rooted profile mapper translating verified Bedrock identities into
 * collision-free Java {@link GameProfile} instances without permitting XUID spoofing or
 * gamertag collisions.
 */
public final class BedrockProfileMapper {

    public static final int MAX_COLLISION_ATTEMPTS = 100_000;
    private static final Pattern NUMERIC_XUID = Pattern.compile("^[0-9]{1,32}$");
    private static final Pattern VALID_JAVA_NAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Map<Character, String> CYRILLIC_TRANSLIT = buildCyrillicMap();

    public interface NameCandidateStrategy {
        String baseCandidate(String canonicalXuid, String originalGamertag);

        String collisionCandidate(String canonicalXuid, String originalGamertag, int attempt);
    }

    public static final NameCandidateStrategy DEFAULT_STRATEGY = new DefaultCandidateStrategy();

    private final BedrockIdentityStore store;
    private final NameCandidateStrategy candidateStrategy;
    private final Object resolutionLock = new Object();

    public BedrockProfileMapper(BedrockIdentityStore store) {
        this(store, DEFAULT_STRATEGY);
    }

    public BedrockProfileMapper(BedrockIdentityStore store, NameCandidateStrategy candidateStrategy) {
        this.store = Objects.requireNonNull(store, "store");
        this.candidateStrategy = Objects.requireNonNull(candidateStrategy, "candidateStrategy");
    }

    public static boolean isValidCanonicalXuid(String xuid) {
        return xuid != null && NUMERIC_XUID.matcher(xuid).matches();
    }

    public static boolean isValidJavaUsername(String name) {
        return name != null && VALID_JAVA_NAME.matcher(name).matches();
    }

    public static String requireValidXuid(String rawXuid) {
        String canonical = rawXuid == null ? "" : rawXuid.trim();
        if (!isValidCanonicalXuid(canonical)) {
            throw new SecurityException("Missing or invalid Xbox XUID: \"" + rawXuid + "\"");
        }
        return canonical;
    }

    /**
     * Derives a restart-stable deterministic UUID exclusively from the verified Xbox XUID,
     * completely independent of display gamertag and isolated from vanilla OfflinePlayer UUIDs.
     */
    public static UUID deterministicBedrockUuid(String canonicalXuid) {
        String validXuid = requireValidXuid(canonicalXuid);
        return UUID.nameUUIDFromBytes(("NetherNetBridge:bedrock:xuid:" + validXuid).getBytes(StandardCharsets.UTF_8));
    }

    public MappedBedrockProfile resolve(
            AuthResult authResult,
            AuthPolicy policy,
            IdentityLookup lookup
    ) {
        Objects.requireNonNull(authResult, "authResult");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(lookup, "lookup");

        if (policy.requireMicrosoftAuth()) {
            if (authResult.authType() != AuthType.FULL || !authResult.chainSigned()) {
                throw new SecurityException("STRICT_MICROSOFT policy requires signed AuthType.FULL for profile mapping");
            }
        }

        String canonicalXuid = requireValidXuid(authResult.xuid());
        String originalGamertag = authResult.displayName() == null ? "" : authResult.displayName();
        UUID bedrockUuid = deterministicBedrockUuid(canonicalXuid);

        synchronized (resolutionLock) {
            Optional<BedrockIdentityStore.StoredIdentity> existingOpt = store.findByXuid(canonicalXuid);
            if (existingOpt.isPresent()) {
                BedrockIdentityStore.StoredIdentity existing = existingOpt.get();
                if (isValidJavaUsername(existing.javaName())
                        && isNameAvailableFor(existing.javaName(), bedrockUuid, canonicalXuid, lookup)) {
                    store.updateGamertag(canonicalXuid, originalGamertag);
                    return new MappedBedrockProfile(
                            canonicalXuid,
                            bedrockUuid,
                            new GameProfile(bedrockUuid, existing.javaName()),
                            originalGamertag,
                            !existing.javaName().equals(originalGamertag),
                            false
                    );
                }
            }

            String baseCandidate = candidateStrategy.baseCandidate(canonicalXuid, originalGamertag);
            validateCandidateInvariant(baseCandidate);

            if (isNameAvailableFor(baseCandidate, bedrockUuid, canonicalXuid, lookup)) {
                store.bind(canonicalXuid, bedrockUuid, baseCandidate, originalGamertag);
                return new MappedBedrockProfile(
                        canonicalXuid,
                        bedrockUuid,
                        new GameProfile(bedrockUuid, baseCandidate),
                        originalGamertag,
                        !baseCandidate.equals(originalGamertag),
                        false
                );
            }

            for (int attempt = 0; attempt < MAX_COLLISION_ATTEMPTS; attempt++) {
                String candidate = candidateStrategy.collisionCandidate(canonicalXuid, originalGamertag, attempt);
                validateCandidateInvariant(candidate);
                if (isNameAvailableFor(candidate, bedrockUuid, canonicalXuid, lookup)) {
                    store.bind(canonicalXuid, bedrockUuid, candidate, originalGamertag);
                    return new MappedBedrockProfile(
                            canonicalXuid,
                            bedrockUuid,
                            new GameProfile(bedrockUuid, candidate),
                            originalGamertag,
                            true,
                            true
                    );
                }
            }

            throw new IllegalStateException("Exhausted " + MAX_COLLISION_ATTEMPTS
                    + " deterministic name collision attempts for XUID " + canonicalXuid);
        }
    }

    private boolean isNameAvailableFor(
            String candidate,
            UUID bedrockUuid,
            String canonicalXuid,
            IdentityLookup lookup
    ) {
        Optional<UUID> onlineOwner = lookup.findOnlineJavaUuidByNameIgnoreCase(candidate);
        if (onlineOwner.isPresent() && !onlineOwner.get().equals(bedrockUuid)) {
            return false;
        }
        Optional<UUID> cachedOwner = lookup.findCachedJavaUuidByNameIgnoreCase(candidate);
        if (cachedOwner.isPresent() && !cachedOwner.get().equals(bedrockUuid)) {
            return false;
        }
        Optional<String> inFlightOwner = lookup.findInFlightBedrockXuidByNameIgnoreCase(candidate);
        if (inFlightOwner.isPresent() && !inFlightOwner.get().equals(canonicalXuid)) {
            return false;
        }
        Optional<String> storedOwner = store.findXuidByJavaNameIgnoreCase(candidate);
        return storedOwner.isEmpty() || storedOwner.get().equals(canonicalXuid);
    }

    private static void validateCandidateInvariant(String candidate) {
        if (!isValidJavaUsername(candidate)) {
            throw new IllegalStateException("Generated candidate violates Java username invariant [a-zA-Z0-9_]{3,16}: \""
                    + candidate + "\"");
        }
    }

    public static String sha256Base36(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            String base36 = new BigInteger(1, digest).toString(36);
            if (base36.length() < 50) {
                return "0".repeat(50 - base36.length()) + base36;
            }
            return base36;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String normalizeAsciiPrefix(String rawGamertag, int maxLen, String fallback) {
        if (rawGamertag == null || rawGamertag.isEmpty()) {
            return fallback;
        }
        String decomposed = Normalizer.normalize(rawGamertag, Normalizer.Form.NFKD);
        String strippedMarks = COMBINING_MARKS.matcher(decomposed).replaceAll("");

        StringBuilder transliterated = new StringBuilder(strippedMarks.length() * 2);
        for (int i = 0; i < strippedMarks.length(); i++) {
            char ch = strippedMarks.charAt(i);
            String mapped = CYRILLIC_TRANSLIT.get(ch);
            if (mapped != null) {
                transliterated.append(mapped);
            } else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_') {
                transliterated.append(ch);
            } else if (Character.isWhitespace(ch) || ch == '-' || ch == '.') {
                transliterated.append('_');
            }
        }

        String collapsed = transliterated.toString()
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");

        if (collapsed.length() < 3) {
            return fallback;
        }
        String trimmed = collapsed.length() > maxLen ? collapsed.substring(0, maxLen) : collapsed;
        trimmed = trimmed.replaceAll("_+$", "");
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static final class DefaultCandidateStrategy implements NameCandidateStrategy {
        @Override
        public String baseCandidate(String canonicalXuid, String originalGamertag) {
            if (isValidJavaUsername(originalGamertag)) {
                return originalGamertag;
            }
            String prefix = normalizeAsciiPrefix(originalGamertag, 7, "Bedrock");
            String stream = sha256Base36("NetherNetBridge:name:v1:" + canonicalXuid);
            return prefix + "_" + stream.substring(0, 8);
        }

        @Override
        public String collisionCandidate(String canonicalXuid, String originalGamertag, int attempt) {
            String iterHash = sha256Base36("NetherNetBridge:collision:v1:" + canonicalXuid + ":" + attempt);
            String attemptTag = Integer.toString(attempt, 36);
            if (attempt < 36) {
                String shortPrefix = normalizeAsciiPrefix(originalGamertag, 4, "Br");
                if (shortPrefix.length() > 4) {
                    shortPrefix = shortPrefix.substring(0, 4);
                }
                int hashLen = Math.min(iterHash.length(), 16 - shortPrefix.length() - 1 - attemptTag.length());
                return shortPrefix + "_" + attemptTag + iterHash.substring(0, hashLen);
            }
            String head = "bx" + attemptTag + "_";
            int hashLen = Math.min(iterHash.length(), 16 - head.length());
            return head + iterHash.substring(0, hashLen);
        }
    }

    private static Map<Character, String> buildCyrillicMap() {
        Map<Character, String> m = new java.util.HashMap<>();
        String[][] pairs = {
                {"А", "A"}, {"Б", "B"}, {"В", "V"}, {"Г", "G"}, {"Д", "D"}, {"Е", "E"}, {"Ё", "Yo"},
                {"Ж", "Zh"}, {"З", "Z"}, {"И", "I"}, {"Й", "Y"}, {"К", "K"}, {"Л", "L"}, {"М", "M"},
                {"Н", "N"}, {"О", "O"}, {"П", "P"}, {"Р", "R"}, {"С", "S"}, {"Т", "T"}, {"У", "U"},
                {"Ф", "F"}, {"Х", "Kh"}, {"Ц", "Ts"}, {"Ч", "Ch"}, {"Ш", "Sh"}, {"Щ", "Shch"},
                {"Ъ", ""}, {"Ы", "Y"}, {"Ь", ""}, {"Э", "E"}, {"Ю", "Yu"}, {"Я", "Ya"},
                {"І", "I"}, {"Ї", "Yi"}, {"Є", "Ye"}, {"Ґ", "G"},
                {"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"д", "d"}, {"е", "e"}, {"ё", "yo"},
                {"ж", "zh"}, {"з", "z"}, {"и", "i"}, {"й", "y"}, {"к", "k"}, {"л", "l"}, {"м", "m"},
                {"н", "n"}, {"о", "o"}, {"п", "p"}, {"р", "r"}, {"с", "s"}, {"т", "t"}, {"у", "u"},
                {"ф", "f"}, {"х", "kh"}, {"ц", "ts"}, {"ч", "ch"}, {"ш", "sh"}, {"щ", "shch"},
                {"ъ", ""}, {"ы", "y"}, {"ь", ""}, {"э", "e"}, {"ю", "yu"}, {"я", "ya"},
                {"і", "i"}, {"ї", "yi"}, {"є", "ye"}, {"ґ", "g"}
        };
        for (String[] p : pairs) {
            m.put(p[0].charAt(0), p[1]);
        }
        return Map.copyOf(m);
    }
}
