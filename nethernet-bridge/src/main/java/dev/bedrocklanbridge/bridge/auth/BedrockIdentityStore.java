package dev.bedrocklanbridge.bridge.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent XUID-keyed identity registry maintaining 1:1 case-insensitive ownership
 * between Xbox XUIDs and bound Java {@code GameProfile} usernames across restarts.
 */
public final class BedrockIdentityStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockIdentityStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type RECORD_LIST_TYPE = new TypeToken<List<SerializedEntry>>() {}.getType();

    public record StoredIdentity(
            String xuid,
            UUID bedrockUuid,
            String javaName,
            String originalGamertag,
            long updatedAtEpochMs
    ) {
    }

    private static final class SerializedEntry {
        String xuid;
        String bedrockUuid;
        String javaName;
        String originalGamertag;
        long updatedAtEpochMs;
    }

    private final Path storagePath;
    private final Map<String, StoredIdentity> byXuid = new LinkedHashMap<>();
    private final Map<String, String> xuidByLowerJavaName = new LinkedHashMap<>();

    private BedrockIdentityStore(Path storagePath) {
        this.storagePath = storagePath;
    }

    public static BedrockIdentityStore inMemory() {
        return new BedrockIdentityStore(null);
    }

    public static BedrockIdentityStore open(Path storagePath) {
        Objects.requireNonNull(storagePath, "storagePath");
        BedrockIdentityStore store = new BedrockIdentityStore(storagePath);
        store.load();
        return store;
    }

    public synchronized Optional<StoredIdentity> findByXuid(String xuid) {
        return Optional.ofNullable(byXuid.get(xuid));
    }

    public synchronized Optional<String> findXuidByJavaNameIgnoreCase(String javaName) {
        if (javaName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(xuidByLowerJavaName.get(javaName.toLowerCase(Locale.ROOT)));
    }

    public synchronized StoredIdentity bind(
            String xuid,
            UUID bedrockUuid,
            String javaName,
            String originalGamertag
    ) {
        Objects.requireNonNull(xuid, "xuid");
        Objects.requireNonNull(bedrockUuid, "bedrockUuid");
        Objects.requireNonNull(javaName, "javaName");
        String safeTag = originalGamertag == null ? "" : originalGamertag;
        String lowerNewName = javaName.toLowerCase(Locale.ROOT);

        String currentOwner = xuidByLowerJavaName.get(lowerNewName);
        if (currentOwner != null && !currentOwner.equals(xuid)) {
            throw new IllegalStateException("Java username '" + javaName + "' is already bound to XUID " + currentOwner);
        }

        StoredIdentity previous = byXuid.get(xuid);
        if (previous != null) {
            String previousLower = previous.javaName().toLowerCase(Locale.ROOT);
            if (!previousLower.equals(lowerNewName) && xuid.equals(xuidByLowerJavaName.get(previousLower))) {
                xuidByLowerJavaName.remove(previousLower);
            }
        }

        StoredIdentity updated = new StoredIdentity(
                xuid,
                bedrockUuid,
                javaName,
                safeTag,
                System.currentTimeMillis()
        );
        byXuid.put(xuid, updated);
        xuidByLowerJavaName.put(lowerNewName, xuid);
        saveQuietly();
        return updated;
    }

    public synchronized StoredIdentity updateGamertag(String xuid, String originalGamertag) {
        StoredIdentity existing = byXuid.get(xuid);
        if (existing == null) {
            throw new IllegalStateException("Cannot update gamertag for unknown XUID: " + xuid);
        }
        String safeTag = originalGamertag == null ? "" : originalGamertag;
        if (safeTag.equals(existing.originalGamertag())) {
            return existing;
        }
        StoredIdentity updated = new StoredIdentity(
                existing.xuid(),
                existing.bedrockUuid(),
                existing.javaName(),
                safeTag,
                System.currentTimeMillis()
        );
        byXuid.put(xuid, updated);
        saveQuietly();
        return updated;
    }

    public synchronized Map<String, StoredIdentity> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(byXuid));
    }

    private void load() {
        if (storagePath == null || !Files.exists(storagePath)) {
            return;
        }
        try {
            String json = Files.readString(storagePath, StandardCharsets.UTF_8);
            List<SerializedEntry> entries = GSON.fromJson(json, RECORD_LIST_TYPE);
            if (entries == null) {
                return;
            }
            for (SerializedEntry raw : entries) {
                if (raw == null || !BedrockProfileMapper.isValidCanonicalXuid(raw.xuid)
                        || !BedrockProfileMapper.isValidJavaUsername(raw.javaName)
                        || raw.bedrockUuid == null) {
                    LOGGER.warn("[NetherNetBridge] Skipping invalid identity entry in {}", storagePath);
                    continue;
                }
                UUID parsedUuid;
                try {
                    parsedUuid = UUID.fromString(raw.bedrockUuid);
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("[NetherNetBridge] Skipping identity with invalid UUID {} in {}", raw.bedrockUuid, storagePath);
                    continue;
                }
                String lower = raw.javaName.toLowerCase(Locale.ROOT);
                if (xuidByLowerJavaName.containsKey(lower)) {
                    LOGGER.warn("[NetherNetBridge] Skipping duplicate javaName '{}' for XUID {} in {}", raw.javaName, raw.xuid, storagePath);
                    continue;
                }
                StoredIdentity entry = new StoredIdentity(
                        raw.xuid,
                        parsedUuid,
                        raw.javaName,
                        raw.originalGamertag == null ? "" : raw.originalGamertag,
                        raw.updatedAtEpochMs
                );
                byXuid.put(raw.xuid, entry);
                xuidByLowerJavaName.put(lower, raw.xuid);
            }
        } catch (Exception e) {
            LOGGER.error("[NetherNetBridge] Failed loading identity store from {}", storagePath, e);
        }
    }

    private void saveQuietly() {
        if (storagePath == null) {
            return;
        }
        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<SerializedEntry> serialized = new ArrayList<>(byXuid.size());
            for (StoredIdentity identity : byXuid.values()) {
                SerializedEntry entry = new SerializedEntry();
                entry.xuid = identity.xuid();
                entry.bedrockUuid = identity.bedrockUuid().toString();
                entry.javaName = identity.javaName();
                entry.originalGamertag = identity.originalGamertag();
                entry.updatedAtEpochMs = identity.updatedAtEpochMs();
                serialized.add(entry);
            }
            String json = GSON.toJson(serialized, RECORD_LIST_TYPE);
            Path tmp = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, storagePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, storagePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("[NetherNetBridge] Failed saving identity store to {}", storagePath, e);
        }
    }
}
