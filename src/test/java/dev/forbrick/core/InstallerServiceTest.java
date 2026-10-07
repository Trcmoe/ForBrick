package dev.forbrick.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.GsonBuilder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarInputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class InstallerServiceTest {
    private Path root;
    private InstallerService service;
    private String priorTemp;
    private Path testTemp;

    @Before public void create() throws IOException {
        testTemp = java.nio.file.Paths.get(System.getProperty("user.dir"), ".local", "test-tmp");
        Files.createDirectories(testTemp);
        priorTemp = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", testTemp.toString());
        root = Files.createTempDirectory(testTemp, "forbrick-core-");
        Files.createDirectory(root.resolve("versions"));
        service = new InstallerService(root);
    }

    @After public void remove() throws IOException {
        if (priorTemp != null) System.setProperty("java.io.tmpdir", priorTemp);
        if (root == null) return;
        List<Path> paths = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) { stream.forEach(paths::add); }
        java.util.Collections.sort(paths, java.util.Collections.reverseOrder());
        for (Path path : paths) Files.deleteIfExists(path);
    }

    @Test public void exactRestoreAndTinyDeterministicJar() throws Exception {
        String source = "{\r\n  \"id\": \"1.21.1\", \"mainClass\": \"net.minecraft.client.main.Main\",\r\n  \"libraries\": []\r\n}\r\n";
        Path json = profile("1.21.1", source);
        service.install("1.21.1");
        assertEquals("dev.forbrick.runtime.Brick", read(json).get("mainClass").getAsString());
        assertEquals(1, read(json).getAsJsonArray("libraries").size());
        Path jar = runtimePath();
        assertArrayEquals(InstallerService.runtimeJar(), Files.readAllBytes(jar));
        assertArrayEquals(InstallerService.runtimeJar(), InstallerService.runtimeJar());
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(Files.readAllBytes(jar)))) {
            assertEquals("dev.forbrick.runtime.Brick", in.getManifest().getMainAttributes().getValue("Main-Class"));
            assertEquals("dev.forbrick.runtime.Brick", in.getManifest().getMainAttributes().getValue("Premain-Class"));
            assertEquals("dev/forbrick/runtime/Brick.class", in.getNextJarEntry().getName());
            assertEquals("dev/forbrick/runtime/BrickNotice.class", in.getNextJarEntry().getName());
            assertNull(in.getNextJarEntry());
        }
        assertEquals(1, service.listInstalled().size());
        service.uninstall("1.21.1");
        assertArrayEquals(source.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(json));
        assertFalse(Files.exists(jar));
        assertFalse(Files.exists(root.resolve(".forbrick")));
        assertTrue(service.listInstalled().isEmpty());
        assertFalse(Files.exists(root.resolve(".forbrick")));
    }

    @Test public void unicodeDirectoryAndVersionIdRestoreExactly() throws Exception {
        Path unicodeRoot = Files.createDirectory(root.resolve("中文 Minecraft"));
        String id = "整合包 1.21.1";
        Path json = unicodeRoot.resolve("versions").resolve(id).resolve(id + ".json");
        Files.createDirectories(json.getParent());
        byte[] original = "{\"mainClass\":\"net.minecraft.client.main.Main\",\"note\":\"保留中文\"}\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(json, original);
        InstallerService installer = new InstallerService(unicodeRoot);
        installer.install(id);
        assertEquals(id, installer.listInstalled().get(0).id);
        installer.uninstallAll();
        assertArrayEquals(original, Files.readAllBytes(json));
        assertFalse(Files.exists(unicodeRoot.resolve(".forbrick")));
        assertFalse(Files.exists(unicodeRoot.resolve("libraries")));
    }

    @Test public void inheritedProfileAndUnrelatedChangesSurvive() throws Exception {
        Path json = profile("fabric-loader", "{\"inheritsFrom\":\"1.20.4\",\"libraries\":[{\"name\":\"org.example:old:1\"}]}\n");
        assertNull(service.listVersions().get(0).mainClass);
        service.install("fabric-loader");
        JsonObject edited = read(json);
        edited.addProperty("customSetting", "kept");
        edited.getAsJsonArray("libraries").add(JsonParser.parseString("{\"name\":\"org.example:new:2\"}"));
        Files.write(json, edited.toString().getBytes(StandardCharsets.UTF_8));
        service.uninstall("fabric-loader");
        JsonObject restored = read(json);
        assertFalse(restored.has("mainClass"));
        assertEquals("kept", restored.get("customSetting").getAsString());
        assertEquals(2, restored.getAsJsonArray("libraries").size());
    }

    @Test public void repeatedOperationsAndSharedRuntime() throws Exception {
        profile("vanilla", "{\"mainClass\":\"net.minecraft.client.main.Main\"}");
        profile("forge", "{\"mainClass\":\"cpw.mods.bootstraplauncher.BootstrapLauncher\"}");
        service.install("vanilla");
        service.install("vanilla");
        service.install("forge");
        Files.delete(runtimePath());
        service.install("vanilla");
        assertTrue(Files.isRegularFile(runtimePath()));
        service.uninstall("vanilla");
        assertTrue(Files.isRegularFile(runtimePath()));
        assertEquals(1, service.listInstalled().size());
        service.uninstallAll();
        service.uninstall("forge");
        assertFalse(Files.exists(runtimePath()));
        assertTrue(service.listInstalled().isEmpty());
    }

    @Test public void foreignRuntimeAndHookChangesAreNeverOverwritten() throws Exception {
        Path json = profile("quilt", "{\"mainClass\":\"org.quiltmc.loader.impl.launch.knot.KnotClient\"}");
        Files.createDirectories(runtimePath().getParent());
        Files.write(runtimePath(), new byte[] {1, 2, 3}, StandardOpenOption.CREATE_NEW);
        expectIOException(() -> service.install("quilt"));
        assertFalse(read(json).get("mainClass").getAsString().contains("forbrick"));
        Files.delete(runtimePath());
        service.install("quilt");
        JsonObject edited = read(json);
        edited.addProperty("mainClass", "org.example.UserOverride");
        Files.write(json, edited.toString().getBytes(StandardCharsets.UTF_8));
        expectIOException(() -> service.uninstall("quilt"));
        assertEquals("org.example.UserOverride", read(json).get("mainClass").getAsString());
        assertTrue(Files.isRegularFile(runtimePath()));
    }

    @Test public void interruptedInstallAndCleanupCanRecover() throws Exception {
        String source = "{\"mainClass\":\"net.minecraft.client.main.Main\"}";
        Path json = profile("optifine", source);
        service.install("optifine");
        Files.write(json, source.getBytes(StandardCharsets.UTF_8)); // crash after record, before version edit
        service.install("optifine");
        assertEquals(1, service.listInstalled().size());
        Files.write(json, source.getBytes(StandardCharsets.UTF_8)); // crash after restore, before record deletion
        service.uninstall("optifine");
        assertTrue(service.listInstalled().isEmpty());
        assertFalse(Files.exists(runtimePath()));
    }

    @Test public void damagedOrMissingVersionKeepsRecoveryRecord() throws Exception {
        Path json = profile("neo", "{\"mainClass\":\"cpw.mods.bootstraplauncher.BootstrapLauncher\"}");
        service.install("neo");
        Files.delete(json);
        assertEquals("neo", service.listVersions().get(0).id);
        assertTrue(service.listVersions().get(0).installed);
        expectIOException(() -> service.uninstallAll());
        assertTrue(Files.isDirectory(root.resolve(".forbrick/versions")));
    }

    @Test public void corruptRecordDoesNotHideOrBlockHealthyVersions() throws Exception {
        profile("corrupt", "{\"mainClass\":\"a.Main\"}");
        Path healthy = profile("healthy", "{\"mainClass\":\"b.Main\"}");
        service.install("corrupt");
        service.install("healthy");
        Path record = recordPath("corrupt");
        Files.write(record, "broken".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, service.listVersions().size());
        assertEquals(2, service.listInstalled().size());
        try { service.uninstallAll(); fail("Expected partial error"); }
        catch (IOException e) { assertTrue(e.getMessage().contains("corrupt")); }
        assertEquals("b.Main", read(healthy).get("mainClass").getAsString());
        assertTrue(Files.exists(record));
        assertTrue(Files.exists(runtimePath()));
    }

    @Test public void missingRecordCannotCauseFalseCleanUninstall() throws Exception {
        profile("orphan", "{\"mainClass\":\"a.Main\"}");
        service.install("orphan");
        Files.delete(recordPath("orphan"));
        try { service.uninstallAll(); fail("Expected unmanaged hook error"); }
        catch (IOException e) { assertTrue(e.getMessage().contains("orphan")); }
        assertTrue(Files.exists(runtimePath()));
    }

    @Test public void interruptedTemporaryFileIsReportedAndPreserved() throws Exception {
        profile("staged", "{\"mainClass\":\"a.Main\"}");
        service.install("staged");
        Path temp = root.resolve("versions/staged/.forbrick-crash.tmp");
        Files.write(temp, new byte[] {4, 5, 6});
        try { service.uninstallAll(); fail("Expected temporary file warning"); }
        catch (IOException e) { assertTrue(e.getMessage().contains(temp.toString())); }
        assertArrayEquals(new byte[] {4, 5, 6}, Files.readAllBytes(temp));
        assertTrue(Files.exists(runtimePath()));
        Files.delete(temp);
        service.uninstallAll();
        assertFalse(Files.exists(runtimePath()));
        assertFalse(Files.exists(root.resolve(".forbrick")));
    }

    @Test public void uninstallAllReportsConflictButRestoresOtherVersions() throws Exception {
        Path broken = profile("broken", "{\"mainClass\":\"a.Main\"}");
        Path okay = profile("okay", "{\"mainClass\":\"b.Main\"}");
        service.install("broken");
        service.install("okay");
        JsonObject edited = read(broken);
        edited.addProperty("mainClass", "user.Main");
        Files.write(broken, edited.toString().getBytes(StandardCharsets.UTF_8));
        try { service.uninstallAll(); fail("Expected conflict"); }
        catch (IOException e) { assertTrue(e.getMessage().contains("broken")); }
        assertEquals("b.Main", read(okay).get("mainClass").getAsString());
        assertTrue(Files.exists(runtimePath()));
    }

    @Test public void changedRuntimeIsRetainedAndReportedOnCleanup() throws Exception {
        Path json = profile("legacy", "{\"mainClass\":\"net.minecraft.client.main.Main\"}");
        service.install("legacy");
        byte[] foreign = new byte[] {9, 8, 7};
        Files.write(runtimePath(), foreign);
        try { service.uninstall("legacy"); fail("Expected runtime conflict"); }
        catch (IOException e) { assertTrue(e.getMessage().contains("Runtime library was changed")); }
        assertArrayEquals(foreign, Files.readAllBytes(runtimePath()));
        assertEquals("net.minecraft.client.main.Main", read(json).get("mainClass").getAsString());
        assertTrue(Files.isRegularFile(root.resolve(".forbrick/runtime-owned-1.0.1.sha256")));
    }

    @Test public void legacyInstallUninstallsToExactOriginalBytes() throws Exception {
        String original = "{\r\n  \"mainClass\": \"old.Main\", \"libraries\": []\r\n}\r\n";
        Path json = profile("old", original);
        seedLegacyInstall("old");
        assertEquals(1, service.listInstalled().size());
        service.uninstall("old");
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(json));
        assertFalse(Files.exists(legacyRuntimePath()));
        assertFalse(Files.exists(root.resolve(".forbrick")));
    }

    @Test public void editedLegacyInstallUninstallsAndKeepsUserChanges() throws Exception {
        Path json = profile("old", "{\"mainClass\":\"old.Main\"}");
        seedLegacyInstall("old");
        JsonObject edited = read(json);
        edited.addProperty("userOption", "keep me");
        edited.getAsJsonArray("libraries").add(JsonParser.parseString("{\"name\":\"user:library:1\"}"));
        Files.write(json, edited.toString().getBytes(StandardCharsets.UTF_8));
        service.uninstall("old");
        JsonObject restored = read(json);
        assertEquals("old.Main", restored.get("mainClass").getAsString());
        assertEquals("keep me", restored.get("userOption").getAsString());
        assertEquals("user:library:1", restored.getAsJsonArray("libraries").get(0).getAsJsonObject().get("name").getAsString());
        assertFalse(Files.exists(legacyRuntimePath()));
    }

    @Test public void repeatedInstallUpgradesLegacyAndKeepsUnrelatedEdits() throws Exception {
        Path json = profile("old", "{\"mainClass\":\"old.Main\",\"libraries\":[{\"name\":\"other:lib:1\"}]}\n");
        seedLegacyInstall("old");
        JsonObject edited = read(json);
        edited.addProperty("userOption", "keep me");
        edited.addProperty("minecraftArguments", "--user-flag");
        edited.getAsJsonArray("libraries").add(JsonParser.parseString("{\"name\":\"user:added:2\"}"));
        Files.write(json, edited.toString().getBytes(StandardCharsets.UTF_8));
        service.install("old");
        JsonObject upgraded = read(json);
        assertEquals("keep me", upgraded.get("userOption").getAsString());
        assertEquals("dev.forbrick:runtime:1.0.1", upgraded.getAsJsonArray("libraries").get(1).getAsJsonObject().get("name").getAsString());
        assertTrue(Files.exists(runtimePath()));
        assertTrue(Files.exists(legacyRuntimePath()));
        service.uninstall("old");
        JsonObject restored = read(json);
        assertEquals("old.Main", restored.get("mainClass").getAsString());
        assertEquals("keep me", restored.get("userOption").getAsString());
        assertEquals("--user-flag", restored.get("minecraftArguments").getAsString());
        assertEquals(2, restored.getAsJsonArray("libraries").size());
        assertEquals("user:added:2", restored.getAsJsonArray("libraries").get(1).getAsJsonObject().get("name").getAsString());
        assertFalse(Files.exists(runtimePath()));
        assertFalse(Files.exists(legacyRuntimePath()));
    }

    @Test public void upgradeWithoutOtherEditsRestoresExactOriginalBytes() throws Exception {
        String original = "{\r\n\"mainClass\":\"old.Main\"\r\n}\r\n";
        Path json = profile("old", original);
        seedLegacyInstall("old");
        service.install("old");
        service.uninstall("old");
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(json));
    }

    @Test public void interruptedUpgradeBeforeProfileWriteCanRestoreBothKindsOfLegacyProfile() throws Exception {
        String original = "{\r\n\"mainClass\":\"old.Main\"\r\n}\r\n";
        Path pristine = profile("pristine", original);
        seedLegacyInstall("pristine");
        byte[] legacyApplied = Files.readAllBytes(pristine);
        service.install("pristine");
        Files.write(pristine, legacyApplied); // upgraded receipt persisted; profile write did not
        service.uninstall("pristine");
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(pristine));

        Path edited = profile("edited", original);
        seedLegacyInstall("edited");
        JsonObject changed = read(edited);
        changed.addProperty("userOption", "keep me");
        Files.write(edited, changed.toString().getBytes(StandardCharsets.UTF_8));
        byte[] editedLegacy = Files.readAllBytes(edited);
        service.install("edited");
        Files.write(edited, editedLegacy); // same interruption, with a prior user edit
        service.uninstall("edited");
        assertEquals("old.Main", read(edited).get("mainClass").getAsString());
        assertEquals("keep me", read(edited).get("userOption").getAsString());
        assertFalse(Files.exists(runtimePath()));
        assertFalse(Files.exists(legacyRuntimePath()));
    }

    @Test public void mixedRuntimeVersionsAreRetainedUntilAllProfilesRestore() throws Exception {
        profile("old", "{\"mainClass\":\"old.Main\"}");
        profile("new", "{\"mainClass\":\"new.Main\"}");
        seedLegacyInstall("old");
        service.install("new");
        service.uninstall("new");
        assertTrue(Files.exists(legacyRuntimePath()));
        assertTrue(Files.exists(runtimePath()));
        service.uninstall("old");
        assertFalse(Files.exists(legacyRuntimePath()));
        assertFalse(Files.exists(runtimePath()));
    }

    @Test public void alteredLegacyLibraryCannotBeUpgradedOrRemoved() throws Exception {
        Path json = profile("old", "{\"mainClass\":\"old.Main\"}");
        seedLegacyInstall("old");
        JsonObject altered = read(json);
        altered.getAsJsonArray("libraries").get(0).getAsJsonObject()
                .getAsJsonObject("downloads").getAsJsonObject("artifact").addProperty("sha1", "bad");
        Files.write(json, altered.toString().getBytes(StandardCharsets.UTF_8));
        expectIOException(() -> service.install("old"));
        expectIOException(() -> service.uninstall("old"));
        assertTrue(Files.exists(recordPath("old")));
        assertTrue(Files.exists(legacyRuntimePath()));
        assertFalse(Files.exists(runtimePath()));
    }

    @Test public void changedLegacyRuntimeIsRetainedAfterRestore() throws Exception {
        Path json = profile("old", "{\"mainClass\":\"old.Main\"}");
        seedLegacyInstall("old");
        Files.write(legacyRuntimePath(), new byte[] {3, 2, 1});
        expectIOException(() -> service.uninstall("old"));
        assertEquals("old.Main", read(json).get("mainClass").getAsString());
        assertTrue(Files.exists(root.resolve(".forbrick/runtime-owned.sha256")));
        assertArrayEquals(new byte[] {3, 2, 1}, Files.readAllBytes(legacyRuntimePath()));
    }

    @Test public void sameRootConcurrentThreadsSerialize() throws Exception {
        profile("one", "{\"mainClass\":\"a.Main\"}");
        profile("two", "{\"mainClass\":\"b.Main\"}");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Void> one = pool.submit(task(start, "one"));
            Future<Void> two = pool.submit(task(start, "two"));
            start.countDown();
            one.get(30, TimeUnit.SECONDS);
            two.get(30, TimeUnit.SECONDS);
            assertEquals(2, service.listInstalled().size());
            assertTrue(Files.isRegularFile(runtimePath()));
        } finally { pool.shutdownNow(); }
    }

    @Test public void separateProcessesSerializeAndCleanUp() throws Exception {
        profile("alpha", "{\"mainClass\":\"a.Main\"}");
        profile("beta", "{\"mainClass\":\"b.Main\"}");
        runPair("install");
        assertEquals(2, service.listInstalled().size());
        runPair("uninstall");
        assertTrue(service.listInstalled().isEmpty());
        assertFalse(Files.exists(root.resolve(".forbrick")));
        assertFalse(Files.exists(runtimePath()));
    }

    @Test public void occupiedLockPortPreventsAllMutation() throws Exception {
        Path json = profile("blocked", "{\"mainClass\":\"a.Main\"}");
        byte[] original = Files.readAllBytes(json);
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(false);
            occupied.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 47691));
            try { service.install("blocked"); fail("Expected busy port"); }
            catch (IOException e) { assertTrue(e.getMessage().contains("47691")); }
        }
        assertArrayEquals(original, Files.readAllBytes(json));
        assertFalse(Files.exists(root.resolve(".forbrick")));
    }

    @Test public void killedLockHolderReleasesPort() throws Exception {
        Path json = profile("after-kill", "{\"mainClass\":\"a.Main\"}");
        Process holder = worker("hold", "after-kill");
        try {
            java.io.BufferedReader output = new java.io.BufferedReader(new java.io.InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("READY", output.readLine());
            holder.destroyForcibly();
            assertTrue(holder.waitFor(10, TimeUnit.SECONDS));
            service.install("after-kill");
            assertEquals("dev.forbrick.runtime.Brick", read(json).get("mainClass").getAsString());
        } finally { holder.destroyForcibly(); }
    }

    private Callable<Void> task(CountDownLatch start, String id) {
        return () -> {
            start.await();
            InstallerService other = new InstallerService(root);
            for (int i = 0; i < 5; i++) other.install(id);
            return null;
        };
    }

    private void runPair(String operation) throws Exception {
        Process a = worker(operation, "alpha");
        Process b = worker(operation, "beta");
        assertTrue("alpha timed out", a.waitFor(30, TimeUnit.SECONDS));
        assertTrue("beta timed out", b.waitFor(30, TimeUnit.SECONDS));
        assertEquals("alpha worker failed: " + processOutput(a), 0, a.exitValue());
        assertEquals("beta worker failed: " + processOutput(b), 0, b.exitValue());
    }

    private String processOutput(Process process) throws IOException {
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) text.append(line).append('\n');
        return text.toString();
    }

    private Process worker(String operation, String id) throws Exception {
        String classes = new File(InstallerService.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        String tests = new File(InstallerServiceTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        String gson = new File(JsonParser.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        String classpath = classes + File.pathSeparator + tests + File.pathSeparator + gson;
        String java = new File(new File(System.getProperty("java.home"), "bin"), "java").getPath();
        return new ProcessBuilder(java, "-Djava.io.tmpdir=" + testTemp, "-cp", classpath,
                ProcessWorker.class.getName(), operation, root.toString(), id).redirectErrorStream(true).start();
    }

    public static final class ProcessWorker {
        public static void main(String[] args) throws Exception {
            if ("hold".equals(args[0])) {
                try (ServerSocket socket = new ServerSocket()) {
                    socket.setReuseAddress(false);
                    socket.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 47691));
                    System.out.println("READY");
                    Thread.sleep(30000L);
                }
                return;
            }
            InstallerService installer = new InstallerService(java.nio.file.Paths.get(args[1]));
            if ("install".equals(args[0])) installer.install(args[2]);
            else installer.uninstall(args[2]);
        }
    }

    @Test public void rejectsTraversalAndLinkedVersionDirectory() throws Exception {
        profile("safe", "{\"mainClass\":\"net.minecraft.client.main.Main\"}");
        try { service.install("../safe"); fail("traversal accepted"); }
        catch (IllegalArgumentException expected) { }
        try { service.install("a\\b"); fail("separator accepted"); }
        catch (IllegalArgumentException expected) { }
        Path external = Files.createTempDirectory(testTemp, "forbrick-external-");
        try {
            Path link = root.resolve("versions/link");
            try { Files.createSymbolicLink(link, external); }
            catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException e) { return; }
            expectIOException(() -> service.listVersions());
        } finally { Files.deleteIfExists(external); }
    }

    private Path profile(String id, String json) throws IOException {
        Path dir = Files.createDirectory(root.resolve("versions").resolve(id));
        Path file = dir.resolve(id + ".json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private Path runtimePath() { return root.resolve("libraries/dev/forbrick/runtime/1.0.1/runtime-1.0.1.jar"); }

    private Path legacyRuntimePath() { return root.resolve("libraries/dev/forbrick/runtime/1.0.0/runtime-1.0.0.jar"); }

    private void seedLegacyInstall(String id) throws Exception {
        Path json = root.resolve("versions").resolve(id).resolve(id + ".json");
        byte[] original = Files.readAllBytes(json);
        byte[] jar;
        try (java.io.InputStream in = getClass().getResourceAsStream("/dev/forbrick/runtime/forbrick-runtime-1.0.0.jar")) {
            assertNotNull(in);
            jar = new byte[816];
            int offset = 0;
            while (offset < jar.length) {
                int count = in.read(jar, offset, jar.length - offset);
                if (count < 0) throw new IOException("Short legacy runtime resource");
                offset += count;
            }
            assertEquals(-1, in.read());
        }
        assertEquals("244068776d1f2a231dcee81845a17d0327fca3ce79f076e4c7a0a7ecd0cdbb63", digest("SHA-256", jar));
        JsonObject profile = read(json);
        profile.addProperty("mainClass", "dev.forbrick.runtime.Brick");
        JsonArray libraries = profile.has("libraries") ? profile.getAsJsonArray("libraries") : new JsonArray();
        if (!profile.has("libraries")) profile.add("libraries", libraries);
        JsonObject artifact = new JsonObject();
        artifact.addProperty("path", "dev/forbrick/runtime/1.0.0/runtime-1.0.0.jar");
        artifact.addProperty("url", "https://github.com/Trcmoe/ForBrick/releases/download/v1.0.0/forbrick-runtime-1.0.0.jar");
        artifact.addProperty("sha1", digest("SHA-1", jar));
        artifact.addProperty("size", jar.length);
        JsonObject downloads = new JsonObject();
        downloads.add("artifact", artifact);
        JsonObject library = new JsonObject();
        library.addProperty("name", "dev.forbrick:runtime:1.0.0");
        library.add("downloads", downloads);
        libraries.add(library);
        byte[] applied = (new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(profile) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(json, applied);
        JsonObject state = new JsonObject();
        state.addProperty("id", id);
        state.addProperty("originalBase64", Base64.getEncoder().encodeToString(original));
        state.addProperty("originalSha256", digest("SHA-256", original));
        state.addProperty("appliedSha256", digest("SHA-256", applied));
        Files.createDirectories(recordPath(id).getParent());
        Files.write(recordPath(id), state.toString().getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(legacyRuntimePath().getParent());
        Files.write(legacyRuntimePath(), jar);
        Files.write(root.resolve(".forbrick/runtime-owned.sha256"), (digest("SHA-256", jar) + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    private static String digest(String algorithm, byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance(algorithm).digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte b : hash) result.append(String.format("%02x", b & 255));
        return result.toString();
    }

    private Path recordPath(String id) throws IOException {
        StringBuilder hex = new StringBuilder();
        for (byte b : id.getBytes(StandardCharsets.UTF_8)) hex.append(String.format("%02x", b & 255));
        return root.resolve(".forbrick/versions").resolve(hex + ".json");
    }

    private interface Checked { void run() throws Exception; }
    private static void expectIOException(Checked action) throws Exception {
        try { action.run(); fail("Expected IOException"); }
        catch (IOException expected) { }
    }
}
