package dev.forbrick.cli;

import dev.forbrick.core.InstallerService;
import dev.forbrick.core.VersionInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Headless access to exactly the same reversible operations as the desktop UI. */
public final class CommandLine {
    private CommandLine() { }

    public static int run(String[] args) {
        try {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
                help();
                return 0;
            }
            if (args.length == 1 && args[0].equals("--version")) {
                System.out.println("ForBrick 1.0.1");
                return 0;
            }
            String command = args[0];
            if (!(command.equals("list") || command.equals("install") || command.equals("uninstall")
                    || command.equals("uninstall-all") || command.equals("export-runtime"))) {
                throw new IllegalArgumentException("Unknown command: " + command + ". Use --help.");
            }
            Map<String, String> options = new LinkedHashMap<String, String>();
            for (int i = 1; i < args.length; i += 2) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value: " + args[i]);
                String name = args[i];
                boolean valid = command.equals("export-runtime") ? name.equals("--output")
                        : name.equals("--minecraft") || ((command.equals("install") || command.equals("uninstall")) && name.equals("--version"));
                if (!valid || options.containsKey(name)) throw new IllegalArgumentException("Unexpected or duplicate option: " + name);
                options.put(name, args[i + 1]);
            }
            if (command.equals("export-runtime")) {
                Path output = Paths.get(required(options, "--output")).toAbsolutePath().normalize();
                byte[] jar = InstallerService.runtimeJar();
                Files.write(output, jar, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                System.out.println("Runtime exported: " + output);
                return 0;
            }
            Path root = options.containsKey("--minecraft") ? Paths.get(options.get("--minecraft")) : InstallerService.defaultMinecraftDirectory();
            String version = command.equals("install") || command.equals("uninstall") ? required(options, "--version") : null;
            InstallerService service = new InstallerService(root);
            if (command.equals("list")) {
                for (VersionInfo info : service.listVersions()) {
                    System.out.println((info.installed ? "[ForBrick] " : "[        ] ") + info.id);
                }
            } else if (command.equals("install")) {
                service.install(version);
                System.out.println("ForBrick installed: " + version + ". Launch will exit the game and show removal instructions.");
            } else if (command.equals("uninstall")) {
                service.uninstall(version);
                System.out.println("ForBrick removed: " + version);
            } else {
                service.uninstallAll();
                System.out.println("ForBrick removed from all managed versions in: " + root.toAbsolutePath());
            }
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("ForBrick: " + e.getMessage());
            return 2;
        } catch (IOException | SecurityException e) {
            System.err.println("ForBrick: " + e.getMessage());
            return 1;
        }
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Required option: " + key);
        return value;
    }

    private static void help() {
        System.out.println("ForBrick 1.0.1 - a reversible Minecraft joke loader\n"
                + "Run without arguments to open the installer. Java 8+ is required.\n\n"
                + "Commands:\n"
                + "  list          [--minecraft <directory>]\n"
                + "  install       [--minecraft <directory>] --version <local-version-id>\n"
                + "  uninstall     [--minecraft <directory>] --version <local-version-id>\n"
                + "  uninstall-all [--minecraft <directory>]\n"
                + "  export-runtime --output <new-jar-path>\n"
                + "  --version\n\n"
                + "Close Minecraft and the launcher before install/uninstall. Only the selected\n"
                + "Minecraft directory is changed. Never add ForBrick to global Java options.\n"
                + "Runtime export is an optional manual -javaagent fallback; remove that launcher\n"
                + "argument yourself before deleting an exported runtime JAR.");
    }
}
