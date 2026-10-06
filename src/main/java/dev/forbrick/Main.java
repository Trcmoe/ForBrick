package dev.forbrick;

import dev.forbrick.cli.CommandLine;
import dev.forbrick.ui.InstallerWindow;
import java.awt.GraphicsEnvironment;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Application entry point. */
public final class Main {
    private Main() { }

    public static void main(final String[] args) {
        if (args != null && args.length > 0) {
            System.exit(CommandLine.run(args));
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("ForBrick requires a graphical desktop to open the installer. Use --help for command-line options.");
            System.exit(1);
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                try {
                    new InstallerWindow().setVisible(true);
                } catch (RuntimeException ex) {
                    JOptionPane.showMessageDialog(null,
                            "ForBrick could not start: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()),
                            "ForBrick", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }
}
