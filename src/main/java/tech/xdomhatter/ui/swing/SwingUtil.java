package tech.xdomhatter.ui.swing;

import javax.swing.*;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

final class SwingUtil {
    private SwingUtil() {}

    static JPanel form(String[] labels, JComponent[] fields) {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < labels.length; i++) {
            g.gridx = 0;
            g.gridy = i;
            g.weightx = 0;
            g.fill = GridBagConstraints.NONE;
            p.add(new JLabel(labels[i]), g);
            g.gridx = 1;
            g.weightx = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            JComponent f = fields[i];
            if (f instanceof JTextField tf) tf.setColumns(20);
            p.add(f, g);
        }
        return p;
    }

    static Integer parseInt(String s) {
        try {
            int v = Integer.parseInt(s.strip());
            return v > 0 && v < 65536 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
