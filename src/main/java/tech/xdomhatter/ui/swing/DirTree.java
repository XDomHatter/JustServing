package tech.xdomhatter.ui.swing;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 懒加载目录树：展开节点时才后台读取子目录（不卡 UI），选中节点回调导航。
 * reveal(chain) 在表格导航后同步树选中并逐级展开；reload(chain) 在目录内容变化后刷新子节点。
 * chain 是从根节点子级到目标的路径列表，元素为各目录的完整路径字符串。
 */
class DirTree extends JPanel {
    interface Loader {
        List<DirEntry> list(String path) throws Exception;
    }

    record DirEntry(String name, String path) {}

    private static final class Node extends DefaultMutableTreeNode {
        final String path;
        boolean loaded;
        boolean loading;
        final List<Runnable> pending = new ArrayList<>();

        Node(String label, String path, boolean allowsChildren) {
            super(label, allowsChildren);
            this.path = path;
        }
    }

    private final JTree tree = new JTree();
    private final DefaultTreeModel model;
    private final Loader loader;
    private final Consumer<String> onNavigate;
    private final Consumer<String> onError;
    private Node root;
    private int gen;
    private int revealGen;
    private boolean revealing;

    DirTree(String rootLabel, String rootPath, Loader loader, Consumer<String> onNavigate, Consumer<String> onError) {
        this.loader = loader;
        this.onNavigate = onNavigate;
        this.onError = onError;
        setLayout(new BorderLayout());
        tree.setRowHeight(24);
        tree.setShowsRootHandles(true);
        DefaultTreeCellRenderer r = new DefaultTreeCellRenderer();
        javax.swing.Icon folder = Ui.icon("folder");
        r.setLeafIcon(folder);
        r.setOpenIcon(folder);
        r.setClosedIcon(folder);
        tree.setCellRenderer(r);
        model = new DefaultTreeModel(null);
        tree.setModel(model);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) throws ExpandVetoException {
                Node n = (Node) e.getPath().getLastPathComponent();
                if (!n.loaded) {
                    ensureLoaded(n, () -> tree.expandPath(e.getPath()));
                    throw new ExpandVetoException(e);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent e) {
            }
        });
        tree.addTreeSelectionListener(e -> {
            if (revealing) return;
            Node n = selected();
            if (n != null) onNavigate.accept(n.path);
        });
        JScrollPane sp = new JScrollPane(tree);
        sp.setBorder(null);
        add(sp, BorderLayout.CENTER);
        resetRoot(rootLabel, rootPath);
    }

    /** 连接/断开时重建根节点；path 为 null 表示占位根（无子节点）。 */
    void resetRoot(String label, String path) {
        gen++;
        revealing = false;
        root = new Node(label, path == null ? "" : path, path != null);
        if (path == null) root.loaded = true;
        model.setRoot(root);
        model.reload();
        if (path != null) ensureLoaded(root, null);
    }

    /** 表格导航后调用：沿 chain 逐级展开并选中目标节点。 */
    void reveal(List<String> chain) {
        final int g = ++revealGen;
        revealing = true;
        descend(root, chain, 0, g);
    }

    /** 目录内容变化后调用：重新拉取 chain 指向节点的子目录。 */
    void reload(List<String> chain) {
        Node n = root;
        for (String p : chain) {
            if (!n.loaded || n.loading) return;
            Node next = childByPath(n, p);
            if (next == null) return;
            n = next;
        }
        if (n.loaded && !n.loading) {
            n.loaded = false;
            ensureLoaded(n, null);
        }
    }

    private void descend(Node parent, List<String> chain, int i, int g) {
        if (i >= chain.size()) {
            if (g == revealGen) {
                TreePath tp = new TreePath(parent.getPath());
                tree.setSelectionPath(tp);
                tree.scrollPathToVisible(tp);
                revealing = false;
            }
            return;
        }
        ensureLoaded(parent, () -> {
            if (g != revealGen) return;
            Node child = childByPath(parent, chain.get(i));
            if (child == null) {
                if (g == revealGen) revealing = false;
                return;
            }
            descend(child, chain, i + 1, g);
        });
    }

    private void ensureLoaded(Node n, Runnable then) {
        boolean run;
        synchronized (n) {
            run = n.loaded;
            if (!run) {
                if (then != null) n.pending.add(then);
                if (!n.loading) {
                    n.loading = true;
                    loadAsync(n);
                }
            }
        }
        if (run && then != null) then.run();
    }

    private void loadAsync(Node n) {
        final int g = gen;
        new Thread(() -> {
            List<DirEntry> kids = List.of();
            String err = null;
            try {
                kids = loader.list(n.path);
                kids = new ArrayList<>(kids);
                kids.sort(Comparator.comparing(DirEntry::name, String.CASE_INSENSITIVE_ORDER));
            } catch (Exception e) {
                err = e.getMessage();
            }
            List<DirEntry> fk = kids;
            String fe = err;
            SwingUtilities.invokeLater(() -> {
                synchronized (n) {
                    n.loading = false;
                    if (g != gen || !attached(n)) {
                        n.pending.clear();
                        n.loaded = true;
                    } else {
                        n.removeAllChildren();
                        for (DirEntry d : fk) n.add(new Node(d.name(), d.path(), true));
                        n.loaded = true;
                        model.nodeStructureChanged(n);
                        List<Runnable> pend = new ArrayList<>(n.pending);
                        n.pending.clear();
                        for (Runnable r : pend) r.run();
                    }
                }
                if (fe != null) onError.accept("读取目录树失败: " + fe);
            });
        }, "dir-tree-load").start();
    }

    private boolean attached(Node n) {
        TreeNode p = n;
        while (p.getParent() != null) p = p.getParent();
        return p == root;
    }

    private Node childByPath(Node n, String path) {
        for (int i = 0; i < n.getChildCount(); i++) {
            Node c = (Node) n.getChildAt(i);
            if (c.path.equals(path)) return c;
        }
        return null;
    }

    private Node selected() {
        TreePath tp = tree.getSelectionPath();
        return tp == null ? null : (Node) tp.getLastPathComponent();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (tree == null) return; // 构造期间 super() 先于字段初始化调用 updateUI
        tree.setRowHeight(24);
        DefaultTreeCellRenderer r = new DefaultTreeCellRenderer();
        javax.swing.Icon folder = Ui.icon("folder");
        r.setLeafIcon(folder);
        r.setOpenIcon(folder);
        r.setClosedIcon(folder);
        tree.setCellRenderer(r);
    }
}
