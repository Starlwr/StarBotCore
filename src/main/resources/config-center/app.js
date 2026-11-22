'use strict';
(() => {
    const { own, clone, normalizeString, normalizeEmptyString, defaultValue, numberInputValue, selectActionConfiguration, customConfigPath, customConfigConflict, groupPath, fieldPath, pendingAt, pendingLocations, get, valueAt, equal, size, isSpringPlaceholder, dynamicOptionValues, duration, durationParts, durationInputUnits, durationInputValue, condition, formatCheck, checkRule, mergeIssues, isDuplicateSpringIssue, currentServerIssues, groupSuggestions, groupConflicts, conflictSelection, sharedIssues, withDefaults } = window.ConfigEngine;
    let accessToken = '';
    async function request(path, body) {
        const response = await fetch(new URL(path.replace(/^\/+/, ''), document.baseURI), { method: body ? 'POST' : 'GET', headers: { Authorization: 'Bearer ' + accessToken, ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined, cache: 'no-store' });
        if (!response.ok) {
            const result = await response.json().catch(() => null);
            const error = new Error(result?.message || '请求失败');
            error.status = response.status;
            error.details = result?.details;
            throw error;
        }
        return response.json();
    }
    document.getElementById('starbot-login-form').addEventListener('submit', async (event) => {
        event.preventDefault();
        const token = document.getElementById('starbot-token');
        const feedback = document.getElementById('starbot-login-error');
        const password = token.value;
        let message = '';
        if (!formatCheck(password, 'password')) {
            message = '访问密码只能使用英文字母、数字和半角符号';
        } else if (password.length < 8 || password.length > 32) {
            message = '访问密码长度应为 8～32 个字符';
        }
        feedback.textContent = message;
        if (message) {
            token.focus();
            return;
        }
        accessToken = password;
        const submit = event.currentTarget.querySelector('button');
        submit.disabled = true;
        try {
            const source = await request('api/bootstrap');
            mount(source);
            document.getElementById('starbot-config-center').hidden = false;
            const login = document.getElementById('starbot-login');
            login.inert = true;
            window.ConfigControls.leave(login, 'fade', () => {
                login.hidden = true;
                (document.querySelector('#starbot-tabs [aria-selected="true"]') || document.getElementById('starbot-raw'))?.focus();
            });
            token.value = '';
        }
        catch (error) {
            feedback.textContent = error.message;
        }
        finally {
            submit.disabled = false;
        }
    });
    function createToast(root) {
        const el = root.querySelector('#starbot-toast');
        let timer;
        return (text, kind = 'info') => {
            el.dataset.kind = kind;
            el.setAttribute('role', kind === 'error' ? 'alert' : 'status');
            el.textContent = text;
            el.hidden = false;
            window.ConfigControls.enter(el, 'toast');
            clearTimeout(timer);
            timer = setTimeout(() => window.ConfigControls.leave(el, 'toast', () => el.hidden = true), kind === 'error' ? 6500 : 4500);
        };
    }
    function parseRawDocument(raw) {
        let draft;
        try {
            draft = JSON.parse(raw.replace(/^\uFEFF/, ''));
        }
        catch {
            throw new Error('JSON 格式错误, 请检查引号, 逗号和括号');
        }
        if (!draft || typeof draft !== 'object' || Array.isArray(draft)) {
            throw new Error('配置文件根节点必须是 JSON 对象');
        }
        for (const area of ['starbot', 'advanced']) {
            if (own(draft, area) && (!draft[area] || typeof draft[area] !== 'object' || Array.isArray(draft[area]))) {
                throw new Error(area + ' 必须是对象');
            }
        }
        return draft;
    }
    function mountRawRecovery(root, snapshot) {
        const editor = root.querySelector('#starbot-editor');
        const save = root.querySelector('[data-action="save"]');
        const reload = root.querySelector('[data-action="reload"]');
        root.querySelector('[data-action="raw-mode"]').hidden = true;
        root.querySelector('.starbot-navigation').hidden = true;
        root.querySelector('#starbot-sidebar').hidden = true;
        root.querySelector('.starbot-layout').classList.add('starbot-recovery-layout');
        editor.removeAttribute('role');
        editor.innerHTML = '<textarea id="starbot-raw" class="starbot-textarea starbot-raw" aria-label="JSON 原文"></textarea>';

        const raw = editor.querySelector('#starbot-raw');
        const status = document.createElement('div');
        status.className = 'starbot-diagnostic starbot-recovery-status';
        root.querySelector('#starbot-diagnostics').appendChild(status);
        const toast = createToast(root);
        const saveReminder = 'JSON 格式已修复，请保存配置并重启 StarBot';
        const restartReminder = '配置已保存，请重启 StarBot';
        let savedRaw, formatIssue;
        const dirty = () => raw.value !== savedRaw;
        const updateSave = () => save.disabled = raw.readOnly || !!formatIssue || !dirty();
        const issueText = errors => (errors || []).map(error => error.path.join('.') + '：' + error.message).join('\n');
        function showStatus(message, invalid = false) {
            status.textContent = message;
            status.classList.toggle('starbot-blocking', invalid);
            status.setAttribute('role', invalid ? 'alert' : 'status');
        }
        function load(doc) {
            raw.value = doc.raw;
            savedRaw = raw.value;
            formatIssue = doc.error || '';
            const issue = formatIssue || issueText(doc.errors);
            showStatus(issue || restartReminder, !!issue);
            updateSave();
        }
        async function run(operation) {
            raw.readOnly = true;
            reload.disabled = true;
            updateSave();
            try {
                await operation();
            }
            catch (error) {
                showStatus(issueText(error.details?.errors) || error.message, true);
                toast(error.message, 'error');
            }
            finally {
                raw.readOnly = false;
                reload.disabled = false;
                updateSave();
            }
        }
        raw.addEventListener('input', () => {
            const wasInvalid = !!formatIssue;
            try {
                parseRawDocument(raw.value);
                formatIssue = '';
            }
            catch (error) {
                formatIssue = error.message;
            }
            showStatus(formatIssue || (dirty() ? saveReminder : restartReminder), !!formatIssue);
            updateSave();
            if (wasInvalid && !formatIssue && dirty()) {
                toast(saveReminder, 'success');
            }
        });
        save.addEventListener('click', () => run(async () => {
            const result = await request('api/save', {raw: raw.value});
            load(result.document);
            toast(restartReminder, 'success');
        }));
        reload.addEventListener('click', () => {
            if (!dirty() || window.confirm('重新读取会放弃当前未保存的修改')) {
                run(async () => load(await request('api/document')));
            }
        });
        window.addEventListener('beforeunload', event => {
            if (dirty()) {
                event.preventDefault();
                event.returnValue = '';
            }
        });
        load(snapshot);
    }
    function mount(source) {
        const root = document.getElementById('starbot-config-center');
        if (!root) {
            return;
        }
        if (source.document.rawRecovery) {
            mountRawRecovery(root, source.document);
            return;
        }
        let snapshot = source.document, rawMode = !!snapshot.error || !!snapshot.readOnly || snapshot.count > 1, rawDraft = snapshot.raw, busy = false, serverErrors = snapshot.errors || [];
        source.data = snapshot.data;
        const springPaths = source.springPaths || [];
        const meta = source.meta.map(moduleMeta => ({...moduleMeta, groups:(moduleMeta.groups || []).filter(g => (g.fields || []).length || (g.actions || []).length)}))
            .filter(moduleMeta => moduleMeta.id === 'custom' || moduleMeta.groups.length)
            .sort((a, b) => {
                const order = { core: -1, custom: 1 };
                return (order[a.id] || 0) - (order[b.id] || 0) || a.title.localeCompare(b.title, 'en', { sensitivity: 'base', numeric: true });
            });
        const conflictDefinitions = snapshot.conflicts || [];
        let active = conflictDefinitions.some(conflict => !conflict.resolved) ? 'conflicts' : (meta[0]?.id || 'core');
        const conflictGroups = groupConflicts(conflictDefinitions);
        const metaIssues = (source.diagnostics || []).filter(item => item.blocking);
        if (metaIssues.length) {
            conflictGroups.push({id:'meta-errors', title:'配置声明问题', fields:[], conflicts:[], issues:metaIssues});
        }
        if (conflictGroups.length) {
            meta.unshift({id:'conflicts', title:'配置冲突', description:'插件对部分配置存在不同要求, 请选择适合你的设置', groups:conflictGroups});
        }
        let data = clone(source.data), baseline = clone(data), section = '', newKey = false;
        const isStoredAccessPassword = (path, value) => typeof value === 'string' && equal(path, source.accessPasswordPath)
            && (snapshot.accessPassword?.stored && value === get(baseline, path)
                || snapshot.accessPassword?.active && value === get(snapshot.activeData, path));
        let contextData = data, actionData = data;
        let formBaseRaw = null, rawIssue = snapshot.error || '';
        let pendingMoves = [];
        let customRenames = snapshot.customRenames || [];
        const numericErrors = new Map(), implicitObjects = new WeakSet();
        const actionStates = new Map();
        const customConflicts = new Set();
        const opened = new Set(), nodeTypes = new Map();
        const tabs = root.querySelector('#starbot-tabs'), side = root.querySelector('#starbot-sidebar'), editor = root.querySelector('#starbot-editor');
        let errors = [], displayErrors = [];
        const toast = createToast(root);
        const icons = { core: 'settings', custom: 'sliders', conflicts: 'alert' };
        const typeLabels = {string:'文本', integer:'整数', number:'数字', duration:'时长', boolean:'开关', object:'对象', map:'键值表', array:'列表', any:'任意值', null:'空值'};
        const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
        const iconPaths = {
            settings: '<path d="M9.67 4.14a2.34 2.34 0 0 1 4.66 0 2.34 2.34 0 0 0 3.32 1.92 2.34 2.34 0 0 1 2.33 4.04 2.34 2.34 0 0 0 0 3.8 2.34 2.34 0 0 1-2.33 4.04 2.34 2.34 0 0 0-3.32 1.92 2.34 2.34 0 0 1-4.66 0 2.34 2.34 0 0 0-3.32-1.92 2.34 2.34 0 0 1-2.33-4.04 2.34 2.34 0 0 0 0-3.8 2.34 2.34 0 0 1 2.33-4.04 2.34 2.34 0 0 0 3.32-1.92"/><circle cx="12" cy="12" r="3"/>',
            boxes: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
            sliders: '<path d="M4 7h4m4 0h8M4 17h10m4 0h2"/><circle cx="10" cy="7" r="2"/><circle cx="16" cy="17" r="2"/>',
            plugin: '<path d="M8 3v4m8-4v4M6 7h12v4a6 6 0 0 1-12 0V7Zm6 10v4"/>',
            restart: '<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6 7a7 7 0 0 1 12-1l2 2M4 16l2 2a7 7 0 0 0 12-1"/>',
            alert: '<path d="m12 3 10 18H2L12 3Z"/><path d="M12 9v4m0 4h.01"/>',
            eye: '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/><path class="starbot-eye-slash" d="m3 3 18 18"/>'
        };
        const pid = p => JSON.stringify(p), pa = p => esc(pid(p)), icon = n => '<svg class="starbot-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (iconPaths[n] || iconPaths.plugin) + '</svg>';
        const currentMeta = () => meta.find(moduleMeta => moduleMeta.id === active) || meta[0];
        const defaults = fields => Object.fromEntries((fields || []).filter(f => defaultValue(f) !== undefined).map(f => [f.key, normalizeString(defaultValue(f), f.type)]));
        const pathText = p => p.join('.');
        const restartBadge = (p, n) => pendingAt(n?.mapEntries
            ? (snapshot.pendingPaths || []).filter(path => !prefix(p, path) || !own(n.mapEntries, path[p.length]))
            : snapshot.pendingPaths, p) ? '<span class="starbot-restart" title="配置已保存, 重启后生效"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="10" fill="currentColor"/><path d="M12 6v7" stroke="#fff" stroke-width="2" stroke-linecap="round"/><circle cx="12" cy="17" r="1.2" fill="#fff"/></svg>待重启生效</span>' : '';
        const restartHint = () => '<span class="starbot-restart-required" role="img" aria-label="修改后需重启 StarBot 生效" title="修改后需重启 StarBot 生效">' + icon('restart') + '</span>';
        const pendingIcon = '<span class="starbot-pending-icon" role="img" aria-label="有配置待重启生效" title="有配置待重启生效">' + icon('restart') + '</span>';
        const actual = p => get(data, p);
        const value = (n, p) => valueAt(contextData, n, p, fieldMetaAt);
        const activeValue = (n, p) => valueAt(snapshot.activeData || {}, n, p, fieldMetaAt);
        const itemTitle = (v, fallback) => String(v ?? '').trim() || fallback;
        const mapExtras = (n, v) => n.mapEntries
            ? Object.fromEntries(Object.entries(v && typeof v === 'object' && !Array.isArray(v) ? v : {}).filter(([key]) => !own(n.mapEntries, key)))
            : v;
        function restoreActiveButton(p) {
            if (p.some(Number.isInteger) || mapPaths.some(path => path.length < p.length && prefix(path, p))
                    && !own(fieldMetaAt(p.slice(0, -1)).mapEntries, p.at(-1))) {
                return '';
            }
            const rename = active === 'custom' && activeCustomRenames().find(link => equal(link.from, p) || equal(link.to, p));
            const values = [actual(p), get(snapshot.activeData || {}, p)];
            const title = rename ? '撤销改名, 恢复原键并移除新键'
                : fieldMetaAt(p).mapEntries ? '恢复此列表中的附加属性'
                : values.some(Array.isArray) ? '恢复整个列表的内容和顺序'
                : values.some(value => value && typeof value === 'object') ? '恢复整个配置对象'
                    : '保存后撤销这项待重启的修改';
            return button('恢复当前生效值', 'restore-active', p, 'title="' + title + '"');
        }
        const fieldActions = (n, p) => (n.nullable && value(n, p) != null && (!n.suggestionConflict || active === 'conflicts') ? button('清除设置', 'null-value', p) : '')
            + (p[0] === 'starbot' && own(n, 'default') && !equal(value(n, p), normalizeString(n.default, n.type)) ? button('恢复默认', 'reset-default', p) : '')
            + ((n.type !== 'any' || p[0] !== 'advanced' || n.ui?.widget === 'password') && (n.restartRequired || p[0] === 'advanced')
                && !equal(mapExtras(n, value(n, p)), mapExtras(n, activeValue(n, p))) ? restoreActiveButton(p) : '');
        function mergeContext(inherited, draft) {
            const result = clone(inherited || {});
            for (const [key, item] of Object.entries(draft)) {
                const merged = item && typeof item === 'object' && !Array.isArray(item) ? mergeContext(result[key] && typeof result[key] === 'object' && !Array.isArray(result[key]) ? result[key] : {}, item) : item;
                Object.defineProperty(result, key, { value: merged, enumerable: true, writable: true, configurable: true });
            }
            return result;
        }
        function set(p, v) {
            serverErrors = serverErrors.filter(isDuplicateSpringIssue);
            for (const [key, issue] of numericErrors) {
                if (prefix(p, issue.path)) {
                    numericErrors.delete(key);
                }
            }
            let o = data;
            const parents = [];
            for (const [index, k] of p.slice(0, -1).entries()) {
                if (own(o, k) && (o[k] === null || typeof o[k] !== 'object')) {
                    toast('父节点不是对象, 请先修正其类型');
                    return;
                }
                if (!own(o, k)) {
                    if (v === undefined) {
                        return;
                    }
                    const parentPath = p.slice(0, index + 1);
                    const fieldMeta = fieldMetaAt(parentPath), reference = value(fieldMeta, parentPath);
                    const next = typeof p[index + 1] === 'number' ? clone(Array.isArray(reference) ? reference : [])
                        : fieldMeta.type === 'map' && reference && typeof reference === 'object' && !Array.isArray(reference) ? clone(reference) : {};
                    Object.defineProperty(o, k, { value: next, writable: true, enumerable: true, configurable: true });
                    if (!Array.isArray(next) && fieldMeta.type !== 'map') {
                        implicitObjects.add(next);
                    }
                }
                parents.push([o, k]);
                o = o[k];
            }
            if (v === undefined) {
                customRenames = customRenames.filter(rename => !prefix(p, rename.to));
                clearTreeTypes(p);
                delete o[p.at(-1)];
                for (const [parent, key] of parents.reverse()) {
                    if (!implicitObjects.has(parent[key]) || Object.keys(parent[key]).length) {
                        break;
                    }
                    delete parent[key];
                }
            }
            else {
                Object.defineProperty(o, p.at(-1), { value: v, writable: true, enumerable: true, configurable: true });
            }
        }
        function clearTreeTypes(path) {
            for (const key of nodeTypes.keys()) {
                if (prefix(path, JSON.parse(key))) {
                    nodeTypes.delete(key);
                }
            }
        }
        function removeArrayItem(path, list, index) {
            list.splice(index, 1);
            pendingMoves = pendingMoves.filter(move => !prefix(path, move.from));
            serverErrors = serverErrors.filter(isDuplicateSpringIssue);
            function shift(entries, update) {
                const shifted = [];
                for (const [key, item] of entries) {
                    const itemPath = JSON.parse(key), itemIndex = itemPath[path.length];
                    if (prefix(path, itemPath) && Number.isInteger(itemIndex) && itemIndex >= index) {
                        entries.delete(key);
                        if (itemIndex > index) {
                            itemPath[path.length]--;
                            shifted.push([pid(itemPath), update(item, itemPath)]);
                        }
                    }
                }
                for (const [key, item] of shifted) {
                    entries.set(key, item);
                }
            }
            shift(nodeTypes, type => type);
            shift(numericErrors, (issue, path) => ({...issue, path}));
        }
        function restoreCollectionDefault(path, field, deleting = false) {
            const remaining = actual(path);
            if ((field.type !== 'map' && field.type !== 'array') || !remaining || typeof remaining !== 'object' || field.suggestionConflict
                    || typeof path.at(-1) !== 'string' || fieldMetaAt(path.slice(0, -1)).type === 'map') {
                return;
            }
            const emptyDeletion = deleting && size(remaining) === 0;
            if (!emptyDeletion && (field.mapEntries || !equal(remaining, defaultValue(field)) && !equal(remaining, get(snapshot.inherited || {}, path)))) {
                return;
            }
            const original = get(baseline, path);
            if (equal(original, remaining)) {
                return;
            }
            if (emptyDeletion && original === null) {
                set(path, null);
                return;
            }
            set(path, undefined);
            const inherited = valueAt(mergeContext(snapshot.inherited, data), field, path, fieldMetaAt);
            if (!equal(inherited, remaining) && !(emptyDeletion && inherited == null && (original === undefined || path[0] === 'advanced'))) {
                set(path, remaining);
            }
            else {
                pendingMoves = pendingMoves.filter(move => !prefix(path, move.from));
            }
        }
        function ctx(n, p, ds) {
            const parentPath = p.slice(0, -1);
            return { parent: value(fieldMetaAt(parentPath), parentPath) || {}, current: value(n, p), document: contextData, defaults: ds || {},
                parentPath, currentPath: p, springPaths };
        }
        function isVisible(n, p, ds) {
            return condition(n.visibleWhen, ctx(n, p, ds)) !== false;
        }
        function isRequired(n, context) {
            return !!n.required || (n.requiredWhen && condition(n.requiredWhen, context) === true);
        }
        function validate() {
            errors = [];
            displayErrors = [];

            let draft = data;
            if (rawMode) {
                try {
                    draft = parseRawDocument(rawDraft);
                }
                catch (error) {
                    rawIssue = error.message;
                    return errors;
                }
                rawIssue = '';
            }

            actionData = mergeContext(snapshot.inherited, draft);
            contextData = withDefaults(actionData, meta);
            function visit(n, p, ds, keyDocument) {
                if (n.conflicted) {
                    return;
                }
                const accessPassword = !keyDocument && currentTab === 'core' && equal(p, source.accessPasswordPath);
                const v = keyDocument ? keyDocument.key : accessPassword ? get(contextData, p) : value(n, p);
                const context = keyDocument ? {parent: keyDocument, current: v, document: keyDocument, defaults: {},
                    parentPath: [], currentPath: ['key'], springPaths: []} : ctx(n, p, ds);
                const label = n.label || n.key || (keyDocument ? 'key' : '配置值');
                if (n.validateWhen && condition(n.validateWhen, context) === false) {
                    return;
                }
                if (n.suggestionConflict && v === undefined) {
                    return;
                }
                const push = m => errors.push({ path: p, message: m, tab: currentTab, group: currentGroup });
                if (!keyDocument && numericErrors.has(pid(p))) {
                    push(numericErrors.get(pid(p)).message);
                    return;
                }
                if (accessPassword) {
                    if (v !== undefined && typeof v !== 'string') {
                        push('访问密码必须为文本');
                        return;
                    }
                    if (isStoredAccessPassword(p, v)) {
                        return;
                    }
                }
                const required = isRequired(n, context);
                if (required && (v === undefined || v === null || v === '')) {
                    push(label + '需要填写');
                }
                for (const r of n.validators || [])
                    if (['required', 'requiredWhen', 'forbiddenWhen'].includes(r.rule) && !checkRule(v, r, context)) {
                        push(r.message || '未通过 ' + r.rule + ' 校验');
                    }
                if (v === undefined || !keyDocument && isSpringPlaceholder(v, p, springPaths)) {
                    return;
                }
                const ts = Array.isArray(n.type) ? n.type : [n.type];
                if (v === null) {
                    if (!required && !ts.includes('any') && !ts.includes('null') && !n.nullable) {
                        push(label + '不允许为空值');
                    }
                    return;
                }
                const checks = { string: typeof v === 'string', integer: typeof v === 'number' && Number.isInteger(v), number: typeof v === 'number' && Number.isFinite(v), duration: ts.includes('duration') && duration(v) !== null, boolean: typeof v === 'boolean', object: !!v && typeof v === 'object' && !Array.isArray(v), map: !!v && typeof v === 'object' && !Array.isArray(v), array: Array.isArray(v), any: true };
                if (!ts.some(t => checks[t])) {
                    push(label + '的数据类型不正确');
                    return;
                }
                if (n.nonBlank && typeof v === 'string' && !v.trim()) {
                    push(label + '不能为空白');
                }
                if (typeof v === 'number') {
                    if (n.minimum !== undefined && v < n.minimum) {
                        push(label + '不能小于 ' + n.minimum);
                    }
                    if (n.maximum !== undefined && v > n.maximum) {
                        push(label + '不能大于 ' + n.maximum);
                    }
                }
                if (n.options && !n.options.some(o => equal(o.value, v))) {
                    push('请选择有效的' + label);
                }
                if (!n.options && n.optionsFrom?.enforce && dynamicOptionValues(n.optionsFrom, context)?.every(candidate => !equal(candidate, v))) {
                    push('"' + label + '" 的当前选项已不可用, 请重新选择');
                }
                if (n.format && !formatCheck(v, n.format)) {
                    push(label + '格式不正确');
                }
                for (const r of n.validators || [])
                    if (!['required', 'requiredWhen', 'forbiddenWhen'].includes(r.rule) && !checkRule(v, r, { ...context, current: v })) {
                        push(r.message || '未通过 ' + r.rule + ' 校验');
                    }
                if (ts.includes('object') && checks.object) {
                    for (const f of n.fields || [])
                        visit(f, fieldPath(f, p), defaults(n.fields));
                }
                if (ts.includes('array') && Array.isArray(v)) {
                    v.forEach((x, i) => visit(n.items, p.concat(i), {}));
                }
                if (ts.includes('map') && checks.map) {
                    for (const k of Object.keys(v)) {
                        if (own(n.mapEntries, k)) {
                            continue;
                        }
                        if (n.keyMeta) {
                            visit(n.keyMeta, p.concat(k), {}, {key: k});
                        }
                        visit(n.values, p.concat(k), {});
                    }
                }
            }
            let currentTab, currentGroup;
            for (const moduleMeta of meta) {
                currentTab = moduleMeta.id;
                for (const g of moduleMeta.groups) {
                    currentGroup = g.id;
                    for (const n of g.fields)
                        visit(n, fieldPath(n, groupPath(moduleMeta, g)), defaults(g.fields));
                }
            }
            for (const issue of rawMode ? [] : numericErrors.values()) {
                const n = fieldMetaAt(issue.path);
                if (n.validateWhen && condition(n.validateWhen, ctx(n, issue.path, {})) === false) {
                    continue;
                }
                if (!errors.some(error => equal(error.path, issue.path))) {
                    errors.push(issue);
                }
            }
            errors = sharedIssues(mergeIssues(errors, currentServerIssues(serverErrors, draft), snapshot.index), locations);
            displayErrors = mergeIssues(errors, [], snapshot.index);
            return errors;
        }
        function errAt(p) {
            return displayErrors.filter(e => equal(e.path, p) || equal(e.relatedPath, p));
        }
        function objectErrors(p) {
            const es = errAt(p);
            return es.length ? '<div class="starbot-validation-box" role="alert">' + es.map(e => esc(e.message)).join('<br>') + '</div>' : '';
        }
        function button(text, action, p, extra = '') {
            return '<button type="button" class="starbot-button starbot-small cursor-interaction" data-action="' + action + '" data-path="' + pa(p) + '" ' + extra + '>' + text + '</button>';
        }
        function arrayMoveButtons(p, length) {
            const index = p.at(-1), item = '第 ' + (index + 1) + ' 项';
            return button('↑', 'move-up', p, 'title="上移" aria-label="上移' + item + '"' + (index === 0 ? ' disabled' : ''))
                + button('↓', 'move-down', p, 'title="下移" aria-label="下移' + item + '"' + (index === length - 1 ? ' disabled' : ''));
        }
        function input(v, p, extra = '') {
            return '<input class="starbot-input ' + (extra.includes('data-rename') ? 'starbot-key-input' : '') + '" type="text" value="' + esc(v ?? '') + '" data-edit="' + pa(p) + '" ' + extra + '>';
        }
        function options(n, p) {
            if (n.options) {
                return n.options;
            }
            if (n.optionsFrom) {
                return dynamicOptionValues(n.optionsFrom, ctx(n, p))?.map(value => ({ label: String(value), value })) ?? null;
            }
            return null;
        }
        function leaf(n, p, placeholder = '') {
            const v = value(n, p);
            if (isSpringPlaceholder(v, p, springPaths)) {
                const numeric = n.type === 'integer' || n.type === 'number';
                return input(v, p, (numeric ? 'data-number="' + n.type + '" ' : '') + 'aria-label="' + esc(n.label || '配置值') + '"');
            }
            const type = Array.isArray(n.type) ? (typeof v === 'number' ? 'integer' : Array.isArray(v) ? 'array' : 'string') : n.type;
            const opts = options(n, p);
            if (type === 'boolean') {
                return '<label class="starbot-switch-row"><span>' + (v ? '已开启' : '已关闭') + '</span><span class="starbot-switch-track"><input class="starbot-switch cursor-interaction" type="checkbox" data-edit="' + pa(p) + '" ' + (v ? 'checked' : '') + ' aria-label="' + esc(n.label) + '"><span class="starbot-switch-thumb" aria-hidden="true"></span></span></label>';
            }
            if (opts) {
                const selected = opts.find(o => equal(o.value, v));
                const prompt = v != null && v !== '' && !selected ? '当前值: ' + v : placeholder ? '请选择' + placeholder : '请选择';
                return '<select class="starbot-select" aria-label="' + esc(n.label || '配置值') + '" data-edit="' + pa(p) + '" data-options="' + esc(JSON.stringify(opts)) + '"><option value="" disabled' + (selected ? '' : ' selected') + '>' + esc(prompt) + '</option>' + opts.map((o, i) => '<option value="' + i + '" ' + (equal(o.value, v) ? 'selected' : '') + '>' + esc(o.label) + '</option>').join('') + '</select>';
            }
            if (n.type === 'duration' || [].concat(n.type).includes('duration')
                    && (v == null || duration(v) !== null || n.type.every(t => t === 'duration' || t === 'null'))) {
                const {amount, unit} = durationParts(v);
                const label = n.label || '时长';
                return '<div class="starbot-input-wrap starbot-duration">'
                    + input(amount, p, 'data-duration="amount" inputmode="decimal" maxlength="40" placeholder="未设置" aria-label="' + esc(label) + '"')
                    + '<select class="starbot-select" data-edit="' + pa(p) + '" data-duration="unit" aria-label="' + esc(label + '单位') + '">'
                    + Object.entries(durationInputUnits).map(([key, name]) => '<option value="' + key + '"' + (key === unit ? ' selected' : '') + '>' + name + '</option>').join('')
                    + '</select></div>';
            }
            if (n.ui?.widget === 'slider') {
                return '<div class="starbot-range-row"><input type="range" min="' + n.minimum + '" max="' + n.maximum + '" value="' + esc(v ?? n.minimum) + '" data-edit="' + pa(p) + '" aria-label="' + esc(n.label) + '"><output class="starbot-range-value">' + esc(v) + '</output></div>';
            }
            const hint = placeholder ? 'placeholder="' + esc(placeholder) + '" ' : '';
            if (n.ui?.widget === 'textarea') {
                return '<textarea class="starbot-textarea" ' + hint + 'aria-label="' + esc(n.label || '配置值') + '" data-edit="' + pa(p) + '">' + esc(v ?? '') + '</textarea>';
            }
            const password = n.ui?.widget === 'password';
            const storedPassword = password && isStoredAccessPassword(p, v);
            const numeric = type === 'integer' || type === 'number';
            let control = input(storedPassword ? '' : v, p, (storedPassword ? 'placeholder="已设置密码, 输入新密码可修改" ' : hint) + (password ? 'data-secret autocomplete="off" spellcheck="false" ' : '') + (numeric ? 'data-number="' + type + '" inputmode="decimal" ' : '') + 'aria-label="' + esc(n.label || '值') + '"');
            if (password) {
                control += '<button type="button" class="starbot-secret-button cursor-interaction" data-action="reveal" aria-label="显示' + esc(n.label) + '" aria-pressed="false" title="显示' + esc(n.label) + '">' + icon('eye') + '</button>';
            }
            if (n.ui?.widget === 'color') {
                control = '<input type="color" class="starbot-color-input" data-edit="' + pa(p) + '" value="' + (/^#[\da-f]{6}$/i.test(v || '') ? v : '#336699') + '" aria-label="选择颜色">' + control;
            }
            return '<div class="starbot-input-wrap">' + control + (n.ui?.unit ? '<span class="starbot-unit">' + esc(n.ui.unit) + '</span>' : '') + '</div>';
        }
        function renderConflictLink(n) {
            return n.suggestionConflict ? '<p class="starbot-conflict-link">' + (n.typeConflict ? '插件对该项的数据类型要求不同, 请在 ' : '此配置有多个不同建议, 请在 ') + '<button type="button" data-action="open-conflicts" data-path="' + pa(n.configPath) + '">配置冲突</button> 中选择</p>' : '';
        }
        function renderDiagnosticSources(sources) {
            return '<ul>' + sources.map(source => {
                const type = source.type ? [].concat(source.type).map(item => typeLabels[item] || item).join(' / ') : '';
                const field = source.label ? ' · ' + esc(source.label) : '';
                return '<li>' + esc(source.source) + field + ' · <code>' + esc(pathText(source.path)) + '</code>'
                    + (type ? ' (' + esc(type) + ')' : '') + (source.detail ? ' · ' + esc(source.detail) : '') + '</li>';
            }).join('') + '</ul>';
        }
        function renderConflicts() {
            const current = currentMeta().groups.find(group => group.id === section) || currentMeta().groups[0];
            const pending = conflictDefinitions.filter(item => actual(item.path) === undefined).length;
            let content = '<div class="starbot-section-heading"><div><h2>配置冲突</h2><p class="starbot-section-description">插件对部分配置有不同要求. 请选择一个建议方案, 或自行填写, 然后点击顶部 "保存配置"</p></div></div>'
                + '<div class="starbot-conflict-summary"><span>待选择 <strong data-conflict-pending>' + pending + '</strong> 项</span><span>已选择 <strong data-conflict-chosen>' + (conflictDefinitions.length - pending) + '</strong> 项</span>' + (metaIssues.length ? '<span>声明问题 <strong>' + metaIssues.length + '</strong> 项</span>' : '') + '</div>';
            if (current.issues) {
                return '<div class="starbot-section-heading"><h2>配置声明问题</h2></div>' + current.issues.map(item => {
                    const title = item.title || (item.springKey ? 'Spring 属性重复映射' : '无法加载配置元数据');
                    const details = item.sources
                        ? '<p>' + esc(item.message) + '</p>' + renderDiagnosticSources(item.sources)
                        : '<p>' + esc(item.source) + ': ' + esc(item.message) + '</p>';
                    return '<section class="starbot-surface starbot-conflict-problem"><h3>' + esc(title) + '</h3>' + details
                        + '<p>这项问题需要插件作者调整配置元数据. 其他配置仍可编辑</p></section>';
                }).join('');
            }
            content += '<div class="starbot-conflict-group-title"><h3>' + esc(current.title) + '</h3><span>' + current.conflicts.length + ' 项配置</span></div>';
            return content + current.conflicts.map(conflict => {
                const status = conflictSelection(conflict.path, data, snapshot.data);
                const groups = groupSuggestions(conflict.definitions, conflict.field.type);
                const value = actual(conflict.path);
                const custom = customConflicts.has(pid(conflict.path)) || value !== undefined && !groups.some(group => equal(value, group.value));
                const statusText = {pending:'待选择',draft:'已选择, 尚未保存',saved:'已保存'}[status];
                return '<section class="starbot-surface starbot-conflict" data-conflict="' + pa(conflict.path) + '"><div class="starbot-conflict-heading"><div><h3>' + esc(conflict.label) + '</h3><code class="starbot-path">' + esc(conflict.path.join('.')) + '</code></div><span class="starbot-conflict-state" data-state="' + status + '">' + statusText + '</span></div>'
                    + (conflict.typeConflict ? '<div class="starbot-conflict-type-notice"><strong>插件要求的数据类型不同</strong><ul>' + (conflict.participants || []).map(source => '<li>' + esc(source.source) + ': ' + esc([].concat(source.type).map(type => typeLabels[type] || type).join(' / ')) + '</li>').join('') + '</ul><p>选择时请确认兼容性, 其他插件可能无法使用所选值</p></div>' : '')
                    + '<div class="starbot-conflict-options">' + groups.map(group => {
                        const chosen = value !== undefined && equal(value, group.value);
                        const display = conflict.field.ui?.widget === 'password' ? '••••••' : group.value === true ? '开启' : group.value === false ? '关闭' : group.value === null ? '不设置此属性' : typeof group.value === 'string' ? group.value || '空文本' : JSON.stringify(group.value, null, 2);
                        return '<div class="starbot-conflict-option ' + (chosen ? 'is-selected' : '') + '" data-choice-path="' + pa(conflict.path) + '" data-choice-index="' + group.index + '"><div class="starbot-conflict-option-head"><span>建议方案</span><span class="starbot-conflict-choice-mark">' + (chosen ? '当前选择' : '') + '</span></div><pre class="starbot-conflict-value">' + esc(display) + '</pre><ul class="starbot-conflict-sources">'
                            + group.sources.map(source => '<li><strong>' + esc(source.source) + '</strong><p>' + esc(source.reason || '插件未说明原因') + '</p></li>').join('') + '</ul>'
                            + button(chosen ? '已选择此方案' : '选择此方案', 'use-suggestion', conflict.path, 'data-suggestion-index="' + group.index + '" aria-pressed="' + chosen + '"') + '</div>';
                    }).join('') + '</div><div class="starbot-conflict-custom-head"><span>' + (groups.length ? '建议都不合适?' : '插件未提供建议值') + '</span>' + button(custom ? '修改自定义值' : '自行填写', 'custom-conflict', conflict.path) + '</div>'
                    + (custom ? '<div class="starbot-conflict-custom">' + renderNode({...conflict.field, suggestionConflict:false, suggestions:[]}, conflict.path) + '</div>' : '') + '</section>';
            }).join('');
        }
        function renderGroupContent(moduleMeta, group) {
            const path = groupPath(moduleMeta, group), actions = renderActions(moduleMeta, group);
            if (group.ui?.layout !== 'cards' || !group.fields.some(field => field.type === 'object')) {
                return '<section class="starbot-surface">' + fields(group.fields, path) + actions + '</section>';
            }

            const ds = defaults(group.fields);
            const surface = (markup, footer = '') => '<section class="starbot-surface">'
                + (markup ? '<div class="starbot-field-grid">' + markup + '</div>' : '') + footer + '</section>';
            let sections = '', ordinary = '';
            for (const field of group.fields) {
                const card = field.type === 'object';
                const markup = renderNode(field, fieldPath(field, path), ds, card);
                if (!markup) {
                    continue;
                }
                if (card) {
                    sections += (ordinary ? surface(ordinary) : '') + markup;
                    ordinary = '';
                }
                else {
                    ordinary += markup;
                }
            }
            return '<div class="starbot-group-cards">' + sections + (ordinary || actions ? surface(ordinary, actions) : '') + '</div>';
        }
        function fields(list, p) {
            const ds = defaults(list);
            return '<div class="starbot-field-grid">' + list.map(n => renderNode(n, fieldPath(n, p), ds)).join('') + '</div>';
        }
        function renderNode(n, p, ds = {}, card = false) {
            if (n.typeConflict && n.conflicted) {
                n = conflictDefinitions.find(conflict => equal(conflict.path, p))?.field || n;
            }
            if (n.conflicted) {
                const message = n.springBindingConflict ? ' 的 Spring 属性映射重复'
                    : n.declarationIssue ? ': ' + esc(n.declarationIssue) : ' 的配置元数据存在冲突';
                return '<div class="starbot-field starbot-wide starbot-constraint' + (card ? ' starbot-surface' : '') + '">' + esc(n.label || n.key) + message
                    + (metaIssues.length ? ' <span class="starbot-conflict-link"><button type="button" data-action="open-meta-errors">查看配置声明问题</button></span>' : '') + '</div>';
            }
            if (!isVisible(n, p, ds)) {
                return '';
            }
            const v = value(n, p), declaredTypes = Array.isArray(n.type) ? n.type : [n.type], ts = declaredTypes.length > 1 ? [Array.isArray(v) ? 'array' : typeof v === 'number' ? (Number.isInteger(v) && declaredTypes.includes('integer') ? 'integer' : 'number') : v === undefined ? declaredTypes[0] : typeof v] : declaredTypes;
            const complex = ts.some(t => ['object', 'array', 'map', 'any'].includes(t));
            const es = errAt(p);
            let content = '';
            const tag = card ? 'section' : 'div', titleTag = card ? 'h3' : 'label';
            const cardClasses = card ? 'starbot-surface starbot-object-card ' + (n.restartRequired ? 'starbot-restart-card ' : '') : '';
            const prefix = '<' + tag + ' class="starbot-field ' + cardClasses + (complex ? 'starbot-wide ' : '') + (es.length ? 'starbot-invalid' : '') + '" data-field="' + pa(p) + '">'
                + '<div class="starbot-field-head"><div class="starbot-field-title"><' + titleTag + ' class="starbot-field-label">' + esc(n.label || n.key || '值')
                + (isRequired(n, ctx(n, p, ds)) ? '<span class="starbot-required">*</span>' : '') + '</' + titleTag + '>'
                + (restartBadge(p, n) || (n.restartRequired ? restartHint() : '')) + '</div><div class="starbot-field-actions">' + fieldActions(n, p) + '</div></div>';
            const description = n.description ? '<div class="' + (card ? 'starbot-section-description' : 'starbot-help') + '">' + esc(n.description) + '</div>' : '';
            if (n.suggestionConflict && active !== 'conflicts') {
                const chosen = v === undefined ? '尚未选择配置值' : n.ui?.widget === 'password' ? '••••••' : v === true ? '开启' : v === false ? '关闭' : v === null ? '不设置此属性' : typeof v === 'string' ? v || '空文本' : JSON.stringify(v, null, 2);
                return prefix + (card ? description : '') + '<pre class="starbot-conflict-current-value">' + esc(chosen) + '</pre>' + renderConflictLink(n)
                    + '<div class="starbot-error">' + es.map(issue => esc(issue.message)).join('<br>') + '</div></' + tag + '>';
            }
            const placeholder = isSpringPlaceholder(v, p, springPaths);
            if (placeholder || v === null && declaredTypes.includes('duration')) {
                content = leaf(n, p);
            }
            else if (v === null && !ts.some(type => ['string', 'integer', 'number'].includes(type))) {
                content = '<div class="starbot-tree-null">未设置 ' + button('填写', 'literal', p) + '</div>';
            }
            else if (ts.includes('any')) {
                content = n.ui?.widget === 'password' ? v && typeof v === 'object'
                    ? '<p class="starbot-muted">已设置复杂配置值, 可在 JSON 原文中编辑</p>' : leaf({...n, type:'string'}, p)
                    : tree(v, p, { root: true });
            }
            else if (ts.includes('map')) {
                const keys = Object.keys(mapExtras(n, v) || {}), complexValue = ['object', 'array', 'map', 'any'].includes(n.values.type);
                const keyLabel = String(n.keyMeta?.label ?? '').trim();
                const nameLabel = esc(keyLabel || '条目名称');
                const caption = keyLabel ? '<span class="starbot-entry-key-label">' + nameLabel + '</span>' : '';
                const keyError = 'starbot-map-key-error-' + encodeURIComponent(pid(p));
                content = '<div class="starbot-collection"><div class="starbot-collection-bar"><span class="starbot-muted">' + keys.length + ' 项</span>' + button('+ 添加条目', 'add-map', p) + '</div>'
                    + '<form class="starbot-map-new" data-new-map="' + pa(p) + '" hidden><label class="starbot-entry-key"><span class="starbot-entry-key-label">' + nameLabel + '</span><input type="text" name="key" class="starbot-input" autocomplete="off" placeholder="请输入' + nameLabel + '" aria-describedby="' + keyError + '"></label>'
                    + '<div class="starbot-map-new-actions">' + button('取消', 'cancel-map-entry', p) + '<button type="submit" class="starbot-button starbot-small starbot-primary">添加</button></div>'
                    + '<div class="starbot-error" id="' + keyError + '" role="alert"></div></form>';
                if (!keys.length) {
                    content += '<div class="starbot-empty">还没有配置条目</div>';
                }
                for (const k of keys) {
                    const ep = p.concat(k);
                    const keyEditor = '<label class="starbot-entry-key">' + caption
                        + input(k, ep, 'data-rename="map" aria-label="' + nameLabel + '"') + '</label>';
                    if (complexValue) {
                        const open = opened.has(pid(ep));
                        const body = n.values.type === 'object' ? objectErrors(ep) + fields(n.values.fields, ep)
                            : renderNode({ ...n.values, label: n.values.label || '配置内容' }, ep);
                        content += '<details class="starbot-entry" data-open="' + pa(ep) + '" ' + (open ? 'open' : '') + '><summary>' + keyEditor + button('删除', 'delete', ep) + '</summary><div class="starbot-entry-body">' + body + '</div></details>';
                    }
                    else {
                        content += '<div class="starbot-simple-row">' + keyEditor + renderNode({ ...n.values, label: n.values.label || '值' }, ep) + button('删除', 'delete', ep) + '</div>';
                    }
                }
                content += '</div>';
            }
            else if (ts.includes('array') && Array.isArray(v)) {
                content = '<div class="starbot-collection"><div class="starbot-collection-bar"><span class="starbot-muted">' + v.length + ' 项</span>' + button('+ 添加一项', 'add-array', p) + '</div>';
                if (!v.length) {
                    content += '<div class="starbot-empty">还没有添加内容</div>';
                }
                v.forEach((x, i) => {
                    const ep = p.concat(i), complexItem = ['object', 'map', 'array', 'any'].includes(n.items.type);
                    const actions = arrayMoveButtons(ep, v.length) + button('删除', 'delete', ep);
                    if (complexItem) {
                        const titlePath = n.ui?.itemTitlePath, fallback = '第 ' + (i + 1) + ' 项';
                        const titleData = titlePath ? ' data-item-title="' + pa(ep.concat(titlePath)) + '" data-item-fallback="' + fallback + '"' : '';
                        content += '<details class="starbot-entry" data-open="' + pa(ep) + '" ' + (opened.has(pid(ep)) ? 'open' : '') + '><summary><span class="starbot-entry-name"' + titleData + '>' + esc(itemTitle(titlePath ? get(x, titlePath) : null, fallback)) + '</span>' + actions + '</summary><div class="starbot-entry-body">' + (n.items.type === 'object' ? objectErrors(ep) + fields(n.items.fields, ep) : renderNode({ ...n.items, label: '内容' }, ep)) + '</div></details>';
                    }
                    else {
                        content += '<div class="starbot-list-row"><div class="starbot-list-field" data-field="' + pa(ep) + '">' + leaf(n.items, ep, n.items.label) + '<div class="starbot-error"></div></div><div class="starbot-list-actions">' + actions + '</div></div>';
                    }
                });
                content += '</div>';
            }
            else if (ts.includes('object')) {
                content = fields(n.fields, p);
            }
            else if (ts.includes('array') && v === undefined) {
                content = '<div class="starbot-empty">' + button('+ 添加一项', 'add-array', p) + '</div>';
            }
            else {
                content = leaf(n, p);
            }
            return prefix + (card ? description : '') + content + (card ? '' : description) + renderConflictLink(n)
                + '<div class="starbot-error" ' + (es.length ? 'role="alert"' : '') + '>' + es.map(e => esc(e.message)).join('<br>') + '</div></' + tag + '>';
        }
        function treeType(v, p) {
            return nodeTypes.get(pid(p)) || (v === null ? 'null' : Array.isArray(v) ? 'array' : typeof v === 'object' ? 'object' : typeof v === 'number' ? (Number.isInteger(v) ? 'integer' : 'number') : typeof v === 'boolean' ? 'boolean' : 'string');
        }
        function tree(v, p, settings = {}) {
            const t = treeType(v, p), isIndex = settings.arraySize !== undefined;
            const numeric = !settings.root && (t === 'integer' || t === 'number');
            let html = '<div class="starbot-tree-row"' + (numeric ? ' data-field="' + pa(p) + '"' : '') + '><div class="starbot-tree-heading">';
            if (!settings.root) {
                html += isIndex ? '<span class="starbot-count">' + (Number(p.at(-1)) + 1) + '</span>' : input(p.at(-1), p, 'data-rename="tree" aria-label="配置键"');
            }
            if (!settings.root) {
                html += restartBadge(p) || (active === 'custom' ? '' : restartHint());
            }
            if (settings.restore !== false && !equal(v, get(snapshot.activeData || {}, p))) {
                html += restoreActiveButton(p);
            }
            html += '<select class="starbot-select" data-tree-type="' + pa(p) + '" aria-label="' + esc(p.join('.') + ' 的值类型') + '">' + [['string', '文本'], ['integer', '整数'], ['number', '小数'], ['boolean', '开关'], ['null', '空值'], ['object', '对象'], ['array', '列表']].map(([k, l]) => '<option value="' + k + '" ' + (t === k ? 'selected' : '') + '>' + l + '</option>').join('') + '</select>';
            if (!settings.root) {
                if (isIndex) {
                    html += arrayMoveButtons(p, settings.arraySize);
                }
                html += button('删除', 'delete', p);
            }
            if (t === 'object' || t === 'array') {
                html += button('+ 添加', 'add-tree', p);
            }
            html += '</div><div class="starbot-tree-value">';
            if (t === 'object') {
                html += Object.entries(v || {}).map(([k, x]) => tree(x, p.concat(k), {restore:false})).join('');
            }
            else if (t === 'array') {
                html += (v || []).map((x, i) => tree(x, p.concat(i), {restore:false, arraySize:v.length})).join('');
            }
            else if (t === 'null') {
                html += '<span class="starbot-tree-null">null</span>';
            }
            else {
                html += leaf({ type: t, label: '配置值' }, p);
            }
            return html + '</div>' + (numeric ? '<div class="starbot-error"></div>' : '') + '</div>';
        }
        const claims = [], locations = [], mapPaths = [];
        function collect(n, p, tab, group) {
            if ((Array.isArray(n.type) ? n.type : [n.type]).includes('map')) {
                mapPaths.push(p);
            }
            if (n.type === 'object') {
                for (const f of n.fields || [])
                    collect(f, fieldPath(f, p), tab, group);
            }
            else {
                claims.push(p);
                locations.push({ path: p, tab, group });
            }
        }
        for (const moduleMeta of meta)
            for (const g of moduleMeta.groups || [])
                for (const n of g.fields)
                    collect(n, fieldPath(n, groupPath(moduleMeta, g)), moduleMeta.id, g.id);
        for (const conflict of conflictDefinitions) {
            locations.push({path: conflict.path, tab: 'conflicts', group: conflictGroups.find(group => group.conflicts.includes(conflict)).id});
        }
        const prefix = (a, b) => a.length <= b.length && a.every((v, i) => v === b[i]);
        function unclaimed(document = data) {
            const result = [];
            function visit(v, p) {
                if (claims.some(c => prefix(c, p))) {
                    return;
                }
                if (!claims.some(c => prefix(p, c))) {
                    result.push({ p, v });
                    return;
                }
                if (v && typeof v === 'object') {
                    for (const [k, x] of Object.entries(v))
                        visit(x, p.concat(k));
                }
                else {
                    result.push({ p, v });
                }
            }
            for (const [k, v] of Object.entries(document)) {
                if (['starbot', 'advanced'].includes(k) && v && typeof v === 'object' && !Array.isArray(v)) {
                    for (const [name, item] of Object.entries(v)) {
                        visit(item, [k, name]);
                    }
                }
                else {
                    visit(v, [k]);
                }
            }
            return result;
        }
        function renderCustom() {
            const entries = unclaimed();
            const removed = unclaimed(snapshot.activeData || {}).filter(({p}) => actual(p) === undefined
                && (get(snapshot.data, p) !== undefined || pendingAt(snapshot.pendingPaths, p)));
            const shared = currentMeta().groups.map(g => '<section class="starbot-surface starbot-contribution"><h3>' + esc(g.title) + '</h3>' + (g.description ? '<p class="starbot-section-description">' + esc(g.description) + '</p>' : '') + fields(g.fields, groupPath(currentMeta(), g)) + renderActions(currentMeta(), g) + '</section>').join('');
            return shared + '<div class="starbot-section-heading"><h2>自定义配置</h2>' + button('+ 新增配置', 'new-key', [])
                + '</div><div class="starbot-notice">自定义配置可能影响 StarBot 运行, 除非您知道您在做什么, 否则请不要修改此页面中的设置</div>'
                + (newKey ? '<div class="starbot-surface starbot-new-key"><h3>新增配置</h3><div class="starbot-field-grid"><label class="starbot-field">配置键<input id="starbot-new-path" class="starbot-input"></label><label class="starbot-field">值类型<select id="starbot-new-type" class="starbot-select"><option value="string">文本</option><option value="integer">整数</option><option value="number">小数</option><option value="boolean">开关</option><option value="object">对象</option><option value="array">列表</option><option value="null">空值</option></select></label></div><div class="starbot-new-key-actions">' + button('取消', 'cancel-key', []) + button('添加配置', 'confirm-key', []) + '</div></div>' : '')
                + entries.map(({ p, v }) => '<section class="starbot-surface starbot-custom-block"><span class="starbot-path starbot-custom-path">' + esc(p[0] === 'advanced' ? '高级配置' : p[0] === 'starbot' ? 'StarBot 配置 - ' + p.slice(0, -1).join(' / ') : p.slice(0, -1).join(' / ') || '根节点') + '</span>' + tree(v, p) + '</section>').join('')
                + (removed.length ? '<section class="starbot-surface starbot-custom-block"><h3>已移除的自定义配置</h3>'
                    + removed.map(({p}) => '<div class="starbot-tree-heading"><span class="starbot-path">' + esc(p[0] === 'advanced' ? p[1] : p.join('.')) + '</span>'
                        + restoreActiveButton(p) + '</div>').join('') + '</section>' : '');
        }
        function activeCustomRenames(draft = data) {
            return customRenames.filter(({from, to}) => get(draft, from) === undefined && get(draft, to) !== undefined
                && get(snapshot.activeData || {}, from) !== undefined);
        }
        function renderActions(moduleMeta, group) {
            return (group.actions || []).map(action => '<div class="starbot-config-action" data-config-action="' + pa([moduleMeta.id, group.id, action.id]) + '">'
                + button(action.label, 'execute-config', [moduleMeta.id, group.id, action.id])
                + (action.description ? '<p class="starbot-muted starbot-action-description">' + esc(action.description) + '</p>' : '')
                + '<p class="starbot-action-result" role="status" aria-live="polite"></p></div>').join('');
        }
        function actionBlockReason() {
            if (errors.length || rawIssue) {
                return '请先修正页面中的配置错误, 再执行此操作';
            }
            if (metaIssues.length || conflictDefinitions.some(item => get(actionData, item.path) === undefined)) {
                return '请先处理 "配置冲突" 中的问题, 再执行此操作';
            }
            return '';
        }
        function refreshActions() {
            const blocked = actionBlockReason();
            for (const wrapper of editor.querySelectorAll('[data-config-action]')) {
                const key = wrapper.dataset.configAction, [module, groupId, actionId] = JSON.parse(key);
                const moduleMeta = meta.find(item => item.id === module), group = moduleMeta.groups.find(item => item.id === groupId);
                const action = group.actions.find(item => item.id === actionId);
                const path = groupPath(moduleMeta, group), parent = get(contextData, path);
                wrapper.hidden = !!action.visibleWhen && condition(action.visibleWhen, {parent, current:parent, document:contextData,
                    defaults:defaults(group.fields), parentPath:path, currentPath:path, springPaths}) !== true;
                const state = actionStates.get(key), running = state?.state === 'running';
                const control = wrapper.querySelector('button'), result = wrapper.querySelector('.starbot-action-result');
                control.disabled = running || !!blocked;
                control.setAttribute('aria-busy', String(running));
                control.textContent = running ? '正在执行…' : action.label;
                result.textContent = running ? state.message : blocked || state?.message || '';
                result.dataset.state = blocked && !running ? 'blocked' : state?.state || '';
                result.setAttribute('role', result.dataset.state === 'failed' ? 'alert' : 'status');
            }
        }
        function confirmAction(message, title = '确认操作', confirmLabel = '继续执行') {
            if (root.querySelector('.starbot-confirm-dialog')) {
                return Promise.resolve(false);
            }
            return new Promise(resolve => {
                const dialog = document.createElement('dialog');
                dialog.className = 'starbot-confirm-dialog';
                dialog.setAttribute('aria-labelledby', 'starbot-action-confirm-title');
                dialog.setAttribute('aria-describedby', 'starbot-action-confirm-description');
                dialog.innerHTML = '<form method="dialog"><h2 id="starbot-action-confirm-title">' + esc(title) + '</h2><p id="starbot-action-confirm-description"></p>'
                    + '<div class="starbot-confirm-actions"><button type="submit" class="starbot-button" value="cancel" autofocus>取消</button>'
                    + '<button type="submit" class="starbot-button starbot-primary" value="confirm">' + esc(confirmLabel) + '</button></div></form>';
                dialog.querySelector('p').textContent = message;
                const close = value => {
                    if (dialog.dataset.closing) {
                        return;
                    }
                    dialog.dataset.closing = 'true';
                    window.ConfigControls.leave(dialog, 'fade', () => dialog.close(value));
                };
                dialog.addEventListener('submit', event => {
                    event.preventDefault();
                    close(event.submitter?.value || 'cancel');
                });
                dialog.addEventListener('keydown', event => {
                    if (event.key === 'Escape') {
                        event.preventDefault();
                        close('cancel');
                    }
                });
                dialog.addEventListener('cancel', event => {
                    event.preventDefault();
                    close('cancel');
                });
                dialog.addEventListener('close', () => {
                    const confirmed = dialog.returnValue === 'confirm';
                    dialog.remove();
                    resolve(confirmed);
                }, {once:true});
                root.appendChild(dialog);
                dialog.showModal();
                window.ConfigControls.enter(dialog);
            });
        }
        async function executeConfigAction(path) {
            const key = pid(path);
            if (actionStates.get(key)?.state === 'running') {
                return;
            }
            validate();
            const blocked = actionBlockReason();
            if (blocked) {
                refresh();
                toast(blocked, 'error');
                return;
            }
            const moduleMeta = meta.find(item => item.id === path[0]), group = moduleMeta.groups.find(item => item.id === path[1]);
            const action = group.actions.find(item => item.id === path[2]);
            if (action.confirmation && !await confirmAction(action.confirmation)) {
                return;
            }
            actionStates.set(key, {state:'running', message:'正在执行, 请稍候'});
            refreshActions();
            try {
                let result = await request('api/action', {module:path[0], group:path[1], action:path[2], configuration:selectActionConfiguration(actionData, action.parameters)});
                actionStates.set(key, result);
                refreshActions();
                while (result.state === 'running') {
                    await new Promise(resolve => setTimeout(resolve, 1000));
                    try {
                        result = await request('api/action-status/' + encodeURIComponent(result.id));
                        actionStates.set(key, result);
                    }
                    catch (error) {
                        if ([401, 403, 404].includes(error.status)) {
                            throw error;
                        }
                        actionStates.set(key, {...result, message:'暂时无法获取进度, 正在重新连接…'});
                    }
                    refreshActions();
                }
            }
            catch (error) {
                actionStates.set(key, {state:'failed', message:error.message || '请求未完成, 请稍后重试'});
                if (error.details?.errors) {
                    serverErrors = mergeIssues(serverErrors.filter(isDuplicateSpringIssue), error.details.errors, snapshot.index);
                    refresh();
                }
                refreshActions();
            }
        }
        function render(animateChange = false) {
            validate();
            const moduleMeta = currentMeta();
            if (moduleMeta.id !== 'custom' && !moduleMeta.groups.some(g => g.id === section)) {
                section = moduleMeta.groups[0]?.id || '';
            }
            if (!tabs.children.length) {
                tabs.innerHTML = meta.map(t => '<button type="button" id="starbot-tab-' + esc(t.id) + '" class="starbot-tab cursor-interaction" role="tab" aria-selected="' + (t.id === active) + '" aria-controls="starbot-editor" data-tab="' + esc(t.id) + '">' + icon(icons[t.id]) + ' ' + esc(t.title) + '</button>').join('');
            }
            for (const tab of tabs.querySelectorAll('[data-tab]')) {
                tab.setAttribute('aria-selected', String(tab.dataset.tab === active));
            }
            const description = root.querySelector('#starbot-tab-description');
            description.textContent = moduleMeta.description || '';
            description.hidden = !description.textContent.trim();
            editor.setAttribute('aria-labelledby', 'starbot-tab-' + active);
            side.innerHTML = '<div class="starbot-sidebar-title">配置分组</div>' + (active === 'custom' ? '' : (moduleMeta.groups || []).map(g => '<button type="button" class="starbot-section-link" data-section="' + esc(g.id) + '" aria-current="' + (section === g.id) + '"><span class="starbot-group-title">' + (active === 'conflicts' ? g.title.split(' / ').map(title => '<span class="starbot-group-name">' + esc(title) + '</span>').join(' / ') : esc(g.title)) + '</span></button>').join(''));
            let body = '<div id="starbot-validation"></div>';
            if (active === 'conflicts') {
                body += renderConflicts();
            }
            else if (active === 'custom') {
                body += renderCustom();
            }
            else if (moduleMeta.groups.length) {
                const group = moduleMeta.groups.find(g => g.id === section);
                body += '<div class="starbot-section-heading"><div><h2>' + esc(group.title) + '</h2>' + (group.description ? '<p class="starbot-section-description">' + esc(group.description) + '</p>' : '') + '</div></div>'
                    + renderGroupContent(moduleMeta, group);
            }
            if (rawMode) {
                body = '<div class="starbot-section-heading"><h2>' + (snapshot.readOnly ? 'JSON 原文 (只读)' : '编辑 JSON') + '</h2></div>' + (snapshot.readOnly || snapshot.count > 1 ? '<div class="starbot-notice">' + esc(snapshot.readOnly || '此文件包含多个 JSON 配置段, 请直接编辑原文') + '</div>' : '') + '<textarea id="starbot-raw" class="starbot-textarea starbot-raw" aria-label="JSON 原文" ' + (snapshot.readOnly ? 'readonly' : '') + '>' + esc(rawDraft) + '</textarea>';
            }
            editor.innerHTML = body;
            renderMeta();
            window.ConfigControls.enhance(root);
            refresh();
            if (animateChange) {
                window.ConfigControls.enter(editor);
            }
        }
        function refresh() {
            validate();
            for (const title of editor.querySelectorAll('[data-item-title]')) {
                const text = itemTitle(get(contextData, JSON.parse(title.dataset.itemTitle)), title.dataset.itemFallback);
                if (title.textContent !== text) {
                    title.textContent = text;
                }
            }
            refreshActions();
            for (const wrap of editor.querySelectorAll('[data-field]')) {
                const fieldPath = JSON.parse(wrap.dataset.field), n = fieldMetaAt(fieldPath), es = errAt(fieldPath);
                wrap.classList.toggle('starbot-invalid', !!es.length);
                const actions = wrap.querySelector(':scope > .starbot-field-head > .starbot-field-actions');
                if (actions) {
                    const content = fieldActions(n, fieldPath);
                    if (actions.dataset.content !== content) {
                        actions.innerHTML = content;
                        actions.dataset.content = content;
                    }
                }
                const dest = wrap.querySelector(':scope > .starbot-error');
                if (dest) {
                    dest.id = 'starbot-error-' + encodeURIComponent(pid(fieldPath));
                    dest.innerHTML = es.map(e => esc(e.message)).join('<br>');
                    if (es.length) {
                        dest.setAttribute('role', 'alert');
                    }
                    else {
                        dest.removeAttribute('role');
                    }
                    for (const input of wrap.querySelectorAll('[data-edit]')) {
                        if (input.dataset.rename || !equal(JSON.parse(input.dataset.edit), fieldPath)) {
                            continue;
                        }
                        const control = input.tagName === 'SELECT' ? input.parentElement.querySelector('.starbot-select-trigger') || input : input;
                        control.setAttribute('aria-invalid', String(!!es.length));
                        if (es.length) {
                            control.setAttribute('aria-describedby', dest.id);
                        }
                        else {
                            control.removeAttribute('aria-describedby');
                        }
                    }
                }
            }
            const pendingCount = conflictDefinitions.filter(item => actual(item.path) === undefined).length;
            const pendingSummary = editor.querySelector('[data-conflict-pending]');
            if (pendingSummary) {
                pendingSummary.textContent = pendingCount;
                editor.querySelector('[data-conflict-chosen]').textContent = conflictDefinitions.length - pendingCount;
            }
            for (const card of editor.querySelectorAll('[data-conflict]')) {
                const path = JSON.parse(card.dataset.conflict);
                const status = conflictSelection(path, data, snapshot.data);
                const state = card.querySelector('.starbot-conflict-state');
                state.textContent = {pending:'待选择',draft:'已选择, 尚未保存',saved:'已保存'}[status];
                state.dataset.state = status;
                const n = fieldMetaAt(path);
                for (const option of card.querySelectorAll('[data-choice-index]')) {
                    const selected = actual(path) !== undefined && equal(actual(path), n.suggestions[Number(option.dataset.choiceIndex)].value);
                    option.classList.toggle('is-selected', selected);
                    option.querySelector('.starbot-conflict-choice-mark').textContent = selected ? '当前选择' : '';
                    const button = option.querySelector('[data-action="use-suggestion"]');
                    button.textContent = selected ? '已选择此方案' : '选择此方案';
                    button.setAttribute('aria-pressed', String(selected));
                }
            }
            const summary = root.querySelector('#starbot-validation');
            if (summary) {
                summary.innerHTML = displayErrors.length ? '<div class="starbot-validation-box" role="alert"><strong>有 ' + displayErrors.length + ' 项配置需要处理</strong>' + displayErrors.map((e, i) => '<button type="button" data-error-index="' + i + '">' + esc(pathText(e.path)) + ': ' + esc(e.message) + '</button>').join('') + '</div>' : '';
            }
            const pending = pendingLocations(snapshot.pendingPaths, locations);
            for (const moduleMeta of meta) {
                const tab = document.getElementById('starbot-tab-' + moduleMeta.id), count = mergeIssues(errors.filter(error => error.tab === moduleMeta.id), [], snapshot.index).length + (moduleMeta.id === 'conflicts' ? conflictDefinitions.filter(item => get(contextData, item.path) === undefined).length + (source.diagnostics || []).filter(item => item.blocking).length : 0);
                const marker = tab.querySelector('.starbot-pending-icon');
                if (pending.tabs.has(moduleMeta.id) && !marker) {
                    tab.insertAdjacentHTML('beforeend', pendingIcon);
                }
                else if (!pending.tabs.has(moduleMeta.id)) {
                    marker?.remove();
                }
                let badge = tab.querySelector('.starbot-tab-count');
                if (count) {
                    if (!badge) {
                        badge = document.createElement('span');
                        badge.className = 'starbot-tab-count';
                        tab.appendChild(badge);
                    }
                    badge.textContent = count;
                }
                else {
                    badge?.remove();
                }
            }
            for (const group of side.querySelectorAll('[data-section]')) {
                const conflictGroup = active === 'conflicts' ? conflictGroups.find(item => item.id === group.dataset.section) : null;
                const count = mergeIssues(errors.filter(error => error.tab === active && error.group === group.dataset.section), [], snapshot.index).length + (conflictGroup ? conflictGroup.conflicts.filter(item => actual(item.path) === undefined).length + (conflictGroup.issues || []).length : 0);
                const hasPending = pending.groups.has(pid([active, group.dataset.section]));
                const marker = group.querySelector('.starbot-pending-icon');
                if (hasPending && !marker) {
                    group.insertAdjacentHTML('beforeend', pendingIcon);
                }
                else if (!hasPending) {
                    marker?.remove();
                }
                let badge = group.querySelector('.starbot-group-count');
                if (count) {
                    if (!badge) {
                        badge = document.createElement('span');
                        badge.className = 'starbot-group-count';
                        group.appendChild(badge);
                    }
                    badge.textContent = count;
                }
                else {
                    badge?.remove();
                }
            }
            root.querySelector('#starbot-error-total').textContent = displayErrors.length ? ' - ' + displayErrors.length : '';
        }
        window.addEventListener('config-regex-result', () => {
            const focused = document.activeElement;
            const path = focused?.dataset?.edit;
            const selection = focused?.selectionStart;
            render();
            if (path) {
                const replacement = [...root.querySelectorAll('[data-edit]')].find(input => input.dataset.edit === path);
                replacement?.focus();
                if (selection !== null && selection !== undefined && replacement?.type === 'text') {
                    replacement.setSelectionRange(selection, selection);
                }
            }
        });
        function fieldMetaAt(p) {
            const conflict = conflictDefinitions.find(item => item.typeConflict && prefix(item.path, p));
            if (conflict) {
                return equal(conflict.path, p) ? conflict.field : {type:'any', typeConflict:true, configPath:p};
            }
            function search(n, np) {
                if (equal(np, p)) {
                    return n;
                }
                if (n.type === 'object') {
                    for (const f of n.fields || []) {
                        const a = search(f, fieldPath(f, np));
                        if (a) {
                            return a;
                        }
                    }
                }
                if (n.type === 'map' && prefix(np, p) && p.length > np.length && !own(n.mapEntries, p[np.length])) {
                    return search(n.values, np.concat(p[np.length]));
                }
                if (n.type === 'array' && prefix(np, p) && p.length > np.length) {
                    return search(n.items, np.concat(p[np.length]));
                }
            }
            for (const moduleMeta of [...meta].sort((a, b) => Number(b.id === active) - Number(a.id === active)))
                for (const g of [...(moduleMeta.groups || [])].sort((a, b) => Number(b.id === section) - Number(a.id === section)))
                    for (const n of g.fields) {
                        const a = search(n, fieldPath(n, groupPath(moduleMeta, g)));
                        if (a) {
                            return a;
                        }
                    }
            return { type: 'any' };
        }
        function initial(n) {
            const ts = Array.isArray(n.type) ? n.type : [n.type];
            if (defaultValue(n) !== undefined) {
                return clone(defaultValue(n));
            }
            const t = ts[0];
            return t === 'object' || t === 'map' ? {} : t === 'array' ? [] : t === 'boolean' ? false : t === 'integer' || t === 'number' ? 0 : t === 'null' ? null : '';
        }
        function edit(el, rerender = false) {
            if (!el.dataset.edit) {
                return;
            }
            const p = JSON.parse(el.dataset.edit);
            if (el.dataset.rename) {
                const parentPath = p.slice(0, -1), draftParent = actual(parentPath);
                const old = p.at(-1), k = el.value;
                const parent = el.dataset.rename === 'map' ? value(fieldMetaAt(parentPath), parentPath) : draftParent;
                if (!own(parent, old)) {
                    toast('请通过编辑 JSON 修改该条目名称');
                    render();
                    return;
                }
                if (k === String(old)) {
                    return;
                }
                if (!k) {
                    toast('名称不能为空');
                    render();
                    return;
                }
                if (own(fieldMetaAt(parentPath).mapEntries, k)) {
                    toast('这个属性已有独立表单入口, 请直接编辑该配置项');
                    render();
                    return;
                }
                const np = parentPath.concat(k);
                if (active === 'custom' && !['starbot', 'advanced'].includes(np[0])) {
                    toast('自定义配置只能放在 starbot 或 advanced 下');
                    render();
                    return;
                }
                if (own(parent, k)) {
                    toast('这个名称已经存在');
                    render();
                    return;
                }
                if (active === 'custom') {
                    const before = clone(data);
                    delete get(before, parentPath)[old];
                    const conflict = customConfigConflict(before, np, claims, fieldMetaAt);
                    if (conflict) {
                        toast(conflict);
                        render();
                        return;
                    }
                }
                const outerCustom = active === 'custom' && unclaimed().some(entry => equal(entry.p, p));
                const original = outerCustom ? customRenames.find(rename => equal(rename.to, p))?.from || p : null;
                const oldValue = own(draftParent, old) ? draftParent[old] : parent[old];
                if (own(draftParent, old)) {
                    pendingMoves.push({ op: 'move', from: p.slice(), path: np.slice() });
                }
                set(np, oldValue);
                set(p, undefined);
                restoreCollectionDefault(parentPath, fieldMetaAt(parentPath));
                if (original && !equal(original, np) && get(snapshot.activeData || {}, original) !== undefined) {
                    customRenames = customRenames.filter(rename => !equal(rename.from, original) && !equal(rename.to, np));
                    customRenames.push({from: original, to: np});
                }
                opened.add(pid(np));
                render();
                return;
            }
            let v, durationError;
            if (el.type === 'checkbox') {
                v = el.checked;
            }
            else if (el.dataset.options) {
                if (el.value === '') {
                    return;
                }
                const opts = JSON.parse(el.dataset.options);
                v = opts[Number(el.value)].value;
            }
            else if (el.dataset.duration) {
                const wrap = el.closest('.starbot-duration');
                const amount = wrap.querySelector('[data-duration="amount"]').value;
                const unit = wrap.querySelector('[data-duration="unit"]').value;
                if (el.dataset.duration === 'unit' && !amount.trim()) {
                    return;
                }
                if (amount.trim()) {
                    v = isSpringPlaceholder(amount, p, springPaths) ? amount : durationInputValue(amount, unit);
                    if (v === null) {
                        v = amount;
                        durationError = '请输入有效时长';
                    }
                }
                else {
                    const parent = fieldMetaAt(p.slice(0, -1));
                    if (typeof p.at(-1) === 'number' || parent.type === 'map' && !own(parent.mapEntries, p.at(-1))) {
                        v = '';
                        durationError = '请填写时长，或删除此条目';
                    }
                }
            }
            else if (el.dataset.number || el.type === 'range') {
                v = numberInputValue(el.value, el.dataset.number);
                if (v === undefined) {
                    const parent = fieldMetaAt(p.slice(0, -1));
                    if (typeof p.at(-1) === 'number' || parent.type === 'map' && !own(parent.mapEntries, p.at(-1))
                            || el.closest('.starbot-tree-row')) {
                        v = el.value;
                    }
                }
            }
            else {
                v = el.value;
                if (v === '' && equal(p, source.accessPasswordPath) && snapshot.accessPassword?.stored) {
                    v = get(baseline, p);
                }
                if (v === '' && fieldMetaAt(p).ui?.emptyAsDefault) {
                    v = undefined;
                }
            }
            set(p, normalizeEmptyString(v, fieldMetaAt(p)));
            const parentPath = p.slice(0, -1);
            restoreCollectionDefault(parentPath, fieldMetaAt(parentPath));
            if (durationError) {
                numericErrors.set(pid(p), { path: p, message: durationError, tab: active, group: section });
            }
            if ((el.dataset.number || el.type === 'range') && typeof v === 'string' && !isSpringPlaceholder(v, p, springPaths)) {
                const integer = el.dataset.number === 'integer';
                let message;
                if (!el.value.trim()) {
                    message = '请填写' + (integer ? '整数' : '数字') + ', 或删除此条目';
                }
                else if (integer && Number.isInteger(Number(el.value))) {
                    message = '数字超出支持范围, 请输入 -9007199254740991 到 9007199254740991 之间的整数';
                }
                else {
                    message = integer ? '请填写整数' : '请填写完整的数字, 例如 10 或 1.5';
                }
                numericErrors.set(pid(p), { path: p, message, tab: active, group: section });
                if (fieldMetaAt(p).type === 'any') {
                    nodeTypes.set(pid(p), el.dataset.number || 'number');
                }
            }
            if (el.type === 'range') {
                const out = el.parentElement.querySelector('output');
                if (out) {
                    out.textContent = String(v);
                }
            }
            const wrap = el.closest('.starbot-input-wrap');
            if (wrap?.querySelector('.starbot-color-input')) {
                const txt = wrap.querySelector('input[type=text]'), color = wrap.querySelector('input[type=color]');
                if (txt && el.type === 'color') {
                    txt.value = v;
                }
                if (color && typeof v === 'string' && /^#[\da-f]{6}$/i.test(v)) {
                    color.value = v;
                }
            }
            if (rerender) {
                const switchTransition = el.matches('.starbot-switch') ? window.ConfigControls.switchTransition(el) : null;
                const focused = el === document.activeElement;
                render();
                if (switchTransition) {
                    const next = editor.querySelector('.starbot-switch[data-edit="' + CSS.escape(el.dataset.edit) + '"]');
                    if (next) {
                        switchTransition(next);
                        if (focused) {
                            next.focus({preventScroll:true});
                        }
                    }
                }
            }
            else {
                refresh();
            }
        }
        root.addEventListener('submit', e => {
            const form = e.target;
            if (!form.matches('[data-new-map]')) {
                return;
            }
            e.preventDefault();
            const p = JSON.parse(form.dataset.newMap), n = fieldMetaAt(p), keyInput = form.elements.namedItem('key'), key = keyInput.value;
            const label = String(n.keyMeta?.label ?? '').trim() || '条目名称';
            const message = !key.trim() ? '请输入' + label
                : own(n.mapEntries, key) ? '这个属性已有独立表单入口, 请直接编辑该配置项'
                    : own(value(n, p), key) ? label + '已存在' : '';
            if (message) {
                form.querySelector('.starbot-error').textContent = message;
                keyInput.setAttribute('aria-invalid', 'true');
                keyInput.focus();
                return;
            }
            const entry = p.concat(key);
            set(entry, initial(n.values));
            opened.add(pid(entry));
            render();
        });
        root.addEventListener('input', e => {
            const mapForm = e.target.closest('[data-new-map]');
            if (mapForm) {
                mapForm.querySelector('.starbot-error').textContent = '';
                e.target.removeAttribute('aria-invalid');
                return;
            }
            if (e.target.id === 'starbot-raw') {
                rawDraft = snapshot.raw.includes('\r\n') ? e.target.value.replace(/\r?\n/g,'\r\n') : e.target.value;
                serverErrors = serverErrors.filter(isDuplicateSpringIssue);
                rawIssue = '';
                refresh();
                renderMeta();
                return;
            }
            if (e.target.dataset.rename) {
                return;
            }
            if (e.target.dataset.edit && e.target.tagName !== 'SELECT' && e.target.type !== 'checkbox') {
                edit(e.target);
            }
        });
        root.addEventListener('change', async e => {
            const el = e.target;
            if (el.dataset.treeType) {
                const p = JSON.parse(el.dataset.treeType), t = el.value;
                clearTreeTypes(p);
                nodeTypes.set(pid(p), t);
                set(p, t === 'object' ? {} : t === 'array' ? [] : t === 'boolean' ? false : t === 'null' ? null : t === 'number' || t === 'integer' ? 0 : '');
                render();
                return;
            }
            if (!el.dataset.edit) {
                return;
            }
            if (el.matches('.starbot-switch')) {
                const checked = el.checked;
                const ui = fieldMetaAt(JSON.parse(el.dataset.edit)).ui;
                const confirmation = checked ? ui?.confirmOnEnable : ui?.confirmOnDisable;
                if (confirmation) {
                    el.checked = !checked;
                    const confirmed = await confirmAction(confirmation, checked ? '确认开启' : '确认关闭', checked ? '开启' : '关闭');
                    if (!confirmed || !el.isConnected) {
                        return;
                    }
                    el.checked = checked;
                }
            }
            edit(el, !!el.dataset.rename || el.tagName === 'SELECT' && !el.dataset.duration || el.type === 'checkbox');
        });
        root.addEventListener('focusout', e => {
            const el = e.target;
            if (el.dataset.rename) {
                edit(el, true);
                return;
            }
            if (!el.dataset.edit || !el.matches('input[type="text"], textarea') || el.hasAttribute('data-secret') || el.dataset.duration
                    || (el.dataset.number ? el.value.trim() !== '' : el.value !== '')) {
                return;
            }
            const p = JSON.parse(el.dataset.edit), n = fieldMetaAt(p);
            if ((el.dataset.number || n.ui?.emptyAsDefault) && !numericErrors.has(pid(p))) {
                const effective = value(n, p);
                if (typeof effective === 'string' || typeof effective === 'number') {
                    el.value = String(effective);
                    if (el.dataset.number) {
                        toast((n.label || '数值') + ' 格式不正确，已恢复默认值 ' + effective);
                    }
                }
            }
        });
        function rememberNodeState(e) {
            const {open, expanded} = e.target.dataset;
            if (open && expanded === 'true') {
                opened.add(open);
            }
            if (open && expanded === 'false') {
                opened.delete(open);
            }
        }
        root.addEventListener('toggle', rememberNodeState, true);
        root.addEventListener('config-node-toggle', rememberNodeState);
        root.addEventListener('click', async (e) => {
            const tab = e.target.closest('[data-tab]');
            if (tab) {
                if (tab.dataset.tab === active) {
                    return;
                }
                active = tab.dataset.tab;
                section = currentMeta().groups?.[0]?.id || '';
                newKey = false;
                render(true);
                return;
            }
            const link = e.target.closest('[data-section]');
            if (link) {
                if (link.dataset.section === section) {
                    return;
                }
                section = link.dataset.section;
                render(true);
                return;
            }
            const err = e.target.closest('[data-error-index]');
            if (err) {
                const issue = displayErrors[Number(err.dataset.errorIndex)];
                active = issue.tab || 'custom';
                section = issue.group || '';
                for (let i = 1; i <= issue.path.length; i++)
                    opened.add(pid(issue.path.slice(0, i)));
                render(true);
                const key = CSS.escape(pid(issue.path));
                editor.querySelector('[data-field="' + key + '"], [data-tree-type="' + key + '"]')
                    ?.scrollIntoView({block:'nearest'});
                return;
            }
            const b = e.target.closest('[data-action]');
            if (!b) {
                return;
            }
            const a = b.dataset.action, p = b.dataset.path ? JSON.parse(b.dataset.path) : [];
            if (b.closest('summary')) {
                e.preventDefault();
            }
            if (a === 'save') {
                await saveDocument();
                return;
            }
            if (a === 'execute-config') {
                await executeConfigAction(p);
                return;
            }
            if (a === 'reveal') {
                const el = b.parentElement.querySelector('input');
                window.ConfigControls.toggleSecret(el);
                return;
            }
            if (a === 'reload') {
                if (isDirty() && !await confirmAction('重新读取会放弃当前未保存的修改', '重新读取配置', '重新读取')) {
                    return;
                }
                await loadDocument();
                return;
            }
            if (a === 'raw-mode') {
                await switchEditor();
                return;
            }
            if (a === 'open-conflicts' || a === 'open-meta-errors') {
                active = 'conflicts';
                section = a === 'open-meta-errors' ? 'meta-errors'
                    : conflictGroups.find(group => group.conflicts.some(item => equal(item.path, p)))?.id || currentMeta().groups[0]?.id || '';
                render(true);
                return;
            }
            if (a === 'custom-conflict') {
                if (fieldMetaAt(p).typeConflict && !await confirmAction('插件间建议的数据类型不一致, 其他插件可能无法兼容', '确认配置选择', '仍然选择')) {
                    return;
                }
                customConflicts.add(pid(p));
                if (actual(p) === undefined) {
                    const n = fieldMetaAt(p);
                    set(p, initial({...n, suggestionConflict:false, effectiveDefault:undefined}));
                }
            }
            if (a === 'use-suggestion') {
                if (fieldMetaAt(p).typeConflict && !await confirmAction('插件间建议的数据类型不一致, 其他插件可能无法兼容', '确认配置选择', '仍然选择')) {
                    return;
                }
                customConflicts.delete(pid(p));
                const n = fieldMetaAt(p);
                set(p, clone(normalizeString(n.suggestions[Number(b.dataset.suggestionIndex)].value, n.type)));
            }
            if (a === 'reset-default') {
                set(p, clone(fieldMetaAt(p).default));
            }
            if (a === 'restore-active') {
                const rename = active === 'custom' && activeCustomRenames().find(link => equal(link.from, p) || equal(link.to, p));
                const path = rename ? rename.from : p;
                const field = fieldMetaAt(path);
                let restored = clone(active === 'custom' ? get(snapshot.activeData || {}, path) : activeValue(field, path));
                if (field.mapEntries && !rename) {
                    const fixed = Object.fromEntries(Object.entries(actual(path) || {}).filter(([key]) => own(field.mapEntries, key)));
                    const entries = {...fixed, ...mapExtras(field, restored)};
                    restored = Object.keys(entries).length ? entries : undefined;
                }
                const affected = rename ? [rename.from, rename.to] : [path];
                pendingMoves = pendingMoves.filter(move => !affected.some(key => prefix(key, move.from) || prefix(key, move.path)));
                if (rename) {
                    set(rename.to, undefined);
                }
                if (restored !== undefined) {
                    clearTreeTypes(path);
                }
                set(path, restored);
            }
            if (a === 'literal') {
                set(p, initial({ ...fieldMetaAt(p), default: undefined }));
            }
            if (a === 'null-value') {
                b.focus({preventScroll:true});
                set(p, null);
            }
            if (a === 'delete') {
                const parentPath = p.slice(0, -1);
                let parent = actual(parentPath);
                const fieldMeta = fieldMetaAt(parentPath), reference = value(fieldMeta, parentPath);
                if (Array.isArray(reference) && !Array.isArray(parent)
                        || fieldMeta.type === 'map' && parent === undefined && own(reference, p.at(-1))) {
                    parent = clone(reference);
                    set(parentPath, parent);
                }
                if (!own(parent, p.at(-1))) {
                    toast('请通过编辑 JSON 删除该条目');
                    return;
                }
                if (Array.isArray(parent)) {
                    removeArrayItem(parentPath, parent, Number(p.at(-1)));
                }
                else {
                    set(p, undefined);
                }
                restoreCollectionDefault(parentPath, fieldMeta, true);
            }
            if (a === 'move-up' || a === 'move-down') {
                const parentPath = p.slice(0, -1), field = fieldMetaAt(parentPath), current = value(field, parentPath);
                const index = p.at(-1), target = index + (a === 'move-up' ? -1 : 1);
                if (!Array.isArray(current) || !own(current, index) || !own(current, target)) {
                    return;
                }
                const list = clone(current);
                [list[index], list[target]] = [list[target], list[index]];
                pendingMoves = pendingMoves.filter(move => !prefix(parentPath, move.from));

                function movedPath(path) {
                    const position = path[parentPath.length];
                    return prefix(parentPath, path) && (position === index || position === target)
                        ? parentPath.concat(position === index ? target : index, path.slice(parentPath.length + 1)) : path;
                }
                const expanded = [...opened].map(key => pid(movedPath(JSON.parse(key))));
                const types = [...nodeTypes].map(([key, type]) => [pid(movedPath(JSON.parse(key))), type]);
                const issues = [...numericErrors.values()].filter(issue => prefix(parentPath, issue.path));
                set(parentPath, list);
                restoreCollectionDefault(parentPath, field);
                opened.clear();
                expanded.forEach(key => opened.add(key));
                nodeTypes.clear();
                types.forEach(([key, type]) => nodeTypes.set(key, type));
                for (const issue of issues) {
                    const path = movedPath(issue.path);
                    numericErrors.set(pid(path), {...issue, path});
                }
                render();
                const key = CSS.escape(pid(parentPath.concat(target)));
                const next = editor.querySelector('[data-action="' + a + '"][data-path="' + key + '"]:enabled')
                    || editor.querySelector('[data-action^="move-"][data-path="' + key + '"]:enabled');
                next?.focus({preventScroll:true});
                return;
            }
            if (a === 'add-array') {
                const n = fieldMetaAt(p), list = clone(value(n, p) || []);
                list.push(initial(n.items));
                set(p, list);
                opened.add(pid(p.concat(list.length - 1)));
            }
            if (a === 'add-map') {
                const form = editor.querySelector('[data-new-map="' + CSS.escape(pid(p)) + '"]');
                form.hidden = false;
                form.elements.namedItem('key').focus();
                return;
            }
            if (a === 'cancel-map-entry') {
                const form = b.closest('[data-new-map]');
                form.reset();
                form.querySelector('.starbot-error').textContent = '';
                form.elements.namedItem('key').removeAttribute('aria-invalid');
                form.hidden = true;
                editor.querySelector('[data-action="add-map"][data-path="' + CSS.escape(pid(p)) + '"]')?.focus();
                return;
            }
            if (a === 'add-tree') {
                const v = actual(p);
                if (Array.isArray(v)) {
                    v.push('');
                }
                else {
                    let i = 1;
                    while (own(v, 'key-' + i))
                        i++;
                    set(p.concat('key-' + i), '');
                }
            }
            if (a === 'new-key') {
                newKey = true;
                render();
                return;
            }
            if (a === 'cancel-key') {
                newKey = false;
                render();
                return;
            }
            if (a === 'confirm-key') {
                let np;
                try {
                    np = customConfigPath(root.querySelector('#starbot-new-path').value);
                }
                catch (error) {
                    toast(error.message, 'error');
                    return;
                }
                const conflict = customConfigConflict(data, np, claims, fieldMetaAt);
                if (conflict) {
                    toast(conflict, 'error');
                    return;
                }
                const t = root.querySelector('#starbot-new-type').value;
                set(np, t === 'object' ? {} : t === 'array' ? [] : t === 'boolean' ? false : t === 'null' ? null : t === 'integer' || t === 'number' ? 0 : '');
                newKey = false;
                nodeTypes.set(pid(np), t);
            }
            render();
        });
        function isDirty() {
            return rawMode ? rawDraft !== snapshot.raw : (formBaseRaw !== null && formBaseRaw !== snapshot.raw) || !equal(data, baseline);
        }
        function changes(before, after, path = []) {
            if (equal(before, after)) {
                return [];
            }
            if (before && after && typeof before === 'object' && typeof after === 'object' && !Array.isArray(before) && !Array.isArray(after)) {
                return [...new Set([...Object.keys(before), ...Object.keys(after)])].flatMap(key => {
                    if (!own(after, key)) {
                        return [{ op: 'unset', path: path.concat(key) }];
                    }
                    if (!own(before, key)) {
                        return [{ op: 'set', path: path.concat(key), value: after[key] }];
                    }
                    return changes(before[key], after[key], path.concat(key));
                });
            }
            return [{ op: 'set', path, value: after }];
        }
        function payload() {
            if (rawMode) {
                return { document: snapshot.index, raw: rawDraft, customRenames: activeCustomRenames(actionData) };
            }
            const base = { document: snapshot.index, customRenames: activeCustomRenames(), ...(formBaseRaw === null ? {} : { baseRaw: formBaseRaw }) };
            if (equal(baseline, data)) {
                return { ...base, changes: [] };
            }

            const before = clone(baseline), operations = [];
            for (const move of pendingMoves) {
                const parent = get(before, move.from.slice(0, -1)), oldKey = move.from.at(-1), newKey = move.path.at(-1);
                if (!own(parent, oldKey) || Array.isArray(parent)) {
                    continue;
                }
                if (own(parent, newKey)) {
                    operations.push({ op: 'unset', path: move.path });
                    delete parent[newKey];
                }
                Object.defineProperty(parent, newKey, { value: parent[oldKey], enumerable: true, writable: true, configurable: true });
                delete parent[oldKey];
                operations.push(move);
            }
            return { ...base, changes: operations.concat(changes(before, data)) };
        }
        function renderMeta() {
            const modeButton = root.querySelector('[data-action="raw-mode"]');
            modeButton.textContent = rawMode ? '返回表单' : '编辑 JSON';
            modeButton.setAttribute('aria-pressed', String(rawMode));
            root.querySelector('#starbot-diagnostics').innerHTML = (source.diagnostics || []).filter(d => d.severity !== 'info' && !d.blocking)
                .map(d => '<div class="starbot-diagnostic">' + (d.sources
                    ? '<strong>' + esc(d.title) + '</strong><p>' + esc(d.message) + '</p>' + renderDiagnosticSources(d.sources)
                    : esc(d.source) + ' - ' + esc(pathText(d.path)) + ': ' + esc(d.message)) + '</div>').join('');
            if (snapshot.pendingPaths?.length) {
                root.querySelector('#starbot-diagnostics').innerHTML += '<div class="starbot-pending-notice" role="status">' + restartBadge([]) + '<span>有 ' + snapshot.pendingPaths.length + ' 项已保存配置等待重启生效</span></div>';
            }
            if (rawMode && rawIssue) {
                root.querySelector('#starbot-diagnostics').innerHTML += '<div class="starbot-diagnostic starbot-blocking" role="alert">' + esc(rawIssue) + '</div>';
            }
            if (snapshot.unsafeNumbers) {
                root.querySelector('#starbot-diagnostics').innerHTML += '<div class="starbot-diagnostic">文件包含超出浏览器精度的整数. 涉及这些值的修改请使用原文编辑, 其他字段可正常修改</div>';
            }
            if (rawMode && displayErrors.length) {
                root.querySelector('#starbot-diagnostics').innerHTML += '<div class="starbot-diagnostic starbot-blocking">' + displayErrors.map(error => (snapshot.count > 1 && error.document !== undefined ? '第 ' + (error.document + 1) + ' 段 - ' : '') + esc(error.path.join('.')) + ': ' + esc(error.message)).join('<br>') + '</div>';
            }
            root.querySelector('[data-action="save"]').disabled = busy || !!snapshot.readOnly;
            root.querySelector('[data-action="reload"]').disabled = busy;
            modeButton.disabled = busy || !!snapshot.readOnly;
        }
        async function switchEditor() {
            if (busy || snapshot.readOnly) {
                return;
            }
            if (!rawMode && numericErrors.size) {
                refresh();
                toast('请先修正无效的数字输入', 'error');
                return;
            }
            busy = true;
            root.inert = true;
            renderMeta();
            try {
                const result = await request('api/preview', payload());
                if (rawMode) {
                    const draft = result.draft;
                    if (draft.count !== 1) {
                        const message = '此文件包含多个 JSON 配置段, 请继续编辑 JSON, 暂时无法用表单表示';
                        rawIssue = message;
                        serverErrors = [];
                        render();
                        toast(message, 'error');
                        return;
                    }
                    formBaseRaw = result.json;
                    data = clone(draft.data);
                    baseline = clone(data);
                    pendingMoves = [];
                    customRenames = activeCustomRenames();
                    nodeTypes.clear();
                    snapshot = { ...snapshot, error: undefined, readOnly: draft.readOnly, unsafeNumbers: draft.unsafeNumbers,
                        index: draft.index, count: draft.count, inherited: draft.inherited, accessPassword: draft.accessPassword };
                    rawMode = false;
                }
                else {
                    rawDraft = result.json;
                    rawMode = true;
                }
                rawIssue = '';
                serverErrors = result.errors || [];
                render(true);
            }
            catch (error) {
                rawIssue = rawMode ? error.message : '';
                serverErrors = error.details?.errors || [];
                render();
                toast(error.message, 'error');
            }
            finally {
                busy = false;
                root.inert = false;
                renderMeta();
            }
        }
        async function loadDocument() {
            if (busy) {
                return;
            }
            busy = true;
            root.inert = true;
            try {
                snapshot = await request('api/document');
                data = clone(snapshot.data);
                baseline = clone(data);
                formBaseRaw = null;
                pendingMoves = [];
                customRenames = snapshot.customRenames || [];
                numericErrors.clear();
                nodeTypes.clear();
                rawDraft = snapshot.raw;
                rawIssue = snapshot.error || '';
                rawMode = !!snapshot.error || !!snapshot.readOnly || snapshot.count > 1;
                serverErrors = snapshot.errors || [];
                render();
            }
            catch (error) {
                toast(error.message);
            }
            finally {
                busy = false;
                root.inert = false;
                renderMeta();
            }
        }
        async function saveDocument() {
            if (busy) {
                return;
            }

            validate();
            if (errors.length || rawMode && rawIssue) {
                refresh();
                renderMeta();
                toast(rawIssue || '有 ' + displayErrors.length + ' 项配置需要修正, 请查看下方标红的内容', 'error');
                return;
            }

            busy = true;
            root.inert = true;
            renderMeta();
            try {
                const result = await request('api/save', payload());

                serverErrors = result.errors || [];
                rawIssue = '';
                snapshot = result.document;
                data = clone(snapshot.data);
                baseline = clone(data);
                formBaseRaw = null;
                pendingMoves = [];
                customRenames = snapshot.customRenames || [];
                rawDraft = snapshot.raw;
                serverErrors = [];
                render();
                if (errors.length) {
                    toast('有 ' + displayErrors.length + ' 项配置需要修正, 请查看下方标红的内容', 'error');
                }
                else {
                    toast(result.unchanged ? '配置没有变化' : result.restartRequired ? '配置已保存, 部分修改将在重启后生效' : '配置已保存', 'success');
                }
            }
            catch (error) {
                serverErrors = error.details?.errors || [];
                rawIssue = rawMode && !serverErrors.length ? error.message : '';
                render();
                toast(errors.length ? '有 ' + displayErrors.length + ' 项配置需要修正, 请查看下方标红的内容' : error.message, 'error');
            }
            finally {
                busy = false;
                root.inert = false;
                renderMeta();
            }
        }
        window.addEventListener('beforeunload', event => {
            if (isDirty()) {
                event.preventDefault();
                event.returnValue = '';
            }
        });
        render();
    }
})();
