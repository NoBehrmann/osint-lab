'use strict';

/*
 * OSINT Lab frontend.
 *
 * Flow: user enters a target -> type is auto-detected (overridable) -> tools that accept
 * that type are listed as checkboxes (defaults pre-checked) -> Run fires every checked
 * tool in parallel and each result fills its own section of the report.
 *
 * All untrusted data is inserted via textContent - never innerHTML.
 */

const TYPES = ['DOMAIN', 'IP', 'URL', 'EMAIL', 'USERNAME', 'PHONE', 'COMPANY'];
const MAX_UPLOAD = 1024 ** 3; // must match spring.servlet.multipart.max-file-size
// HEIC and camera RAW files often arrive without a MIME type, so the extension counts too
const IMAGE_EXT = /\.(jpe?g|png|webp|gif|bmp|tiff?|heic|heif|avif|dng|cr2|cr3|nef|arw|orf|rw2|raf|srw|pef)$/i;
let lastUploadId = null; // the server keeps one upload per investigation; freed when replaced

const isImageFile = f => f.type.startsWith('image/') || IMAGE_EXT.test(f.name);

function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    const units = ['KB', 'MB', 'GB'];
    let v = bytes, u = -1;
    while (v >= 1024 && u < units.length - 1) { v /= 1024; u++; }
    return v.toFixed(v < 10 ? 1 : 0) + ' ' + units[u];
}
const HISTORY_KEY = 'osintlab.history';

let tools = [];

// ---------- DOM helper ----------
function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
        if (v === false || v == null) continue;
        if (k === 'class') node.className = v;
        else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
        else node.setAttribute(k, v === true ? '' : v);
    }
    for (const c of children.flat()) {
        if (c == null) continue;
        node.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return node;
}

// ---------- target type detection ----------
function detectType(raw) {
    const s = raw.trim();
    if (!s) return null;
    if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s)) return 'EMAIL';
    if (/^(\d{1,3}\.){3}\d{1,3}$/.test(s)) return 'IP';
    if (s.includes(':') && /^[0-9a-f:]+$/i.test(s) && s.split(':').length > 2) return 'IP';
    if (/^https?:\/\//i.test(s)) return 'URL';
    if (/^\+?[\d\s().\/-]+$/.test(s) && s.replace(/\D/g, '').length >= 7) return 'PHONE';
    if (/^([a-z0-9-]+\.)+[a-z]{2,63}\/\S*$/i.test(s)) return 'URL';
    if (/^([a-z0-9-]+\.)+[a-z]{2,63}$/i.test(s)) return 'DOMAIN';
    if (/^@?[\w.-]{1,40}$/.test(s)) return 'USERNAME';
    return 'COMPANY';
}

// ---------- history (per-browser convenience only) ----------
function loadHistory() {
    try {
        return JSON.parse(localStorage.getItem(HISTORY_KEY)) || [];
    } catch {
        return [];
    }
}

function saveHistory(entry) {
    const list = [entry, ...loadHistory().filter(h => !(h.target === entry.target && h.type === entry.type))].slice(0, 20);
    try {
        localStorage.setItem(HISTORY_KEY, JSON.stringify(list));
    } catch { /* storage unavailable - ignore */ }
}

function clearHistory() {
    try {
        localStorage.removeItem(HISTORY_KEY);
    } catch { /* ignore */ }
    renderSidebar();
}

// ---------- sidebar ----------
function renderSidebar() {
    const view = location.hash.startsWith('#catalog') ? 'catalog' : 'investigate';
    const history = loadHistory();
    const nav = document.getElementById('sidebar');
    nav.replaceChildren(...[
        el('a', {href: '#', class: 'nav-item' + (view === 'investigate' ? ' active' : '')}, 'New investigation'),
        el('a', {href: '#catalog', class: 'nav-item' + (view === 'catalog' ? ' active' : '')}, 'Tool catalog'),
        el('div', {class: 'nav-group'}, 'Recent',
            history.length ? el('a', {href: '#', class: 'nav-clear', onclick: e => (e.preventDefault(), clearHistory())}, 'clear') : null),
        history.length
            ? history.map(h => el('a', {
                href: '#', class: 'nav-item recent', title: h.target,
                onclick: e => (e.preventDefault(), renderInvestigate(h.target, h.type))
            }, el('span', {class: 'recent-target'}, h.target), el('span', {class: 'tag'}, h.type.toLowerCase())))
            : el('div', {class: 'nav-empty'}, 'nothing yet')
    ].flat());
}

// ---------- investigate view ----------
function renderInvestigate(prefillTarget = '', prefillType = '') {
    renderSidebar();
    const main = document.getElementById('main');

    const input = el('input', {
        type: 'text', class: 'target-input', placeholder: 'domain, IP, URL, email, username, phone or company',
        autocomplete: 'off', spellcheck: 'false', value: prefillTarget
    });
    const typeSelect = el('select', {},
        el('option', {value: ''}, 'auto'),
        TYPES.map(t => el('option', {value: t}, t.toLowerCase())));
    typeSelect.value = prefillType && prefillTarget && detectType(prefillTarget) !== prefillType ? prefillType : '';
    const runBtn = el('button', {type: 'submit', class: 'primary'}, 'Run');
    const detected = el('span', {class: 'detected'});
    const igControl = el('div', {class: 'ig-control', hidden: true});
    const checklist = el('div', {class: 'checklist'});
    const report = el('div', {class: 'report'});

    let currentType = null;
    let running = null; // AbortController of the run in progress
    let file = null;    // chosen file, when the target is a file (photo, video, document...)
    const checked = new Map(); // toolId -> boolean, reset when the type changes

    const fileInput = el('input', {type: 'file', hidden: true});
    const fileBtn = el('button', {
        type: 'button', title: 'Analyse a file: photo, video, PDF, Office document... (max 1 GB)',
        onclick: () => fileInput.click()
    }, 'File');
    fileInput.addEventListener('change', () => {
        if (!fileInput.files.length) return;
        file = fileInput.files[0];
        input.value = file.name;
        typeSelect.value = '';
        refresh();
    });

    const effectiveType = () => file ? (isImageFile(file) ? 'IMAGE' : 'FILE') : typeSelect.value || detectType(input.value);
    const fileTooLarge = () => file && file.size > MAX_UPLOAD;

    function refresh() {
        const type = effectiveType();
        detected.textContent = type ? (typeSelect.value ? 'type: ' : 'detected: ') + type.toLowerCase() : '';
        if (file) detected.textContent += ` (${formatSize(file.size)})` + (fileTooLarge() ? ' - too large, max 1 GB' : '');
        if (type !== currentType) {
            currentType = type;
            checked.clear();
            tools.filter(t => type && t.accepts.includes(type)).forEach(t => checked.set(t.id, t.ready && t.defaultOn));
        }
        renderChecklist(type);
        refreshIgControl(type);
        runBtn.disabled = !running && (!type || fileTooLarge() || ![...checked.values()].some(Boolean));
    }

    // ---- Instagram session control (only when Instagram tools apply) ----
    let igStatus = null; // {configured, source} - fetched once, cached in this view

    function needsIgSession(type) {
        return tools.some(t => (t.id === 'instagram' || t.id === 'toutatis') && t.ready && t.accepts.includes(type));
    }

    async function refreshIgControl(type) {
        if (!needsIgSession(type)) {
            igControl.hidden = true;
            return;
        }
        igControl.hidden = false;
        if (igStatus === null) {
            try {
                igStatus = await (await fetch('/api/instagram/status')).json();
            } catch {
                igStatus = {configured: false};
            }
        }
        renderIgControl();
    }

    function renderIgControl(message) {
        const loadBtn = el('button', {
            type: 'button', class: 'link', onclick: async e => {
                e.preventDefault();
                const b = e.target;
                b.disabled = true;
                b.textContent = 'reading browser...';
                try {
                    const r = await (await fetch('/api/instagram/load-session', {method: 'POST'})).json();
                    if (r.loaded) {
                        igStatus = {configured: true, source: r.source};
                        // auto-select the Instagram tools now that they can run (user can still untick)
                        const type = effectiveType();
                        tools.filter(t => (t.id === 'instagram' || t.id === 'toutatis') && t.ready && t.accepts.includes(type))
                            .forEach(t => checked.set(t.id, true));
                        renderChecklist(type);
                        runBtn.disabled = !running && ![...checked.values()].some(Boolean);
                        renderIgControl('loaded from ' + r.source + ' - Instagram tools ticked');
                    } else {
                        renderIgControl(r.message || 'could not read a session from the browser');
                    }
                } catch (err) {
                    renderIgControl(String(err));
                }
            }
        }, igStatus.configured ? 'reload from browser' : 'Load from browser');

        igControl.replaceChildren(
            el('span', {class: 'muted'}, 'Instagram session: '),
            el('span', {class: igStatus.configured ? 'ok' : 'muted'},
                igStatus.configured ? `set (${igStatus.source})` : 'not set'),
            loadBtn,
            // replaceChildren() would render a null argument as the text "null"
            ...(message ? [el('span', {class: 'ig-msg muted'}, message)] : []));
    }

    function renderChecklist(type) {
        if (!type) {
            checklist.replaceChildren(el('div', {class: 'muted'}, 'Enter a target to see applicable tools.'));
            return;
        }
        const applicable = tools.filter(t => t.accepts.includes(type));
        if (!applicable.length) {
            checklist.replaceChildren(el('div', {class: 'muted'}, 'No tools for this type yet.'));
            return;
        }
        const setAll = fn => () => {
            applicable.forEach(t => t.ready && checked.set(t.id, fn(t)));
            refresh();
        };
        const groups = Map.groupBy(applicable, t => t.category);
        checklist.replaceChildren(
            el('div', {class: 'checklist-actions'},
                el('button', {type: 'button', class: 'link', onclick: setAll(t => t.defaultOn)}, 'defaults'),
                el('button', {type: 'button', class: 'link', onclick: setAll(() => true)}, 'all'),
                el('button', {type: 'button', class: 'link', onclick: setAll(() => false)}, 'none')),
            el('div', {class: 'checklist-groups'}, [...groups].map(([category, list]) =>
                el('fieldset', {}, el('legend', {}, category), list.map(t =>
                    el('label', {class: 'check' + (t.ready ? '' : ' disabled'), title: t.description},
                        el('input', {
                            type: 'checkbox', disabled: !t.ready, checked: !!checked.get(t.id),
                            onchange: e => (checked.set(t.id, e.target.checked), refresh())
                        }),
                        el('span', {}, t.name),
                        t.slow ? el('span', {class: 'tag'}, 'slow') : null,
                        t.ready ? null : el('span', {class: 'tag'}, 'soon'))))))
        );
    }

    const form = el('form', {
        class: 'target-form', onsubmit: async e => {
            e.preventDefault();
            if (running) {          // button shows "Stop" while a run is in progress
                running.abort();
                return;
            }
            const type = effectiveType();
            const selected = tools.filter(t => checked.get(t.id) && t.accepts.includes(type));
            if (!type || !selected.length) return;

            running = new AbortController();
            runBtn.textContent = 'Stop';
            try {
                await runInvestigation(input.value.trim(), type, selected, report, running.signal, file);
            } finally {
                running = null;
                runBtn.textContent = 'Run';
                refresh();
            }
        }
    }, input, typeSelect, fileBtn, fileInput, runBtn);

    input.addEventListener('input', () => {
        if (file) {         // typing replaces an uploaded file
            file = null;
            fileInput.value = '';
        }
        refresh();
    });
    typeSelect.addEventListener('change', refresh);

    main.replaceChildren(form, detected, igControl, checklist, report);
    refresh();
    input.focus();
}

async function runInvestigation(target, type, selected, report, signal, file) {
    if (!file) {            // an upload can't be replayed from history
        saveHistory({target, type, at: new Date().toISOString()});
        renderSidebar();
    }

    const results = {};
    const exportBtn = el('button', {type: 'button', disabled: true, onclick: () => downloadJson(target, type, results)}, 'Export JSON');
    const summary = el('span', {class: 'muted'});
    report.replaceChildren(
        el('div', {class: 'report-head'},
            el('div', {}, el('span', {class: 'report-target'}, target), el('span', {class: 'tag'}, type.toLowerCase())),
            el('div', {class: 'report-actions'}, summary, exportBtn)));

    if (file) report.querySelector('.report-head .tag').after(el('span', {class: 'tag'}, formatSize(file.size)));

    // A file is uploaded ONCE; every selected tool then analyses the same stored copy by id.
    let fileId = null;
    if (file) {
        if (lastUploadId) fetch('/api/files/' + encodeURIComponent(lastUploadId), {method: 'DELETE'}).catch(() => {});
        lastUploadId = null;
        try {
            summary.textContent = 'uploading...';
            const up = await uploadFile(file, signal,
                p => summary.textContent = `uploading ${Math.round(p * 100)}% of ${formatSize(file.size)}`);
            fileId = lastUploadId = up.id;
        } catch (err) {
            const stopped = err.name === 'AbortError';
            summary.textContent = stopped ? 'upload stopped' : 'upload failed';
            report.append(el('div', {class: 'error'}, stopped ? 'Upload stopped by user.' : err.message));
            return;
        }
    }

    let done = 0;
    const updateSummary = () => summary.textContent = `${done}/${selected.length} done`;
    updateSummary();

    await Promise.all(selected.map(async tool => {
        const status = el('span', {class: 'section-status'}, 'running...');
        const body = el('div', {class: 'section-body'});
        const refreshBtn = el('button', {type: 'button', class: 'link', hidden: true}, 'refresh');
        const parts = {status, body, refreshBtn};
        refreshBtn.addEventListener('click', e => {
            e.preventDefault(); // don't toggle the <details>
            runSection(tool, target, parts, results, null, true, fileId);
        });
        report.append(el('details', {class: 'section', open: true},
            el('summary', {}, el('span', {class: 'section-name'}, tool.name), refreshBtn, status), body));

        await runSection(tool, target, parts, results, signal, false, fileId);
        done++;
        updateSummary();
    }));
    exportBtn.disabled = false;
}

/**
 * Uploads a file once (multipart, streamed by the browser) and resolves to {id, name, size}.
 * XMLHttpRequest instead of fetch because only it reports upload progress.
 */
function uploadFile(file, signal, onProgress) {
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/files');
        xhr.upload.onprogress = e => e.lengthComputable && onProgress(e.loaded / e.total);
        xhr.onload = () => {
            let data = {};
            try { data = JSON.parse(xhr.responseText); } catch { /* not JSON */ }
            if (xhr.status >= 200 && xhr.status < 300 && data.id) resolve(data);
            else reject(new Error(data.error || `Upload failed (HTTP ${xhr.status})`));
        };
        xhr.onerror = () => reject(new Error('Upload failed - connection lost (file too large?)'));
        xhr.onabort = () => reject(new DOMException('Upload stopped', 'AbortError'));
        signal?.addEventListener('abort', () => xhr.abort());
        const form = new FormData();
        form.append('file', file);
        xhr.send(form);
    });
}

/**
 * Runs one tool and fills its report section. {@code fresh} bypasses the server cache;
 * with a {@code fileId} the tool analyses that stored upload instead of the target text.
 */
async function runSection(tool, target, {status, body, refreshBtn}, results, signal, fresh, fileId) {
    status.textContent = 'running...';
    status.classList.remove('bad');
    refreshBtn.hidden = true;
    body.replaceChildren();

    const started = performance.now();
    const ticker = setInterval(() =>
        status.textContent = `running... ${Math.round((performance.now() - started) / 1000)} s`, 1000);
    try {
        let res;
        if (fileId) {
            const params = new URLSearchParams({id: fileId});
            res = await fetch('/api/file/' + encodeURIComponent(tool.id), {method: 'POST', body: params, signal});
        } else {
            const params = new URLSearchParams({target});
            if (fresh) params.set('fresh', 'true');
            res = await fetch('/api/run/' + encodeURIComponent(tool.id), {method: 'POST', body: params, signal});
        }
        const data = await res.json().catch(() => ({error: `HTTP ${res.status}`}));
        if (!res.ok || data.error) throw new Error(data.error || `HTTP ${res.status}`);
        results[tool.id] = data;
        const cachedAt = res.headers.get('X-Cached-At');
        const ms = performance.now() - started;
        status.textContent = cachedAt
            ? 'cached ' + timeAgo(new Date(cachedAt))
            : ms < 10000 ? `${Math.round(ms)} ms` : `${Math.round(ms / 1000)} s`;
        body.append(renderValue(data));
    } catch (err) {
        const stopped = err.name === 'AbortError';
        results[tool.id] = {error: stopped ? 'stopped' : err.message};
        status.textContent = stopped ? 'stopped' : 'error';
        status.classList.add('bad');
        body.append(el('div', {class: 'error'}, stopped ? 'Stopped by user.' : err.message));
    } finally {
        clearInterval(ticker);
        refreshBtn.hidden = false;
    }
}

function timeAgo(date) {
    const min = Math.floor((Date.now() - date.getTime()) / 60000);
    if (min < 1) return 'just now';
    if (min < 60) return `${min} min ago`;
    return `${Math.floor(min / 60)} h ${min % 60} min ago`;
}

function downloadJson(target, type, results) {
    const blob = new Blob([JSON.stringify({target, type, generatedAt: new Date().toISOString(), results}, null, 2)],
        {type: 'application/json'});
    const a = el('a', {href: URL.createObjectURL(blob), download: `osint-${target.replace(/[^\w.-]+/g, '_')}.json`});
    a.click();
    URL.revokeObjectURL(a.href);
}

// ---------- catalog view ----------
function renderCatalog() {
    renderSidebar();
    const ready = tools.filter(t => t.ready).length;
    document.getElementById('main').replaceChildren(
        el('h1', {}, 'Tool catalog'),
        el('p', {class: 'muted'}, `${ready} of ${tools.length} tools ready.`),
        el('table', {class: 'catalog'},
            el('tr', {}, ['Tool', 'Category', 'Works on', 'Description', 'Status'].map(h => el('th', {}, h))),
            tools.map(t => el('tr', {class: t.ready ? '' : 'planned'},
                el('td', {}, t.name),
                el('td', {}, t.category),
                el('td', {}, t.accepts.map(a => a.toLowerCase()).join(', ')),
                el('td', {}, t.description),
                el('td', {}, t.ready ? 'ready' : 'planned')))))
}

// ---------- generic JSON -> HTML ----------
const isObj = v => v !== null && typeof v === 'object' && !Array.isArray(v);

function renderValue(v) {
    if (v === null || v === undefined || v === '') return el('span', {class: 'muted'}, '-');
    if (Array.isArray(v)) return renderArray(v);
    if (isObj(v)) return renderObject(v);
    const s = String(v);
    if (/^https?:\/\//i.test(s)) return el('a', {href: s, target: '_blank', rel: 'noopener noreferrer'}, s);
    return document.createTextNode(s);
}

// Secondary/bulky fields start collapsed so the findings stay readable
const COLLAPSED_KEYS = new Set(['raw', 'allMetadata', 'headers', 'urls', 'timeline', 'inconclusive', 'couldNotCheck']);
const COLLAPSE_OVER = 25;

function renderObject(obj) {
    const keys = Object.keys(obj);
    if (!keys.length) return el('span', {class: 'muted'}, '(empty)');
    return el('table', {}, keys.map(k => el('tr', {}, el('th', {}, k), el('td', {}, renderField(k, obj[k])))));
}

function renderField(key, value) {
    const size = Array.isArray(value) ? value.length : isObj(value) ? Object.keys(value).length : 0;
    const bulky = COLLAPSED_KEYS.has(key) || (Array.isArray(value) && size > COLLAPSE_OVER);
    if (!bulky || size === 0 && typeof value !== 'string') return renderValue(value);
    const label = typeof value === 'string' ? 'show' : `show ${size} ${Array.isArray(value) ? 'items' : 'fields'}`;
    const details = el('details', {class: 'field'}, el('summary', {}, label));
    // render lazily - some of these are large
    details.addEventListener('toggle', () => {
        if (details.open && details.children.length === 1) {
            details.append(typeof value === 'string' ? el('pre', {}, value) : renderValue(value));
        }
    });
    return details;
}

function renderArray(arr) {
    if (!arr.length) return el('span', {class: 'muted'}, '(empty)');
    if (arr.every(isObj)) {
        const isEmpty = v => v === null || v === undefined || v === '';
        const cols = [...new Set(arr.flatMap(Object.keys))].filter(c => arr.some(row => !isEmpty(row[c])));
        return el('table', {},
            el('tr', {}, cols.map(c => el('th', {}, c))),
            arr.map(row => el('tr', {}, cols.map(c => el('td', {}, renderValue(row[c]))))));
    }
    return el('ul', {class: 'plain'}, arr.map(v => el('li', {}, renderValue(v))));
}

// ---------- routing ----------
function route() {
    location.hash.startsWith('#catalog') ? renderCatalog() : renderInvestigate();
}

(async function init() {
    try {
        tools = await (await fetch('/api/tools')).json();
    } catch (e) {
        document.getElementById('main').append(el('div', {class: 'error'}, 'Could not load tool list: ' + e));
        return;
    }
    window.addEventListener('hashchange', route);
    route();
})();
