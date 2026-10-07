package dev.forbrick.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.forbrick.runtime.Brick;
import dev.forbrick.runtime.BrickNotice;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Installs a reversible, per-version launcher main class replacement. */
public final class InstallerService {
    private static final String MAIN = "dev.forbrick.runtime.Brick";
    private static final String COORDINATE = "dev.forbrick:runtime:1.0.1";
    private static final String LIBRARY_RELATIVE = "dev/forbrick/runtime/1.0.1/runtime-1.0.1.jar";
    private static final String DOWNLOAD_URL = "https://github.com/Trcmoe/ForBrick/releases/download/v1.0.1/forbrick-runtime-1.0.1.jar";
    private static final String LEGACY_COORDINATE = "dev.forbrick:runtime:1.0.0";
    private static final String LEGACY_LIBRARY_RELATIVE = "dev/forbrick/runtime/1.0.0/runtime-1.0.0.jar";
    private static final String LEGACY_DOWNLOAD_URL = "https://github.com/Trcmoe/ForBrick/releases/download/v1.0.0/forbrick-runtime-1.0.0.jar";
    private static final String LEGACY_JAR_SHA256 = "244068776d1f2a231dcee81845a17d0327fca3ce79f076e4c7a0a7ecd0cdbb63";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    // One fixed loopback endpoint serializes every ForBrick process without leaving lock files.
    // Never accept or connect: the bound socket is only an OS-owned mutex and sends no data.
    private static final int LOCK_PORT = 47691;
    private static final ReentrantLock LOCAL_LOCK = new ReentrantLock();

    private final Path root;

    public InstallerService(Path minecraftRoot) {
        if (minecraftRoot == null) throw new IllegalArgumentException("Minecraft directory is required");
        this.root = minecraftRoot.toAbsolutePath().normalize();
    }

    public List<VersionInfo> listVersions() throws IOException {
        return locked(new Action<List<VersionInfo>>() {
            @Override public List<VersionInfo> run() throws IOException {
                Set<String> managed = new HashSet<String>(stateIds());
                Path versions = checked(root.resolve("versions"));
                List<VersionInfo> result = new ArrayList<VersionInfo>();
                if (Files.isDirectory(versions, LinkOption.NOFOLLOW_LINKS)) {
                    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(versions)) {
                        for (Path dir : dirs) {
                            String id = dir.getFileName().toString();
                            if (!validId(id)) continue;
                            boolean installed = managed.remove(id);
                            try {
                                checked(dir);
                                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                                Path json = checked(dir.resolve(id + ".json"));
                                if (!Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS)) {
                                    if (installed) result.add(new VersionInfo(id, null, true));
                                    continue;
                                }
                                JsonObject profile = parseProfile(Files.readAllBytes(json), json);
                                result.add(new VersionInfo(id, mainClass(profile), installed));
                            } catch (IOException e) {
                                if (installed) result.add(new VersionInfo(id, null, true));
                                else throw e;
                            }
                        }
                    }
                }
                for (String id : managed) result.add(new VersionInfo(id, null, true));
                Collections.sort(result, new Comparator<VersionInfo>() {
                    @Override public int compare(VersionInfo a, VersionInfo b) { return a.id.compareToIgnoreCase(b.id); }
                });
                return result;
            }
        });
    }

    public List<VersionInfo> listInstalled() throws IOException {
        List<VersionInfo> installed = new ArrayList<VersionInfo>();
        for (VersionInfo version : listVersions()) if (version.installed) installed.add(version);
        return installed;
    }

    public void install(final String id) throws IOException {
        requireId(id);
        locked(new Action<Void>() {
            @Override public Void run() throws IOException {
                Path json = versionJson(id);
                byte[] original = Files.readAllBytes(json);
                State old = readState(id);
                if (old != null) {
                    if (same(original, old.original)) {
                        Files.delete(statePath(id)); // interrupted before the version was changed
                    } else {
                        JsonObject profile = parseProfile(original, json);
                        boolean legacy = requireOwned(profile, id);
                        ensureRuntime(); // repair a missing local library on a repeated install
                        if (!legacy) return null;
                        JsonObject changed = profile.deepCopy();
                        JsonArray libs = libraries(changed, false);
                        for (int i = 0; i < libs.size(); i++) {
                            if (isLegacyLibrary(libs.get(i))) {
                                libs.set(i, libraryEntry());
                                break;
                            }
                        }
                        byte[] applied = jsonBytes(changed);
                        // Record the new checksum before changing the profile only when the
                        // legacy profile was pristine. Otherwise uninstall must merge edits.
                        String upgradedHash = hash("SHA-256", original).equals(old.appliedHash)
                                ? hash("SHA-256", applied) : null;
                        writeAtomic(statePath(id), stateBytes(new State(id, old.original, old.appliedHash,
                                upgradedHash)));
                        writeAtomic(json, applied);
                        return null;
                    }
                }
                JsonObject profile = parseProfile(original, json);
                if (MAIN.equals(mainClass(profile)) || containsCoordinate(profile))
                    throw new IOException("Version " + id + " already contains ForBrick settings without a recovery record");
                JsonObject changed = profile.deepCopy();
                changed.addProperty("mainClass", MAIN);
                JsonArray libraries = libraries(changed, true);
                libraries.add(libraryEntry());
                byte[] applied = jsonBytes(changed);
                ensureRuntime();
                State state = new State(id, original, hash("SHA-256", applied));
                writeAtomic(statePath(id), stateBytes(state)); // recovery record precedes the version edit
                writeAtomic(json, applied);
                return null;
            }
        });
    }

    public void uninstall(final String id) throws IOException {
        requireId(id);
        locked(new Action<Void>() {
            @Override public Void run() throws IOException {
                uninstallInsideLock(id);
                cleanupRuntimeIfUnused();
                cleanupMetadataDirs();
                return null;
            }
        });
    }

    public void uninstallAll() throws IOException {
        locked(new Action<Void>() {
            @Override public Void run() throws IOException {
                List<String> ids = stateIds();
                IOException failure = null;
                for (String id : ids) {
                    try { uninstallInsideLock(id); }
                    catch (IOException e) {
                        if (failure == null) failure = new IOException("Some versions could not be restored");
                        failure.addSuppressed(new IOException("Version " + id + ": " + e.getMessage(), e));
                    }
                }
                if (failure == null) {
                    cleanupRuntimeIfUnused();
                    cleanupMetadataDirs();
                } else {
                    StringBuilder message = new StringBuilder("Some versions could not be restored:");
                    for (Throwable item : failure.getSuppressed()) message.append("\n- ").append(item.getMessage());
                    throw new IOException(message.toString(), failure);
                }
                return null;
            }
        });
    }

    public static Path defaultMinecraftDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            return appdata != null && !appdata.isEmpty() ? Paths.get(appdata, ".minecraft") : Paths.get(home, "AppData", "Roaming", ".minecraft");
        }
        if (os.contains("mac")) return Paths.get(home, "Library", "Application Support", "minecraft");
        return Paths.get(home, ".minecraft");
    }

    /** Deterministic, tiny standalone JAR. No Gson or installer classes are included. */
    public static byte[] runtimeJar() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            JarEntry manifest = new JarEntry("META-INF/MANIFEST.MF");
            manifest.setTime(0L);
            jar.putNextEntry(manifest);
            jar.write(("Manifest-Version: 1.0\r\nMain-Class: " + MAIN + "\r\nPremain-Class: " + MAIN + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            addRuntimeClass(jar, Brick.class);
            addRuntimeClass(jar, BrickNotice.class);
        }
        return out.toByteArray();
    }

    private static void addRuntimeClass(JarOutputStream jar, Class<?> type) throws IOException {
        String name = type.getName().replace('.', '/') + ".class";
        JarEntry clazz = new JarEntry(name);
        clazz.setTime(0L);
        jar.putNextEntry(clazz);
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (in == null) throw new IOException("Packaged " + name + " resource is missing");
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) jar.write(buffer, 0, count);
        }
        jar.closeEntry();
    }

    private void uninstallInsideLock(String id) throws IOException {
        State state = readState(id);
        if (state == null) {
            Path json = versionJson(id);
            JsonObject current = parseProfile(Files.readAllBytes(json), json);
            if (MAIN.equals(mainClass(current)) || containsCoordinate(current))
                throw new IOException("Version " + id + " has ForBrick settings but no recovery record; refusing to guess its original settings");
            return;
        }
        Path json = versionJson(id);
        byte[] current = Files.readAllBytes(json);
        if (same(current, state.original)) {
            Files.delete(statePath(id)); // interrupted cleanup, or install stopped before edit
            return;
        }
        JsonObject profile = parseProfile(current, json);
        requireOwned(profile, id);
        byte[] restored;
        String currentHash = hash("SHA-256", current);
        if (currentHash.equals(state.appliedHash) || currentHash.equals(state.upgradedHash)) {
            restored = state.original; // exact bytes, whitespace and ordering included
        } else {
            JsonObject original = parseProfile(state.original, statePath(id));
            JsonObject merged = profile.deepCopy();
            if (original.has("mainClass")) merged.add("mainClass", original.get("mainClass"));
            else merged.remove("mainClass");
            JsonArray libs = libraries(merged, false);
            for (int i = 0; i < libs.size(); i++) {
                if (isOurLibrary(libs.get(i)) || isLegacyLibrary(libs.get(i))) { libs.remove(i); break; }
            }
            if (!original.has("libraries") && libs.size() == 0) merged.remove("libraries");
            restored = jsonBytes(merged);
        }
        writeAtomic(json, restored);
        Files.delete(statePath(id));
    }

    /** Returns whether this profile still references the exact 1.0.0 library entry. */
    private boolean requireOwned(JsonObject profile, String id) throws IOException {
        if (!MAIN.equals(mainClass(profile)))
            throw new IOException("Version " + id + " mainClass changed since installation; recovery record retained");
        JsonArray libs = libraries(profile, false);
        int found = 0;
        boolean legacy = false;
        for (JsonElement e : libs) {
            if (isOurLibrary(e)) found++;
            else if (isLegacyLibrary(e)) { found++; legacy = true; }
        }
        if (found != 1 || countCoordinate(profile) != 1)
            throw new IOException("Version " + id + " ForBrick library changed since installation; recovery record retained");
        return legacy;
    }

    private JsonObject libraryEntry() throws IOException {
        return libraryEntry(COORDINATE, LIBRARY_RELATIVE, DOWNLOAD_URL, runtimeJar());
    }

    private JsonObject legacyLibraryEntry() throws IOException {
        return libraryEntry(LEGACY_COORDINATE, LEGACY_LIBRARY_RELATIVE, LEGACY_DOWNLOAD_URL, legacyRuntimeJar());
    }

    private static JsonObject libraryEntry(String coordinate, String path, String url, byte[] jar) {
        JsonObject artifact = new JsonObject();
        artifact.addProperty("path", path);
        artifact.addProperty("url", url);
        artifact.addProperty("sha1", hash("SHA-1", jar));
        artifact.addProperty("size", jar.length);
        JsonObject downloads = new JsonObject();
        downloads.add("artifact", artifact);
        JsonObject library = new JsonObject();
        library.addProperty("name", coordinate);
        library.add("downloads", downloads);
        return library;
    }

    private boolean isOurLibrary(JsonElement entry) throws IOException {
        return entry.isJsonObject() && libraryEntry().equals(entry.getAsJsonObject());
    }
    private boolean isLegacyLibrary(JsonElement entry) throws IOException {
        return entry.isJsonObject() && legacyLibraryEntry().equals(entry.getAsJsonObject());
    }

    private static byte[] legacyRuntimeJar() throws IOException {
        try (InputStream in = InstallerService.class.getResourceAsStream("/dev/forbrick/runtime/forbrick-runtime-1.0.0.jar")) {
            if (in == null) throw new IOException("Packaged legacy runtime resource is missing");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            byte[] jar = out.toByteArray();
            if (!LEGACY_JAR_SHA256.equals(hash("SHA-256", jar)))
                throw new IOException("Packaged legacy runtime failed integrity check");
            return jar;
        }
    }

    private boolean containsCoordinate(JsonObject profile) throws IOException { return countCoordinate(profile) != 0; }
    private int countCoordinate(JsonObject profile) throws IOException {
        int found = 0;
        for (JsonElement item : libraries(profile, false)) {
            if (item.isJsonObject()) {
                JsonElement name = item.getAsJsonObject().get("name");
                if (name != null && name.isJsonPrimitive()
                        && (COORDINATE.equals(name.getAsString()) || LEGACY_COORDINATE.equals(name.getAsString()))) found++;
            }
        }
        return found;
    }

    private void ensureRuntime() throws IOException {
        byte[] expected = runtimeJar();
        Path jar = checked(root.resolve("libraries").resolve(LIBRARY_RELATIVE));
        Path marker = checked(root.resolve(".forbrick").resolve("runtime-owned-1.0.1.sha256"));
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            String ownedHash = new String(Files.readAllBytes(marker), StandardCharsets.US_ASCII).trim();
            if (!ownedHash.equals(hash("SHA-256", expected))) throw new IOException("ForBrick runtime ownership marker is unexpected");
        }
        if (Files.exists(jar, LinkOption.NOFOLLOW_LINKS)) {
            if (!same(Files.readAllBytes(jar), expected)) throw new IOException("Runtime library path already contains a different file: " + jar);
            return;
        }
        createParents(jar.getParent());
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS))
            writeAtomic(marker, (hash("SHA-256", expected) + "\n").getBytes(StandardCharsets.US_ASCII));
        writeAtomic(jar, expected);
    }

    private void cleanupRuntimeIfUnused() throws IOException {
        if (!stateIds().isEmpty()) return;
        List<Path> staged = stagedFiles();
        if (!staged.isEmpty()) throw new IOException("Interrupted ForBrick temporary files need review before cleanup: " + staged);
        List<String> unknown = unmanagedHooks();
        if (!unknown.isEmpty()) throw new IOException("Unmanaged ForBrick settings remain in versions " + unknown
                + "; runtime retained because their original settings are unknown");
        Path currentMarker = checked(root.resolve(".forbrick").resolve("runtime-owned-1.0.1.sha256"));
        Path legacyMarker = checked(root.resolve(".forbrick").resolve("runtime-owned.sha256"));
        Path currentJar = checked(root.resolve("libraries").resolve(LIBRARY_RELATIVE));
        Path legacyJar = checked(root.resolve("libraries").resolve(LEGACY_LIBRARY_RELATIVE));
        // Validate both libraries before deleting either: mixed installs may need both.
        boolean currentOwned = validateRuntimeOwnership(currentMarker, currentJar, runtimeJar());
        boolean legacyOwned = validateRuntimeOwnership(legacyMarker, legacyJar, legacyRuntimeJar());
        if (currentOwned) { Files.deleteIfExists(currentJar); Files.delete(currentMarker); }
        if (legacyOwned) { Files.deleteIfExists(legacyJar); Files.delete(legacyMarker); }
        if (currentOwned) pruneEmpty(currentJar.getParent(), root.resolve("libraries"));
        if (legacyOwned) pruneEmpty(legacyJar.getParent(), root.resolve("libraries"));
    }

    private boolean validateRuntimeOwnership(Path marker, Path jar, byte[] expected) throws IOException {
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false;
        String ownedHash = new String(Files.readAllBytes(marker), StandardCharsets.US_ASCII).trim();
        if (!ownedHash.equals(hash("SHA-256", expected))) throw new IOException("ForBrick runtime ownership marker is unexpected");
        if (Files.exists(jar, LinkOption.NOFOLLOW_LINKS) && !same(Files.readAllBytes(jar), expected))
            throw new IOException("Runtime library was changed; it was not removed: " + jar);
        return true;
    }

    private List<Path> stagedFiles() throws IOException {
        List<Path> files = new ArrayList<Path>();
        collectStaged(root.resolve(".forbrick"), files);
        collectStaged(root.resolve(".forbrick").resolve("versions"), files);
        collectStaged(root.resolve("libraries").resolve("dev/forbrick/runtime/1.0.0"), files);
        collectStaged(root.resolve("libraries").resolve("dev/forbrick/runtime/1.0.1"), files);
        Path versions = checked(root.resolve("versions"));
        if (Files.isDirectory(versions, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(versions)) {
                for (Path dir : dirs) {
                    checked(dir);
                    if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) collectStaged(dir, files);
                }
            }
        }
        Collections.sort(files);
        return files;
    }

    private void collectStaged(Path directory, List<Path> files) throws IOException {
        directory = checked(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, ".forbrick-*.tmp")) {
            for (Path file : stream) files.add(checked(file));
        }
    }

    private List<String> unmanagedHooks() throws IOException {
        Path versions = checked(root.resolve("versions"));
        if (!Files.isDirectory(versions, LinkOption.NOFOLLOW_LINKS)) return Collections.emptyList();
        Set<String> managed = new HashSet<String>(stateIds());
        List<String> unknown = new ArrayList<String>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(versions)) {
            for (Path dir : dirs) {
                String id = dir.getFileName().toString();
                if (!validId(id) || managed.contains(id)) continue;
                checked(dir);
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                Path json = checked(dir.resolve(id + ".json"));
                if (!Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS)) continue;
                byte[] bytes = Files.readAllBytes(json);
                try {
                    JsonObject profile = parseProfile(bytes, json);
                    if (MAIN.equals(mainClass(profile)) || containsCoordinate(profile)) unknown.add(id);
                } catch (IOException malformed) {
                    String raw = new String(bytes, StandardCharsets.UTF_8);
                    if (raw.contains(MAIN) || raw.contains(COORDINATE) || raw.contains(LEGACY_COORDINATE)) unknown.add(id + " (malformed JSON)");
                }
            }
        }
        Collections.sort(unknown);
        return unknown;
    }

    private void cleanupMetadataDirs() throws IOException {
        Path metadata = checked(root.resolve(".forbrick"));
        Path records = checked(metadata.resolve("versions"));
        if (Files.isDirectory(records, LinkOption.NOFOLLOW_LINKS)) deleteIfEmpty(records);
        if (Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS)) deleteIfEmpty(metadata);
    }

    private void deleteIfEmpty(Path directory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            if (stream.iterator().hasNext()) return;
        }
        Files.delete(directory);
    }

    private List<String> stateIds() throws IOException {
        Path dir = checked(root.resolve(".forbrick").resolve("versions"));
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return Collections.emptyList();
        List<String> ids = new ArrayList<String>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                checked(file);
                String encoded = file.getFileName().toString();
                encoded = encoded.substring(0, encoded.length() - 5);
                if ((encoded.length() & 1) != 0 || encoded.isEmpty() || !encoded.matches("[0-9a-f]+"))
                    throw new IOException("Unexpected recovery record filename: " + file);
                byte[] utf8 = new byte[encoded.length() / 2];
                for (int i = 0; i < utf8.length; i++) utf8[i] = (byte) Integer.parseInt(encoded.substring(i * 2, i * 2 + 2), 16);
                String id = new String(utf8, StandardCharsets.UTF_8);
                if (!validId(id) || !statePath(id).equals(file)) throw new IOException("Unexpected recovery record filename: " + file);
                ids.add(id);
            }
        }
        Collections.sort(ids);
        return ids;
    }

    private State readState(String id) throws IOException {
        Path path = statePath(id);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        JsonObject data = parseProfile(Files.readAllBytes(path), path);
        if (!id.equals(requiredString(data, "id", path))) throw new IOException("Recovery record belongs to another version: " + path);
        byte[] original;
        try { original = Base64.getDecoder().decode(requiredString(data, "originalBase64", path)); }
        catch (IllegalArgumentException e) { throw new IOException("Recovery record contains invalid original data: " + path, e); }
        if (!hash("SHA-256", original).equals(requiredString(data, "originalSha256", path)))
            throw new IOException("Recovery record failed integrity check: " + path);
        String applied = requiredString(data, "appliedSha256", path);
        if (!applied.matches("[0-9a-f]{64}")) throw new IOException("Recovery record has invalid applied checksum: " + path);
        String upgraded = null;
        if (data.has("upgradedSha256")) {
            upgraded = requiredString(data, "upgradedSha256", path);
            if (!upgraded.matches("[0-9a-f]{64}"))
                throw new IOException("Recovery record has invalid upgraded checksum: " + path);
        }
        parseProfile(original, path); // ensure restore source remains usable
        return new State(id, original, applied, upgraded);
    }

    private byte[] stateBytes(State state) {
        JsonObject data = new JsonObject();
        data.addProperty("id", state.id);
        data.addProperty("originalBase64", Base64.getEncoder().encodeToString(state.original));
        data.addProperty("originalSha256", hash("SHA-256", state.original));
        data.addProperty("appliedSha256", state.appliedHash);
        if (state.upgradedHash != null) data.addProperty("upgradedSha256", state.upgradedHash);
        return jsonBytes(data);
    }

    private Path statePath(String id) throws IOException {
        StringBuilder encoded = new StringBuilder();
        for (byte b : id.getBytes(StandardCharsets.UTF_8)) encoded.append(String.format("%02x", b & 0xff));
        return checked(root.resolve(".forbrick").resolve("versions").resolve(encoded + ".json"));
    }

    private Path versionJson(String id) throws IOException {
        Path dir = checked(root.resolve("versions").resolve(id));
        Path json = checked(dir.resolve(id + ".json"));
        if (!Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Version JSON is missing: " + json);
        return json;
    }

    private static JsonObject parseProfile(byte[] bytes, Path path) throws IOException {
        try {
            JsonElement value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!value.isJsonObject()) throw new IOException("Expected a JSON object: " + path);
            return value.getAsJsonObject();
        } catch (RuntimeException e) { throw new IOException("Malformed JSON: " + path, e); }
    }

    private static String requiredString(JsonObject object, String key, Path path) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IOException("Missing or invalid " + key + " in " + path);
        return value.getAsString();
    }

    private static String mainClass(JsonObject profile) throws IOException {
        JsonElement value = profile.get("mainClass");
        if (value == null) return null; // inherited profiles often omit this key
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IOException("Invalid mainClass in version JSON");
        return value.getAsString();
    }

    private static JsonArray libraries(JsonObject profile, boolean create) throws IOException {
        JsonElement value = profile.get("libraries");
        if (value == null) {
            JsonArray array = new JsonArray();
            if (create) profile.add("libraries", array);
            return array;
        }
        if (!value.isJsonArray()) throw new IOException("Invalid libraries array in version JSON");
        return value.getAsJsonArray();
    }

    private static byte[] jsonBytes(JsonObject data) { return (GSON.toJson(data) + "\n").getBytes(StandardCharsets.UTF_8); }

    private static String hash(String algorithm, byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static boolean same(byte[] a, byte[] b) { return java.util.Arrays.equals(a, b); }

    private static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.equals(".") || id.equals("..") || id.endsWith(" ") || id.endsWith(".")) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 32 || c == 127 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') return false;
        }
        return true;
    }

    private static void requireId(String id) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid local version id: " + id);
    }

    private Path checked(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) throw new IOException("Path escapes Minecraft directory: " + path);
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(root) || Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther())
                throw new IOException("Minecraft directory is a link or junction: " + root);
            Path realRoot = root.toRealPath();
            Path part = root;
            for (Path name : root.relativize(absolute)) {
                part = part.resolve(name);
                if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
                    java.nio.file.attribute.BasicFileAttributes attr = Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attr.isSymbolicLink() || attr.isOther() || !part.toRealPath().startsWith(realRoot))
                        throw new IOException("Refusing linked path inside Minecraft directory: " + part);
                }
            }
        }
        return absolute;
    }

    private void createParents(Path parent) throws IOException {
        if (parent.equals(root)) return;
        createParents(parent.getParent());
        Path checked = checked(parent);
        if (!Files.exists(checked, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(checked);
        checked(checked);
    }

    private void writeAtomic(Path target, byte[] data) throws IOException {
        target = checked(target);
        createParents(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".forbrick-", ".tmp");
        try {
            Files.write(temp, data, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            checked(target);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private void pruneEmpty(Path path, Path stop) throws IOException {
        while (path != null && path.startsWith(stop) && !path.equals(stop.getParent())) {
            checked(path);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                if (stream.iterator().hasNext()) return;
            }
            Files.delete(path);
            if (path.equals(stop)) return;
            path = path.getParent();
        }
    }

    private <T> T locked(Action<T> action) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Minecraft directory does not exist: " + root);
        checked(root);
        LOCAL_LOCK.lock();
        try (ServerSocket mutex = bindMutex()) {
            return action.run();
        } finally { LOCAL_LOCK.unlock(); }
    }

    private static ServerSocket bindMutex() throws IOException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), LOCK_PORT);
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (true) {
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(false);
                socket.bind(address, 1);
                return socket;
            } catch (BindException busy) {
                socket.close();
                if (System.nanoTime() >= deadline)
                    throw new IOException("ForBrick is busy: local lock port " + LOCK_PORT + " remained occupied for 15 seconds", busy);
                try { Thread.sleep(50L); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for ForBrick lock", e);
                }
            } catch (IOException | SecurityException e) {
                socket.close();
                throw new IOException("Cannot reserve ForBrick local lock port " + LOCK_PORT, e);
            }
        }
    }

    private interface Action<T> { T run() throws IOException; }
    private static final class State {
        final String id;
        final byte[] original;
        final String appliedHash;
        final String upgradedHash;
        State(String id, byte[] original, String appliedHash) { this(id, original, appliedHash, null); }
        State(String id, byte[] original, String appliedHash, String upgradedHash) {
            this.id = id; this.original = original; this.appliedHash = appliedHash; this.upgradedHash = upgradedHash;
        }
    }
}
