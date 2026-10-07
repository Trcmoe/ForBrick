package dev.forbrick.runtime;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** The helper owns no game files and exits when its information dialog is closed. */
public final class BrickNotice {
    static final String RELEASE_URL = "https://github.com/Trcmoe/ForBrick/releases/latest";

    private BrickNotice() { }

    public static void main(String[] args) {
        try {
            // The launching game keeps this pipe open until Runtime.halt completes.
            // EOF works on Java 8 too and needs no PID files or background polling.
            awaitGameExit(System.in);
            if (GraphicsEnvironment.isHeadless()) return;
            Locale language = Locale.getDefault(Locale.Category.DISPLAY);
            SwingUtilities.invokeAndWait(() -> showNotice(language));
        } catch (Exception | LinkageError | java.awt.AWTError ignored) {
            // No display, desktop module or working window system: exit without a crash report.
        }
    }

    static void awaitGameExit(InputStream gameLifetime) throws IOException {
        while (gameLifetime.read() != -1) { /* Only EOF grants permission to show the dialog. */ }
    }

    static boolean isChinese(Locale locale) { return "zh".equals(locale.getLanguage()); }

    static String message(Locale locale) {
        return isChinese(locale)
                ? "该实例由于 ForBrick 的存在已经变成砖头了。\n\n如需卸载 ForBrick，请从 GitHub Release 下载安装器，\n并在安装器的“卸载”页卸载 ForBrick。"
                : "This instance has turned into a brick because ForBrick is installed.\n\nTo remove ForBrick, download the installer from GitHub Release,\nthen uninstall ForBrick from its Uninstall tab.";
    }

    static String downloadLabel(Locale locale) { return isChinese(locale) ? "打开下载页面" : "Open downloads"; }
    static String closeLabel(Locale locale) { return isChinese(locale) ? "关闭" : "Close"; }

    static void showNotice(Locale locale) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) { /* Swing's default look and feel also works. */ }
        JTextArea text = new JTextArea(message(locale), 6, 52);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setFont(UIManager.getFont("Label.font"));
        text.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        JTextField link = new JTextField(RELEASE_URL);
        link.setEditable(false);
        JPanel content = new JPanel(new BorderLayout());
        content.add(text, BorderLayout.CENTER);
        content.add(link, BorderLayout.SOUTH);
        Object[] options = {downloadLabel(locale), closeLabel(locale)};
        while (JOptionPane.showOptionDialog(null, content, "ForBrick", JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE, null, options, options[1]) == 0) {
            try {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                    throw new IOException("Browser unavailable");
                Desktop.getDesktop().browse(URI.create(RELEASE_URL));
                return;
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null,
                        (isChinese(locale) ? "无法打开浏览器，请复制以下地址：\n" : "Could not open a browser. Copy this address:\n") + RELEASE_URL,
                        "ForBrick", JOptionPane.INFORMATION_MESSAGE);
            }
        }
    }
}
