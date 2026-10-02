package tech.xdomhatter.ui.swing;

import com.jcraft.jsch.ChannelSftp;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.sftp.SftpOps;
import tech.xdomhatter.core.sftp.TransferService;
import tech.xdomhatter.core.util.Fmt;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class FilePanel {
    private static final int TREE_WIDTH = 200;
    private static final int CACHE_MAX = 64;

    private final SwingApp app;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));

    private final FileTableModel localModel = new FileTableModel();
    private final FileTableModel remoteModel = new FileTableModel();
    private final JTable localTable = Ui.table(localModel);
    private final JTable remoteTable = Ui.table(remoteModel);
    private final JTextField localPath = new JTextField();
    private final JTextField remotePath = new JTextField();

    private DirTree localTree;
    private DirTree remoteTree;
    private JComponent localTreeWrap;
    private JComponent remoteTreeWrap;

    private Path localDir = Path.of(System.getProperty("user.home"));
    private List<Path> localItems = List.of();
    private String remoteDir = "/";
    private List<SftpOps.Entry> remoteEntries = List.of();
    private SshProfile current;

    /** 远程目录列表缓存：进入看过的目录先渲染缓存，后台再校准（stale-while-revalidate）。 */
    private final Map<String, List<SftpOps.Entry>> remoteCache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<SftpOps.Entry>> eldest) {
                    return size() > CACHE_MAX;
                }
            });
    private final AtomicInteger remoteReq = new AtomicInteger();
    private final AtomicInteger localReq = new AtomicInteger();

    public FilePanel(SwingApp app) {
        this.app = app;
        build();
        remotePath.setText("未连接");
        refreshLocal();
    }

    public JPanel panel() {
        return panel;
    }

    private void build() {
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));

        localTree = new DirTree("此电脑", "", this::listLocalDirs, this::onLocalTreeSelect, msg -> app.status(msg));
        remoteTree = new DirTree("未连接", null, this::listRemoteDirs, this::onRemoteTreeSelect, msg -> app.status(msg));
        localTreeWrap = treeWrap(localTree);
        remoteTreeWrap = treeWrap(remoteTree);

        JPanel localPane = sidePane(localTable, localPath, true, localTreeWrap);
        JPanel remotePane = sidePane(remoteTable, remotePath, false, remoteTreeWrap);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, localPane, remotePane);
        split.setResizeWeight(0.5);
        split.setBorder(null);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        JButton up = Ui.primary("上传到服务器", "upload");
        up.addActionListener(e -> uploadSelected());
        JButton down = Ui.button("下载到本地", "download");
        down.addActionListener(e -> downloadSelected());
        JButton open = Ui.button("打开远程文件", "open");
        open.addActionListener(e -> openSelectedRemoteFile());
        bottom.add(up);
        bottom.add(down);
        bottom.add(open);

        for (JTable t : new JTable[]{localTable, remoteTable}) {
            Ui.width(t, 0, 240);
            Ui.width(t, 1, 90);
            Ui.width(t, 2, 140);
        }
        installRenderers(localTable, localModel);
        installRenderers(remoteTable, remoteModel);

        panel.add(split, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);

        localPath.addActionListener(e -> {
            Path p = Path.of(localPath.getText().strip());
            if (Files.isDirectory(p)) {
                localDir = p;
                refreshLocal();
            }
        });
        remotePath.addActionListener(e -> {
            if (current != null) {
                remoteDir = remotePath.getText().strip();
                refreshRemote();
            }
        });

        localTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = localTable.convertRowIndexToModel(localTable.getSelectedRow());
                    if (row < 0 || row >= localItems.size()) return;
                    Path p = localItems.get(row);
                    if (Files.isDirectory(p)) {
                        localDir = p;
                        refreshLocal();
                    } else {
                        openLocal(p);
                    }
                }
            }
        });
        remoteTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = remoteTable.convertRowIndexToModel(remoteTable.getSelectedRow());
                    if (row < 0 || row >= remoteEntries.size()) return;
                    SftpOps.Entry en = remoteEntries.get(row);
                    if (en.dir()) {
                        remoteDir = SftpOps.join(remoteDir, en.name());
                        refreshRemote();
                    } else {
                        openRemote(en.name());
                    }
                }
            }
        });

        remoteTable.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport ts) {
                return ts.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport ts) {
                try {
                    List<File> files = (List<File>) ts.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (current == null) {
                        app.status("请先连接服务器");
                        return false;
                    }
                    TransferService.ConflictResolver r = askResolver();
                    if (r == null) return false;
                    for (File f : files) {
                        app.ctx().transfers.upload(current.id, f.toPath(), remoteDir, r);
                    }
                    app.status("已接收拖拽上传 " + files.size() + " 项");
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        });
    }

    private static JComponent treeWrap(DirTree tree) {
        tree.setPreferredSize(new Dimension(TREE_WIDTH, 0));
        return tree;
    }

    private void installRenderers(JTable t, FileTableModel m) {
        TableRowSorter<FileTableModel> s = new TableRowSorter<>(m);
        s.setComparator(0, Comparator
                .comparingInt((FileTableModel.Row r) -> r.dir() ? 0 : 1)
                .thenComparing(r -> r.name().toLowerCase()));
        s.setComparator(1, Comparator.<Long>naturalOrder());
        s.setComparator(2, Comparator.<Long>naturalOrder());
        s.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        t.setRowSorter(s);

        t.getColumnModel().getColumn(0).setCellRenderer(NAME_RENDERER);
        t.getColumnModel().getColumn(1).setCellRenderer(new SizeRenderer());
        t.getColumnModel().getColumn(2).setCellRenderer(new TimeRenderer());
    }

    private static final DefaultTableCellRenderer NAME_RENDERER = new DefaultTableCellRenderer() {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, foc, r, c);
            if (v instanceof FileTableModel.Row row) {
                setText(row.name());
                setIcon(FileIcons.iconFor(row.name(), row.dir()));
            }
            return this;
        }
    };

    private static class SizeRenderer extends DefaultTableCellRenderer {
        SizeRenderer() {
            setFont(Ui.monoFont());
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        protected void setValue(Object v) {
            setText(v instanceof Long n ? (n < 0 ? "<目录>" : Fmt.bytes(n)) : "");
        }
    }

    private static class TimeRenderer extends DefaultTableCellRenderer {
        TimeRenderer() {
            setFont(Ui.monoFont());
        }

        @Override
        protected void setValue(Object v) {
            setText(v instanceof Long n
                    ? FileTableModel.TS.format(Instant.ofEpochMilli(n).atZone(ZoneId.systemDefault()))
                    : "");
        }
    }

    private JPanel sidePane(JTable table, JTextField pathField, boolean isLocal, JComponent treeWrap) {
        JPanel p = new JPanel(new BorderLayout(6, 6));

        JButton upBtn = Ui.tool("上级", "up");
        JButton refreshBtn = Ui.tool(null, "refresh");
        refreshBtn.setToolTipText("刷新");
        JButton treeBtn = Ui.tool("目录树", null);
        treeBtn.setToolTipText("显示/隐藏目录树");
        treeBtn.setSelected(true);
        JButton mkdirBtn = Ui.tool("新建文件夹", "folder");
        JButton delBtn = Ui.tool("删除", "trash");
        JButton renBtn = Ui.tool("重命名", "edit");
        upBtn.addActionListener(e -> {
            if (isLocal) {
                Path parent = localDir.getParent();
                if (parent != null) {
                    localDir = parent;
                    refreshLocal();
                }
            } else if (current != null) {
                remoteDir = SftpOps.parent(remoteDir);
                refreshRemote();
            }
        });
        refreshBtn.addActionListener(e -> {
            if (isLocal) refreshLocal();
            else refreshRemote();
        });
        treeBtn.addActionListener(e -> {
            boolean show = !treeWrap.isVisible();
            treeWrap.setVisible(show);
            treeBtn.setSelected(show);
            treeWrap.revalidate();
        });
        mkdirBtn.addActionListener(e -> mkdir(isLocal));
        delBtn.addActionListener(e -> deleteSelected(isLocal));
        renBtn.addActionListener(e -> renameSelected(isLocal));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        bar.setOpaque(false);
        bar.add(upBtn);
        bar.add(refreshBtn);
        bar.add(treeBtn);
        bar.add(mkdirBtn);
        bar.add(delBtn);
        bar.add(renBtn);

        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        head.add(Ui.section(isLocal ? "本地" : "远程"), BorderLayout.WEST);
        head.add(bar, BorderLayout.EAST);

        JPanel north = new JPanel(new BorderLayout(0, 4));
        north.setOpaque(false);
        north.add(head, BorderLayout.NORTH);
        north.add(pathField, BorderLayout.CENTER);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(null);
        JPanel center = new JPanel(new BorderLayout());
        center.add(treeWrap, BorderLayout.WEST);
        center.add(tableScroll, BorderLayout.CENTER);

        p.add(north, BorderLayout.NORTH);
        p.add(center, BorderLayout.CENTER);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        return p;
    }

    // ---------- 目录树的数据源 ----------

    private List<DirTree.DirEntry> listLocalDirs(String path) throws Exception {
        List<DirTree.DirEntry> out = new ArrayList<>();
        if (path.isEmpty()) {
            for (Path r : FileSystems.getDefault().getRootDirectories()) {
                out.add(new DirTree.DirEntry(r.toString(), r.toString()));
            }
            return out;
        }
        try (var st = Files.list(Path.of(path))) {
            st.filter(Files::isDirectory)
                    .map(p -> new DirTree.DirEntry(p.getFileName().toString(), p.toString()))
                    .forEach(out::add);
        }
        return out;
    }

    private List<DirTree.DirEntry> listRemoteDirs(String path) throws Exception {
        SshProfile prof = current;
        if (prof == null) return List.of();
        return app.ctx().sftp.withChannel(prof.id, c -> {
            List<DirTree.DirEntry> out = new ArrayList<>();
            for (SftpOps.Entry e : SftpOps.list(c, path)) {
                if (e.dir()) out.add(new DirTree.DirEntry(e.name(), SftpOps.join(path, e.name())));
            }
            return out;
        });
    }

    private void onLocalTreeSelect(String path) {
        if (path == null || path.isEmpty()) return;
        Path p = Path.of(path);
        if (Files.isDirectory(p)) {
            localDir = p;
            refreshLocal();
        }
    }

    private void onRemoteTreeSelect(String path) {
        if (path == null || path.isEmpty() || current == null) return;
        remoteDir = path;
        refreshRemote();
    }

    // ---------- 本地 ----------

    private void refreshLocal() {
        localDir = localDir.toAbsolutePath().normalize();
        localPath.setText(localDir.toString());
        localTree.reveal(localChain(localDir));
        final Path dir = localDir;
        final int req = localReq.incrementAndGet();
        new Thread(() -> {
            List<Path> items = new ArrayList<>();
            List<FileTableModel.Row> rows = new ArrayList<>();
            try (var st = Files.list(dir)) {
                List<Path> sorted = st.sorted(Comparator
                        .comparing((Path p) -> !Files.isDirectory(p))
                        .thenComparing(p -> p.getFileName().toString().toLowerCase())).toList();
                for (Path p : sorted) {
                    var a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
                    boolean isDir = a.isDirectory();
                    items.add(p);
                    rows.add(new FileTableModel.Row(p.getFileName().toString(), isDir,
                            isDir ? -1 : a.size(), a.lastModifiedTime().toMillis()));
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    if (req == localReq.get()) app.status("读取本地目录失败: " + e.getMessage());
                });
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (req != localReq.get()) return;
                localItems = items;
                localModel.set(rows);
            });
        }, "local-list").start();
    }

    private void openLocal(Path p) {
        app.status("打开 " + p.getFileName() + " ...");
        new Thread(() -> {
            try {
                app.ctx().openWith.open(p);
                app.status("已打开 " + p.getFileName());
            } catch (Exception ex) {
                app.status("打开失败: " + ex.getMessage());
            }
        }, "open-local").start();
    }

    /** 本地路径 → 树节点链（[根盘符, ..., 目标]）。 */
    private static List<String> localChain(Path p) {
        Path root = p.getRoot();
        if (root == null) return List.of(p.toString());
        List<String> chain = new ArrayList<>();
        chain.add(root.toString());
        Path acc = root;
        for (int i = 0; i < p.getNameCount(); i++) {
            acc = acc.resolve(p.getName(i));
            chain.add(acc.toString());
        }
        return chain;
    }

    // ---------- 远程 ----------

    public void onConnected(SshProfile p) {
        current = p;
        remoteCache.clear();
        remoteModel.set(List.of());
        remoteEntries = List.of();
        remotePath.setText("加载中…");
        remoteTree.resetRoot("服务器", "/");
        final int req = remoteReq.incrementAndGet();
        new Thread(() -> {
            String home = null;
            try {
                home = app.ctx().sftp.withChannel(p.id, SftpOps::home);
            } catch (Exception ignored) {
            }
            final String h = home;
            SwingUtilities.invokeLater(() -> {
                if (p != current || req != remoteReq.get()) return;
                remoteDir = (h == null || h.isBlank()) ? "/" : h;
                refreshRemote();
            });
        }, "sftp-home").start();
    }

    public void onDisconnected(String profileId) {
        if (current != null && current.id.equals(profileId)) {
            current = null;
            remoteReq.incrementAndGet();
            remoteEntries = List.of();
            remoteModel.set(List.of());
            remotePath.setText("未连接");
            remoteCache.clear();
            remoteTree.resetRoot("未连接", null);
        }
    }

    private void refreshRemote() {
        if (current == null) {
            remotePath.setText("未连接");
            remoteEntries = List.of();
            remoteModel.set(List.of());
            return;
        }
        remoteDir = normRemote(remoteDir);
        remotePath.setText(remoteDir);
        remoteTree.reveal(remoteChain(remoteDir));
        final SshProfile prof = current;
        final String dir = remoteDir;
        final int req = remoteReq.incrementAndGet();

        List<SftpOps.Entry> cached = remoteCache.get(dir);
        if (cached != null) applyRemote(req, dir, cached);

        new Thread(() -> {
            try {
                List<SftpOps.Entry> es = app.ctx().sftp.withChannel(prof.id, c -> SftpOps.list(c, dir));
                remoteCache.put(dir, es);
                SwingUtilities.invokeLater(() -> applyRemote(req, dir, es));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    if (req == remoteReq.get() && dir.equals(remoteDir)) {
                        app.status("读取远程目录失败: " + e.getMessage());
                    }
                });
            }
        }, "sftp-list").start();
    }

    private void applyRemote(int req, String dir, List<SftpOps.Entry> es) {
        if (req != remoteReq.get() || !dir.equals(remoteDir)) return;
        remoteEntries = es;
        List<FileTableModel.Row> rows = new ArrayList<>(es.size());
        for (SftpOps.Entry e : es) {
            rows.add(new FileTableModel.Row(e.name(), e.dir(), e.dir() ? -1 : e.size(), e.mtime()));
        }
        remoteModel.set(rows);
    }

    /** 远程路径 → 树节点链（["/a", "/a/b", ...]），根目录本身为空链。 */
    private static List<String> remoteChain(String path) {
        List<String> chain = new ArrayList<>();
        String acc = "";
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            acc = acc + "/" + seg;
            chain.add(acc);
        }
        return chain;
    }

    private static String normRemote(String p) {
        p = p == null ? "" : p.strip();
        if (p.isEmpty()) return "/";
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private interface SftpAction {
        void run(ChannelSftp c) throws Exception;
    }

    private void withSftp(String what, SftpAction action) {
        SshProfile prof = current;
        if (prof == null) {
            info("请先连接服务器");
            return;
        }
        String dir = remoteDir;
        new Thread(() -> {
            try {
                app.ctx().sftp.withChannel(prof.id, c -> {
                    action.run(c);
                    return null;
                });
                remoteCache.remove(dir);
                SwingUtilities.invokeLater(() -> {
                    refreshRemote();
                    remoteTree.reload(remoteChain(dir));
                    app.status(what + " 完成");
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> app.status(what + " 失败: " + e.getMessage()));
            }
        }, "sftp-op").start();
    }

    private void openRemote(String name) {
        SshProfile prof = current;
        if (prof == null) return;
        String remote = SftpOps.join(remoteDir, name);
        app.status("下载并打开 " + name + " ...");
        new Thread(() -> {
            try {
                Path tmpDir = Files.createTempDirectory("justserving-open");
                Path target = tmpDir.resolve(name);
                app.ctx().sftp.withChannel(prof.id, c -> {
                    c.get(remote, target.toString());
                    return null;
                });
                SwingUtilities.invokeLater(() -> {
                    try {
                        app.ctx().openWith.open(target);
                        app.status("已打开 " + name);
                    } catch (Exception ex) {
                        app.status("打开失败: " + ex.getMessage());
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> app.status("打开远程文件失败: " + e.getMessage()));
            }
        }, "open-remote").start();
    }

    // ---------- 传输 ----------

    private TransferService.ConflictResolver askResolver() {
        Object[] opts = {"覆盖", "跳过", "重命名", "每次询问"};
        int r = JOptionPane.showOptionDialog(app.frame(), "目标文件已存在时如何处理？", "冲突策略",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        return switch (r) {
            case 0 -> n -> TransferService.Conflict.OVERWRITE;
            case 1 -> n -> TransferService.Conflict.SKIP;
            case 2 -> n -> TransferService.Conflict.RENAME;
            case 3 -> n -> askOnce(n);
            default -> null;
        };
    }

    private TransferService.Conflict askOnce(String name) {
        int[] res = new int[]{-1};
        Runnable dialog = () -> {
            Object[] o2 = {"覆盖", "跳过", "重命名"};
            res[0] = JOptionPane.showOptionDialog(app.frame(), "“" + name + "” 已存在", "文件冲突",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, o2, o2[0]);
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) dialog.run();
            else SwingUtilities.invokeAndWait(dialog);
        } catch (Exception ignored) {
        }
        return switch (res[0]) {
            case 0 -> TransferService.Conflict.OVERWRITE;
            case 1 -> TransferService.Conflict.SKIP;
            default -> TransferService.Conflict.RENAME;
        };
    }

    private void uploadSelected() {
        if (current == null) {
            info("请先连接服务器");
            return;
        }
        int[] rows = localTable.getSelectedRows();
        if (rows.length == 0) {
            info("请先在左侧选择要上传的文件/文件夹（支持直接拖拽到右侧）");
            return;
        }
        TransferService.ConflictResolver r = askResolver();
        if (r == null) return;
        for (int row : rows) {
            Path p = localItems.get(localTable.convertRowIndexToModel(row));
            app.ctx().transfers.upload(current.id, p, remoteDir, r);
        }
        app.status("已加入上传队列 " + rows.length + " 项");
    }

    private void downloadSelected() {
        if (current == null) {
            info("请先连接服务器");
            return;
        }
        int[] rows = remoteTable.getSelectedRows();
        if (rows.length == 0) {
            info("请先在右侧选择要下载的文件/文件夹");
            return;
        }
        TransferService.ConflictResolver r = askResolver();
        if (r == null) return;
        for (int row : rows) {
            SftpOps.Entry en = remoteEntries.get(remoteTable.convertRowIndexToModel(row));
            app.ctx().transfers.download(current.id, SftpOps.join(remoteDir, en.name()), localDir, r);
        }
        app.status("已加入下载队列 " + rows.length + " 项");
    }

    private void openSelectedRemoteFile() {
        int row = remoteTable.getSelectedRow();
        if (row < 0) {
            info("请先在右侧选择文件");
            return;
        }
        SftpOps.Entry en = remoteEntries.get(remoteTable.convertRowIndexToModel(row));
        if (en.dir()) info("请选择文件（非目录）");
        else openRemote(en.name());
    }

    // ---------- 增删改 ----------

    private void mkdir(boolean isLocal) {
        String name = prompt("新建文件夹", "名称:");
        if (name == null || name.isBlank()) return;
        if (isLocal) {
            try {
                Files.createDirectory(localDir.resolve(name.strip()));
                refreshLocal();
                localTree.reload(localChain(localDir));
            } catch (Exception e) {
                app.status("新建文件夹失败: " + e.getMessage());
            }
        } else {
            withSftp("新建文件夹", c -> c.mkdir(SftpOps.join(remoteDir, name.strip())));
        }
    }

    private void deleteSelected(boolean isLocal) {
        if (isLocal) {
            int[] rows = localTable.getSelectedRows();
            if (rows.length == 0) {
                info("请先选择要删除的项");
                return;
            }
            if (JOptionPane.showConfirmDialog(app.frame(), "删除选中的 " + rows.length + " 项？", "确认",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            boolean fail = false;
            for (int row : rows) {
                Path p = localItems.get(localTable.convertRowIndexToModel(row));
                try {
                    if (Files.isDirectory(p)) {
                        try (var st = Files.walk(p)) {
                            st.sorted(Comparator.reverseOrder()).forEach(x -> {
                                try {
                                    Files.deleteIfExists(x);
                                } catch (Exception ignored) {
                                }
                            });
                        }
                    } else {
                        Files.deleteIfExists(p);
                    }
                } catch (Exception e) {
                    fail = true;
                    app.status("删除失败: " + e.getMessage());
                }
            }
            if (!fail) app.status("删除完成");
            refreshLocal();
            localTree.reload(localChain(localDir));
        } else {
            int[] rows = remoteTable.getSelectedRows();
            if (rows.length == 0) {
                info("请先选择要删除的项");
                return;
            }
            if (JOptionPane.showConfirmDialog(app.frame(), "删除服务器上选中的 " + rows.length + " 项？", "确认",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            List<String> targets = new ArrayList<>();
            for (int row : rows) {
                SftpOps.Entry en = remoteEntries.get(remoteTable.convertRowIndexToModel(row));
                targets.add(SftpOps.join(remoteDir, en.name()));
            }
            withSftp("删除", c -> {
                for (String t : targets) SftpOps.deleteRecursive(c, t);
            });
        }
    }

    private void renameSelected(boolean isLocal) {
        String name = prompt("重命名", "新名称:");
        if (name == null || name.isBlank()) return;
        if (isLocal) {
            int row = localTable.getSelectedRow();
            if (row < 0) {
                info("请先选择要重命名的项");
                return;
            }
            Path p = localItems.get(localTable.convertRowIndexToModel(row));
            try {
                Files.move(p, p.resolveSibling(name.strip()));
                refreshLocal();
                localTree.reload(localChain(localDir));
            } catch (Exception e) {
                app.status("重命名失败: " + e.getMessage());
            }
        } else {
            int row = remoteTable.getSelectedRow();
            if (row < 0) {
                info("请先选择要重命名的项");
                return;
            }
            SftpOps.Entry en = remoteEntries.get(remoteTable.convertRowIndexToModel(row));
            String old = SftpOps.join(remoteDir, en.name());
            withSftp("重命名", c -> c.rename(old, SftpOps.join(remoteDir, name.strip())));
        }
    }

    private String prompt(String title, String label) {
        JTextField f = new JTextField();
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(new JLabel(label), BorderLayout.WEST);
        p.add(f, BorderLayout.CENTER);
        int r = JOptionPane.showConfirmDialog(app.frame(), p, title, JOptionPane.OK_CANCEL_OPTION);
        return r == JOptionPane.OK_OPTION ? f.getText() : null;
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(app.frame(), msg);
    }
}
