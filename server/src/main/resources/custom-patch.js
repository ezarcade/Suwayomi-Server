/**
 * custom-patch.js — Suwayomi Scanlator Filter + Local Download Scanner
 *
 * Injected into the WebUI index.html at serve-time by WebInterfaceManager.
 * Provides:
 *   1. Per-manga scanlator filter (persisted via MangaMetaTable under key "filteredScanlators")
 *   2. Local folder scanner that detects downloaded chapters not marked as such in the DB
 *
 * All communication uses the existing GraphQL endpoint at /api/graphql.
 */
(function () {
    'use strict';

    /* ─────────────────────────────── helpers ─────────────────────────────── */

    const GQL_ENDPOINT = '/api/graphql';

    async function gql(query, variables = {}) {
        const res = await fetch(GQL_ENDPOINT, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query, variables }),
        });
        const json = await res.json();
        if (json.errors) throw new Error(json.errors.map(e => e.message).join('\n'));
        return json.data;
    }

    function getMangaIdFromUrl() {
        const match = location.pathname.match(/\/manga\/(\d+)/);
        return match ? parseInt(match[1], 10) : null;
    }

    /* ─────────────────────────────── styles ──────────────────────────────── */

    const STYLE = `
    #scp-fab {
        position: fixed;
        bottom: 88px;
        right: 24px;
        z-index: 9999;
        width: 56px;
        height: 56px;
        border-radius: 50%;
        background: linear-gradient(135deg, #7c3aed, #4f46e5);
        color: #fff;
        border: none;
        cursor: pointer;
        box-shadow: 0 4px 20px rgba(79,70,229,.55);
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 22px;
        transition: transform .2s, box-shadow .2s;
    }
    #scp-fab:hover { transform: scale(1.1); box-shadow: 0 6px 28px rgba(79,70,229,.75); }

    #scp-overlay {
        position: fixed;
        inset: 0;
        background: rgba(0,0,0,.55);
        z-index: 10000;
        display: flex;
        align-items: center;
        justify-content: center;
        backdrop-filter: blur(3px);
    }
    #scp-modal {
        background: #1e1e2e;
        color: #cdd6f4;
        border-radius: 16px;
        padding: 28px 32px;
        width: min(520px, 92vw);
        max-height: 80vh;
        overflow-y: auto;
        box-shadow: 0 24px 64px rgba(0,0,0,.6);
        font-family: 'Inter', 'Segoe UI', sans-serif;
    }
    #scp-modal h2 {
        margin: 0 0 6px;
        font-size: 1.25rem;
        font-weight: 700;
        color: #cba6f7;
        display: flex;
        align-items: center;
        gap: 8px;
    }
    #scp-modal .scp-sub {
        font-size: .82rem;
        color: #6c7086;
        margin-bottom: 20px;
    }
    .scp-section-title {
        font-size: .75rem;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: .08em;
        color: #6c7086;
        margin: 22px 0 10px;
    }
    .scp-scanlator-list { display: flex; flex-direction: column; gap: 8px; }
    .scp-scanlator-item {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 10px 14px;
        background: #2a2a3f;
        border-radius: 10px;
        transition: background .15s;
    }
    .scp-scanlator-item:hover { background: #313148; }
    .scp-scanlator-item input[type=checkbox] {
        accent-color: #cba6f7;
        width: 16px;
        height: 16px;
        cursor: pointer;
    }
    .scp-scanlator-item label {
        flex: 1;
        cursor: pointer;
        font-size: .93rem;
        color: #cdd6f4;
    }
    .scp-empty { color: #585b70; font-size: .9rem; }

    .scp-actions { display: flex; gap: 10px; margin-top: 24px; flex-wrap: wrap; }
    .scp-btn {
        flex: 1;
        padding: 10px 18px;
        border-radius: 10px;
        border: none;
        cursor: pointer;
        font-size: .92rem;
        font-weight: 600;
        transition: filter .15s, transform .1s;
    }
    .scp-btn:hover { filter: brightness(1.12); transform: translateY(-1px); }
    .scp-btn-primary   { background: linear-gradient(135deg, #7c3aed, #4f46e5); color: #fff; }
    .scp-btn-secondary { background: #313148; color: #cdd6f4; }
    .scp-btn-warning   { background: linear-gradient(135deg, #f38ba8, #e06c75); color: #fff; }
    .scp-btn-success   { background: linear-gradient(135deg, #a6e3a1, #40b36c); color: #1e1e2e; }

    .scp-confirm-list { display: flex; flex-direction: column; gap: 6px; max-height: 260px; overflow-y: auto; }
    .scp-confirm-item {
        padding: 8px 12px;
        background: #2a2a3f;
        border-radius: 8px;
        font-size: .87rem;
        display: flex;
        align-items: center;
        gap: 8px;
    }
    .scp-confirm-item::before { content: '📥'; font-size: .8rem; }
    .scp-confirm-item-hidden {
        padding: 8px 12px;
        background: #2a2a3f;
        border-radius: 8px;
        font-size: .87rem;
        display: flex;
        align-items: center;
        gap: 8px;
    }
    .scp-confirm-item-hidden::before { content: '🗑️'; font-size: .8rem; }

    .scp-spinner {
        display: inline-block;
        width: 18px; height: 18px;
        border: 3px solid #44475a;
        border-top-color: #cba6f7;
        border-radius: 50%;
        animation: scp-spin .7s linear infinite;
        vertical-align: middle;
        margin-right: 6px;
    }
    @keyframes scp-spin { to { transform: rotate(360deg); } }

    .scp-toast {
        position: fixed;
        bottom: 160px;
        right: 24px;
        z-index: 11000;
        background: #313148;
        color: #cdd6f4;
        padding: 12px 20px;
        border-radius: 10px;
        font-size: .88rem;
        box-shadow: 0 4px 20px rgba(0,0,0,.5);
        animation: scp-fadein .25s ease;
    }
    @keyframes scp-fadein { from { opacity: 0; transform: translateY(6px); } }
    `;

    /* ─────────────────────────────── toast ───────────────────────────────── */

    function showToast(msg, duration = 3000) {
        const t = document.createElement('div');
        t.className = 'scp-toast';
        t.textContent = msg;
        document.body.appendChild(t);
        setTimeout(() => t.remove(), duration);
    }

    /* ──────────────────────────── main modal ─────────────────────────────── */

    async function openModal(mangaId) {
        removeModal();

        const overlay = document.createElement('div');
        overlay.id = 'scp-overlay';
        overlay.innerHTML = `<div id="scp-modal"><h2>🎛️ Manga Tools</h2><p class="scp-sub">Manage scanlator filters and local downloads for this manga.</p><div id="scp-content"><span class="scp-spinner"></span> Loading…</div></div>`;
        document.body.appendChild(overlay);

        overlay.addEventListener('click', e => { if (e.target === overlay) removeModal(); });

        // Load data concurrently
        try {
            const [scanlatorsData, metaRes] = await Promise.all([
                gql(`query($id:Int!){allScanlators(mangaId:$id)}`, { id: mangaId }),
                gql(`query($id:Int!){manga(id:$id){meta{key value}}}`, { id: mangaId }),
            ]);

            const allScanlators = scanlatorsData.allScanlators || [];
            const metaList = metaRes?.manga?.meta || [];
            const metaEntry = metaList.find(m => m.key === 'filteredScanlators');
            let filteredScanlators = [];
            if (metaEntry) {
                try { filteredScanlators = JSON.parse(metaEntry.value); } catch (_) {}
            }

            const priorityEntry = metaList.find(m => m.key === 'scanlatorPriority');
            const scanlatorPriority = priorityEntry ? priorityEntry.value : '';

            const overrideEntry = metaList.find(m => m.key === 'scanlatorOverride');
            const scanlatorOverride = overrideEntry ? overrideEntry.value : '';

            const hideBelowOneEntry = metaList.find(m => m.key === 'hideChaptersBelowOne');
            const hideBelowOne = hideBelowOneEntry ? hideBelowOneEntry.value === 'true' : false;

            const hideFractionalEntry = metaList.find(m => m.key === 'hideFractionalChapters');
            const hideFractional = hideFractionalEntry ? hideFractionalEntry.value === 'true' : false;

            const hideHalfEntry = metaList.find(m => m.key === 'hideHalfChapters');
            const hideHalf = hideHalfEntry ? hideHalfEntry.value === 'true' : false;

            const hiddenNumbersEntry = metaList.find(m => m.key === 'hiddenChapterNumbers');
            const hiddenNumbers = hiddenNumbersEntry ? hiddenNumbersEntry.value : '';

            const hiddenScanlatorExceptionEntry = metaList.find(m => m.key === 'hiddenChapterScanlatorException');
            const hiddenScanlatorException = hiddenScanlatorExceptionEntry ? hiddenScanlatorExceptionEntry.value : '';

            const showOrphanFractionalEntry = metaList.find(m => m.key === 'showOrphanFractional');
            const showOrphanFractional = showOrphanFractionalEntry ? showOrphanFractionalEntry.value === 'true' : false;

            const autoDeleteOrphanFractionalEntry = metaList.find(m => m.key === 'autoDeleteOrphanFractional');
            const autoDeleteOrphanFractional = autoDeleteOrphanFractionalEntry ? autoDeleteOrphanFractionalEntry.value === 'true' : false;

            const hiddenPairsEntry = metaList.find(m => m.key === 'hiddenChapterScanlatorPairs');
            const hiddenPairs = hiddenPairsEntry ? hiddenPairsEntry.value : '';

            renderMainView(mangaId, allScanlators, filteredScanlators, scanlatorPriority, scanlatorOverride, { hideBelowOne, hideFractional, hideHalf, showOrphanFractional, autoDeleteOrphanFractional, hiddenNumbers, hiddenScanlatorException, hiddenPairs });
        } catch (err) {
            document.getElementById('scp-content').innerHTML =
                `<span style="color:#f38ba8">Error loading data: ${err.message}</span>`;
        }
    }

    function renderMainView(mangaId, allScanlators, filteredScanlators, scanlatorPriority, scanlatorOverride, hiding) {
        hiding = hiding || {};
        const content = document.getElementById('scp-content');
        if (!content) return;

        content.innerHTML = `
            <div class="scp-section-title">🚫 Filter Scanlators</div>
            <p style="font-size:.82rem;color:#6c7086;margin-bottom:12px;">Checked scanlators will be hidden from chapter lists and excluded from auto-downloads and unread counts.</p>
            <div class="scp-scanlator-list" id="scp-scanlator-list">
                ${allScanlators.length === 0
                    ? '<span class="scp-empty">No scanlator info found for this manga.</span>'
                    : allScanlators.map(s => `
                        <div class="scp-scanlator-item">
                            <input type="checkbox" id="scp-s-${encodeURIComponent(s)}" value="${s}"
                                ${filteredScanlators.includes(s) ? 'checked' : ''}>
                            <label for="scp-s-${encodeURIComponent(s)}">${s}</label>
                        </div>`).join('')}
            </div>

            <div class="scp-section-title" style="margin-top:24px;">🎯 Scanlator Priority Override</div>
            <p style="font-size:.82rem;color:#6c7086;margin-bottom:8px;">Comma-separated list of scanlator group names in order of preference. Overrides the default extension settings.</p>
            <input type="text" id="scp-scanlator-priority" class="scp-input" style="width: 100%; box-sizing: border-box; background: #2a2a3f; color: #cdd6f4; border: 1px solid #45475a; border-radius: 8px; padding: 10px 14px; font-family: inherit; font-size: .93rem; margin-bottom: 12px;" placeholder="e.g. Violet Scans, Comix" value="${scanlatorPriority || ''}">

            <div class="scp-section-title" style="margin-top:24px;">🎯 Per-Chapter Scanlator Override</div>
            <p style="font-size:.82rem;color:#6c7086;margin-bottom:8px;">Force a specific scanlator for a chapter when dedup is on. Format: chapter:scanlator, comma-separated.</p>
            <input type="text" id="scp-scanlator-override" class="scp-input" style="width: 100%; box-sizing: border-box; background: #2a2a3f; color: #cdd6f4; border: 1px solid #45475a; border-radius: 8px; padding: 10px 14px; font-family: inherit; font-size: .93rem; margin-bottom: 12px;" placeholder="e.g. 47:SomeGroup, 48:AnotherGroup" value="${scanlatorOverride || ''}">

            <div class="scp-section-title" style="margin-top:28px;">🙈 Chapter Hiding</div>
            <p style="font-size:.82rem;color:#6c7086;margin-bottom:12px;">Auto-hide chapters based on chapter number rules. Hidden chapters are excluded from lists and unread counts.</p>

            <label style="display:flex;align-items:center;gap:10px;padding:6px 0;cursor:pointer;font-size:.9rem;">
                <input type="checkbox" id="scp-hide-below-one" ${hiding.hideBelowOne ? 'checked' : ''}>
                Hide chapters below 1 (e.g. Chapter 0, 0.5)
            </label>

            <label style="display:flex;align-items:center;gap:10px;padding:6px 0;cursor:pointer;font-size:.9rem;">
                <input type="checkbox" id="scp-hide-fractional" ${hiding.hideFractional ? 'checked' : ''}>
                Hide in-between chapters (e.g. 23.1, 23.2, 45.4)
            </label>

            <label style="display:flex;align-items:center;gap:10px;padding:6px 0;cursor:pointer;font-size:.9rem;">
                <input type="checkbox" id="scp-hide-half" ${hiding.hideHalf ? 'checked' : ''}>
                Hide half chapters (e.g. 23.5, 33.5)
            </label>

            <label style="display:flex;align-items:center;gap:10px;padding:6px 0;cursor:pointer;font-size:.9rem;">
                <input type="checkbox" id="scp-show-orphan-fractional" ${hiding.showOrphanFractional ? 'checked' : ''}>
                Show orphan fractional chapters when parent is missing
            </label>

            <label style="display:flex;align-items:center;gap:10px;padding:6px 0;cursor:pointer;font-size:.9rem;">
                <input type="checkbox" id="scp-auto-delete-orphan-fractional" ${hiding.autoDeleteOrphanFractional ? 'checked' : ''}>
                Auto-delete orphan fractional downloads when parent arrives
            </label>

            <div style="margin-top:10px;">
                <label style="font-size:.82rem;color:#6c7086;display:block;margin-bottom:6px;">Custom chapter numbers to hide (comma-separated):</label>
                <input type="text" id="scp-hidden-numbers" class="scp-input" style="width:100%;box-sizing:border-box;background:#2a2a3f;color:#cdd6f4;border:1px solid #45475a;border-radius:8px;padding:10px 14px;font-family:inherit;font-size:.93rem;" placeholder="e.g. 0, 0.5, 23.1, 23.2" value="${hiding.hiddenNumbers || ''}">
            </div>

            <div style="margin-top:12px;">
                <label style="font-size:.82rem;color:#6c7086;display:block;margin-bottom:6px;">Skip hiding for these scanlators (comma-separated):</label>
                <input type="text" id="scp-hidden-scanlator-exception" class="scp-input" style="width:100%;box-sizing:border-box;background:#2a2a3f;color:#cdd6f4;border:1px solid #45475a;border-radius:8px;padding:10px 14px;font-family:inherit;font-size:.93rem;" placeholder="e.g. Violet Scans, Comix" value="${hiding.hiddenScanlatorException || ''}">
            </div>

            <div style="margin-top:12px;">
                <label style="font-size:.82rem;color:#6c7086;display:block;margin-bottom:6px;">Hide specific chapter:scanlator pairs (comma-separated, e.g. 6.1:BadScans, 23.5:SomeGroup):</label>
                <input type="text" id="scp-hidden-pairs" class="scp-input" style="width:100%;box-sizing:border-box;background:#2a2a3f;color:#cdd6f4;border:1px solid #45475a;border-radius:8px;padding:10px 14px;font-family:inherit;font-size:.93rem;" placeholder="e.g. 6.1:BadScans, 23.5:SomeGroup" value="${hiding.hiddenPairs || ''}">
            </div>

            <div class="scp-section-title" style="margin-top:28px;">📂 Local Download Scanner</div>
            <p style="font-size:.82rem;color:#6c7086;margin-bottom:12px;">Scans the manga's download folder for chapters that exist on disk but aren't marked as downloaded in the database.</p>

            <div class="scp-actions">
                <button class="scp-btn scp-btn-primary" id="scp-save-filters">💾 Save Settings</button>
                <button class="scp-btn scp-btn-warning" id="scp-scan-local">🔍 Scan Local Folder</button>
                <button class="scp-btn scp-btn-secondary" id="scp-close">✕ Close</button>
            </div>
        `;

        document.getElementById('scp-close')?.addEventListener('click', removeModal);

        document.getElementById('scp-save-filters')?.addEventListener('click', async () => {
            await saveFilters(mangaId);
        });

        document.getElementById('scp-scan-local')?.addEventListener('click', async () => {
            await runLocalScan(mangaId);
        });
    }

    /* ──────────────────────────── save filters ───────────────────────────── */

    function saveMeta(mangaId, key, value) {
        return gql(
            `mutation($input:SetMangaMetaInput!){setMangaMeta(input:$input){meta{key value}}}`,
            { input: { meta: { mangaId, key, value } } }
        );
    }

    async function saveFilters(mangaId) {
        const checkboxes = document.querySelectorAll('#scp-scanlator-list input[type=checkbox]:checked');
        const selected = Array.from(checkboxes).map(cb => cb.value);
        const valueJson = JSON.stringify(selected);

        const priorityVal = document.getElementById('scp-scanlator-priority')?.value || '';
        const overrideVal = document.getElementById('scp-scanlator-override')?.value || '';

        const hideBelowOne = document.getElementById('scp-hide-below-one')?.checked ? 'true' : 'false';
        const hideFractional = document.getElementById('scp-hide-fractional')?.checked ? 'true' : 'false';
        const hideHalf = document.getElementById('scp-hide-half')?.checked ? 'true' : 'false';
        const showOrphanFractional = document.getElementById('scp-show-orphan-fractional')?.checked ? 'true' : 'false';
        const autoDeleteOrphanFractional = document.getElementById('scp-auto-delete-orphan-fractional')?.checked ? 'true' : 'false';
        const hiddenNumbers = document.getElementById('scp-hidden-numbers')?.value || '';
        const hiddenScanlatorException = document.getElementById('scp-hidden-scanlator-exception')?.value || '';
        const hiddenPairs = document.getElementById('scp-hidden-pairs')?.value || '';

        const btn = document.getElementById('scp-save-filters');
        if (btn) { btn.disabled = true; btn.textContent = '⏳ Saving…'; }

        try {
            await Promise.all([
                saveMeta(mangaId, 'filteredScanlators', valueJson),
                saveMeta(mangaId, 'scanlatorPriority', priorityVal),
                saveMeta(mangaId, 'scanlatorOverride', overrideVal),
                saveMeta(mangaId, 'hideChaptersBelowOne', hideBelowOne),
                saveMeta(mangaId, 'hideFractionalChapters', hideFractional),
                saveMeta(mangaId, 'hideHalfChapters', hideHalf),
                saveMeta(mangaId, 'showOrphanFractional', showOrphanFractional),
                saveMeta(mangaId, 'autoDeleteOrphanFractional', autoDeleteOrphanFractional),
                saveMeta(mangaId, 'hiddenChapterNumbers', hiddenNumbers),
                saveMeta(mangaId, 'hiddenChapterScanlatorException', hiddenScanlatorException),
                saveMeta(mangaId, 'hiddenChapterScanlatorPairs', hiddenPairs),
            ]);
            showToast('✅ Saved settings! Refresh chapters to see changes.');
            removeModal();
        } catch (err) {
            showToast(`❌ Failed to save: ${err.message}`, 5000);
            if (btn) { btn.disabled = false; btn.textContent = '💾 Save Settings'; }
        }
    }


    /* ──────────────────────────── local scan ─────────────────────────────── */

    async function runLocalScan(mangaId) {
        const scanBtn = document.getElementById('scp-scan-local');
        if (scanBtn) { scanBtn.disabled = true; scanBtn.innerHTML = '<span class="scp-spinner"></span>Scanning…'; }

        try {
            const data = await gql(
                `query($id:Int!){
                    checkLocalDownloads(mangaId:$id){id name scanlator chapterNumber}
                    hiddenDownloadedChapters(mangaId:$id){id name scanlator chapterNumber}
                }`,
                { id: mangaId }
            );
            const untracked = data.checkLocalDownloads || [];
            const hidden = data.hiddenDownloadedChapters || [];
            renderConfirmDialog(mangaId, untracked, hidden);
        } catch (err) {
            showToast(`❌ Scan failed: ${err.message}`, 5000);
            if (scanBtn) { scanBtn.disabled = false; scanBtn.innerHTML = '🔍 Scan Local Folder'; }
        }
    }

    function renderConfirmDialog(mangaId, untracked, hidden) {
        const content = document.getElementById('scp-content');
        if (!content) return;

        const hasUntracked = untracked.length > 0;
        const hasHidden = hidden.length > 0;

        if (!hasUntracked && !hasHidden) {
            content.innerHTML = `
                <h2>✅ All Good!</h2>
                <p style="color:#a6e3a1;margin-bottom:20px;">No untracked local chapters found. Every downloaded chapter is already marked correctly in the database.</p>
                <div class="scp-actions">
                    <button class="scp-btn scp-btn-secondary" id="scp-back">← Back</button>
                </div>`;
            document.getElementById('scp-back')?.addEventListener('click', () =>
                openModal(mangaId));
            return;
        }

        let html = `<h2>📋 Scan Results</h2>`;

        if (hasUntracked) {
            html += `
                <div class="scp-section-title">📥 Found on Disk (${untracked.length})</div>
                <p style="font-size:.82rem;color:#6c7086;margin-bottom:10px;">
                    Chapters on disk not marked as downloaded in DB.
                </p>
                <div class="scp-confirm-list" style="margin-bottom:18px;">
                    ${untracked.map(c => `<div class="scp-confirm-item">${c.name}${c.scanlator ? ` <span style="color:#6c7086;font-size:.8rem;">[${c.scanlator}]</span>` : ''}</div>`).join('')}
                </div>`;
        }

        if (hasHidden) {
            html += `
                <div class="scp-section-title" style="margin-top:16px;">🗑️ Hidden Downloads (${hidden.length})</div>
                <p style="font-size:.82rem;color:#6c7086;margin-bottom:10px;">
                    Downloaded chapters hidden by current hiding rules. Prune to free disk space.
                </p>
                <div class="scp-confirm-list" style="margin-bottom:18px;">
                    ${hidden.map(c => `<div class="scp-confirm-item-hidden">${c.name}${c.scanlator ? ` <span style="color:#6c7086;font-size:.8rem;">[${c.scanlator}]</span>` : ''}</div>`).join('')}
                </div>`;
        }

        html += `<div class="scp-actions" style="margin-top:16px;">`;

        if (hasUntracked) {
            html += `<button class="scp-btn scp-btn-success" id="scp-confirm-mark">✅ Mark Downloaded (${untracked.length})</button>`;
        }
        if (hasHidden) {
            html += `<button class="scp-btn scp-btn-warning" id="scp-confirm-prune">🗑️ Prune Hidden (${hidden.length})</button>`;
        }
        html += `<button class="scp-btn scp-btn-secondary" id="scp-back-confirm">← Back</button></div>`;

        content.innerHTML = html;

        document.getElementById('scp-back-confirm')?.addEventListener('click', () =>
            openModal(mangaId));

        document.getElementById('scp-confirm-mark')?.addEventListener('click', async () => {
            const btn = document.getElementById('scp-confirm-mark');
            if (btn) { btn.disabled = true; btn.innerHTML = '<span class="scp-spinner"></span>Marking…'; }
            try {
                await gql(
                    `mutation($input:MarkLocalDownloadsInput!){markLocalDownloads(input:$input){chapters{id name isDownloaded}}}`,
                    { input: { mangaId, chapterIds: untracked.map(c => c.id) } }
                );
                showToast(`✅ Marked ${untracked.length} chapter(s) as downloaded!`);
                removeModal();
            } catch (err) {
                showToast(`❌ Failed to mark: ${err.message}`, 5000);
                if (btn) { btn.disabled = false; btn.innerHTML = `✅ Mark Downloaded (${untracked.length})`; }
            }
        });

        document.getElementById('scp-confirm-prune')?.addEventListener('click', async () => {
            const btn = document.getElementById('scp-confirm-prune');
            if (btn) { btn.disabled = true; btn.innerHTML = '<span class="scp-spinner"></span>Pruning…'; }
            try {
                const data = await gql(
                    `mutation($input:PruneHiddenDownloadsInput!){pruneHiddenDownloads(input:$input){count}}`,
                    { input: { mangaId } }
                );
                showToast(`🗑️ Deleted ${data.pruneHiddenDownloads.count} hidden download(s)!`);
                removeModal();
            } catch (err) {
                showToast(`❌ Failed to prune: ${err.message}`, 5000);
                if (btn) { btn.disabled = false; btn.innerHTML = `🗑️ Prune Hidden (${hidden.length})`; }
            }
        });
    }

    /* ─────────────────────────── FAB & lifecycle ─────────────────────────── */

    function removeModal() {
        document.getElementById('scp-overlay')?.remove();
    }

    function injectFab(mangaId) {
        document.getElementById('scp-fab')?.remove();

        const fab = document.createElement('button');
        fab.id = 'scp-fab';
        fab.title = 'Manga Tools (Scanlator Filter + Local Scan)';
        fab.textContent = '🎛️';
        fab.addEventListener('click', () => openModal(mangaId));
        document.body.appendChild(fab);
    }

    function removeFab() {
        document.getElementById('scp-fab')?.remove();
        removeModal();
    }

    /* ──────────────────────────── route watcher ──────────────────────────── */

    function onRouteChange() {
        const mangaId = getMangaIdFromUrl();
        if (mangaId) {
            injectFab(mangaId);
        } else {
            removeFab();
        }
    }

    /* ────────────────────────────── bootstrap ────────────────────────────── */

    // Inject styles
    const styleEl = document.createElement('style');
    styleEl.textContent = STYLE;
    document.head.appendChild(styleEl);

    // Watch for SPA route changes (history API + popstate + hashchange)
    const _pushState = history.pushState.bind(history);
    const _replaceState = history.replaceState.bind(history);

    history.pushState = function (...args) {
        _pushState(...args);
        setTimeout(onRouteChange, 150);
    };
    history.replaceState = function (...args) {
        _replaceState(...args);
        setTimeout(onRouteChange, 150);
    };
    window.addEventListener('popstate', () => setTimeout(onRouteChange, 150));
    window.addEventListener('hashchange', () => setTimeout(onRouteChange, 150));

    // Initial check
    onRouteChange();

})();
