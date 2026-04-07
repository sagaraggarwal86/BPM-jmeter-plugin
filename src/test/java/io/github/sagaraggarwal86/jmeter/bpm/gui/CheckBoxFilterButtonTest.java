package io.github.sagaraggarwal86.jmeter.bpm.gui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CheckBoxFilterButton} — multi-select filter component.
 */
@DisplayName("CheckBoxFilterButton")
class CheckBoxFilterButtonTest {

    private CheckBoxFilterButton button;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        button = new CheckBoxFilterButton("Test", new String[]{"A", "B", "C"});
    }

    @Test
    @DisplayName("Initial state: all selected, getSelectedValues returns null")
    void initialState_allSelected_returnsNull() {
        assertNull(button.getSelectedValues(), "All selected should return null (no filter)");
    }

    @Test
    @DisplayName("Initial button text shows 'Test: All'")
    void initialState_buttonText() {
        assertEquals("Test: All", button.getText());
    }

    @Test
    @DisplayName("toPersistString returns 'All' when all selected")
    void toPersistString_allSelected() {
        assertEquals("All", button.toPersistString());
    }

    @Test
    @DisplayName("fromPersistString 'All' selects everything")
    void fromPersistString_all() {
        button.fromPersistString("All");
        assertNull(button.getSelectedValues());
        assertEquals("All", button.toPersistString());
    }

    @Test
    @DisplayName("fromPersistString null selects everything")
    void fromPersistString_null() {
        button.fromPersistString(null);
        assertNull(button.getSelectedValues());
    }

    @Test
    @DisplayName("fromPersistString empty selects everything")
    void fromPersistString_empty() {
        button.fromPersistString("");
        assertNull(button.getSelectedValues());
    }

    @Test
    @DisplayName("fromPersistString with single value selects only that value")
    void fromPersistString_singleValue() {
        button.fromPersistString("B");
        Set<String> selected = button.getSelectedValues();
        assertNotNull(selected);
        assertEquals(Set.of("B"), selected);
    }

    @Test
    @DisplayName("fromPersistString with multiple values selects those values")
    void fromPersistString_multipleValues() {
        button.fromPersistString("A,C");
        Set<String> selected = button.getSelectedValues();
        assertNotNull(selected);
        assertEquals(Set.of("A", "C"), selected);
    }

    @Test
    @DisplayName("Round-trip: toPersistString → fromPersistString preserves selection")
    void roundTrip_preservesSelection() {
        button.fromPersistString("A,C");
        String persisted = button.toPersistString();
        assertEquals("A,C", persisted);

        button.fromPersistString(persisted);
        assertEquals(Set.of("A", "C"), button.getSelectedValues());
    }

    @Test
    @DisplayName("selectAll resets to no-filter state")
    void selectAll_resetsToNoFilter() {
        button.fromPersistString("B");
        assertNotNull(button.getSelectedValues());

        button.selectAll();
        assertNull(button.getSelectedValues());
        assertEquals("All", button.toPersistString());
    }

    @Test
    @DisplayName("fromPersistString with all values individually selected returns null (all)")
    void fromPersistString_allValuesSelected_returnsNull() {
        button.fromPersistString("A,B,C");
        assertNull(button.getSelectedValues(), "All values selected should return null");
    }

    @Test
    @DisplayName("fromPersistString with unknown value ignores it")
    void fromPersistString_unknownValue() {
        button.fromPersistString("A,UNKNOWN");
        Set<String> selected = button.getSelectedValues();
        assertNotNull(selected);
        assertEquals(Set.of("A"), selected);
    }

    @Test
    @DisplayName("Button text shows selected items when not all selected")
    void buttonText_partialSelection() {
        button.fromPersistString("A");
        assertEquals("Test: A", button.getText());
    }
}
