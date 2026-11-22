package com.bspsms.android

import org.json.JSONObject

object PortalHrpcDomScripts {

    private fun sharedRowActionJs(): String = """
        function _bspNormalizeText(s) {
            return String(s || '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
        }
        function _bspGetColMap(tbl) {
            var headers = Array.from(tbl.querySelectorAll('thead tr th, thead tr td, tr:first-child th, tr:first-child td'));
            var map = { roll: -1, name: -1, view: -1, print: -1, action: -1, headers: headers.map(function(c) { return _bspNormalizeText(c.innerText).toLowerCase(); }) };
            map.headers.forEach(function(txt, idx) {
                if (/roll/.test(txt) && map.roll === -1) map.roll = idx;
                else if ((/student\s*name|\bname\b/.test(txt)) && map.name === -1) map.name = idx;
                else if (/\bview\b/.test(txt) && !/print/.test(txt) && map.view === -1) map.view = idx;
                else if (/\bprint\b/.test(txt) && !/view/.test(txt) && map.print === -1) map.print = idx;
                else if (/action|marksheet|report|view.*print|print.*view/.test(txt) && map.action === -1) map.action = idx;
            });
            return map;
        }
        function _bspElementOwnAndChildMeta(el) {
            var cls = typeof el.className === 'string' ? el.className : (el.getAttribute('class') || '');
            var parts = [
                el.innerText || '',
                el.textContent || '',
                el.value || '',
                el.title || '',
                el.id || '',
                el.name || '',
                cls,
                el.getAttribute('aria-label') || '',
                el.getAttribute('data-original-title') || '',
                el.getAttribute('data-bs-original-title') || '',
                el.getAttribute('data-title') || '',
                el.getAttribute('data-tooltip') || '',
                el.getAttribute('data-action') || '',
                el.getAttribute('href') || '',
                el.getAttribute('onclick') || ''
            ];
            var nodes = el.querySelectorAll('i, span, svg, img, path, use');
            for (var i = 0; i < nodes.length; i++) {
                var n = nodes[i];
                var nCls = typeof n.className === 'string' ? n.className : (n.getAttribute('class') || '');
                parts.push(nCls);
                parts.push(n.id || '');
                parts.push(n.alt || '');
                parts.push(n.title || '');
                parts.push(n.getAttribute('src') || '');
                parts.push(n.getAttribute('href') || '');
                parts.push(n.getAttribute('xlink:href') || '');
                parts.push(n.getAttribute('data-original-title') || '');
                parts.push(n.getAttribute('data-bs-original-title') || '');
                parts.push(n.getAttribute('aria-label') || '');
            }
            var nonHtmlMeta = parts.join(' ').toLowerCase();
            var htmlMeta = (el.innerHTML || '').toLowerCase();
            return { nonHtml: nonHtmlMeta, full: nonHtmlMeta + ' ' + htmlMeta };
        }
        function _bspCollectRowClickables(container) {
            if (!container) return [];
            var raw = Array.from(container.querySelectorAll('a, button, input[type="button"], input[type="submit"], [role="button"], span[onclick], i[onclick], img[onclick], td i.fa, td i[class*="icon"], td svg'));
            var filtered = raw.filter(function(el) {
                var parentCtrl = el.parentElement ? el.parentElement.closest('a, button, input[type="button"], input[type="submit"], [role="button"]') : null;
                if (parentCtrl && parentCtrl !== el && container.contains(parentCtrl)) return false;
                var st = null;
                try { st = window.getComputedStyle(el); } catch(e) {}
                if (st && (st.display === 'none' || st.visibility === 'hidden')) return false;
                if (el.tagName === 'I' || el.tagName === 'SVG') {
                    // Only treat bare icon/svg as clickable if inside td or has onclick/cursor pointer
                    var clickableStyle = st && st.cursor === 'pointer';
                    if (!el.getAttribute('onclick') && !clickableStyle && !el.closest('td')) return false;
                }
                return true;
            });
            return filtered.length > 0 ? filtered : Array.from(container.querySelectorAll('a, button, input[type="button"], input[type="submit"], [role="button"], [onclick]'));
        }
        function _bspFindActionControl(tbl, row, actionWord, map) {
            if (!map) map = _bspGetColMap(tbl);
            var cells = Array.from(row.querySelectorAll('td'));
            var childRow = (row.nextElementSibling && row.nextElementSibling.classList && row.nextElementSibling.classList.contains('child')) ? row.nextElementSibling : null;

            function matchesPrint(el, tdIdx) {
                var m = _bspElementOwnAndChildMeta(el);
                if (/(?:^|[^a-z])(print|printer|pdf)(?:[^a-z]|${'$'})|fa-print|glyphicon-print|bi-printer|mdi-printer|la-print|print_hrpc|printcard|printmarksheet|printreport/i.test(m.nonHtml)) return true;
                if (/fa-print|glyphicon-print|bi-printer|mdi-printer|printer|print_icon|print-icon/i.test(m.full)) return true;
                if (tdIdx >= 0 && map.headers[tdIdx] && /print/.test(map.headers[tdIdx]) && !/view/.test(map.headers[tdIdx])) return true;
                return false;
            }
            function matchesView(el, tdIdx) {
                var m = _bspElementOwnAndChildMeta(el);
                if (/(?:^|[^a-z])(view|preview|eye|marksheet)(?:[^a-z]|${'$'})|fa-eye|glyphicon-eye|bi-eye|mdi-eye|view_hrpc|viewcard|viewmarksheet/i.test(m.nonHtml)) return true;
                if (/fa-eye|glyphicon-eye|bi-eye|mdi-eye/i.test(m.full)) return true;
                if (tdIdx >= 0 && map.headers[tdIdx] && /view/.test(map.headers[tdIdx]) && !/print/.test(map.headers[tdIdx])) return true;
                return false;
            }

            var allCtrls = _bspCollectRowClickables(row);
            if (childRow) {
                allCtrls = allCtrls.concat(_bspCollectRowClickables(childRow));
            }
            if (allCtrls.length === 0) return null;

            // 1. Direct explicit column match
            if (actionWord === 'print' && map.print >= 0 && cells[map.print]) {
                var pColCtrls = _bspCollectRowClickables(cells[map.print]);
                if (pColCtrls.length > 0) return pColCtrls[0];
            }
            if (actionWord === 'view' && map.view >= 0 && cells[map.view]) {
                var vColCtrls = _bspCollectRowClickables(cells[map.view]);
                if (vColCtrls.length > 0) return vColCtrls[0];
            }

            // 2. Attribute / class / icon / SVG / title / onclick / href match
            for (var i = 0; i < allCtrls.length; i++) {
                var ctrl = allCtrls[i];
                var td = ctrl.closest('td');
                var tdIdx = td ? cells.indexOf(td) : -1;
                if (actionWord === 'print' && matchesPrint(ctrl, tdIdx)) return ctrl;
                if (actionWord === 'view' && matchesView(ctrl, tdIdx)) return ctrl;
            }

            // 3. Actions cell & positional pair fallback (View = 1st control, Print = 2nd control)
            var actionCell = (map.action >= 0 && cells[map.action]) ? cells[map.action] : null;
            var actionCtrls = actionCell ? _bspCollectRowClickables(actionCell) : [];
            if (actionCtrls.length === 0) {
                // Gather controls not in the Roll No or Student Name columns
                actionCtrls = allCtrls.filter(function(el) {
                    var td = el.closest('td');
                    var idx = td ? cells.indexOf(td) : -1;
                    if (idx === -1) return true;
                    if (idx === map.roll || idx === map.name) return false;
                    return true;
                });
                // If none excluded (or last column is actions), focus on the rightmost td having >= 2 controls if present
                for (var c = cells.length - 1; c >= 0; c--) {
                    var cCtrls = _bspCollectRowClickables(cells[c]);
                    if (cCtrls.length >= 2) {
                        actionCtrls = cCtrls;
                        break;
                    }
                }
            }

            // Identify view element among actionCtrls so Print picks the control right after View
            var viewCtrl = null;
            for (var j = 0; j < actionCtrls.length; j++) {
                var vtd = actionCtrls[j].closest('td');
                if (matchesView(actionCtrls[j], vtd ? cells.indexOf(vtd) : -1)) {
                    viewCtrl = actionCtrls[j];
                    break;
                }
            }

            if (actionWord === 'view') {
                if (viewCtrl) return viewCtrl;
                if (actionCtrls.length >= 1) return actionCtrls[0];
                return allCtrls[0];
            }

            if (actionWord === 'print') {
                if (viewCtrl) {
                    var vPos = actionCtrls.indexOf(viewCtrl);
                    if (vPos >= 0 && vPos + 1 < actionCtrls.length) {
                        return actionCtrls[vPos + 1];
                    }
                    var other = actionCtrls.find(function(c) { return c !== viewCtrl; });
                    if (other) return other;
                }
                if (actionCtrls.length >= 2) {
                    return actionCtrls[1];
                }
                if (allCtrls.length >= 2 && map.roll !== 0) {
                    return allCtrls[1];
                }
            }
            return null;
        }
    """.trimIndent()

    fun snapshotHrpcStateJs(): String = """
        (function() {
            function findSelect(patterns) {
                var selects = Array.from(document.querySelectorAll('select'));
                for (var p = 0; p < patterns.length; p++) {
                    var rx = patterns[p];
                    for (var i = 0; i < selects.length; i++) {
                        var s = selects[i];
                        var lbl = '';
                        if (s.id) {
                            var l = document.querySelector('label[for="' + s.id + '"]');
                            if (l) lbl = l.innerText || '';
                        }
                        if (!lbl && s.closest('.form-group, td, div')) {
                            lbl = (s.closest('.form-group, td, div').innerText || '').split('\n')[0] || '';
                        }
                        var meta = ((s.name || '') + ' ' + (s.id || '') + ' ' + lbl).toLowerCase();
                        if (rx.test(meta)) {
                            var selText = s.options && s.selectedIndex >= 0 ? (s.options[s.selectedIndex].text || '').trim() : '';
                            return { value: s.value || '', text: selText };
                        }
                    }
                }
                return { value: '', text: '' };
            }

            var pageInfo = { startEntry: 0, endEntry: 0, totalEntries: 0, currentPage: 1, totalPages: 1 };
            var bodyTxt = (document.body && document.body.innerText) ? document.body.innerText : '';
            var infoEl = document.querySelector('.dataTables_info, [id*="_info"]');
            var infoTxt = infoEl ? infoEl.innerText : bodyTxt;
            var m = infoTxt.match(/Showing\s+(\d+)\s+to\s+(\d+)\s+of\s+(\d+)\s+entries/i);
            if (m) {
                pageInfo.startEntry = parseInt(m[1], 10) || 0;
                pageInfo.endEntry = parseInt(m[2], 10) || 0;
                pageInfo.totalEntries = parseInt(m[3], 10) || 0;
            }
            var activePageEl = document.querySelector('.dataTables_paginate .paginate_button.active, .pagination li.active a, .pagination li.active span, .paginate_button.current');
            if (activePageEl) {
                var pn = parseInt((activePageEl.innerText || '').trim(), 10);
                if (pn > 0) pageInfo.currentPage = pn;
            }
            var pageNums = Array.from(document.querySelectorAll('.dataTables_paginate .paginate_button, .pagination li a, .pagination li span'))
                .map(function(el) { return parseInt((el.innerText || '').trim(), 10); })
                .filter(function(n) { return !isNaN(n) && n > 0; });
            if (pageNums.length > 0) {
                pageInfo.totalPages = Math.max.apply(null, pageNums);
            } else if (pageInfo.totalEntries > 0) {
                var perPage = (pageInfo.endEntry - pageInfo.startEntry + 1);
                if (perPage > 0) pageInfo.totalPages = Math.max(1, Math.ceil(pageInfo.totalEntries / perPage));
            }

            return JSON.stringify({
                url: location.href,
                academicYear: findSelect([/academic|session|year/]),
                medium: findSelect([/medium/]),
                className: findSelect([/\bclass\b|grade/]),
                section: findSelect([/section/]),
                currentPage: pageInfo.currentPage,
                totalPages: pageInfo.totalPages,
                totalEntries: pageInfo.totalEntries,
                startEntry: pageInfo.startEntry,
                endEntry: pageInfo.endEntry
            });
        })();
    """.trimIndent()

    fun scanHrpcTableJs(): String = """
        (function() {
            var bodyTxt = (document.body && document.body.innerText) ? document.body.innerText : '';
            var hasAuthForm = !!document.querySelector('input[type="password"], input[name*="captcha" i], img[src*="captcha" i]');
            if (hasAuthForm) {
                return JSON.stringify({ ok: false, sessionExpired: true, reason: 'Portal sign-in page is displayed. Please sign in first.' });
            }

            ${sharedRowActionJs()}

            var tables = Array.from(document.querySelectorAll('table'));
            var bestTable = null;
            var bestColMap = null;
            var bestScore = -1;

            for (var t = 0; t < tables.length; t++) {
                var tbl = tables[t];
                var headers = Array.from(tbl.querySelectorAll('thead tr th, thead tr td, tr:first-child th, tr:first-child td'));
                if (headers.length === 0) continue;
                var map = _bspGetColMap(tbl);

                var dataRows = Array.from(tbl.querySelectorAll('tbody tr'));
                if (dataRows.length === 0) {
                    dataRows = Array.from(tbl.querySelectorAll('tr')).filter(function(r) { return !r.querySelector('th'); });
                }

                var hasViewOrPrintButtons = false;
                for (var r = 0; r < Math.min(dataRows.length, 5); r++) {
                    var rowTxt = (dataRows[r].innerText || '').toLowerCase();
                    if (/\bview\b|\bprint\b/i.test(rowTxt) ||
                        _bspCollectRowClickables(dataRows[r]).length > 0) {
                        hasViewOrPrintButtons = true;
                        break;
                    }
                }

                var score = 0;
                if (map.roll >= 0) score += 3;
                if (map.name >= 0) score += 3;
                if (map.view >= 0 || map.print >= 0 || map.action >= 0) score += 2;
                if (hasViewOrPrintButtons) score += 3;
                if (dataRows.length > 0) score += 1;

                if (score > bestScore && dataRows.length > 0 && (map.roll >= 0 || map.name >= 0 || hasViewOrPrintButtons)) {
                    bestScore = score;
                    bestTable = tbl;
                    bestColMap = map;
                }
            }

            if (!bestTable) {
                return JSON.stringify({
                    ok: false,
                    sessionExpired: false,
                    reason: 'Student Information table not found. Select Academic Year, Medium, Class, Section and click Search first.'
                });
            }

            bestTable.setAttribute('data-bsp-student-table', 'true');

            var rows = Array.from(bestTable.querySelectorAll('tbody tr'));
            if (rows.length === 0) {
                rows = Array.from(bestTable.querySelectorAll('tr')).filter(function(r) { return !r.querySelector('th'); });
            }

            var students = [];
            rows.forEach(function(row, idx) {
                if (row.classList && row.classList.contains('child')) return;
                var cells = Array.from(row.querySelectorAll('td'));
                if (cells.length === 0) return;
                var rowFullTxt = (row.innerText || '').trim();
                if (/no\s*(?:data|matching\s*records)\s*(?:available|found)/i.test(rowFullTxt)) return;

                var rollIdx = bestColMap.roll >= 0 ? bestColMap.roll : 0;
                var nameIdx = bestColMap.name >= 0 ? bestColMap.name : (cells.length > 1 ? 1 : 0);

                var rollNo = cells[rollIdx] ? (cells[rollIdx].innerText || '').trim() : '';
                var name = cells[nameIdx] ? (cells[nameIdx].innerText || '').trim() : '';

                if (!name && rollNo) {
                    name = 'STUDENT_' + rollNo;
                }
                if (!rollNo && !name) return;

                var viewEl = _bspFindActionControl(bestTable, row, 'view', bestColMap);
                var printEl = _bspFindActionControl(bestTable, row, 'print', bestColMap);

                row.setAttribute('data-bsp-row-idx', String(idx));
                students.push({
                    rowIndex: idx,
                    rollNo: rollNo.replace(/\s+/g, ' ').trim(),
                    name: name.replace(/\s+/g, ' ').trim(),
                    hasView: !!viewEl,
                    hasPrint: !!printEl,
                    viewHref: viewEl && viewEl.tagName === 'A' ? (viewEl.getAttribute('href') || '') : '',
                    printHref: printEl && printEl.tagName === 'A' ? (printEl.getAttribute('href') || '') : ''
                });
            });

            var infoEl = document.querySelector('.dataTables_info, [id*="_info"]');
            var infoTxt = infoEl ? infoEl.innerText : bodyTxt;
            var startEntry = 0, endEntry = 0, totalEntries = students.length, currentPage = 1, totalPages = 1;
            var m = infoTxt.match(/Showing\s+(\d+)\s+to\s+(\d+)\s+of\s+(\d+)\s+entries/i);
            if (m) {
                startEntry = parseInt(m[1], 10) || 0;
                endEntry = parseInt(m[2], 10) || 0;
                totalEntries = parseInt(m[3], 10) || students.length;
            }
            var activePageEl = document.querySelector('.dataTables_paginate .paginate_button.active, .pagination li.active a, .pagination li.active span, .paginate_button.current');
            if (activePageEl) {
                var pn = parseInt((activePageEl.innerText || '').trim(), 10);
                if (pn > 0) currentPage = pn;
            }
            var pageNums = Array.from(document.querySelectorAll('.dataTables_paginate .paginate_button, .pagination li a, .pagination li span'))
                .map(function(el) { return parseInt((el.innerText || '').trim(), 10); })
                .filter(function(n) { return !isNaN(n) && n > 0; });
            if (pageNums.length > 0) {
                totalPages = Math.max.apply(null, pageNums);
            } else if (totalEntries > 0 && endEntry >= startEntry && startEntry > 0) {
                var perPage = endEntry - startEntry + 1;
                totalPages = Math.max(1, Math.ceil(totalEntries / perPage));
            }

            var nextBtn = document.querySelector('.dataTables_paginate .paginate_button.next:not(.disabled), li.next:not(.disabled) a, a.next:not(.disabled), button.next:not([disabled])');
            var hasNext = false;
            if (nextBtn) {
                var cls = (nextBtn.className || '') + ' ' + ((nextBtn.parentElement && nextBtn.parentElement.className) || '');
                hasNext = !/disabled/i.test(cls);
            }
            if (!hasNext && currentPage < totalPages) hasNext = true;

            return JSON.stringify({
                ok: true,
                sessionExpired: false,
                url: location.href,
                currentPage: currentPage,
                totalPages: totalPages,
                startEntry: startEntry,
                endEntry: endEntry,
                totalEntries: totalEntries,
                hasNextPage: hasNext,
                students: students
            });
        })();
    """.trimIndent()

    fun triggerStudentActionJs(
        rowIndex: Int,
        rollNo: String,
        studentName: String,
        actionType: String
    ): String {
        val qRoll = JSONObject.quote(rollNo)
        val qName = JSONObject.quote(studentName)
        val qAction = JSONObject.quote(actionType)
        return """
            (function() {
                // Prevent Android print spooler dialog during automated capture
                window.__bspPrintTriggered = false;
                if (!window.__bspOrigPrint) {
                    window.__bspOrigPrint = window.print;
                }
                window.print = function() {
                    window.__bspPrintTriggered = true;
                };

                ${sharedRowActionJs()}

                var tbl = document.querySelector('table[data-bsp-student-table="true"]') ||
                    Array.from(document.querySelectorAll('table')).find(function(t) {
                        return t.querySelector('tbody tr');
                    });
                if (!tbl) return JSON.stringify({ ok: false, error: 'Student table missing on page' });

                var rows = Array.from(tbl.querySelectorAll('tbody tr')).filter(function(r) {
                    return !(r.classList && r.classList.contains('child'));
                });
                if (rows.length === 0) rows = Array.from(tbl.querySelectorAll('tr')).filter(function(r) { return !r.querySelector('th'); });

                var targetRow = tbl.querySelector('tr[data-bsp-row-idx="' + ${rowIndex} + '"]');
                var wantRoll = ($qRoll || '').replace(/\s+/g, ' ').trim().toLowerCase();
                var wantName = ($qName || '').replace(/\s+/g, ' ').trim().toLowerCase();
                if (!targetRow || (wantName && _bspNormalizeText(targetRow.innerText).toLowerCase().indexOf(wantName) === -1)) {
                    targetRow = rows.find(function(r) {
                        var txt = _bspNormalizeText(r.innerText).toLowerCase();
                        return (wantName && txt.indexOf(wantName) !== -1) && (!wantRoll || txt.indexOf(wantRoll) !== -1);
                    }) || rows.find(function(r) {
                        var txt = _bspNormalizeText(r.innerText).toLowerCase();
                        return wantRoll && txt.indexOf(wantRoll) !== -1;
                    }) || rows[${rowIndex}];
                }
                if (!targetRow) return JSON.stringify({ ok: false, error: 'Student row not found: ' + $qName });

                var colMap = _bspGetColMap(tbl);
                var action = ($qAction || 'print').toLowerCase();
                var chosen = _bspFindActionControl(tbl, targetRow, action, colMap);

                if (!chosen) {
                    return JSON.stringify({ ok: false, error: 'Action button "' + action + '" not found for ' + $qName });
                }

                // Force target into same WebView if _blank
                if (chosen.tagName === 'A' && chosen.getAttribute('target') === '_blank') {
                    chosen.setAttribute('target', '_self');
                }
                var parentForm = chosen.closest('form');
                if (parentForm && parentForm.getAttribute('target') === '_blank') {
                    parentForm.setAttribute('target', '_self');
                }

                window.__bspLastClickedHref = chosen.href || '';
                if (typeof chosen.scrollIntoView === 'function') {
                    chosen.scrollIntoView({ block: 'center' });
                }
                if (typeof chosen.click === 'function') {
                    chosen.click();
                } else {
                    chosen.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                }
                return JSON.stringify({ ok: true, href: window.__bspLastClickedHref });
            })();
        """.trimIndent()
    }

    fun suppressAutoPrintJs(): String = """
        (function() {
            if (!window.__bspOrigPrint) {
                window.__bspOrigPrint = window.print;
            }
            window.print = function() {
                window.__bspPrintTriggered = true;
            };
            return 'ok';
        })();
    """.trimIndent()

    fun checkMarksheetRenderJs(): String = """
        (function() {
            if (!window.__bspOrigPrint) {
                window.__bspOrigPrint = window.print;
            }
            window.print = function() {
                window.__bspPrintTriggered = true;
            };

            var bodyTxt = (document.body && document.body.innerText) ? document.body.innerText : '';
            var hasAuthForm = !!document.querySelector('input[type="password"], input[name*="captcha" i], img[src*="captcha" i]');
            var hasStudentListTable = !!document.querySelector('table[data-bsp-student-table="true"]') ||
                (/Showing\s+\d+\s+to\s+\d+\s+of\s+\d+\s+entries/i.test(bodyTxt) &&
                 !/Summative\s+Evaluation/i.test(bodyTxt) &&
                 !/Progress\s+Report\s+Card/i.test(bodyTxt));

            var hasSummative = /Summative\s+Evaluation/i.test(bodyTxt);
            var hasReportHeader = /Progress\s+Report|Holistic\s+Report|Mark\s*sheet|Banglarshiksha|Academic\s+Year|Evaluation/i.test(bodyTxt);
            var hasSheetContainer = !!document.querySelector('section.sheet, .sheet, .print-page, .marksheet-page, #printableArea, #printArea');
            var hasModalOpen = !!document.querySelector('.modal.show, .modal.in, [role="dialog"]:not([hidden])');
            var spinnerVisible = !!document.querySelector('.loading:not([style*="display: none"]), .spinner:not([style*="display: none"]), #loader:not([style*="display: none"])');

            var imgs = Array.from(document.querySelectorAll('img'));
            var pendingImgs = imgs.filter(function(img) {
                return img.src && !img.complete && img.offsetParent !== null;
            }).length;

            var looksLikeMarksheet = (!hasStudentListTable || hasModalOpen) &&
                (hasSummative || hasSheetContainer || (hasReportHeader && bodyTxt.length > 250) || window.__bspPrintTriggered === true);

            return JSON.stringify({
                readyState: document.readyState || '',
                hasAuthForm: hasAuthForm,
                hasStudentListTable: hasStudentListTable,
                hasModalOpen: hasModalOpen,
                hasSummative: hasSummative,
                looksLikeMarksheet: looksLikeMarksheet,
                spinnerVisible: spinnerVisible,
                pendingImgs: pendingImgs,
                url: location.href,
                textLen: bodyTxt.length
            });
        })();
    """.trimIndent()

    fun extractSummativeMarksJs(fallbackRoll: String, fallbackName: String): String {
        val qRoll = JSONObject.quote(fallbackRoll)
        val qName = JSONObject.quote(fallbackName)
        return """
            (function() {
                var bodyTxt = (document.body && document.body.innerText) ? document.body.innerText : '';
                var hasAuth = !!document.querySelector('input[type="password"], input[name*="captcha" i]');
                if (hasAuth) {
                    return JSON.stringify({ ok: false, sessionExpired: true, error: 'Portal session expired' });
                }

                function cleanVal(v) {
                    if (v === undefined || v === null) return '';
                    var s = String(v).replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
                    if (/^[-–—]+$/.test(s) || /^n\/?a$/i.test(s)) return '';
                    return s;
                }

                // Extract Student Name & Roll fallback from marksheet text if available
                var detectedName = $qName;
                var detectedRoll = $qRoll;
                var nameM = bodyTxt.match(/(?:Student(?:'s)?\s*Name|Name\s*of\s*Student)\s*[:\-]\s*([^\n\r]+)/i);
                if (nameM && nameM[1] && cleanVal(nameM[1])) detectedName = cleanVal(nameM[1]);
                var rollM = bodyTxt.match(/(?:Roll\s*(?:No\.?|Number)?)\s*[:\-]\s*([0-9A-Za-z\/\-]+)/i);
                if (rollM && rollM[1] && cleanVal(rollM[1])) detectedRoll = cleanVal(rollM[1]);

                // Locate Summative Evaluation table
                var tables = Array.from(document.querySelectorAll('table'));
                var targetTable = null;
                var bestScore = -1;

                for (var t = 0; t < tables.length; t++) {
                    var tbl = tables[t];
                    var txt = (tbl.innerText || '').toLowerCase();
                    var prevTxt = '';
                    var p = tbl.previousElementSibling;
                    for (var hops = 0; hops < 3 && p; hops++) {
                        prevTxt += ' ' + (p.innerText || '').toLowerCase();
                        p = p.previousElementSibling;
                    }
                    var parentTxt = (tbl.parentElement ? tbl.parentElement.innerText.slice(0, 300) : '').toLowerCase();
                    var combined = prevTxt + ' ' + parentTxt + ' ' + txt;

                    var score = 0;
                    if (/summative\s+evaluation/.test(combined)) score += 10;
                    if (/1st|first/.test(txt)) score += 3;
                    if (/2nd|second/.test(txt)) score += 3;
                    if (/3rd|third/.test(txt)) score += 3;
                    if (/bengali|first\s*language/.test(txt)) score += 4;
                    if (/english|second\s*language/.test(txt)) score += 4;
                    if (/math/.test(txt)) score += 4;
                    if (/environment|evs/.test(txt)) score += 3;
                    if (/health/.test(txt)) score += 2;
                    if (/art|work/.test(txt)) score += 2;
                    if (/formative/.test(combined) && !/summative/.test(combined)) score -= 8;

                    if (score > bestScore && score >= 6) {
                        bestScore = score;
                        targetTable = tbl;
                    }
                }

                if (!targetTable) {
                    return JSON.stringify({
                        ok: false,
                        sessionExpired: false,
                        error: 'Summative Evaluation table not found on marksheet view.'
                    });
                }

                // Analyze column layout using thead / header rows
                var allRows = Array.from(targetTable.querySelectorAll('tr'));
                var colIndices = {
                    subject: 0,
                    t1: -1, t2: -1, t3: -1,
                    total: -1
                };

                // Flatten header grid accounting for colSpan/rowSpan
                var headerRows = allRows.slice(0, Math.min(4, allRows.length)).filter(function(r) {
                    return r.closest('thead') || r.querySelector('th') || /subject|summative|term|1st|2nd|3rd|total/i.test(r.innerText || '');
                });
                var grid = [];
                for (var hr = 0; hr < headerRows.length; hr++) grid[hr] = [];
                headerRows.forEach(function(row, rIdx) {
                    var cells = Array.from(row.querySelectorAll('th, td'));
                    var cCursor = 0;
                    cells.forEach(function(cell) {
                        while (grid[rIdx][cCursor] !== undefined) cCursor++;
                        var rs = parseInt(cell.getAttribute('rowspan') || '1', 10) || 1;
                        var cs = parseInt(cell.getAttribute('colspan') || '1', 10) || 1;
                        var label = cleanVal(cell.innerText).toLowerCase();
                        for (var rr = 0; rr < rs; rr++) {
                            if (rIdx + rr < grid.length) {
                                for (var cc = 0; cc < cs; cc++) {
                                    grid[rIdx + rr][cCursor + cc] = { text: label, colSpan: cs };
                                }
                            }
                        }
                        cCursor += cs;
                    });
                });

                var maxCols = grid.reduce(function(acc, r) { return Math.max(acc, r.length); }, 0);
                var obtainedCols = [];
                for (var c = 0; c < maxCols; c++) {
                    var colStack = grid.map(function(r) { return (r[c] && r[c].text) ? r[c].text : ''; }).join(' ');
                    if (/subject|learning\s*area/.test(colStack) && colIndices.subject === 0) {
                        colIndices.subject = c;
                    } else if (/obtained|marks?\s*obtained|mark|score/.test(colStack) && !/full|max/.test(colStack)) {
                        obtainedCols.push({ c: c, label: colStack });
                    }
                    if (/1st|first|\(20\)|\b20\b/.test(colStack) && !/full|max/.test(colStack)) {
                        if (colIndices.t1 === -1 || /obtained|marks?/.test(colStack)) colIndices.t1 = c;
                    }
                    if (/2nd|second|\(30\)|\b30\b/.test(colStack) && !/full|max/.test(colStack)) {
                        if (colIndices.t2 === -1 || /obtained|marks?/.test(colStack)) colIndices.t2 = c;
                    }
                    if (/3rd|third|\(50\)|\b50\b/.test(colStack) && !/full|max/.test(colStack)) {
                        if (colIndices.t3 === -1 || /obtained|marks?/.test(colStack)) colIndices.t3 = c;
                    }
                    if (/\btotal\b|grand\s*total|aggregate/.test(colStack) && !/full|max|1st|2nd|3rd/.test(colStack)) {
                        colIndices.total = c;
                    }
                }

                // If obtainedCols cleanly identified 3 terms + total (e.g. [t1, t2, t3, total])
                if (colIndices.t1 === -1 && obtainedCols.length >= 3) {
                    colIndices.t1 = obtainedCols[0].c;
                    colIndices.t2 = obtainedCols[1].c;
                    colIndices.t3 = obtainedCols[2].c;
                    if (colIndices.total === -1 && obtainedCols.length >= 4) colIndices.total = obtainedCols[3].c;
                }

                function mapSubjectKey(subjText) {
                    var s = subjText.toLowerCase();
                    if (/bengali|first\s*lang|\bbn\b|\bben\b|বাংলা/.test(s)) return 'ben';
                    if (/english|second\s*lang|\ben\b|\beng\b/.test(s)) return 'eng';
                    if (/environment|our\s*env|\bevs\b|paribesh|poribesh|science/.test(s)) return 'evs';
                    if (/math|ganit|arithmetic/.test(s)) return 'math';
                    if (/health|physical|hygiene|swasthya/.test(s)) return 'health';
                    if (/art|work\s*ed|drawing|craft|music/.test(s)) return 'art';
                    return null;
                }

                var subjects = {
                    ben: { t1: '', t2: '', t3: '', total: '' },
                    eng: { t1: '', t2: '', t3: '', total: '' },
                    evs: { t1: '', t2: '', t3: '', total: '' },
                    math: { t1: '', t2: '', t3: '', total: '' },
                    health: { t1: '', t2: '', t3: '', total: '' },
                    art: { t1: '', t2: '', t3: '', total: '' }
                };

                var printedRowTotal = { t1: '', t2: '', t3: '', grandTotal: '', percentage: '', position: '' };

                var bodyDataRows = allRows.slice(headerRows.length);
                bodyDataRows.forEach(function(row) {
                    var cells = Array.from(row.querySelectorAll('td, th')).map(function(el) {
                        return cleanVal(el.innerText);
                    });
                    if (cells.length < 2) return;
                    var rowJoined = cells.join(' ').toLowerCase();

                    // Check summary rows at bottom
                    if (/grand\s*total|\btotal\b/.test(cells[0].toLowerCase()) && !mapSubjectKey(cells[0])) {
                        if (colIndices.t1 >= 0 && cells[colIndices.t1]) printedRowTotal.t1 = cells[colIndices.t1];
                        if (colIndices.t2 >= 0 && cells[colIndices.t2]) printedRowTotal.t2 = cells[colIndices.t2];
                        if (colIndices.t3 >= 0 && cells[colIndices.t3]) printedRowTotal.t3 = cells[colIndices.t3];
                        if (colIndices.total >= 0 && cells[colIndices.total]) printedRowTotal.grandTotal = cells[colIndices.total];
                        return;
                    }
                    if (/percent|%/.test(rowJoined)) {
                        var pm = rowJoined.match(/(\d+(?:\.\d+)?)\s*%?/);
                        if (pm) printedRowTotal.percentage = pm[1];
                    }
                    if (/position|rank/.test(rowJoined)) {
                        var rm = rowJoined.match(/(?:position|rank)\s*[:\-]?\s*(\d+)/i);
                        if (rm) printedRowTotal.position = rm[1];
                    }

                    var subjName = cells[colIndices.subject] || cells[0] || '';
                    var key = mapSubjectKey(subjName);
                    if (!key) {
                        // Try finding any cell that identifies subject
                        for (var k = 0; k < Math.min(2, cells.length); k++) {
                            key = mapSubjectKey(cells[k]);
                            if (key) break;
                        }
                    }
                    if (!key) return;

                    // If columns weren't resolved via complex colspans, fall back heuristically on marks cells
                    var t1Val = (colIndices.t1 >= 0 && colIndices.t1 < cells.length) ? cells[colIndices.t1] : '';
                    var t2Val = (colIndices.t2 >= 0 && colIndices.t2 < cells.length) ? cells[colIndices.t2] : '';
                    var t3Val = (colIndices.t3 >= 0 && colIndices.t3 < cells.length) ? cells[colIndices.t3] : '';
                    var totVal = (colIndices.total >= 0 && colIndices.total < cells.length) ? cells[colIndices.total] : '';

                    if (colIndices.t1 === -1 || colIndices.t2 === -1 || colIndices.t3 === -1) {
                        var numericLike = cells.slice(1).filter(function(x) {
                            return /^(\d+(?:\.\d+)?|ab|absent)$/i.test(x);
                        });
                        if (numericLike.length >= 3) {
                            // Often [Full, Obt1, Full, Obt2, Full, Obt3, Total] vs [Obt1, Obt2, Obt3, Total]
                            if (numericLike.length >= 6 && numericLike[0] === '20') {
                                t1Val = numericLike[1] || '';
                                t2Val = numericLike[3] || '';
                                t3Val = numericLike[5] || '';
                                totVal = numericLike[6] || '';
                            } else {
                                t1Val = numericLike[0] || '';
                                t2Val = numericLike[1] || '';
                                t3Val = numericLike[2] || '';
                                totVal = numericLike[3] || '';
                            }
                        }
                    }

                    subjects[key] = {
                        t1: t1Val,
                        t2: t2Val,
                        t3: t3Val,
                        total: totVal
                    };
                });

                if (!printedRowTotal.percentage) {
                    var pmBody = bodyTxt.match(/(?:Percentage|%\s*of\s*Marks)\s*[:\-]?\s*(\d+(?:\.\d+)?)/i);
                    if (pmBody) printedRowTotal.percentage = pmBody[1];
                }
                if (!printedRowTotal.position) {
                    var posBody = bodyTxt.match(/(?:Position|Rank\s*in\s*Class)\s*[:\-]?\s*(\d+)/i);
                    if (posBody) printedRowTotal.position = posBody[1];
                }

                return JSON.stringify({
                    ok: true,
                    sessionExpired: false,
                    rollNo: detectedRoll,
                    studentName: detectedName,
                    subjects: subjects,
                    printed: printedRowTotal
                });
            })();
        """.trimIndent()
    }

    fun tryCloseInPageMarksheetOrModalJs(): String = """
        (function() {
            var closeSelectors = [
                '.modal.show .close',
                '.modal.show .btn-close',
                '.modal.show [data-dismiss="modal"]',
                '.modal.show [data-bs-dismiss="modal"]',
                '.modal.in .close',
                '.modal.in [data-dismiss="modal"]',
                '[role="dialog"]:not([hidden]) button.close',
                '[role="dialog"]:not([hidden]) .btn-close'
            ];
            for (var i = 0; i < closeSelectors.length; i++) {
                var btn = document.querySelector(closeSelectors[i]);
                if (btn && btn.offsetParent !== null) {
                    btn.click();
                    return JSON.stringify({ handled: true, mode: 'modal_closed' });
                }
            }

            var bodyTxt = (document.body && document.body.innerText) ? document.body.innerText : '';
            var onMarksheetView = /Summative\s+Evaluation|Progress\s+Report|Holistic\s+Report|Mark\s*sheet/i.test(bodyTxt) ||
                !!document.querySelector('section.sheet, .sheet, .print-page, .marksheet-page, #printableArea, #printArea');
            var onStudentList = !!document.querySelector('table[data-bsp-student-table="true"]') ||
                /Showing\s+\d+\s+to\s+\d+\s+of\s+\d+\s+entries/i.test(bodyTxt);

            if (onMarksheetView && !onStudentList) {
                var actionButtons = Array.from(document.querySelectorAll('a, button'));
                for (var j = 0; j < actionButtons.length; j++) {
                    var el = actionButtons[j];
                    var txt = (el.innerText || el.title || '').trim().toLowerCase();
                    if (/^(back|back\s*to\s*list|return|close)$/.test(txt) && el.offsetParent !== null) {
                        el.click();
                        return JSON.stringify({ handled: true, mode: 'back_button_clicked' });
                    }
                }
                return JSON.stringify({ handled: false, onMarksheetView: true });
            }
            return JSON.stringify({ handled: false, onMarksheetView: false });
        })();
    """.trimIndent()

    fun clickPaginationPageJs(targetPage: Int, mode: String = "next"): String = """
        (function() {
            var currentActive = document.querySelector('.dataTables_paginate .paginate_button.active, .pagination li.active a, .pagination li.active span, .paginate_button.current');
            var currentPage = currentActive ? (parseInt((currentActive.innerText || '').trim(), 10) || 1) : 1;

            if ($targetPage > 0 && currentPage === $targetPage && '$mode' === 'exact') {
                return JSON.stringify({ ok: true, alreadyOnPage: true, page: currentPage });
            }

            var firstRowBefore = '';
            var tbl = document.querySelector('table[data-bsp-student-table="true"]') || document.querySelector('table');
            if (tbl) {
                var firstRow = tbl.querySelector('tbody tr');
                if (firstRow) firstRowBefore = (firstRow.innerText || '').trim();
            }

            if ('$mode' === 'exact' && $targetPage > 0) {
                var numBtns = Array.from(document.querySelectorAll('.dataTables_paginate .paginate_button a, .dataTables_paginate .paginate_button, .pagination li a, .pagination li button'));
                var exactBtn = numBtns.find(function(b) {
                    return (b.innerText || '').trim() === String($targetPage);
                });
                if (exactBtn) {
                    exactBtn.click();
                    return JSON.stringify({ ok: true, clicked: true, mode: 'exact', prevFirstRow: firstRowBefore });
                }
            }

            var nextBtn = document.querySelector('.dataTables_paginate .paginate_button.next:not(.disabled) a, .dataTables_paginate .paginate_button.next:not(.disabled), li.next:not(.disabled) a, a.next:not(.disabled), button.next:not([disabled])');
            if (nextBtn) {
                var parentCls = (nextBtn.parentElement && nextBtn.parentElement.className) || '';
                if (!/disabled/i.test((nextBtn.className || '') + ' ' + parentCls)) {
                    nextBtn.click();
                    return JSON.stringify({ ok: true, clicked: true, mode: 'next', prevFirstRow: firstRowBefore });
                }
            }

            return JSON.stringify({ ok: false, error: 'Next page button not found or disabled' });
        })();
    """.trimIndent()

    fun restoreHrpcFiltersJs(snapshotJson: String): String {
        val qSnapshot = JSONObject.quote(snapshotJson)
        return """
            (function() {
                if (window.__bspRestoringFilters) {
                    return JSON.stringify({ triggeredSearch: true, alreadyRestoring: true });
                }
                var snap = JSON.parse($qSnapshot || '{}');

                function findSelect(patterns) {
                    var selects = Array.from(document.querySelectorAll('select'));
                    for (var p = 0; p < patterns.length; p++) {
                        var rx = patterns[p];
                        for (var i = 0; i < selects.length; i++) {
                            var s = selects[i];
                            var lbl = '';
                            if (s.id) {
                                var l = document.querySelector('label[for="' + s.id + '"]');
                                if (l) lbl = l.innerText || '';
                            }
                            if (!lbl && s.closest('.form-group, td, div')) {
                                lbl = (s.closest('.form-group, td, div').innerText || '').split('\n')[0] || '';
                            }
                            var meta = ((s.name || '') + ' ' + (s.id || '') + ' ' + lbl).toLowerCase();
                            if (rx.test(meta)) return s;
                        }
                    }
                    return null;
                }

                function setSelectValue(patterns, wanted) {
                    if (!wanted || (!wanted.value && !wanted.text)) return { found: false, changed: false, missingOption: false };
                    var s = findSelect(patterns);
                    if (!s) return { found: false, changed: false, missingOption: false };
                    if (wanted.value && s.value === wanted.value) return { found: true, changed: false, missingOption: false };
                    var opts = Array.from(s.options || []);
                    var match = opts.find(function(o) { return wanted.value && o.value === wanted.value; }) ||
                        opts.find(function(o) { return wanted.text && (o.text || '').trim().toLowerCase() === wanted.text.toLowerCase(); });
                    if (match && s.value !== match.value) {
                        s.value = match.value;
                        s.dispatchEvent(new Event('change', { bubbles: true }));
                        if (window.jQuery) {
                            try { window.jQuery(s).val(match.value).trigger('change'); } catch(e) {}
                        }
                        return { found: true, changed: true, missingOption: false };
                    }
                    return { found: true, changed: false, missingOption: !match && !!(wanted.value || wanted.text) };
                }

                function findSearchBtn() {
                    var btns = Array.from(document.querySelectorAll('button, input[type="submit"], input[type="button"], a.btn'));
                    return btns.find(function(b) {
                        var txt = (b.innerText || b.value || '').trim().toLowerCase();
                        return /^(search|show|submit|get\s*details|find)$/.test(txt);
                    }) || btns.find(function(b) {
                        var txt = (b.innerText || b.value || '').trim().toLowerCase();
                        return /\b(search|show|get\s*details)\b/.test(txt) && !/reset|clear|back|logout|cancel/.test(txt);
                    });
                }

                var hasTableRows = !!document.querySelector('table tbody tr td') &&
                    /Showing\s+\d+\s+to\s+\d+/i.test((document.body && document.body.innerText) || '');

                var rYear = setSelectValue([/academic|session|year/], snap.academicYear);
                var rMed = setSelectValue([/medium/], snap.medium);
                var rCls = setSelectValue([/\bclass\b|grade/], snap.className);
                var rSec = setSelectValue([/section/], snap.section);
                var changed = rYear.changed || rMed.changed || rCls.changed || rSec.changed;

                if (changed || !hasTableRows) {
                    var searchBtn = findSearchBtn();
                    if (searchBtn) {
                        window.__bspRestoringFilters = true;
                        var attempts = 0;
                        function waitCascadeAndClick() {
                            attempts++;
                            if (rMed.missingOption) rMed = setSelectValue([/medium/], snap.medium);
                            if (rCls.missingOption) rCls = setSelectValue([/\bclass\b|grade/], snap.className);
                            if (rSec.missingOption) rSec = setSelectValue([/section/], snap.section);

                            var stillWaitingAjax = (rMed.missingOption || rCls.missingOption || rSec.missingOption) && attempts < 10;
                            if (stillWaitingAjax) {
                                setTimeout(waitCascadeAndClick, 180);
                                return;
                            }
                            var btnNow = findSearchBtn();
                            window.__bspRestoringFilters = false;
                            if (btnNow) btnNow.click();
                        }
                        setTimeout(waitCascadeAndClick, changed ? 320 : 120);
                        return JSON.stringify({ triggeredSearch: true, changed: changed });
                    }
                }
                return JSON.stringify({ triggeredSearch: false, changed: changed, hasTableRows: hasTableRows });
            })();
        """.trimIndent()
    }
}
