package io.github.sagaraggarwal86.jmeter.bpm.gui;

import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;

import javax.swing.*;
import javax.swing.table.TableRowSorter;
import java.util.Comparator;

class TotalPinnedRowSorter extends TableRowSorter<BpmTableModel> {

    TotalPinnedRowSorter(BpmTableModel model) {
        super(model);
        installTotalFilter();
        installComparators();
    }

    // -- TOTAL pinning --------------------------------------------------------

    private static double parsePercent(Object v) {
        if (v == null || "\u2014".equals(v)) return -1.0;
        try {
            return Double.parseDouble(v.toString().replace("%", "").trim());
        } catch (NumberFormatException e) {
            return -1.0;
        }
    }

    private static double parseDouble(Object v) {
        if (v == null || "\u2014".equals(v)) return -1.0;
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return -1.0;
        }
    }

    /**
     * Returns {@code Long.MIN_VALUE} for null/em-dash/unparseable so they sort to the end.
     */
    private static long parseLong(Object v) {
        if (v == null || "\u2014".equals(v)) return Long.MIN_VALUE;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private void installTotalFilter() {
        setRowFilter(new RowFilter<BpmTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends BpmTableModel, ? extends Integer> e) {
                int count = getModel().getRowCount();
                return count > 1 && e.getIdentifier() < count - 1;
            }
        });
    }

    @Override
    public int getViewRowCount() {
        int base = super.getViewRowCount();
        return getModel().getRowCount() > 0 ? base + 1 : base;
    }

    @Override
    public int convertRowIndexToModel(int viewIndex) {
        int count = getModel().getRowCount();
        if (count > 0 && viewIndex == super.getViewRowCount()) {
            return count - 1;
        }
        return super.convertRowIndexToModel(viewIndex);
    }

    @Override
    public int convertRowIndexToView(int modelIndex) {
        int count = getModel().getRowCount();
        if (count > 0 && modelIndex == count - 1) {
            return super.getViewRowCount();
        }
        return super.convertRowIndexToView(modelIndex);
    }

    // Re-install filter on model structure changes so the excluded-last-row index stays current.
    // fireTableDataChanged() triggers modelStructureChanged() which resets the sorter's internal
    // mapping arrays — without reinstalling, the filter's stale state can hide all rows.
    @Override
    public void modelStructureChanged() {
        installTotalFilter();
        super.modelStructureChanged();
    }

    @Override
    public void allRowsChanged() {
        installTotalFilter();
        super.allRowsChanged();
    }

    @Override
    public void rowsInserted(int f, int e) {
        installTotalFilter();
        super.rowsInserted(f, e);
    }

    @Override
    public void rowsDeleted(int f, int e) {
        installTotalFilter();
        super.rowsDeleted(f, e);
    }

    @Override
    public void rowsUpdated(int f, int e) {
        installTotalFilter();
        super.rowsUpdated(f, e);
    }

    // -- Per-column comparators -----------------------------------------------

    private void installComparators() {
        for (int col = 0; col < BpmConstants.TOTAL_COLUMN_COUNT; col++) {
            setComparator(col, buildComparator(col));
        }
    }

    @SuppressWarnings("unchecked")
    private Comparator<Object> buildComparator(int col) {
        return switch (col) {
            case BpmConstants.COL_IDX_LABEL,
                 BpmConstants.COL_IDX_STABILITY,
                 BpmConstants.COL_IDX_IMPROVEMENT_AREA -> Comparator.comparing(v -> v == null ? "" : v.toString());

            case BpmConstants.COL_IDX_SERVER_RATIO,
                 BpmConstants.COL_IDX_HEADROOM -> (a, b) -> Double.compare(parsePercent(a), parsePercent(b));

            case BpmConstants.COL_IDX_CLS -> (a, b) -> Double.compare(parseDouble(a), parseDouble(b));

            default -> (a, b) -> Long.compare(parseLong(a), parseLong(b));
        };
    }
}
