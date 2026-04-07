/* BPM Report Scripts — loaded at render time and inlined into the HTML report */

/* ── Error banner helper ────────────────────────────────────── */
function bpmShowError(msg) {
    var banner = document.createElement('div');
    banner.style.cssText = 'padding:10px 16px;margin:8px 0;background:#fff5f5;border:1px solid #fed7d7;border-radius:6px;color:#c53030;font-size:12px';
    banner.textContent = msg;
    var area = document.querySelector('.content-area');
    if (area) area.insertBefore(banner, area.firstChild);
}

/* ── Scroll shadow detection ────────────────────────────────── */
try {
    document.querySelectorAll('.tbl-scroll').forEach(function (el) {
        function check() {
            el.classList.toggle('has-scroll', el.scrollWidth > el.clientWidth && el.scrollLeft < el.scrollWidth - el.clientWidth - 1);
        }

        check();
        el.addEventListener('scroll', check);
        window.addEventListener('resize', check);
    });
} catch (e) { /* non-critical */
}

/* ── Panel switching (with ARIA + keyboard navigation) ──────── */
try {
    var tabs = Array.from(document.querySelectorAll('.nav-item[role="tab"]'));

    function activateTab(tab) {
        tabs.forEach(function (t) {
            t.classList.remove('active');
            t.setAttribute('aria-selected', 'false');
            t.setAttribute('tabindex', '-1');
        });
        document.querySelectorAll('.panel').forEach(function (p) {
            p.classList.remove('active');
        });
        tab.classList.add('active');
        tab.setAttribute('aria-selected', 'true');
        tab.setAttribute('tabindex', '0');
        tab.focus();
        var el = document.getElementById(tab.dataset.panel);
        if (el) el.classList.add('active');
        if (el && el.querySelector('canvas') && typeof Chart !== 'undefined') {
            Object.values(Chart.instances).forEach(function (inst) {
                inst.resize();
            });
        }
    }

    tabs.forEach(function (btn) {
        btn.addEventListener('click', function () {
            activateTab(btn);
        });
        btn.addEventListener('keydown', function (e) {
            var idx = tabs.indexOf(btn);
            var next = -1;
            if (e.key === 'ArrowDown' || e.key === 'ArrowRight') {
                next = (idx + 1) % tabs.length;
            } else if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') {
                next = (idx - 1 + tabs.length) % tabs.length;
            } else if (e.key === 'Home') {
                next = 0;
            } else if (e.key === 'End') {
                next = tabs.length - 1;
            }
            if (next >= 0) {
                e.preventDefault();
                activateTab(tabs[next]);
            }
        });
    });
} catch (e) {
    bpmShowError('Navigation failed to initialize. Refresh the page to retry.');
}

/* ── Table pagination + sorting ──────────────────────────────── */
try {
    (function () {
        function initTable(tableId) {
            var tbody = document.querySelector('[data-body-id="' + tableId + '"]');
            var sel = document.querySelector('.row-limit[data-for="' + tableId + '"]');
            var pager = document.querySelector('.pager[data-for="' + tableId + '"]');
            if (!tbody) return;
            var allRows = [];
            tbody.querySelectorAll('tr').forEach(function (r) {
                allRows.push(r);
            });
            var page = 0;
            var sortCol = -1, sortAsc = true;

            function render() {
                var limit = sel ? parseInt(sel.value) : allRows.length;
                var total = allRows.length;
                var pages = Math.ceil(total / limit) || 1;
                if (page >= pages) page = pages - 1;
                var start = page * limit;
                allRows.forEach(function (r, i) {
                    r.style.display = (i >= start && i < start + limit) ? '' : 'none';
                });
                if (pager) buildPager(pager, page, pages, tableId);
            }

            var table = tbody.closest('table');
            if (table) {
                var ths = table.querySelectorAll('thead th');
                ths.forEach(function (th, idx) {
                    th.style.cursor = 'pointer';
                    th.addEventListener('click', function () {
                        if (sortCol === idx) {
                            sortAsc = !sortAsc;
                        } else {
                            sortCol = idx;
                            sortAsc = true;
                        }
                        ths.forEach(function (h) {
                            h.classList.remove('sort-asc', 'sort-desc');
                        });
                        th.classList.add(sortAsc ? 'sort-asc' : 'sort-desc');
                        allRows.sort(function (a, b) {
                            var at = (a.children[idx] || {}).textContent || '';
                            var bt = (b.children[idx] || {}).textContent || '';
                            var an = parseFloat(at.replace(/[^\d.\-]/g, ''));
                            var bn = parseFloat(bt.replace(/[^\d.\-]/g, ''));
                            if (!isNaN(an) && !isNaN(bn)) return sortAsc ? an - bn : bn - an;
                            return sortAsc ? at.localeCompare(bt) : bt.localeCompare(at);
                        });
                        allRows.forEach(function (r) {
                            tbody.appendChild(r);
                        });
                        page = 0;
                        render();
                    });
                });
            }

            if (sel) {
                sel.addEventListener('change', function () {
                    page = 0;
                    render();
                });
            }
            render();

            window['goPage_' + tableId] = function (p) {
                page = p;
                render();
            };
        }

        function buildPager(el, cur, total, tableId) {
            var h = '';
            if (total <= 1) {
                el.innerHTML = '';
                return;
            }
            h += '<button class="pg-btn" ' + (cur === 0 ? 'disabled' : '') +
                ' onclick="goPage_' + tableId + '(' + (cur - 1) + ')">&laquo;</button> ';
            var start = Math.max(0, cur - 2), end = Math.min(total, start + 5);
            if (end - start < 5) start = Math.max(0, end - 5);
            for (var i = start; i < end; i++) {
                h += '<button class="pg-btn' + (i === cur ? ' pg-active' : '') + '"'
                    + ' onclick="goPage_' + tableId + '(' + i + ')">' + (i + 1) + '</button> ';
            }
            h += '<button class="pg-btn" ' + (cur >= total - 1 ? 'disabled' : '') +
                ' onclick="goPage_' + tableId + '(' + (cur + 1) + ')">&raquo;</button>';
            h += ' <span class="pg-info">Page ' + (cur + 1) + ' of ' + total + '</span>';
            el.innerHTML = h;
        }

        document.querySelectorAll('.paginated-tbl').forEach(function (wrap) {
            var id = wrap.dataset.tableId;
            if (id) initTable(id);
        });
    })();
} catch (e) {
    bpmShowError('Table pagination failed to initialize. All data rows are visible.');
}

/* ── Search filter ───────────────────────────────────────────── */
try {
    (function () {
        var pairs = [
            {input: 'metricsSearch', table: 'metrics'},
            {input: 'slaSearch', table: 'sla'}
        ];
        pairs.forEach(function (p) {
            var input = document.getElementById(p.input);
            if (!input) return;
            var timer;
            input.addEventListener('input', function () {
                clearTimeout(timer);
                var self = this;
                timer = setTimeout(function () {
                    var q = self.value.toLowerCase();
                    var tbody = document.querySelector('[data-body-id="' + p.table + '"]');
                    if (!tbody) return;
                    tbody.querySelectorAll('tr').forEach(function (r) {
                        var label = r.querySelector('.label-cell');
                        if (!label) return;
                        r.style.display = label.textContent.toLowerCase().indexOf(q) >= 0 ? '' : 'none';
                    });
                }, 200);
            });
        });
    })();
} catch (e) { /* search is non-critical */
}

/* ── Excel export (styled via xlsx-js-style) ───────────────── */
(function () {
    // Style constants
    var HDR = {
        font: {bold: true, color: {rgb: 'FFFFFF'}, sz: 11},
        fill: {fgColor: {rgb: '2D3748'}},
        alignment: {horizontal: 'center', vertical: 'center'},
        border: {
            top: {style: 'thin', color: {rgb: 'A0AEC0'}},
            bottom: {style: 'thin', color: {rgb: 'A0AEC0'}},
            left: {style: 'thin', color: {rgb: 'A0AEC0'}},
            right: {style: 'thin', color: {rgb: 'A0AEC0'}}
        }
    };
    var CELL_BORDER = {
        top: {style: 'thin', color: {rgb: 'E2E8F0'}},
        bottom: {style: 'thin', color: {rgb: 'E2E8F0'}},
        left: {style: 'thin', color: {rgb: 'E2E8F0'}},
        right: {style: 'thin', color: {rgb: 'E2E8F0'}}
    };
    var SLA_PASS = {
        font: {color: {rgb: '276749'}, bold: true},
        fill: {fgColor: {rgb: 'F0FFF4'}},
        border: CELL_BORDER,
        alignment: {horizontal: 'center'}
    };
    var SLA_WARN = {
        font: {color: {rgb: 'B7791F'}, bold: true},
        fill: {fgColor: {rgb: 'FFFFF0'}},
        border: CELL_BORDER,
        alignment: {horizontal: 'center'}
    };
    var SLA_FAIL = {
        font: {color: {rgb: 'C53030'}, bold: true},
        fill: {fgColor: {rgb: 'FFF5F5'}},
        border: CELL_BORDER,
        alignment: {horizontal: 'center'}
    };
    var CELL_DEFAULT = {border: CELL_BORDER, alignment: {vertical: 'center'}};
    var CELL_NUM = {border: CELL_BORDER, alignment: {horizontal: 'right', vertical: 'center'}};
    var CELL_LABEL = {border: CELL_BORDER, font: {bold: true}, alignment: {vertical: 'center'}};
    var INFO_KEY = {font: {bold: true, sz: 11}, alignment: {vertical: 'center'}};
    var INFO_VAL = {alignment: {vertical: 'center'}};

    function getSlaStyle(td) {
        if (!td) return null;
        var cls = td.className || '';
        if (cls.indexOf('sla-pass') >= 0) return SLA_PASS;
        if (cls.indexOf('sla-fail') >= 0) return SLA_FAIL;
        if (cls.indexOf('sla-warn') >= 0) return SLA_WARN;
        return null;
    }

    function styleHeaderRow(ws, colCount) {
        for (var c = 0; c < colCount; c++) {
            var addr = XLSX.utils.encode_cell({r: 0, c: c});
            if (ws[addr]) ws[addr].s = HDR;
        }
    }

    function styleDataCells(ws, table) {
        var rows = table.querySelectorAll('tbody tr');
        var range = XLSX.utils.decode_range(ws['!ref'] || 'A1');
        var colCount = range.e.c + 1;
        rows.forEach(function (tr, ri) {
            var r = ri + 1; // skip header row
            var tds = tr.querySelectorAll('td');
            for (var c = 0; c < colCount && c < tds.length; c++) {
                var addr = XLSX.utils.encode_cell({r: r, c: c});
                if (!ws[addr]) continue;
                var slaStyle = getSlaStyle(tds[c]);
                if (slaStyle) {
                    ws[addr].s = slaStyle;
                } else if (c === 0) {
                    ws[addr].s = CELL_LABEL;
                } else if (typeof ws[addr].v === 'number') {
                    ws[addr].s = CELL_NUM;
                } else {
                    ws[addr].s = CELL_DEFAULT;
                }
            }
        });
    }

    function autoColWidths(ws) {
        var range = XLSX.utils.decode_range(ws['!ref'] || 'A1');
        var cols = [];
        for (var c = 0; c <= range.e.c; c++) {
            var maxW = 8;
            for (var r = 0; r <= range.e.r; r++) {
                var cell = ws[XLSX.utils.encode_cell({r: r, c: c})];
                if (cell && cell.v != null) {
                    var len = String(cell.v).length;
                    if (len > maxW) maxW = len;
                }
            }
            cols.push({wch: Math.min(maxW + 3, 50)});
        }
        ws['!cols'] = cols;
    }

    function exportExcel() {
        if (typeof XLSX === 'undefined') {
            alert('Excel export is unavailable. The SheetJS library could not be loaded.');
            return;
        }
        try {
            var wb = XLSX.utils.book_new();

            // Test Info sheet
            var infoRows = [['Field', 'Value']];
            if (window.bpmMeta) {
                var m = window.bpmMeta;
                if (m.scenarioName) infoRows.push(['Scenario Name', m.scenarioName]);
                if (m.description) infoRows.push(['Description', m.description]);
                if (m.virtualUsers) infoRows.push(['Virtual Users', m.virtualUsers]);
                if (m.runDateTime) infoRows.push(['Run Date/Time', m.runDateTime]);
                if (m.duration) infoRows.push(['Duration', m.duration]);
                if (m.version) infoRows.push(['Version', m.version]);
            }
            var wsInfo = XLSX.utils.aoa_to_sheet(infoRows);
            wsInfo['!cols'] = [{wch: 20}, {wch: 60}];
            styleHeaderRow(wsInfo, 2);
            for (var i = 1; i < infoRows.length; i++) {
                var kAddr = XLSX.utils.encode_cell({r: i, c: 0});
                var vAddr = XLSX.utils.encode_cell({r: i, c: 1});
                if (wsInfo[kAddr]) wsInfo[kAddr].s = INFO_KEY;
                if (wsInfo[vAddr]) wsInfo[vAddr].s = INFO_VAL;
            }
            XLSX.utils.book_append_sheet(wb, wsInfo, 'Test Info');

            // Panel sheets
            document.querySelectorAll('.panel').forEach(function (panel) {
                var title = panel.dataset.title || 'Sheet';
                var sheetName = title.substring(0, 31);
                var ws;
                var table = panel.querySelector('table');

                if (panel.querySelector('canvas')) {
                    ws = XLSX.utils.aoa_to_sheet([
                        ['Performance charts are not available in Excel export.'],
                        ['Please refer to the HTML report for interactive charts.']
                    ]);
                    ws['!cols'] = [{wch: 65}];
                } else if (table) {
                    // Show all paginated rows before export
                    var hiddenRows = [];
                    table.querySelectorAll('tbody tr').forEach(function (tr) {
                        if (tr.style.display === 'none') {
                            tr.style.display = '';
                            hiddenRows.push(tr);
                        }
                    });

                    ws = XLSX.utils.table_to_sheet(table, {raw: false});
                    var colCount = table.querySelectorAll('thead th').length;
                    styleHeaderRow(ws, colCount);
                    styleDataCells(ws, table);
                    autoColWidths(ws);

                    // Restore hidden rows
                    hiddenRows.forEach(function (tr) {
                        tr.style.display = 'none';
                    });
                } else {
                    // Text panels (Executive Summary, Risk Assessment, Critical Findings)
                    var text = panel.innerText || '';
                    var rows = text.split('\n')
                        .filter(function (l) {
                            return l.trim().length > 0;
                        })
                        .map(function (l) {
                            return [l.trim()];
                        });
                    ws = XLSX.utils.aoa_to_sheet(rows.length > 0 ? rows : [['(empty)']]);
                    ws['!cols'] = [{wch: 120}];
                    // Style first row as title
                    var a1 = ws['A1'];
                    if (a1) a1.s = {font: {bold: true, sz: 13}};
                }
                XLSX.utils.book_append_sheet(wb, ws, sheetName);
            });

            var ts = new Date().toISOString().replace(/[^0-9]/g, '').slice(0, 14);
            XLSX.writeFile(wb, 'BPM_Report_' + ts + '.xlsx');
        } catch (e) {
            alert('Excel export failed: ' + e.message);
        }
    }

    window.exportExcel = exportExcel;

    // Grey out Excel button if SheetJS unavailable
    if (typeof XLSX === 'undefined') {
        document.querySelectorAll('.exp-btn').forEach(function (btn) {
            if (btn.textContent.indexOf('Excel') >= 0) {
                btn.style.opacity = '0.4';
                btn.style.cursor = 'default';
                btn.title = 'Excel export unavailable (SheetJS library not loaded)';
            }
        });
    }
})();

/* ── Dark mode toggle ──────────────────────────────────────── */
(function () {
    var themes = ['auto', 'dark', 'light'];
    var labels = {
        auto: '\uD83C\uDF19\u00A0 Dark Mode',
        dark: '\u2600\uFE0F\u00A0 Light Mode',
        light: '\uD83D\uDD04\u00A0 Auto'
    };
    var current = 0;

    window.toggleTheme = function () {
        current = (current + 1) % themes.length;
        var theme = themes[current];
        if (theme === 'auto') {
            document.documentElement.removeAttribute('data-theme');
        } else {
            document.documentElement.setAttribute('data-theme', theme);
        }
        var btn = document.getElementById('themeToggle');
        if (btn) btn.innerHTML = labels[theme];
    };

    // Set initial button label
    var btn = document.getElementById('themeToggle');
    if (btn) btn.innerHTML = labels.auto;
})();
