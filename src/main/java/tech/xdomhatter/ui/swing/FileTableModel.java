package tech.xdomhatter.ui.swing;

import javax.swing.table.AbstractTableModel;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 名称/大小/修改时间三列模型：整体换数据 + 单次事件刷新（替代逐行 addRow），配合表头排序。 */
class FileTableModel extends AbstractTableModel {
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String[] COLS = {"名称", "大小", "修改时间"};

    /** dir 时 size 约定为 -1；size/mtime 保留原始数值，格式化交给渲染层。 */
    record Row(String name, boolean dir, long size, long mtime) {}

    private List<Row> rows = List.of();

    void set(List<Row> rows) {
        this.rows = rows;
        fireTableDataChanged();
    }

    Row row(int i) {
        return rows.get(i);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLS.length;
    }

    @Override
    public String getColumnName(int c) {
        return COLS[c];
    }

    @Override
    public Class<?> getColumnClass(int c) {
        return c == 0 ? Row.class : Long.class;
    }

    @Override
    public Object getValueAt(int r, int c) {
        Row x = rows.get(r);
        return switch (c) {
            case 0 -> x;
            case 1 -> x.size();
            default -> x.mtime();
        };
    }
}
