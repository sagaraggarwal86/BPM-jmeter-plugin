package io.github.sagaraggarwal86.jmeter.bpm.gui;

import javax.swing.*;
import java.util.*;

/**
 * A button that opens a popup menu with checkboxes for multi-select filtering.
 *
 * <p>"All" checkbox at the top toggles all options. When all or none are selected,
 * {@link #getSelectedValues()} returns {@code null} (no filter). Button text shows
 * the label plus "All" or a comma-separated list of selected items.</p>
 *
 * <p>Persistence: {@link #toPersistString()} returns comma-separated internal values
 * (or "All"). {@link #fromPersistString(String)} restores from that format.</p>
 */
public final class CheckBoxFilterButton extends JButton {

    private static final int MAX_BUTTON_TEXT_LENGTH = 30;
    private static final String ALL_LABEL = "All";

    private final JPopupMenu popup;
    private final JCheckBoxMenuItem allItem;
    private final JCheckBoxMenuItem[] valueItems;
    private final String[] values;
    private final String label;

    /**
     * Creates a multi-select filter button.
     *
     * @param label  prefix shown on the button (e.g. "Stability")
     * @param values selectable values — used both as internal filter keys and display text
     */
    public CheckBoxFilterButton(String label, String[] values) {
        this.label = label;
        this.values = values.clone();

        popup = new JPopupMenu();
        valueItems = new JCheckBoxMenuItem[values.length];
        allItem = new JCheckBoxMenuItem(ALL_LABEL, true);
        allItem.addActionListener(e -> {
            boolean sel = allItem.isSelected();
            for (JCheckBoxMenuItem item : valueItems) {
                item.setSelected(sel);
            }
            updateText();
        });
        popup.add(allItem);
        popup.addSeparator();

        for (int i = 0; i < values.length; i++) {
            valueItems[i] = new JCheckBoxMenuItem(values[i], true);
            valueItems[i].addActionListener(e -> {
                syncAllCheckBox();
                updateText();
            });
            popup.add(valueItems[i]);
        }

        updateText();
        addActionListener(e -> popup.show(this, 0, getHeight()));
    }

    private boolean allItemsSelected() {
        for (JCheckBoxMenuItem item : valueItems) {
            if (!item.isSelected()) {
                return false;
            }
        }
        return true;
    }

    private void syncAllCheckBox() {
        allItem.setSelected(allItemsSelected());
    }

    private void updateText() {
        if (allItem.isSelected() || noneSelected()) {
            setText(label + ": " + ALL_LABEL);
        } else {
            List<String> selected = new ArrayList<>();
            for (JCheckBoxMenuItem valueItem : valueItems) {
                if (valueItem.isSelected()) {
                    selected.add(valueItem.getText());
                }
            }
            String joined = String.join(", ", selected);
            if (joined.length() > MAX_BUTTON_TEXT_LENGTH) {
                joined = joined.substring(0, MAX_BUTTON_TEXT_LENGTH - 3) + "...";
            }
            setText(label + ": " + joined);
        }
    }

    private boolean noneSelected() {
        for (JCheckBoxMenuItem item : valueItems) {
            if (item.isSelected()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the set of selected internal values, or {@code null} if all (or none) are selected
     * — meaning "no filter".
     */
    public Set<String> getSelectedValues() {
        if (allItem.isSelected() || noneSelected()) {
            return null;
        }
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < valueItems.length; i++) {
            if (valueItems[i].isSelected()) {
                result.add(values[i]);
            }
        }
        return result;
    }

    /**
     * Selects all options (resets to "no filter" state).
     */
    public void selectAll() {
        allItem.setSelected(true);
        for (JCheckBoxMenuItem item : valueItems) {
            item.setSelected(true);
        }
        updateText();
    }

    /**
     * Serializes current selection to a comma-separated string for TestElement persistence.
     * Returns "All" when all/none are selected.
     */
    public String toPersistString() {
        if (allItem.isSelected() || noneSelected()) {
            return ALL_LABEL;
        }
        StringJoiner sj = new StringJoiner(",");
        for (int i = 0; i < valueItems.length; i++) {
            if (valueItems[i].isSelected()) {
                sj.add(values[i]);
            }
        }
        return sj.toString();
    }

    /**
     * Restores selection from a comma-separated persistence string.
     * "All", null, or empty string selects everything.
     */
    public void fromPersistString(String csv) {
        if (csv == null || csv.isEmpty() || ALL_LABEL.equals(csv)) {
            selectAll();
            return;
        }
        Set<String> selected = new LinkedHashSet<>(Arrays.asList(csv.split(",")));
        for (int i = 0; i < values.length; i++) {
            valueItems[i].setSelected(selected.contains(values[i]));
        }
        syncAllCheckBox();
        updateText();
    }
}
