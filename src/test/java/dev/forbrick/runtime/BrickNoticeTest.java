package dev.forbrick.runtime;

import dev.forbrick.core.InstallerService;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class BrickNoticeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void systemDisplayLanguagesChooseChineseOrEnglish() {
        for (Locale locale : new Locale[] {Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE,
                Locale.forLanguageTag("zh-HK")}) {
            assertTrue(BrickNotice.message(locale).contains("砖头"));
            assertTrue(BrickNotice.message(locale).contains("卸载"));
            assertTrue(BrickNotice.message(locale).contains("-javaagent"));
            assertEquals("关闭", BrickNotice.closeLabel(locale));
            assertEquals("打开下载页面", BrickNotice.downloadLabel(locale));
        }
        for (Locale locale : new Locale[] {Locale.ENGLISH, Locale.US, Locale.JAPAN, Locale.ROOT}) {
            assertTrue(BrickNotice.message(locale).contains("brick"));
            assertTrue(BrickNotice.message(locale).contains("Uninstall"));
            assertTrue(BrickNotice.message(locale).contains("-javaagent"));
            assertEquals("Close", BrickNotice.closeLabel(locale));
            assertEquals("Open downloads", BrickNotice.downloadLabel(locale));
        }
        assertEquals("https://github.com/Trcmoe/ForBrick/releases/latest", BrickNotice.RELEASE_URL);
    }

    @Test public void noticeWaitsForGamePipeEofEvenAfterReceivingData() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        try (PipedInputStream input = new PipedInputStream(); PipedOutputStream game = new PipedOutputStream(input)) {
            InputStream observed = new InputStream() {
                @Override public int read() throws java.io.IOException {
                    reading.countDown();
                    return input.read();
                }
            };
            FutureTask<Void> waiting = new FutureTask<Void>(() -> {
                BrickNotice.awaitGameExit(observed);
                return null;
            });
            Thread thread = new Thread(waiting, "notice-pipe-test");
            thread.setDaemon(true);
            thread.start();
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            assertFalse("Must not display while game is alive", waiting.isDone());
            game.write(42);
            game.flush();
            assertFalse("Data is not an exit signal", waiting.isDone());
            game.close();
            waiting.get(5, TimeUnit.SECONDS);
        }
    }

    @Test public void launchedHelperKeepsWaitingThenExitsCleanlyWithoutDesktop() throws Exception {
        // startNotice uses the current JVM's headless=true property, set by Surefire.
        Process helper = Brick.startNotice();
        verifyHeadlessHelper(helper);
    }

    @Test public void packagedHelperWorksFromUnicodeDirectoryAndDoesNotRequireInstallerDependencies() throws Exception {
        Path directory = Files.createDirectory(temp.getRoot().toPath().resolve("砖头 runtime"));
        Files.write(directory.resolve("runtime with spaces.jar"), InstallerService.runtimeJar());
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "javaw.exe" : "java";
        ProcessBuilder builder = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", executable).toString(),
                "-Djava.awt.headless=true", "-cp", "runtime with spaces.jar", BrickNotice.class.getName());
        builder.directory(directory.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        verifyHeadlessHelper(builder.start());
    }

    private static void verifyHeadlessHelper(Process helper) throws Exception {
        try {
            assertFalse("Helper returned before parent pipe closed", helper.waitFor(500, TimeUnit.MILLISECONDS));
            helper.getOutputStream().close();
            assertTrue("Headless helper failed to finish", helper.waitFor(15, TimeUnit.SECONDS));
            assertEquals("Helper failed to load or execute", 0, helper.exitValue());
            assertEquals("Unexpected stdout", -1, helper.getInputStream().read());
            assertEquals("Unexpected stderr", -1, helper.getErrorStream().read());
        } finally {
            helper.destroyForcibly();
        }
    }
}
