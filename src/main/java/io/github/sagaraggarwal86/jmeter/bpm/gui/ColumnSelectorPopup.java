package io.github.sagaraggarwal86.jmeter.bpm.gui;

import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;

import javax.swing.*;
import java.awt.event.ActionListener;

/**
 * Dropdown popup with checkboxes for toggling raw metric column visibility.
 *
 * <p>Contains exactly the <strong>8 raw metric columns</strong> (FCP, LCP, CLS, TTFB,
 * Reqs, Size, Errs, Warns). Derived columns (Score, Render Time, Srvr%, FCP-LCP Gap,
 * Bottleneck) and identity columns (Label, Samples) are always visible and do NOT
 * appear in this selector.</p>
 *
 * <p>Selection state is session-only — resets to defaults (all OFF) on restart or Clear.</p>
 */
public final class ColumnSelectorPopup extends JPopupMenu {

    private static final long serialVersionUID = 1L;

    private final JCheckBoxMenuItem[] checkBoxes;
    private final ActionListener changeListener;

    /**
     * Creates a new column selector popup.
     *
     * @param changeListener listener invoked when any checkbox state changes;
     *                       the source is the {@link JCheckBoxMenuItem} that changed
     */
    public ColumnSelectorPopup(ActionListener changeListener) {
        this.changeListener = changeListener;
        this.checkBoxes = new JCheckBoxMenuItem[BpmConstants.RAW_COLUMN_COUNT];

        for (int i = 0; i < BpmConstants.RAW_COLUMN_COUNT; i++) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(
                    BpmConstants.RAW_METRIC_HEADERS[i],
                    BpmConstants.RAW_COLUMNS_DEFAULT_VISIBILITY[i]);
            item.addActionListener(changeListener);
            checkBoxes[i] = item;
            add(item);
        }
    }

    /**
     * Returns whether the raw column at the given index (0-based within the 8 raw columns)
     * is currently selected (visible).
     *
     * @param rawColumnIndex index from 0 (FCP) to 7 (Warns)
     * @return true if the column is visible
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public boolean isColumnVisible(int rawColumnIndex) {
        return checkBoxes[rawColumnIndex].isSelected();
    }

    /**
     * Returns the visibility state of all 8 raw columns as a boolean array.
     *
     * @return array of 8 booleans; index 0 = FCP, index 7 = Warns
     */
    public boolean[] getVisibility() {
        boolean[] visibility = new boolean[BpmConstants.RAW_COLUMN_COUNT];
        for (int i = 0; i < BpmConstants.RAW_COLUMN_COUNT; i++) {
            visibility[i] = checkBoxes[i].isSelected();
        }
        return visibility;
    }

    /**
     * Sets the visibility state of all 8 raw columns from a boolean array.
     * Fires a single change notification so the table updates.
     *
     * @param visibility array of 8 booleans; index 0 = FCP, index 7 = Warns
     */
    public void setVisibility(boolean[] visibility) {
        for (int i = 0; i < BpmConstants.RAW_COLUMN_COUNT && i < visibility.length; i++) {
            checkBoxes[i].removeActionListener(changeListener);
            checkBoxes[i].setSelected(visibility[i]);
            checkBoxes[i].addActionListener(changeListener);
        }
        changeListener.actionPerformed(
                new java.awt.event.ActionEvent(this, java.awt.event.ActionEvent.ACTION_PERFORMED, "restore"));
    }

    /**
     * Resets all checkboxes to their defaults (all OFF). Called during Clear/Clear All
     * and at test restart.
     */
    public void resetToDefaults() {
        for (int i = 0; i < BpmConstants.RAW_COLUMN_COUNT; i++) {
            checkBoxes[i].removeActionListener(changeListener);
            checkBoxes[i].setSelected(BpmConstants.RAW_COLUMNS_DEFAULT_VISIBILITY[i]);
            checkBoxes[i].addActionListener(changeListener);
        }
        changeListener.actionPerformed(
                new java.awt.event.ActionEvent(this, java.awt.event.ActionEvent.ACTION_PERFORMED, "reset"));
    }

    /**
     * Returns the number of currently visible raw columns.
     *
     * @return count of selected checkboxes (0-8)
     */
    public int getVisibleCount() {
        int count = 0;
        for (JCheckBoxMenuItem cb : checkBoxes) {
            if (cb.isSelected()) {
                count++;
            }
        }
        return count;
    }
}