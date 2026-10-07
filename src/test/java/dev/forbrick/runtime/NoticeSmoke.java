package dev.forbrick.runtime;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;

/** Exercises the helper's actual main method with a display, as a live game would launch it. */
public final class NoticeSmoke {
    private NoticeSmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "child".equals(args[0])) runChild(args[1]);
        else {
            runLocale("zh", "CN");
            runLocale("en", "US");
            System.out.println("Notice smoke passed: EOF-gated Chinese and English dialogs inspected and closed.");
        }
    }

    private static void runLocale(String language, String country) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-Djava.awt.headless=false", "-Duser.language=" + language,
                "-Duser.country=" + country, "-cp", System.getProperty("java.class.path"),
                NoticeSmoke.class.getName(), "child", language);
        builder.redirectErrorStream(true);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        Process child = builder.start();
        BlockingQueue<String> lines = new LinkedBlockingQueue<String>();
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) lines.offer(line);
            } catch (Exception e) { lines.offer("READER_ERROR " + e); }
        }, "notice-smoke-output");
        reader.setDaemon(true);
        reader.start();
        try {
            String ready = lines.poll(20, TimeUnit.SECONDS);
            check("READY".equals(ready), language + " helper did not confirm it was waiting; output=" + ready);
            child.getOutputStream().close(); // EOF is the real game-exit signal consumed by BrickNotice.main.
            String result = lines.poll(20, TimeUnit.SECONDS);
            check("CHILD_OK".equals(result), language + " helper failed: " + result);
            check(child.waitFor(5, TimeUnit.SECONDS), language + " helper did not exit after closing the dialog");
            check(child.exitValue() == 0, language + " helper exit code was " + child.exitValue());
            check(lines.poll(100, TimeUnit.MILLISECONDS) == null, language + " helper emitted unexpected output");
        } finally {
            child.destroyForcibly();
        }
    }

    private static void runChild(String language) throws Exception {
        final Locale locale = "zh".equals(language) ? Locale.SIMPLIFIED_CHINESE : Locale.US;
        Thread observer = new Thread(() -> observe(locale), "notice-dialog-observer");
        observer.setDaemon(true);
        observer.start();
        BrickNotice.main(new String[0]);
        // main returns only after the observed dialog has been closed.
        observer.join(1000);
        check(inspected, "main returned without a verified dialog");
        System.out.println("CHILD_OK");
    }

    private static void observe(Locale locale) {
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (System.nanoTime() < deadline) {
                final JDialog[] dialog = new JDialog[1];
                SwingUtilities.invokeAndWait(() -> {
                    for (Window window : Window.getWindows()) {
                        if (window instanceof JDialog && window.isVisible()) { dialog[0] = (JDialog) window; break; }
                    }
                });
                if (dialog[0] == null) {
                    if (!ready) {
                        Thread.sleep(1200);
                        final boolean[] anyVisible = new boolean[1];
                        SwingUtilities.invokeAndWait(() -> {
                            for (Window window : Window.getWindows()) if (window.isVisible()) anyVisible[0] = true;
                        });
                        check(!anyVisible[0], "notice appeared before game input reached EOF");
                        ready = true;
                        System.out.println("READY");
                    }
                    Thread.sleep(50);
                    continue;
                }
                check(ready, "notice appeared before READY/EOF");
                SwingUtilities.invokeAndWait(() -> inspectAndClose(dialog[0], locale));
                return;
            }
            throw new AssertionError("timed out waiting for notice dialog");
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            Runtime.getRuntime().halt(2);
        }
    }

    private static volatile boolean ready;
    private static volatile boolean inspected;

    private static void inspectAndClose(JDialog dialog, Locale locale) {
        check("ForBrick".equals(dialog.getTitle()), "unexpected dialog title: " + dialog.getTitle());
        String message = BrickNotice.message(locale);
        String download = BrickNotice.downloadLabel(locale);
        String close = BrickNotice.closeLabel(locale);
        check(contains(dialog, JTextArea.class, message), "dialog is missing localized explanatory text");
        check(contains(dialog, JTextField.class, BrickNotice.RELEASE_URL), "dialog is missing release URL");
        JButton closeButton = null;
        int buttonCount = 0;
        for (Component component : descendants(dialog)) {
            if (component instanceof JTextArea) {
                JTextArea area = (JTextArea) component;
                try {
                    for (int i = 0; i < area.getDocument().getLength(); i++) {
                        java.awt.Rectangle character = area.modelToView(i);
                        check(character != null && character.x < area.getWidth()
                                && character.y + character.height <= area.getHeight(),
                                "dialog text is clipped at character " + i);
                    }
                } catch (javax.swing.text.BadLocationException ex) { throw new AssertionError(ex); }
            }
            if (component instanceof JButton) {
                buttonCount++;
                String label = ((JButton) component).getText();
                if (close.equals(label)) closeButton = (JButton) component;
                check(close.equals(label) || download.equals(label), "unexpected dialog button: " + label);
            }
        }
        check(buttonCount == 2 && closeButton != null, "localized download/close buttons are missing");
        try {
            java.nio.file.Path output = Paths.get("target", "notice-preview");
            Files.createDirectories(output);
            BufferedImage preview = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = preview.createGraphics();
            dialog.paint(graphics);
            graphics.dispose();
            ImageIO.write(preview, "png", output.resolve("notice-" + locale.getLanguage() + ".png").toFile());
        } catch (java.io.IOException ex) { throw new AssertionError("Could not save dialog preview", ex); }
        closeButton.doClick();
        inspected = true;
    }

    private static boolean contains(Container root, Class<?> type, String expected) {
        for (Component component : descendants(root)) {
            if (type.isInstance(component)) {
                String value = component instanceof JTextArea ? ((JTextArea) component).getText()
                        : component instanceof JTextField ? ((JTextField) component).getText() : "";
                if (expected.equals(value)) return true;
            }
        }
        return false;
    }

    private static java.util.List<Component> descendants(Container root) {
        java.util.List<Component> all = new java.util.ArrayList<Component>();
        for (Component child : root.getComponents()) {
            all.add(child);
            if (child instanceof Container) all.addAll(descendants((Container) child));
        }
        return all;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
