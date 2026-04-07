package io.github.sagaraggarwal86.jmeter.bpm.gui;

import io.github.sagaraggarwal86.jmeter.bpm.model.*;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class BpmTableModel extends AbstractTableModel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(BpmTableModel.class);
    private final LinkedHashMap<String, RowData> rows = new LinkedHashMap<>();
    private String txPattern;
    private Pattern txCompiledPattern;
    private boolean txRegex;
    private boolean txInclude = true;
    private List<RowData> filteredRows;

    private static boolean matchesTransaction(String label, String pattern, Pattern compiled, boolean useRegex) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        if (useRegex && compiled != null) {
            return compiled.matcher(label).find();
        }
        return label.contains(pattern);
    }

    @Override
    public int getRowCount() {
        return getFilteredRows().size();
    }

    @Override
    public int getColumnCount() {
        return BpmConstants.TOTAL_COLUMN_COUNT;
    }

    @Override
    public String getColumnName(int column) {
        return BpmConstants.ALL_COLUMN_HEADERS[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        return getFilteredValueAt(rowIndex, columnIndex);
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }

    @Override
    public Class<?> getColumnClass(int col) {
        return switch (col) {
            case BpmConstants.COL_IDX_LABEL,
                 BpmConstants.COL_IDX_SERVER_RATIO,
                 BpmConstants.COL_IDX_HEADROOM,
                 BpmConstants.COL_IDX_STABILITY,
                 BpmConstants.COL_IDX_IMPROVEMENT_AREA,
                 BpmConstants.COL_IDX_CLS -> String.class;
            default -> Object.class;
        };
    }

    public Object getFilteredValueAt(int rowIndex, int columnIndex) {
        List<RowData> filtered = getFilteredRows();
        if (rowIndex < 0 || rowIndex >= filtered.size()) {
            return null;
        }
        return filtered.get(rowIndex).getColumn(columnIndex);
    }

    public int getFilteredRowCount() {
        return getFilteredRows().size();
    }

    public void setTransactionFilter(String pattern, boolean useRegex, boolean include) {
        this.txPattern = (pattern != null && !pattern.isEmpty()) ? pattern : null;
        this.txRegex = useRegex;
        this.txInclude = include;
        this.txCompiledPattern = null;
        if (txPattern != null && useRegex) {
            try {
                this.txCompiledPattern = Pattern.compile(txPattern);
            } catch (PatternSyntaxException e) {
                log.warn("BPM: Invalid regex pattern '{}': {}. Falling back to substring matching.",
                        txPattern, e.getDescription());
            }
        }
        this.filteredRows = null;
    }

    public void addOrUpdateResult(BpmResult result) {
        String label = result.samplerLabel();
        RowData row = rows.computeIfAbsent(label, k -> new RowData(label));
        row.update(result);
        filteredRows = null;
    }

    public void clear() {
        rows.clear();
        filteredRows = null;
    }

    public List<RowData> getFilteredRows() {
        if (filteredRows != null) {
            return filteredRows;
        }
        List<RowData> result = new ArrayList<>();
        for (RowData row : rows.values()) {
            if (txPattern != null) {
                boolean matches = matchesTransaction(row.label, txPattern, txCompiledPattern, txRegex);
                if (txInclude && !matches) {
                    continue;
                }
                if (!txInclude && matches) {
                    continue;
                }
            }
            result.add(row);
        }
        if (!result.isEmpty()) {
            result.add(computeTotalRow(result));
        }
        filteredRows = result;
        return filteredRows;
    }

    private RowData computeTotalRow(List<RowData> sourceRows) {
        RowData total = new RowData("TOTAL");
        for (RowData row : sourceRows) {
            total.mergeFrom(row);
        }
        return total;
    }

    public static class RowData {
        final String label;
        int sampleCount;
        long totalScore;
        int scoredSampleCount;
        long totalRenderTime;
        double totalServerRatio;
        long totalFrontendTime;
        int frontendTimeCount;
        long totalFcpLcpGap;
        String lastStabilityCategory = null;
        long totalHeadroom;
        int headroomCount;
        long totalFcp;
        long totalLcp;
        double totalCls;
        long totalTtfb;
        int totalRequests;
        long totalBytes;
        int totalErrors;
        int totalWarnings;
        String lastImprovementArea = BpmConstants.BOTTLENECK_NONE;

        public RowData(String label) {
            this.label = label;
        }

        public void update(BpmResult result) {
            sampleCount++;
            DerivedMetrics d = result.derived();
            if (d != null) {
                if (d.performanceScore() != null) {
                    totalScore += d.performanceScore();
                    scoredSampleCount++;
                }
                totalRenderTime += d.renderTime();
                totalServerRatio += d.serverClientRatio();
                totalFcpLcpGap += d.fcpLcpGap();
                if (d.frontendTime() != null) {
                    totalFrontendTime += d.frontendTime();
                    frontendTimeCount++;
                }
                if (d.stabilityCategory() != null) {
                    lastStabilityCategory = d.stabilityCategory();
                }
                if (d.headroom() != null) {
                    totalHeadroom += d.headroom();
                    headroomCount++;
                }
                if (!BpmConstants.BOTTLENECK_NONE.equals(d.improvementArea())) {
                    lastImprovementArea = d.improvementArea();
                }
            }
            WebVitalsResult v = result.webVitals();
            if (v != null) {
                totalFcp += v.fcp() != null ? v.fcp() : 0L;
                totalLcp += v.lcp() != null ? v.lcp() : 0L;
                totalCls += v.cls() != null ? v.cls() : 0.0;
                totalTtfb += v.ttfb() != null ? v.ttfb() : 0L;
            }
            NetworkResult n = result.network();
            if (n != null) {
                totalRequests += n.totalRequests();
                totalBytes += n.totalBytes();
            }
            ConsoleResult c = result.console();
            if (c != null) {
                totalErrors += c.errors();
                totalWarnings += c.warnings();
            }
        }

        public void mergeFrom(RowData other) {
            sampleCount += other.sampleCount;
            totalScore += other.totalScore;
            scoredSampleCount += other.scoredSampleCount;
            totalRenderTime += other.totalRenderTime;
            totalServerRatio += other.totalServerRatio;
            totalFrontendTime += other.totalFrontendTime;
            frontendTimeCount += other.frontendTimeCount;
            totalFcpLcpGap += other.totalFcpLcpGap;
            if (other.lastStabilityCategory != null)
                lastStabilityCategory = other.lastStabilityCategory;
            totalHeadroom += other.totalHeadroom;
            headroomCount += other.headroomCount;
            totalFcp += other.totalFcp;
            totalLcp += other.totalLcp;
            totalCls += other.totalCls;
            totalTtfb += other.totalTtfb;
            totalRequests += other.totalRequests;
            totalBytes += other.totalBytes;
            totalErrors += other.totalErrors;
            totalWarnings += other.totalWarnings;
        }

        public Object getColumn(int index) {
            int n = Math.max(sampleCount, 1);
            return switch (index) {
                case BpmConstants.COL_IDX_LABEL -> label;
                case BpmConstants.COL_IDX_SAMPLES -> sampleCount;
                case BpmConstants.COL_IDX_SCORE ->
                        scoredSampleCount > 0 ? (int) (totalScore / scoredSampleCount) : "\u2014";
                case BpmConstants.COL_IDX_RENDER_TIME -> totalRenderTime / n;
                case BpmConstants.COL_IDX_SERVER_RATIO -> String.format("%.2f%%", totalServerRatio / n);
                case BpmConstants.COL_IDX_FRONTEND_TIME ->
                        frontendTimeCount > 0 ? totalFrontendTime / frontendTimeCount : "\u2014";
                case BpmConstants.COL_IDX_FCP_LCP_GAP -> totalFcpLcpGap / n;
                case BpmConstants.COL_IDX_STABILITY -> lastStabilityCategory != null ? lastStabilityCategory : "\u2014";
                case BpmConstants.COL_IDX_HEADROOM ->
                        headroomCount > 0 ? (int) (totalHeadroom / headroomCount) + "%" : "\u2014";
                case BpmConstants.COL_IDX_IMPROVEMENT_AREA -> "TOTAL".equals(label) ? ""
                        : BpmConstants.BOTTLENECK_NONE.equals(lastImprovementArea) ? "\u2014" : lastImprovementArea;
                case BpmConstants.COL_IDX_FCP -> totalFcp / n;
                case BpmConstants.COL_IDX_LCP -> totalLcp / n;
                case BpmConstants.COL_IDX_CLS -> String.format("%.3f", totalCls / n);
                case BpmConstants.COL_IDX_TTFB -> totalTtfb / n;
                case BpmConstants.COL_IDX_REQS -> totalRequests / n;
                case BpmConstants.COL_IDX_SIZE -> totalBytes / n / 1024;
                case BpmConstants.COL_IDX_ERRS -> totalErrors;
                case BpmConstants.COL_IDX_WARNS -> totalWarnings;
                default -> "";
            };
        }

        public int getScore() {
            return scoredSampleCount > 0 ? (int) (totalScore / scoredSampleCount) : 0;
        }
    }
}
