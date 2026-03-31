package io.github.sagaraggarwal86.jmeter.bpm.gui;

import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.List;
import java.util.function.Supplier;

class BpmCellRenderer extends DefaultTableCellRenderer {
    private static final long serialVersionUID = 1L;

    static final Color COLOR_GOOD = new Color(0, 128, 0);
    static final Color COLOR_NEEDS_WORK = new Color(204, 102, 0);
    static final Color COLOR_POOR = new Color(204, 0, 0);
    static final Color ROW_TINT_AMBER = new Color(255, 243, 224);
    static final Color ROW_TINT_RED = new Color(255, 230, 230);

    private final BpmTableModel tableModel;
    private final Supplier<BpmPropertiesManager> propertiesSupplier;

    BpmCellRenderer(BpmTableModel tableModel,
                    Supplier<BpmPropertiesManager> propertiesSupplier) {
        this.tableModel = tableModel;
        this.propertiesSupplier = propertiesSupplier;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus, int row, int column) {
        Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        if (isSelected) {
            return c;
        }
        c.setForeground(table.getForeground());
        c.setBackground(table.getBackground());
        int modelCol = table.convertColumnIndexToModel(column);
        BpmPropertiesManager props = propertiesSupplier.get();
        List<BpmTableModel.RowData> filtered = tableModel.getFilteredRows();
        if (row >= 0 && row < filtered.size()) {
            int score = filtered.get(row).getScore();
            int scorePoor = props != null ? props.getSlaScorePoor() : BpmConstants.DEFAULT_SLA_SCORE_POOR;
            int scoreGood = props != null ? props.getSlaScoreGood() : BpmConstants.DEFAULT_SLA_SCORE_GOOD;
            if (score > 0 && score < scorePoor) {
                c.setBackground(ROW_TINT_RED);
            } else if (score > 0 && score < scoreGood) {
                c.setBackground(ROW_TINT_AMBER);
            }
        }
        applySlaColor(c, modelCol, value, props);
        // Text columns left-aligned; numeric columns right-aligned
        if (modelCol == BpmConstants.COL_IDX_LABEL
                || modelCol == BpmConstants.COL_IDX_IMPROVEMENT_AREA
                || modelCol == BpmConstants.COL_IDX_STABILITY) {
            setHorizontalAlignment(SwingConstants.LEFT);
        } else {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }
        // Value-level cell tooltips for Improvement Area and Stability columns
        if (modelCol == BpmConstants.COL_IDX_IMPROVEMENT_AREA && value instanceof String s) {
            setToolTipText(BpmConstants.getImprovementAreaValueTooltip(s));
        } else if (modelCol == BpmConstants.COL_IDX_STABILITY && value instanceof String s) {
            setToolTipText(BpmConstants.getStabilityValueTooltip(s));
        } else {
            setToolTipText(null);
        }
        return c;
    }

    private void applySlaColor(Component c, int modelCol, Object value, BpmPropertiesManager props) {
        if (value == null) {
            return;
        }
        if (modelCol == BpmConstants.COL_IDX_SCORE && !(value instanceof Integer)) {
            c.setForeground(Color.BLACK);
            return;
        }
        int scoreGood = props != null ? props.getSlaScoreGood() : BpmConstants.DEFAULT_SLA_SCORE_GOOD;
        int scorePoor = props != null ? props.getSlaScorePoor() : BpmConstants.DEFAULT_SLA_SCORE_POOR;
        long fcpGood = props != null ? props.getSlaFcpGood() : BpmConstants.DEFAULT_SLA_FCP_GOOD;
        long fcpPoor = props != null ? props.getSlaFcpPoor() : BpmConstants.DEFAULT_SLA_FCP_POOR;
        long lcpGood = props != null ? props.getSlaLcpGood() : BpmConstants.DEFAULT_SLA_LCP_GOOD;
        long lcpPoor = props != null ? props.getSlaLcpPoor() : BpmConstants.DEFAULT_SLA_LCP_POOR;
        double clsGood = props != null ? props.getSlaClsGood() : BpmConstants.DEFAULT_SLA_CLS_GOOD;
        double clsPoor = props != null ? props.getSlaClsPoor() : BpmConstants.DEFAULT_SLA_CLS_POOR;
        long ttfbGood = props != null ? props.getSlaTtfbGood() : BpmConstants.DEFAULT_SLA_TTFB_GOOD;
        long ttfbPoor = props != null ? props.getSlaTtfbPoor() : BpmConstants.DEFAULT_SLA_TTFB_POOR;
        switch (modelCol) {
            case BpmConstants.COL_IDX_SCORE -> {
                int s = toInt(value);
                c.setForeground(s >= scoreGood ? COLOR_GOOD : s >= scorePoor ? COLOR_NEEDS_WORK : COLOR_POOR);
            }
            case BpmConstants.COL_IDX_FCP -> {
                long v = toLong(value);
                c.setForeground(v <= fcpGood ? COLOR_GOOD : v <= fcpPoor ? COLOR_NEEDS_WORK : COLOR_POOR);
            }
            case BpmConstants.COL_IDX_LCP -> {
                long v = toLong(value);
                c.setForeground(v <= lcpGood ? COLOR_GOOD : v <= lcpPoor ? COLOR_NEEDS_WORK : COLOR_POOR);
            }
            case BpmConstants.COL_IDX_CLS -> {
                double v = toDoubleFromFormatted(value);
                c.setForeground(v <= clsGood ? COLOR_GOOD : v <= clsPoor ? COLOR_NEEDS_WORK : COLOR_POOR);
            }
            case BpmConstants.COL_IDX_TTFB -> {
                long v = toLong(value);
                c.setForeground(v <= ttfbGood ? COLOR_GOOD : v <= ttfbPoor ? COLOR_NEEDS_WORK : COLOR_POOR);
            }
            case BpmConstants.COL_IDX_ERRS -> {
                int e = toInt(value);
                c.setForeground(e == 0 ? COLOR_GOOD : COLOR_POOR);
            }
            case BpmConstants.COL_IDX_STABILITY -> {
                if (BpmConstants.STABILITY_STABLE.equals(value)) c.setForeground(COLOR_GOOD);
                else if (BpmConstants.STABILITY_MINOR_SHIFTS.equals(value)) c.setForeground(COLOR_NEEDS_WORK);
                else if (BpmConstants.STABILITY_UNSTABLE.equals(value)) c.setForeground(COLOR_POOR);
            }
            case BpmConstants.COL_IDX_HEADROOM -> {
                String hStr = value instanceof String s ? s.replace("%", "").trim() : "";
                try {
                    int h = Integer.parseInt(hStr);
                    c.setForeground(h > 50 ? COLOR_GOOD : h > 20 ? COLOR_NEEDS_WORK : COLOR_POOR);
                } catch (NumberFormatException ignored) {
                }
            }
            default -> {
            }
        }
    }

    private int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double toDoubleFromFormatted(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
