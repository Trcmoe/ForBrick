package dev.forbrick.runtime;

import java.lang.instrument.Instrumentation;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ends only this game JVM; a separate JVM explains the result after its exit. */
public final class Brick {
    // Keep the stdin pipe open until the OS closes it when this JVM exits.
    private static Process notice;

    private Brick() { }

    public static void main(String[] args) {
        exitGame();
    }

    public static void premain(String args, Instrumentation instrumentation) {
        exitGame();
    }

    private static void exitGame() {
        try { notice = startNotice(); }
        catch (Exception | LinkageError ignored) { /* A missing desktop must not prevent the joke exit. */ }
        Runtime.getRuntime().halt(0);
    }

    static Process startNotice() throws Exception {
        File location = new File(Brick.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File directory = location.isDirectory() ? location : location.getParentFile();
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "javaw.exe" : "java";
        File java = new File(new File(System.getProperty("java.home"), "bin"), executable);
        if (!java.isFile()) throw new IOException("Java executable unavailable");
        List<String> command = new ArrayList<String>();
        command.add(java.getAbsolutePath());
        if (Boolean.getBoolean("java.awt.headless")) command.add("-Djava.awt.headless=true");
        command.add("-cp");
        // Use an ASCII runtime filename relative to its directory. Windows Java can
        // corrupt non-ANSI characters in absolute command-line paths.
        command.add(location.isDirectory() ? "." : location.getName());
        command.add("dev.forbrick.runtime.BrickNotice");
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory);
        // Do not inherit agent injection or locale overrides from a launcher's JVM
        // environment. In particular, an inherited ForBrick agent would recurse.
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        return builder.start();
    }
}
