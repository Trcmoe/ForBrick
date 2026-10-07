package dev.forbrick.ui;

import dev.forbrick.core.InstallerService;
import dev.forbrick.core.VersionInfo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Compact desktop installer. All filesystem work runs off the event thread. */
public final class InstallerWindow extends JFrame {
    private static final Color INK = new Color(48, 47, 45);
    private static final Color MUTED = new Color(113, 110, 105);
    private static final Color ACCENT = new Color(177, 91, 67);
    private static final Color PAPER = new Color(250, 249, 247);
    private static final Color LINE = new Color(226, 222, 217);

    private boolean chinese = true;
    private Path root;
    private InstallerService service;
    private JTextField pathField;
    private JComboBox<String> versionBox;
    private JComboBox<String> installedBox;
    private JLabel status;
    private JButton installButton;
    private JButton uninstallButton;
    private JButton uninstallAllButton;
    private JButton browseButton;
    private JButton refreshButton;
    private JComboBox<String> localeSelector;
    private boolean pathDirty;
    private boolean updatingPathField;
    private JTabbedPane tabs;
    private final List<VersionInfo> versions = new ArrayList<VersionInfo>();
    private final List<Runnable> translations = new ArrayList<Runnable>();
    private Supplier<String> statusMessage;
    private boolean busy;

    public InstallerWindow() {
        this(InstallerService.defaultMinecraftDirectory());
    }

    public InstallerWindow(Path initialRoot) {
        super("ForBrick");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(600, 470));
        setSize(660, 520);
        setLocationByPlatform(true);
        if (initialRoot == null) throw new IllegalArgumentException("Minecraft directory is required");
        root = initialRoot.toAbsolutePath().normalize();
        service = new InstallerService(root);
        setContentPane(buildContent());
        refreshVersions();
    }

    private JPanel buildContent() {
        JPanel page = new JPanel(new BorderLayout(0, 12));
        page.setBackground(PAPER);
        page.setBorder(new EmptyBorder(16, 22, 18, 22));

        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setOpaque(false);
        header.add(new BrickMark(), BorderLayout.WEST);
        JPanel brand = new JPanel(new BorderLayout());
        brand.setOpaque(false);
        JLabel title = new JLabel("ForBrick");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 25f));
        title.setForeground(INK);
        JLabel subtitle = translatedLabel("Minecraft 娱乐加载器 · 1.0.1", "Minecraft joke loader · 1.0.1", false);
        subtitle.setFont(subtitle.getFont().deriveFont(12f));
        subtitle.setForeground(MUTED);
        brand.add(title, BorderLayout.CENTER);
        brand.add(subtitle, BorderLayout.SOUTH);
        header.add(brand, BorderLayout.CENTER);
        JPanel language = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 3));
        language.setOpaque(false);
        localeSelector = new JComboBox<String>(new String[] {"中文", "English"});
        localeSelector.setSelectedIndex(chinese ? 0 : 1);
        localeSelector.addActionListener(e -> applyLanguage(((JComboBox<?>) e.getSource()).getSelectedIndex() == 0));
        language.add(localeSelector);
        header.add(language, BorderLayout.EAST);
        page.add(header, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);
        JPanel pathCard = card();
        pathCard.setLayout(new BorderLayout(9, 6));
        JLabel pathLabel = translatedLabel("Minecraft 文件夹", "Minecraft folder", true);
        pathCard.add(pathLabel, BorderLayout.NORTH);
        JPanel pathRow = new JPanel(new BorderLayout(7, 0));
        pathRow.setOpaque(false);
        pathField = new JTextField(root.toString());
        pathField.setToolTipText(root.toString());
        pathField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { pathEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { pathEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { pathEdited(); }
        });
        pathField.addActionListener(e -> applyPath());
        pathRow.add(pathField, BorderLayout.CENTER);
        JPanel pathActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        pathActions.setOpaque(false);
        browseButton = translatedButton("浏览…", "Browse…", false);
        refreshButton = translatedButton("刷新", "Refresh", false);
        browseButton.addActionListener(e -> chooseDirectory());
        refreshButton.addActionListener(e -> applyPath());
        pathActions.add(browseButton);
        pathActions.add(refreshButton);
        pathRow.add(pathActions, BorderLayout.EAST);
        pathCard.add(pathRow, BorderLayout.CENTER);
        center.add(pathCard, BorderLayout.NORTH);

        tabs = new JTabbedPane();
        tabs.setFont(tabs.getFont().deriveFont(13f));
        tabs.addTab(tr("安装", "Install"), buildInstallTab());
        tabs.addTab(tr("卸载", "Uninstall"), buildUninstallTab());
        center.add(tabs, BorderLayout.CENTER);
        page.add(center, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 6));
        footer.setOpaque(false);
        status = label("", false);
        setStatus(() -> tr("就绪", "Ready"));
        footer.add(status, BorderLayout.NORTH);
        JLabel safety = translatedLabel("所选版本启动后退出并显示卸载提示。安装前请关闭 Minecraft 和启动器。",
                "Launch exits the game and shows removal instructions. Close the game and launcher before installing.", false);
        safety.setForeground(MUTED);
        footer.add(safety, BorderLayout.CENTER);
        page.add(footer, BorderLayout.SOUTH);
        return page;
    }

    private JPanel buildInstallTab() {
        JPanel body = new JPanel(new BorderLayout(0, 14));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(16, 2, 4, 2));
        JLabel explanation = translatedLabel("选择一个本地版本安装 ForBrick。已安装版本会标记显示。", "Choose a local version to install ForBrick. Installed versions are marked.", false);
        body.add(explanation, BorderLayout.NORTH);
        JPanel selection = card();
        selection.setLayout(new BorderLayout(8, 8));
        selection.add(translatedLabel("本地版本", "Local versions", true), BorderLayout.NORTH);
        versionBox = new JComboBox<String>();
        versionBox.setFont(versionBox.getFont().deriveFont(14f));
        selection.add(versionBox, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        installButton = translatedButton("安装 ForBrick", "Install ForBrick", true);
        installButton.addActionListener(e -> installSelected());
        buttons.add(installButton);
        selection.add(buttons, BorderLayout.SOUTH);
        body.add(selection, BorderLayout.CENTER);
        return body;
    }

    private JPanel buildUninstallTab() {
        JPanel body = new JPanel(new BorderLayout(0, 14));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(16, 2, 4, 2));
        body.add(translatedLabel("仅显示由 ForBrick 管理的版本。卸载会清理 ForBrick 文件和对应版本入口。",
                "Only versions managed by ForBrick are shown. Uninstall removes its files and version entry.", false), BorderLayout.NORTH);
        JPanel selection = card();
        selection.setLayout(new BorderLayout(8, 8));
        selection.add(translatedLabel("已安装版本", "Installed versions", true), BorderLayout.NORTH);
        installedBox = new JComboBox<String>();
        installedBox.setFont(installedBox.getFont().deriveFont(14f));
        selection.add(installedBox, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        actions.setOpaque(false);
        uninstallButton = translatedButton("卸载所选", "Uninstall selected", false);
        uninstallButton.addActionListener(e -> uninstallSelected());
        uninstallAllButton = translatedButton("一键卸载全部", "Uninstall all", false);
        uninstallAllButton.addActionListener(e -> uninstallAll());
        actions.add(uninstallButton);
        actions.add(uninstallAllButton);
        selection.add(actions, BorderLayout.SOUTH);
        body.add(selection, BorderLayout.CENTER);
        return body;
    }

    private void applyLanguage(boolean useChinese) {
        if (chinese == useChinese) return;
        String selectedId = selectedVersion() == null ? null : selectedVersion().id;
        chinese = useChinese;
        // A combo ActionEvent runs before its popup closes. Replacing the content pane
        // here removes that live popup during Container.remove and corrupts Swing's state.
        // Keep the component tree intact; localization requires no filesystem access.
        for (Runnable translation : translations) translation.run();
        tabs.setTitleAt(0, tr("安装", "Install"));
        tabs.setTitleAt(1, tr("卸载", "Uninstall"));
        populateVersions(selectedId);
        status.setText(html(statusMessage.get()));
        revalidate();
        repaint();
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser(root.toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(tr("选择 .minecraft 文件夹", "Choose .minecraft folder"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            root = chooser.getSelectedFile().toPath();
            service = new InstallerService(root);
            setPathField(root.toString());
            pathDirty = false;
            refreshVersions();
        }
    }

    private void pathEdited() {
        if (updatingPathField || pathField == null) return;
        pathDirty = !pathMatchesAppliedRoot();
        if (pathDirty) setStatus(() -> tr("路径已更改；按 Enter 或点击刷新后再操作。", "Path changed; press Enter or click Refresh before continuing."));
        else showVersionCount();
        updateActionStates();
    }

    private boolean pathMatchesAppliedRoot() {
        try { return Paths.get(pathField.getText().trim()).toAbsolutePath().normalize().equals(root); }
        catch (RuntimeException ex) { return false; }
    }

    private void setPathField(String value) {
        updatingPathField = true;
        try { pathField.setText(value); pathField.setToolTipText(value); }
        finally { updatingPathField = false; }
    }

    private void applyPath() {
        try {
            String value = pathField.getText().trim();
            if (value.isEmpty()) throw new IllegalArgumentException(tr("请输入 Minecraft 文件夹路径。", "Enter a Minecraft folder path."));
            root = Paths.get(value).toAbsolutePath().normalize();
            service = new InstallerService(root);
            setPathField(root.toString());
            pathDirty = false;
            refreshVersions();
        } catch (RuntimeException ex) {
            setStatus(() -> tr("路径无效：" + ex.getMessage(), "Invalid path: " + ex.getMessage()));
        }
    }

    private void refreshVersions() {
        if (busy) return;
        setBusy(true);
        setStatus(() -> tr("正在读取本地版本…", "Reading local versions…"));
        new SwingWorker<List<VersionInfo>, Void>() {
            @Override protected List<VersionInfo> doInBackground() throws Exception { return service.listVersions(); }
            @Override protected void done() {
                try {
                    String selectedId = selectedVersion() == null ? null : selectedVersion().id;
                    // Preserve selection by exact id across a refresh.
                    versions.clear();
                    versions.addAll(get());
                    populateVersions(selectedId);
                    showVersionCount();
                } catch (Exception ex) { showError(ex); }
                finally { setBusy(false); }
            }
        }.execute();
    }

    private void populateVersions(String priorId) {
        Object installedId = installedBox.getSelectedItem();
        DefaultComboBoxModel<String> available = new DefaultComboBoxModel<String>();
        DefaultComboBoxModel<String> installed = new DefaultComboBoxModel<String>();
        for (VersionInfo v : versions) {
            available.addElement(v.id + (v.installed ? "  ·  " + tr("已安装", "Installed") : "  ·  " + tr("未安装", "Not installed")));
            if (v.installed) installed.addElement(v.id);
        }
        versionBox.setModel(available);
        installedBox.setModel(installed);
        if (priorId != null) for (int i = 0; i < versions.size(); i++) if (versions.get(i).id.equals(priorId)) { versionBox.setSelectedIndex(i); break; }
        if (installedId != null && installed.getIndexOf(installedId) >= 0) installedBox.setSelectedItem(installedId);
        updateActionStates();
    }

    private void installSelected() {
        if (!guardAppliedPath()) return;
        final VersionInfo chosen = selectedVersion();
        if (chosen == null) return;
        runOperation(() -> tr("正在安装 ForBrick…", "Installing ForBrick…"), new Task() {
            @Override public void run() throws Exception { service.install(chosen.id); }
            @Override public String success() { return tr("已安装到 " + chosen.id + "。启动后将退出并显示卸载提示。", "Installed to " + chosen.id + ". Launch will exit and show removal instructions."); }
        });
    }

    private void uninstallSelected() {
        if (!guardAppliedPath()) return;
        final VersionInfo chosen = selectedInstalledVersion();
        if (chosen == null || !chosen.installed) return;
        runOperation(() -> tr("正在卸载 ForBrick…", "Uninstalling ForBrick…"), new Task() {
            @Override public void run() throws Exception { service.uninstall(chosen.id); }
            @Override public String success() { return tr("已恢复 " + chosen.id + " 的原始启动设置。", "Restored the original launch settings for " + chosen.id + "."); }
        });
    }

    private void uninstallAll() {
        if (!guardAppliedPath()) return;
        runOperation(() -> tr("正在卸载全部 ForBrick 安装…", "Uninstalling all ForBrick installations…"), new Task() {
            @Override public void run() throws Exception { service.uninstallAll(); }
            @Override public String success() { return tr("已全部卸载，ForBrick 残留文件已清理。", "All installations removed; ForBrick files were cleaned up."); }
        });
    }

    private void runOperation(final Supplier<String> progress, final Task task) {
        setBusy(true);
        setStatus(progress);
        new SwingWorker<List<VersionInfo>, Void>() {
            @Override protected List<VersionInfo> doInBackground() throws Exception { task.run(); return service.listVersions(); }
            @Override protected void done() {
                try {
                    List<VersionInfo> latest = get();
                    String selectedId = selectedVersion() == null ? null : selectedVersion().id;
                    versions.clear(); versions.addAll(latest); populateVersions(selectedId);
                    setStatus(task::success);
                }
                catch (Exception ex) { showError(ex); }
                finally { setBusy(false); }
            }
        }.execute();
    }

    private VersionInfo selectedVersion() {
        int index = versionBox.getSelectedIndex();
        return index >= 0 && index < versions.size() ? versions.get(index) : null;
    }

    private VersionInfo selectedInstalledVersion() {
        Object selected = installedBox.getSelectedItem();
        if (selected == null) return null;
        for (VersionInfo v : versions) if (v.installed && v.id.equals(selected.toString())) return v;
        return null;
    }

    private boolean hasInstalled() {
        for (VersionInfo v : versions) if (v.installed) return true;
        return false;
    }

    private boolean guardAppliedPath() {
        if (!pathDirty && pathMatchesAppliedRoot()) return true;
        pathDirty = true;
        setStatus(() -> tr("路径已更改；按 Enter 或点击刷新后再操作。", "Path changed; press Enter or click Refresh before continuing."));
        updateActionStates();
        return false;
    }

    private void updateActionStates() {
        boolean allowed = !busy && !pathDirty && pathMatchesAppliedRoot();
        if (installButton != null) installButton.setEnabled(allowed && !versions.isEmpty());
        if (uninstallButton != null) uninstallButton.setEnabled(allowed && hasInstalled());
        // Cleanup must also be reachable after a crash before the first version record was written.
        if (uninstallAllButton != null) uninstallAllButton.setEnabled(allowed);
    }

    private void showVersionCount() {
        setStatus(() -> {
            long managed = 0;
            for (VersionInfo v : versions) if (v.installed) managed++;
            return tr("找到 " + versions.size() + " 个本地版本 · " + managed + " 个 ForBrick 安装",
                    versions.size() + " local versions found · " + managed + " ForBrick installations");
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        browseButton.setEnabled(!value);
        refreshButton.setEnabled(!value);
        localeSelector.setEnabled(!value);
        versionBox.setEnabled(!value);
        installedBox.setEnabled(!value && hasInstalled());
        tabs.setEnabled(!value);
        updateActionStates();
        pathField.setEnabled(!value);
    }

    private void showError(Exception ex) {
        Throwable cause = ex;
        while (cause instanceof java.util.concurrent.ExecutionException && cause.getCause() != null)
            cause = cause.getCause();
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        setStatus(() -> tr("操作失败：" + message, "Operation failed: " + message));
    }

    private void setStatus(Supplier<String> message) {
        statusMessage = message;
        status.setText(html(message.get()));
    }

    private JLabel translatedLabel(String zh, String en, boolean strong) {
        JLabel label = label(tr(zh, en), strong);
        translations.add(() -> label.setText(tr(zh, en)));
        return label;
    }

    private JButton translatedButton(String zh, String en, boolean primary) {
        JButton button = primary ? primaryButton(tr(zh, en)) : new JButton(tr(zh, en));
        translations.add(() -> button.setText(tr(zh, en)));
        return button;
    }

    private String html(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><body style='width:580px'>" + escaped + "</body></html>";
    }

    private JPanel card() {
        JPanel panel = new JPanel();
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), new EmptyBorder(13, 14, 13, 14)));
        return panel;
    }

    private JLabel label(String text, boolean strong) {
        JLabel label = new JLabel(text);
        label.setForeground(strong ? INK : MUTED);
        label.setFont(label.getFont().deriveFont(strong ? Font.BOLD : Font.PLAIN, strong ? 13f : 12f));
        return label;
    }

    private JButton primaryButton(String text) {
        JButton button = new JButton(text);
        button.setForeground(Color.WHITE);
        button.setBackground(ACCENT);
        button.setOpaque(true);
        button.setBorderPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 13f));
        button.setBorder(new EmptyBorder(9, 18, 9, 18));
        return button;
    }

    private String tr(String zh, String en) { return chinese ? zh : en; }

    private interface Task { void run() throws Exception; String success(); }

    private static final class BrickMark extends JPanel {
        BrickMark() { setOpaque(false); setPreferredSize(new Dimension(64, 56)); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(ACCENT);
            g.fillRoundRect(5, 7, 52, 42, 8, 8);
            g.setColor(new Color(255, 239, 231));
            g.fillRoundRect(9, 11, 44, 34, 5, 5);
            g.setColor(ACCENT);
            g.setStroke(new java.awt.BasicStroke(3f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            g.drawLine(12, 27, 50, 27);
            g.drawLine(22, 13, 22, 25);
            g.drawLine(39, 29, 39, 43);
            g.drawLine(31, 29, 31, 43);
            g.dispose();
        }
    }
}
