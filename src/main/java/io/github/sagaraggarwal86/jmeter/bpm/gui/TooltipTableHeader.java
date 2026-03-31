package io.github.sagaraggarwal86.jmeter.bpm.gui;

import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;

import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumnModel;
import java.awt.event.MouseEvent;

class TooltipTableHeader extends JTableHeader {
    private static final long serialVersionUID = 1L;

    TooltipTableHeader(TableColumnModel columnModel) {
        super(columnModel);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int viewCol = columnAtPoint(e.getPoint());
        if (viewCol < 0) {
            return null;
        }
        return BpmConstants.getTooltip(getTable().convertColumnIndexToModel(viewCol));
    }
}
