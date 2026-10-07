package dev.forbrick;

import dev.forbrick.ui.InstallerWindow;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicComboPopup;

/** Real Swing popup and install tests; requires a desktop or xvfb-run. Uses only temporary game directories. */
public final class UiSmoke {
    private static InstallerWindow window;
    private static final byte[] PROFILE = ("{\"id\":\"1.21.1\",\"mainClass\":\"net.minecraft.client.main.Main\",\"libraries\":[]}").getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(60000); } catch (InterruptedException e) { return; }
            System.err.println("GUI smoke timed out (possible event-thread or popup hang)");
            Runtime.getRuntime().halt(1);
        }, "ui-smoke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try { run(args); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
        finally { watchdog.interrupt(); }
    }

    private static void run(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Paths.get("target"), "ui-minecraft-").toAbsolutePath();
        Path other = Files.createTempDirectory(Paths.get("target"), "ui-other-").toAbsolutePath();
        Path original = profile(root);
        profile(other);
        try {
            SwingUtilities.invokeAndWait(() -> {
                window = new InstallerWindow(root);
                window.pack();
                window.setSize(660, 520);
                window.validate();
                window.setAutoRequestFocus(false);
                window.setVisible(true);
            });
            waitIdle();
            // Translation must stay instant and local even if another installer holds its lock.
            // This also exposes the old bug: programmatic selection never opened the popup.
            try (ServerSocket lock = new ServerSocket()) {
                lock.setReuseAddress(false);
                lock.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 47691));
                SwingUtilities.invokeAndWait(() -> {
                    field("tabs", JTabbedPane.class).setSelectedIndex(1);
                    for (int i = 0; i < 8; i++) {
                        selectLanguageFromPopup(i % 2 == 0 ? 1 : 0);
                        check(field("tabs", JTabbedPane.class).getSelectedIndex() == 1, "Language change lost the tab");
                        check(!field("busy", Boolean.class), "Language change started filesystem work");
                        check(field("refreshButton", JButton.class).isEnabled(), "Language change left controls disabled");
                    }
                    field("tabs", JTabbedPane.class).setSelectedIndex(0);
                });
            }
            SwingUtilities.invokeAndWait(() -> {
                check(field("installButton", JButton.class).isEnabled(), "Install is disabled after reading a valid version");
                if (args.length > 0) render(Paths.get(args[0], "installer-zh.png"));
                field("pathField", JTextField.class).setText(other.toString());
                check(!field("installButton", JButton.class).isEnabled(), "Dirty path must disable install");
                selectLanguageFromPopup(1);
                check(field("pathField", JTextField.class).getText().equals(other.toString()), "Language change lost the pending path");
                check(!field("installButton", JButton.class).isEnabled(), "Language change enabled install on an unapplied root");
                check(field("status", JLabel.class).getText().contains("Path changed"), "Pending-path warning was not translated");
                field("refreshButton", JButton.class).doClick();
            });
            waitIdle();
            SwingUtilities.invokeAndWait(() -> {
                if (args.length > 0) render(Paths.get(args[0], "installer-en.png"));
                field("installButton", JButton.class).doClick();
            });
            waitIdle();
            check(java.util.Arrays.equals(PROFILE, Files.readAllBytes(original)), "Install touched the old directory");
            check(new String(Files.readAllBytes(other.resolve("versions/1.21.1/1.21.1.json")), StandardCharsets.UTF_8).contains("dev.forbrick.runtime.Brick"), "Install did not affect the applied directory");
            SwingUtilities.invokeAndWait(() -> {
                field("tabs", JTabbedPane.class).setSelectedIndex(1);
                selectLanguageFromPopup(0);
                check("1.21.1".equals(field("installedBox", JComboBox.class).getSelectedItem()), "Language change lost the installed selection");
                check(field("status", JLabel.class).getText().contains("已安装到"), "Install success status was not translated");
            });
            waitIdle();
            SwingUtilities.invokeAndWait(() -> {
                field("tabs", JTabbedPane.class).setSelectedIndex(1);
                check(field("uninstallAllButton", JButton.class).isEnabled(), "Uninstall all is disabled");
                if (args.length > 0) render(Paths.get(args[0], "uninstaller-zh.png"));
                field("uninstallAllButton", JButton.class).doClick();
            });
            waitIdle();
            check(java.util.Arrays.equals(PROFILE, Files.readAllBytes(other.resolve("versions/1.21.1/1.21.1.json"))), "GUI uninstall did not restore original bytes");
            check(!Files.exists(other.resolve(".forbrick")), "GUI uninstall left management records");
            check(!Files.exists(other.resolve("libraries")), "GUI uninstall left the runtime library");
            System.out.println("GUI smoke passed: real popup clicks, repeated language switches, no rescan under lock, preserved state, install/uninstall and exact restore.");
        } finally {
            if (window != null) SwingUtilities.invokeAndWait(() -> window.dispose());
        }
    }

    private static void selectLanguageFromPopup(int index) {
        JComboBox<?> combo = field("localeSelector", JComboBox.class);
        combo.showPopup();
        BasicComboPopup popup = (BasicComboPopup) combo.getUI().getAccessibleChild(combo, 0);
        JList<?> list = popup.getList();
        list.setSelectedIndex(index);
        Rectangle bounds = list.getCellBounds(index, index);
        // Follow BasicComboPopup's actual mouse-release path: ActionEvent fires before it hides.
        list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                0, bounds.x + 3, bounds.y + bounds.height / 2, 1, false, MouseEvent.BUTTON1));
        check(!combo.isPopupVisible(), "Language popup is stuck open");
        check(MenuSelectionManager.defaultManager().getSelectedPath().length == 0, "Popup retained the menu input grab");
        String title = field("tabs", JTabbedPane.class).getTitleAt(0);
        check(title.equals(index == 0 ? "安装" : "Install"), "Visible tab language did not change");
        check(field("installButton", JButton.class).getText().equals(index == 0 ? "安装 ForBrick" : "Install ForBrick"), "Visible button language did not change");
    }

    private static Path profile(Path root) throws Exception {
        Path file = root.resolve("versions/1.21.1/1.21.1.json");
        Files.createDirectories(file.getParent());
        Files.write(file, PROFILE);
        return file;
    }

    private static void waitIdle() throws Exception {
        long deadline = System.nanoTime() + 20000000000L;
        while (System.nanoTime() < deadline) {
            final boolean[] busy = new boolean[1];
            SwingUtilities.invokeAndWait(() -> busy[0] = field("busy", Boolean.class));
            if (!busy[0]) return;
            Thread.sleep(25);
        }
        throw new AssertionError("GUI operation timed out");
    }

    private static <T> T field(String name, Class<T> type) {
        try {
            Field field = InstallerWindow.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(window));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void render(Path path) {
        try {
            Files.createDirectories(path.getParent());
            window.validate();
            Container content = window.getContentPane();
            BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            content.printAll(graphics);
            graphics.dispose();
            ImageIO.write(image, "png", path.toFile());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
