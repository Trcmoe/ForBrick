package dev.forbrick;

import dev.forbrick.core.InstallerService;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Exercise a real new JVM: an in-process mock cannot verify silent termination. */
public class RuntimeProcessTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    public static class GameMain {
        public static void main(String[] args) {
            System.out.print("GAME_MAIN_RAN");
        }
    }

    @Test public void standaloneRuntimeExitsWithoutAnyOutput() throws Exception {
        Path jar = runtime();
        Result result = java("-jar", jar.toString(), "--username", "ignored");
        assertEquals("Child JVM output: " + result.output, 0, result.exit);
        assertEquals("", result.output);
    }

    @Test public void agentStopsBeforeTheOriginalMain() throws Exception {
        Result baseline = java("-cp", System.getProperty("java.class.path"), GameMain.class.getName());
        assertEquals(0, baseline.exit);
        assertEquals("GAME_MAIN_RAN", baseline.output);
        Result bricked = java("-javaagent:" + runtime(), "-cp", System.getProperty("java.class.path"), GameMain.class.getName());
        assertEquals("Child JVM output: " + bricked.output, 0, bricked.exit);
        assertEquals("", bricked.output);
    }

    @Test public void generatedRuntimeIsReproducibleAndHasJava8Bytecode() throws Exception {
        byte[] first = InstallerService.runtimeJar();
        assertArrayEquals(first, InstallerService.runtimeJar());
        try (JarInputStream jar = new JarInputStream(new java.io.ByteArrayInputStream(first))) {
            assertEquals("dev.forbrick.runtime.Brick", jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertEquals("dev.forbrick.runtime.Brick", jar.getManifest().getMainAttributes().getValue("Premain-Class"));
            java.util.jar.JarEntry entry;
            java.util.Set<String> found = new java.util.HashSet<String>();
            while ((entry = jar.getNextJarEntry()) != null) {
                if (entry.getName().equals("dev/forbrick/runtime/Brick.class")
                        || entry.getName().equals("dev/forbrick/runtime/BrickNotice.class")) {
                    byte[] header = new byte[8];
                    new java.io.DataInputStream(jar).readFully(header);
                    assertEquals(52, ((header[6] & 255) << 8) | (header[7] & 255));
                    found.add(entry.getName());
                } else {
                    assertTrue("Unexpected runtime dependency " + entry.getName(), entry.isDirectory());
                }
            }
            assertEquals(2, found.size());
        }
    }

    @Test public void headlessInstallerOffersCliHelpAndSignalsFailure() throws Exception {
        Result result = java("-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"), Main.class.getName());
        assertEquals(1, result.exit);
        assertTrue(result.output.contains("Use --help"));
        assertFalse(result.output.contains("Exception"));
    }

    @Test public void unicodeWorkingDirectorySupportsMainAndAbsoluteAgentPaths() throws Exception {
        Path directory = Files.createDirectory(temp.getRoot().toPath().resolve("砖块 Minecraft"));
        Files.write(directory.resolve("runtime with spaces.jar"), InstallerService.runtimeJar());
        Result standalone = javaAt(directory, "-jar", "runtime with spaces.jar");
        assertEquals("Child JVM output: " + standalone.output, 0, standalone.exit);
        assertEquals("", standalone.output);
        // Modern Windows instrumentation also canonicalizes relative agent paths using ACP.
        // Keep the optional agent outside that directory and pass its absolute ASCII path.
        Result agent = javaAt(directory, "-javaagent:" + runtime().toAbsolutePath(), "-cp",
                System.getProperty("java.class.path"), GameMain.class.getName());
        assertEquals("Child JVM output: " + agent.output, 0, agent.exit);
        assertEquals("", agent.output);
    }

    private Path runtime() throws Exception {
        // Match the shipped ASCII filename: Windows java.exe converts argv using CP_ACP.
        Path jar = temp.getRoot().toPath().resolve("forbrick runtime with spaces.jar");
        Files.write(jar, InstallerService.runtimeJar());
        return jar;
    }

    private static Result java(String... arguments) throws Exception {
        return javaAt(null, arguments);
    }

    private static Result javaAt(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<String>();
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        command.add(java.nio.file.Paths.get(System.getProperty("java.home"), "bin", executable).toString());
        command.add("-Djava.awt.headless=true");
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (directory != null) builder.directory(directory.toFile());
        // CI runners may have diagnostic flags; test ForBrick's own output in isolation.
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        Process process = builder.start();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue("Child JVM did not terminate", finished);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream stream = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = stream.read(buffer)) != -1) output.write(buffer, 0, read);
        }
        return new Result(process.exitValue(), new String(output.toByteArray(), StandardCharsets.UTF_8));
    }

    private static class Result {
        final int exit;
        final String output;
        Result(int exit, String output) { this.exit = exit; this.output = output; }
    }
}
