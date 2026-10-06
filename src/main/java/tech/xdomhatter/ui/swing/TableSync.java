package tech.xdomhatter.ui.swing;

import javax.swing.JViewport;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.Point;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 键控表格增量同步：更新已有行、追加新行、删除消失行，并保留选中行与滚动位置。
 * 表格未启用 RowSorter，模型行 == 视图行。
 */
final class TableSync {
    private TableSync() { }

    /** 对已有行的逐列更新回调。 */
    interface RowUpdater {
        void update(int modelRow, Object key);
    }

    /**
     * 按主键差异同步表格行。keys 为当前各行的键（与模型行对齐，会被就地维护）；
     * newRow 负责为新键构建行数据，upd 负责更新已有行的各列。
     */
    static void syncRows(DefaultTableModel model, List<Object> keys, List<?> newKeys,
                         Function<Object, Object[]> newRow, RowUpdater upd) {
        Set<Object> keep = new HashSet<>(newKeys);
        for (int i = keys.size() - 1; i >= 0; i--) {
            if (!keep.contains(keys.get(i))) {
                model.removeRow(i);
                keys.remove(i);
            }
        }
        for (Object k : newKeys) {
            int idx = keys.indexOf(k);
            if (idx >= 0) {
                upd.update(idx, k);
            } else {
                model.addRow(newRow.apply(k));
                keys.add(k);
            }
        }
    }

    /** 仅在值变化时写单元格，避免多余的事件与重绘。 */
    static void setCell(DefaultTableModel m, int row, int col, Object v) {
        if (row < 0 || row >= m.getRowCount()) return;
        if (!Objects.equals(m.getValueAt(row, col), v)) m.setValueAt(v, row, col);
    }

    /** 记录当前视口位置（变更前调用）。 */
    static Point viewPosition(JTable t) {
        return t.getParent() instanceof JViewport vp ? vp.getViewPosition() : null;
    }

    /** 选中行 → 键集合（变更前调用）。 */
    static Set<Object> selectedKeys(JTable t, List<Object> keys) {
        Set<Object> sel = new HashSet<>();
        for (int viewRow : t.getSelectedRows()) {
            int m = t.convertRowIndexToModel(viewRow);
            if (m >= 0 && m < keys.size()) sel.add(keys.get(m));
        }
        return sel;
    }

    /** 变更后恢复：按键重选（不触发滚动）并还原视口位置。 */
    static void restore(JTable t, List<Object> keys, Set<Object> selKeys, Point viewPos) {
        t.clearSelection();
        for (int m = 0; m < keys.size(); m++) {
            if (selKeys.contains(keys.get(m))) t.addRowSelectionInterval(m, m);
        }
        if (viewPos != null && t.getParent() instanceof JViewport vp) {
            var extent = vp.getExtentSize();
            var viewSize = vp.getViewSize();
            int x = clamp(viewPos.x, viewSize.width - extent.width);
            int y = clamp(viewPos.y, viewSize.height - extent.height);
            vp.setViewPosition(new Point(x, y));
        }
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, Math.max(0, max)));
    }
}
