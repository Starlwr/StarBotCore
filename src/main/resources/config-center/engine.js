'use strict';
const own = (o, k) => o !== null && typeof o === 'object' && Object.prototype.hasOwnProperty.call(o, k);
const clone = o => o === undefined ? undefined : JSON.parse(JSON.stringify(o));
const normalizeString = (v, type) => {
    const types = Array.isArray(type) ? type : [type];
    return typeof v === 'number' && Number.isFinite(v) && types.includes('string') && !types.includes('any') && !types.includes('number') && !(types.includes('integer') && Number.isInteger(v)) ? String(v) : v;
};
function numberInputValue(text, type) {
    const trimmed = text.trim();
    if (!trimmed) {
        return undefined;
    }
    const value = Number(trimmed);
    return /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(trimmed) && Number.isFinite(value)
        && (type !== 'integer' || Number.isSafeInteger(value)) ? value : text;
}
function selectActionConfiguration(document, parameters) {
    const result = {};
    for (const selector of Object.values(parameters || {})) {
        const path = selector.springProperties === true ? ['advanced'] : selector.path;
        let current = document;
        for (const part of path) {
            if (current === undefined) {
                break;
            }
            if (current === null || typeof current !== 'object' || Array.isArray(current)) {
                throw new Error('配置路径的上级需要是对象, 请检查相关配置');
            }
            current = own(current, part) ? current[part] : undefined;
        }
        if (current === undefined) {
            continue;
        }
        if (selector.keys || selector.keyPrefix) {
            if (current === null || typeof current !== 'object' || Array.isArray(current)) {
                throw new Error('按键名筛选的配置需要是对象');
            }
            current = Object.fromEntries(Object.entries(current).filter(([key]) =>
                selector.keys ? selector.keys.includes(key) : key.startsWith(selector.keyPrefix)));
        }
        let parent = result;
        for (const part of path.slice(0, -1)) {
            if (!own(parent, part)) {
                Object.defineProperty(parent, part, {value:{}, writable:true, enumerable:true, configurable:true});
            }
            parent = parent[part];
        }
        const key = path.at(-1), copy = clone(current);
        if (own(parent, key) && parent[key] && copy && typeof parent[key] === 'object' && typeof copy === 'object'
            && !Array.isArray(parent[key]) && !Array.isArray(copy)) {
            for (const [name, item] of Object.entries(copy)) {
                Object.defineProperty(parent[key], name, {value:item, writable:true, enumerable:true, configurable:true});
            }
        } else {
            Object.defineProperty(parent, key, {value:copy, writable:true, enumerable:true, configurable:true});
        }
    }
    return result;
}
function customConfigPath(text) {
    const parts = text.trim().split('.').map(part => part.trim());
    if (parts.some(part => !part) || (parts.length === 1 && ['starbot', 'advanced'].includes(parts[0]))) {
        throw new Error('请填写配置路径, 层级名称不能为空');
    }
    if (parts[0] === 'starbot') {
        return parts;
    }
    return ['advanced', (parts[0] === 'advanced' ? parts.slice(1) : parts).join('.')];
}

function customConfigConflict(document, path, claims, fieldMetaAt) {
    const starts = (parent, child) => parent.length <= child.length && parent.every((key, index) => key === child[index]);
    const comparable = p => p[0] === 'advanced' && p.length > 1
        ? ['advanced', ...(p[1].match(/\[[^\]]+]|[^.\[\]]+/g) || []),
            ...p.slice(2).map(part => typeof part === 'string' && /^[a-z0-9-]+$/.test(part) ? part : '[' + part + ']')] : p;
    const target = comparable(path);
    const claim = claims.find(claim => starts(comparable(claim), target) || starts(target, comparable(claim)));
    if (claim) {
        const parent = comparable(claim);
        if (parent.length < target.length && starts(parent, target)) {
            const type = fieldMetaAt(claim).type;
            const types = Array.isArray(type) ? type : [type];
            if (types.every(type => ['string', 'integer', 'number', 'boolean', 'null'].includes(type))) {
                const name = claim[0] === 'advanced' ? claim[1] : claim.join('.');
                return '"' + name + '" 只能设置一个值, 不能添加子配置';
            }
        }
        return '这个配置已有表单入口, 请在对应模块中修改';
    }
    if (path[0] === 'advanced' && Object.keys(document.advanced || {}).some(key => {
        if (path.length > 2 && key === path[1]) {
            return false;
        }
        const existing = comparable(['advanced', key]);
        return starts(existing, target) || starts(target, existing);
    })) {
        return '这个高级配置已存在, 或与已有配置的层级重叠';
    }
    let current = document;
    for (let index = 0; index < path.length; index++) {
        if (!current || typeof current !== 'object' || (Array.isArray(current) && !Number.isInteger(path[index]))) {
            return '父级路径不是对象, 不能添加子配置';
        }
        if (!own(current, path[index])) {
            return '';
        }
        current = current[path[index]];
    }
    return '这个配置已存在, 请直接编辑已有配置';
}
const get = (o, p) => p.reduce((v, k) => own(v, k) ? v[k] : undefined, o);
const groupPath = (moduleMeta, group) => group.configPath || moduleMeta.basePath.concat(group.path);
const fieldPath = (field, parent) => field.configPath || parent.concat(field.key);
const pendingAt = (paths, path) => (paths || []).some(changed => changed.every((key, index) => path[index] === key)
    || path.every((key, index) => changed[index] === key));
function pendingLocations(paths, locations) {
    const tabs = new Set(), groups = new Set();
    for (const path of paths || []) {
        const matches = locations.filter(location => pendingAt([path], location.path));
        if (!matches.length) {
            tabs.add('custom');
        }
        for (const location of matches) {
            tabs.add(location.tab);
            groups.add(JSON.stringify([location.tab, location.group]));
        }
    }
    return { tabs, groups };
}
const defaultValue = field => field.suggestionConflict || field.conflicted ? undefined : own(field, 'effectiveDefault') ? field.effectiveDefault : field.configPath?.[0] === 'advanced' ? undefined : field.default;
const normalizeEmptyString = (value, field) => value === '' && field.emptyAsNull ? null : value;
function valueAt(document, field, path, fieldMetaAt) {
    let value = document;
    for (let i = 0; i < path.length; i++) {
        value = get(value, [path[i]]);
        if (value === undefined) {
            const definition = i === path.length - 1 ? field : fieldMetaAt(path.slice(0, i + 1));
            value = clone(defaultValue(definition));
        }
    }
    return normalizeString(normalizeEmptyString(value === '' && field.ui?.emptyAsDefault ? clone(defaultValue(field)) : value, field), field.type);
}
const empty = v => v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0) || !!(v && typeof v === 'object' && Object.keys(v).length === 0);
const equal = (a, b) => {
    if (a === b) {
        return true;
    }
    if (a === null || b === null || typeof a !== typeof b) {
        return false;
    }
    if (Array.isArray(a) !== Array.isArray(b)) {
        return false;
    }
    if (Array.isArray(a)) {
        return Array.isArray(b) && a.length === b.length && a.every((v, i) => equal(v, b[i]));
    }
    if (a && typeof a === 'object') {
        return Object.keys(a).length === Object.keys(b).length && Object.keys(a).every(k => own(b, k) && equal(a[k], b[k]));
    }
    return false;
};
const size = v => typeof v === 'string' ? Array.from(v).length : Array.isArray(v) ? v.length : v && typeof v === 'object' ? Object.keys(v).length : undefined;
function containsPlaceholder(value) {
    if (typeof value !== 'string') {
        return false;
    }
    let depth = 0, found = false;
    for (let index = 0; index < value.length; index++) {
        const character = value[index];
        if (value.startsWith('${', index) && value[index - 1] !== '\\') {
            const key = value[index + 2];
            if (key === undefined || '}: \t\n\r\f\v'.includes(key)) {
                return false;
            }
            depth++;
            index++;
        } else if (depth > 0 && character === '{') {
            depth++;
        } else if (depth > 0 && character === '}') {
            if (--depth === 0) {
                found = true;
            }
        }
    }
    return found && depth === 0;
}
function isSpringPlaceholder(value, path, springPaths) {
    return containsPlaceholder(value) && (path[0] === 'advanced'
        || springPaths.some(binding => binding.every((part, index) => part === path[index])));
}
function defersPlaceholder(value, context, scope, path) {
    const base = scope === 'parent' ? context.parentPath : scope === 'current' ? context.currentPath : [];
    return isSpringPlaceholder(value, (base || []).concat(path), context.springPaths || []);
}
function dynamicOptionValues(ref, context) {
    const scope = ref.scope || 'parent';
    const source = get(context[scope], ref.path);
    if (defersPlaceholder(source, context, scope, ref.path)) {
        return null;
    }
    return source && typeof source === 'object' ? (ref.source === 'values' ? Object.values(source) : Object.keys(source)) : [];
}
const durationUnits = {d:86400000000000n, h:3600000000000n, m:60000000000n, s:1000000000n, ms:1000000n, us:1000n, ns:1n};
const durationInputUnits = {d:'天', h:'小时', m:'分钟', s:'秒', ms:'毫秒'};
const simpleDurationPattern = /^([+-]?\d+)(ns|us|ms|s|m|h|d)?$/i;
const maxLong = (1n << 63n) - 1n;
const minLong = -maxLong - 1n;
const fitsLong = value => value >= minLong && value <= maxLong;
const fitsDuration = nanos => nanos >= minLong * durationUnits.s && nanos < (maxLong + 1n) * durationUnits.s;
function duration(value) {
    if (typeof value === 'number') {
        return Number.isInteger(value) && fitsLong(BigInt(value)) ? BigInt(value) * durationUnits.ms : null;
    }
    if (typeof value !== 'string') {
        return null;
    }
    const simple = simpleDurationPattern.exec(value);
    if (simple?.[0] === value) {
        const amount = BigInt(simple[1]), nanos = amount * durationUnits[simple[2]?.toLowerCase() || 'ms'];
        return fitsLong(amount) && fitsDuration(nanos) ? nanos : null;
    }

    const iso = /^([+-])?P(?:([+-]?\d+)D)?(T(?:([+-]?\d+)H)?(?:([+-]?\d+)M)?(?:([+-]?\d+)(?:[.,](\d{0,9}))?S)?)?$/i.exec(value);
    if (iso?.[0] !== value || iso[3]?.toUpperCase() === 'T' || ![iso[2], iso[4], iso[5], iso[6]].some(Boolean)) {
        return null;
    }
    const days = BigInt(iso[2] || 0) * 86400n, hours = BigInt(iso[4] || 0) * 3600n;
    const minutes = BigInt(iso[5] || 0) * 60n, seconds = BigInt(iso[6] || 0);
    const minuteSeconds = minutes + seconds, hourSeconds = hours + minuteSeconds, totalSeconds = days + hourSeconds;
    if (![days, hours, minutes, seconds, minuteSeconds, hourSeconds, totalSeconds].every(fitsLong)) {
        return null;
    }
    const fraction = BigInt((iso[7] || '').padEnd(9, '0'));
    const nanos = totalSeconds * durationUnits.s + (iso[6]?.startsWith('-') ? -fraction : fraction);
    const result = iso[1] === '-' ? -nanos : nanos;
    return fitsDuration(nanos) && fitsDuration(result) ? result : null;
}
function durationSeconds(nanos) {
    const absolute = nanos < 0n ? -nanos : nanos;
    const seconds = absolute / durationUnits.s;
    const fraction = String(absolute % durationUnits.s).padStart(9, '0').replace(/0+$/, '');
    return (nanos < 0n ? '-' : '') + seconds + (fraction ? '.' + fraction : '');
}
function durationParts(value) {
    const simple = typeof value === 'string' ? simpleDurationPattern.exec(value) : null;
    if (simple && simple[0] === value) {
        const unit = simple[2]?.toLowerCase() || 'ms';
        if (own(durationInputUnits, unit)) {
            return { amount: simple[1], unit };
        }
    }
    if (typeof value === 'number') {
        return { amount: String(value), unit: 'ms' };
    }
    const nanos = duration(value);
    if (nanos === null || nanos === 0n) {
        return { amount: nanos === 0n ? '0' : String(value ?? ''), unit: 's' };
    }
    for (const unit of Object.keys(durationInputUnits)) {
        const size = durationUnits[unit];
        if (nanos % size === 0n && fitsLong(nanos / size)) {
            return { amount: String(nanos / size), unit };
        }
    }
    return { amount: durationSeconds(nanos), unit: 's' };
}
function durationInputValue(text, unit) {
    const trimmed = text.trim();
    const parts = /^([+-]?)(\d*)(?:\.(\d*))?$/.exec(trimmed);
    if (!parts || !(parts[2] || parts[3]) || !own(durationInputUnits, unit) || trimmed.length > 40) {
        return null;
    }
    const fraction = parts[3] || '';
    const divisor = 10n ** BigInt(fraction.length);
    const amount = BigInt(parts[1] + (parts[2] || '0') + fraction);
    const nanos = amount * durationUnits[unit];
    if (nanos % divisor !== 0n || !fitsDuration(nanos / divisor)) {
        return null;
    }
    return fraction || !fitsLong(amount) ? 'PT' + durationSeconds(nanos / divisor) + 'S' : amount + unit;
}
const regexResults = new Map();
const regexQueue = [];
let regexWorker = null;
let regexBusy = false;
let regexTimer;
function regexMatch(value, pattern) {
    if (typeof value !== 'string' || typeof pattern !== 'string' || value.length > 4096 || pattern.length > 256) {
        return false;
    }
    const complex = (pattern.match(/(?<!\\)[*+?{]/g) || []).length > 1;
    if (!complex || typeof Worker === 'undefined') {
        try {
            return new RegExp(pattern, 'u').test(value);
        }
        catch {
            return false;
        }
    }
    const key = JSON.stringify([value, pattern]);
    if (regexResults.has(key)) {
        return regexResults.get(key);
    }
    regexResults.set(key, undefined);
    regexQueue.push({ key, value, pattern });
    nextRegex();
    return undefined;
}
function nextRegex() {
    if (regexBusy || !regexQueue.length) {
        return;
    }
    regexBusy = true;
    const item = regexQueue.shift();
    const finish = result => {
        clearTimeout(regexTimer);
        regexResults.set(item.key, result);
        if (regexResults.size > 2048) {
            regexResults.delete(regexResults.keys().next().value);
        }
        regexBusy = false;
        window.dispatchEvent(new Event('config-regex-result'));
        nextRegex();
    };
    if (!regexWorker) {
        regexWorker = new Worker('assets/regex-worker.js');
    }
    regexWorker.onmessage = event => finish(!!event.data);
    regexWorker.onerror = () => {
        regexWorker.terminate();
        regexWorker = null;
        finish(false);
    };
    regexTimer = setTimeout(() => {
        regexWorker.terminate();
        regexWorker = null;
        finish(false);
    }, 150);
    regexWorker.postMessage({ value: item.value, pattern: item.pattern });
}
function compare(a, op, b) {
    switch (op) {
        case '=': return equal(a, b);
        case '!=': return !equal(a, b);
        case '>': return typeof a === 'number' && typeof b === 'number' && a > b;
        case '>=': return typeof a === 'number' && typeof b === 'number' && a >= b;
        case '<': return typeof a === 'number' && typeof b === 'number' && a < b;
        case '<=': return typeof a === 'number' && typeof b === 'number' && a <= b;
        case 'in': return Array.isArray(b) && b.some(v => equal(a, v));
        case '!in': return Array.isArray(b) && !b.some(v => equal(a, v));
        case 'contains': return typeof a === 'string' && typeof b === 'string' ? a.includes(b) : Array.isArray(a) && a.some(v => equal(v, b));
        case '!contains': return !compare(a, 'contains', b);
        case 'hasKey': return own(a, b);
        case '!hasKey': return !own(a, b);
        case 'exists': return a !== undefined;
        case '!exists': return a === undefined;
        case 'empty': return empty(a);
        case '!empty': return !empty(a);
        case 'startsWith': return typeof a === 'string' && a.startsWith(b);
        case 'endsWith': return typeof a === 'string' && a.endsWith(b);
        case '!matches': return !compare(a, 'matches', b);
        case '!startsWith': return !compare(a, 'startsWith', b);
        case '!endsWith': return !compare(a, 'endsWith', b);
        case '!between': return !compare(a, 'between', b);
        case 'matches': return regexMatch(a, b);
        case 'between': return Array.isArray(b) && typeof a === 'number' && a >= b[0] && a <= b[1];
        default: throw new Error('不支持的比较符: ' + op);
    }
}
function condition(c, ctx) {
    if (!c) {
        return true;
    }
    if (c.all) {
        const v = c.all.map(x => condition(x, ctx));
        return v.includes(false) ? false : v.includes(null) ? null : true;
    }
    if (c.any) {
        const v = c.any.map(x => condition(x, ctx));
        return v.includes(true) ? true : v.includes(null) ? null : false;
    }
    if (c.not) {
        const v = condition(c.not, ctx);
        return v === null ? null : !v;
    }
    const scope = c.scope || 'parent', path = c.path || [];
    let v = get(ctx[scope], path);
    if (v === undefined && ctx.defaults && scope === 'parent') {
        v = get(ctx.defaults, path);
    }
    if (defersPlaceholder(v, ctx, scope, path)) {
        return null;
    }
    if (c.measure === 'size') {
        v = size(v);
    }
    if (['matches', '!matches'].includes(c.op) && regexMatch(v, c.value) === undefined) {
        return null;
    }
    return compare(v, c.op, c.value);
}
function isHttpUrl(value) {
    const address = /^https?:\/\/(\[[0-9a-f:.]+]|[a-z0-9.-]+)(?::([0-9]{1,5}))?(?:\/[-a-z0-9._~!$&'()*+,;=:@/%]*)?(?:\?[-a-z0-9._~!$&'()*+,;=:@/%?\[\]]*)?$/i.exec(value);
    if (!address || address[0] !== value || /%(?![0-9a-f]{2})/i.test(value)) {
        return false;
    }
    const [, host, port] = address;
    if (port !== undefined && (Number(port) === 0 || Number(port) > 65535)) {
        return false;
    }
    return isHostAddress(host);
}

function isListenAddress(text) {
    if (!text.includes(':')) {
        const hostname = text.replace(/\.$/, '');
        return (hostname.toLowerCase() === 'localhost' || hostname.includes('.')) && isHostAddress(text);
    }
    let address = text.startsWith('[') && text.endsWith(']') ? text.slice(1, -1) : text;
    const zone = address.indexOf('%');
    if (zone >= 0) {
        if (!/^[a-z0-9_.-]+$/i.test(address.slice(zone + 1))) {
            return false;
        }
        address = address.slice(0, zone);
    }
    return isHostAddress('[' + address + ']');
}

function isHostAddress(host) {
    const isIpv4Address = host => /^(?:0|[1-9][0-9]{0,2})(?:\.(?:0|[1-9][0-9]{0,2})){3}$/.test(host)
        && formatCheck(host, 'ipv4');
    if (/^\[[0-9a-f:.]+]$/i.test(host)) {
        const ipv4Start = host.lastIndexOf(':') + 1;
        return (!host.includes('.') || isIpv4Address(host.slice(ipv4Start, -1))) && new URL('http://' + host).hostname !== '';
    }
    if (/^[0-9.]+$/.test(host)) {
        return isIpv4Address(host);
    }
    const hostname = host.replace(/\.$/, '');
    const lastDot = hostname.lastIndexOf('.');
    return formatCheck(hostname, 'hostname') && (lastDot < 0 || /^[a-z]/i.test(hostname.slice(lastDot + 1)));
}

function formatCheck(v, format) {
    switch (format) {
        case 'color': return typeof v === 'string' && (/^#?[\da-f]{6}$/i.test(v) || /^#[\da-f]+$/i.test(v) && Number.parseInt(v.slice(1), 16) <= 2147483647 || /^\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}$/.test(v) && v.split(/\s*,\s*/).every(part => Number(part) <= 255) || /^(?:white|black|red|green|blue|yellow|orange|pink|gray|light_gray|dark_gray|cyan|magenta)$/i.test(v));
        case 'http-base-url':
        case 'http-url': try {
            return typeof v === 'string' && (format !== 'http-base-url' || !v.includes('?')) && isHttpUrl(v);
        }
        catch {
            return false;
        }
        case 'listen-address': try {
            return typeof v === 'string' && isListenAddress(v);
        }
        catch {
            return false;
        }
        case 'email': return typeof v === 'string' && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v);
        case 'password': return typeof v === 'string' && !/[^!-~]/.test(v);
        case 'url': try {
            new URL(v);
            return true;
        }
        catch {
            return false;
        }
        case 'uuid': return /^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i.test(v);
        case 'ipv4': return typeof v === 'string' && v.split('.').length === 4 && v.split('.').every(s => /^\d{1,3}$/.test(s) && Number(s) <= 255);
        case 'hostname': return typeof v === 'string' && v.length <= 253 && v.split('.').every(s => /^[a-z\d](?:[a-z\d-]{0,61}[a-z\d])?$/i.test(s));
        case 'json': try {
            JSON.parse(v);
            return true;
        }
        catch {
            return false;
        }
        default: return false;
    }
}
function isMultipleOf(value, step) {
    if (!Number.isFinite(value) || !Number.isFinite(step) || step <= 0) {
        return false;
    }
    const parts = number => {
        const [mantissa, exponent = '0'] = String(number).split('e');
        const [integer, fraction = ''] = mantissa.split('.');
        return [BigInt(integer + fraction), Number(exponent) - fraction.length];
    };
    const [amount, amountScale] = parts(value), [divisor, divisorScale] = parts(step);
    const scale = Math.min(amountScale, divisorScale);
    return (amount * 10n ** BigInt(amountScale - scale)) % (divisor * 10n ** BigInt(divisorScale - scale)) === 0n;
}
function checkRule(v, r, ctx, whenContext = ctx) {
    if (r.when && !['requiredWhen', 'forbiddenWhen', 'nonEmptyOnlyWhen'].includes(r.rule)
        && condition(r.when, whenContext) !== true) {
        return true;
    }
    const scope = r.scope || 'current', path = r.path || [];
    let target = get(ctx[scope], path);
    if (target === undefined && scope === 'parent') {
        target = get(ctx.defaults, path);
    }
    if (defersPlaceholder(target, ctx, scope, path)) {
        return true;
    }
    switch (r.rule) {
        case 'required': return v !== undefined && v !== null && v !== '';
        case 'nonBlank': return typeof v === 'string' && v.trim().length > 0;
        case 'minimum': return typeof v === 'number' && v >= r.value;
        case 'maximum': return typeof v === 'number' && v <= r.value;
        case 'range': return typeof v === 'number' && v >= r.min && v <= r.max;
        case 'multipleOf': return isMultipleOf(v, r.value);
        case 'exclusiveMinimum': return typeof v === 'number' && v > r.value;
        case 'exclusiveMaximum': return typeof v === 'number' && v < r.value;
        case 'notEmpty': return !empty(v);
        case 'trimmed': return typeof v === 'string' && v === v.trim();
        case 'lowercase': return typeof v === 'string' && v === v.toLowerCase();
        case 'uppercase': return typeof v === 'string' && v === v.toUpperCase();
        case 'integer': return Number.isInteger(v);
        case 'finite': return typeof v === 'number' && Number.isFinite(v);
        case 'minLength': return size(v) >= r.value;
        case 'maxLength': return size(v) <= r.value;
        case 'length': return size(v) === r.value;
        case 'pattern': return regexMatch(v, r.value) !== false;
        case 'enum': return Array.isArray(r.values) && r.values.some(x => equal(v, x));
        case 'format': return formatCheck(v, r.value);
        case 'port': return Number.isInteger(v) && v >= (r.allowZero ? 0 : 1) && v <= 65535;
        case 'contains': return Array.isArray(v) && v.some(x => equal(x, r.value));
        case 'minContains': return Array.isArray(v) && v.filter(x => equal(x, r.value)).length >= r.count;
        case 'maxContains': return Array.isArray(v) && v.filter(x => equal(x, r.value)).length <= r.count;
        case 'minItems':
        case 'minKeys': return size(v) >= r.value;
        case 'maxItems':
        case 'maxKeys': return size(v) <= r.value;
        case 'uniqueItems': return Array.isArray(v) && v.every((x, i) => v.findIndex(y => equal(x, y)) === i);
        case 'uniqueItemField': {
            if (!Array.isArray(v)) {
                return false;
            }
            const a = v.map(x => get(x, r.path || [])).filter(x => x !== undefined);
            return a.every((x, i) => a.findIndex(y => equal(x, y)) === i);
        }
        case 'requiredKeys': return v && typeof v === 'object' && r.keys.every(k => own(v, k));
        case 'allowedKeys': return v && typeof v === 'object' && Object.keys(v).every(k => r.keys.includes(k));
        case 'keyPattern': return v && typeof v === 'object' && Object.keys(v).every(k => regexMatch(k, r.value) !== false);
        case 'keyInMap': return (r.allowUnset && empty(v)) || own(target, v);
        case 'valueInField': return Array.isArray(target) && target.some(x => equal(x, v));
        case 'uniqueAcross': {
            const source = Array.isArray(target) ? target : Object.values(target || {});
            const a = Array.isArray(v) ? v : [v];
            return a.every(x => !source.some(y => equal(x, r.itemPath ? get(y, r.itemPath) : y)));
        }
        case 'dependentRequired': return r.dependencies.every(d => empty(get(v, d.when)) || d.require.every(p => !empty(get(v, p))));
        case 'equalTo': return equal(v, target);
        case 'notEqualTo': return !equal(v, target);
        case 'compare': return ['matches', '!matches'].includes(r.op) && regexMatch(v, target) === undefined || compare(v, r.op, target);
        case 'positiveDuration': return (duration(v) ?? 0n) > 0n;
        case 'durationRange': {
            const amount = duration(v), minimum = duration(r.min), maximum = duration(r.max);
            return amount !== null && minimum !== null && maximum !== null && amount >= minimum && amount <= maximum;
        }
        case 'nonEmptyOnlyWhen': return empty(get(v, path)) || condition(r.when, { ...ctx, current: v, parent: v, parentPath: ctx.currentPath, defaults: {} }) !== false;
        case 'requiredWhen': return condition(r.when, ctx) !== true || !empty(v);
        case 'forbiddenWhen': return condition(r.when, ctx) !== true || empty(v);
        case 'atLeastOne': return r.paths.some(p => !empty(get(v, p)));
        case 'exactlyOne': return r.paths.filter(p => !empty(get(v, p))).length === 1;
        case 'allOrNone': {
            const n = r.paths.filter(p => !empty(get(v, p))).length;
            return n === 0 || n === r.paths.length;
        }
        case 'mutuallyExclusive': return r.paths.filter(p => !empty(get(v, p))).length <= 1;
        default: throw new Error('不支持的校验规则: ' + r.rule);
    }
}
function jsonWrite(value) {
    return JSON.stringify(value, null, 2);
}
function mergeIssues(local, remote, document = 0) {
    const unique = new Map();
    for (const issue of local.concat(remote)) {
        const key = JSON.stringify([issue.document ?? document, issue.path, issue.message]);
        unique.set(key, { ...unique.get(key), ...issue });
    }
    return Array.from(unique.values());
}
const isDuplicateSpringIssue = issue => issue.code === 'duplicateSpringProperty';
function currentServerIssues(issues, draft) {
    const hasProperty = path => {
        const value = get(draft, path);
        return value !== undefined && (value === null || typeof value !== 'object' || Object.keys(value).length === 0);
    };
    return issues.filter(issue => !isDuplicateSpringIssue(issue) || hasProperty(issue.path) && hasProperty(issue.relatedPath));
}
function groupSuggestions(definitions, type) {
    const groups = [];
    for (const [index, definition] of (definitions || []).entries()) {
        const value = normalizeString(definition.value, type);
        let group = groups.find(item => equal(item.value, value));
        if (!group) {
            group = { value, index, sources: [] };
            groups.push(group);
        }
        const source = { source: definition.source, module:definition.module || definition.source, type:definition.type, reason: definition.reason || '' };
        if (!group.sources.some(item => item.module === source.module && item.reason === source.reason && equal(item.type, source.type))) {
            group.sources.push(source);
        }
    }
    return groups;
}
function groupConflicts(conflicts) {
    const groups = new Map();
    for (const conflict of conflicts) {
        const participants = conflict.typeConflict ? conflict.participants || [] : conflict.definitions;
        const modules = new Map(participants.map(item => [item.module || item.source, item.source]));
        const ids = Array.from(modules.keys()).sort();
        const id = 'sources-' + encodeURIComponent(JSON.stringify(ids));
        if (!groups.has(id)) {
            groups.set(id, {id, title:ids.map(key => modules.get(key)).join(' / '), fields:[], conflicts:[]});
        }
        groups.get(id).conflicts.push(conflict);
    }
    return Array.from(groups.values()).sort((a, b) => a.title.localeCompare(b.title, 'zh-CN'));
}
function conflictSelection(path, draft, saved) {
    const value = get(draft, path);
    return value === undefined ? 'pending' : equal(value, get(saved, path)) ? 'saved' : 'draft';
}
function withDefaults(document, meta) {
    const draft = clone(document);
    function fill(field, path) {
        if (field.conflicted) {
            return;
        }
        let parent = draft;
        for (const part of path.slice(0, -1)) {
            if (!own(parent, part)) {
                Object.defineProperty(parent, part, {value:{}, enumerable:true, writable:true, configurable:true});
            }
            if (!parent[part] || typeof parent[part] !== 'object') {
                return;
            }
            parent = parent[part];
        }
        const key = path.at(-1);
        if (!own(parent, key)) {
            const missing = defaultValue({...field, configPath:path});
            if (missing !== undefined) {
                Object.defineProperty(parent, key, {value:clone(missing), enumerable:true, writable:true, configurable:true});
            } else if (field.type === 'object' && !field.suggestionConflict) {
                Object.defineProperty(parent, key, {value:{}, enumerable:true, writable:true, configurable:true});
            }
        }
        const original = get(parent, [key]), value = normalizeEmptyString(original, field);
        if (value !== original) {
            parent[key] = value;
        }
        if (!value || typeof value !== 'object') {
            return;
        }
        if (field.fields) {
            for (const child of field.fields) fill(child, fieldPath(child, path));
        } else if (field.values && !Array.isArray(value)) {
            for (const key of Object.keys(value)) {
                if (!own(field.mapEntries, key)) fill(field.values, path.concat(key));
            }
        } else if (field.items && Array.isArray(value)) {
            for (let index = 0; index < value.length; index++) fill(field.items, path.concat(index));
        }
    }
    for (const moduleMeta of meta) for (const group of moduleMeta.groups || []) for (const field of group.fields || []) fill(field, fieldPath(field, groupPath(moduleMeta, group)));
    return draft;
}
function sharedIssues(issues, locations) {
    const result = new Map();
    for (const issue of issues) {
        const matches = locations.filter(location => location.path.length <= issue.path.length && location.path.every((part, index) => part === issue.path[index]));
        for (const location of matches.length ? matches : [issue]) {
            const mirrored = { ...issue, tab: location.tab, group: location.group };
            result.set(JSON.stringify([mirrored.path, mirrored.message, mirrored.source, mirrored.tab, mirrored.group]), mirrored);
        }
    }
    return Array.from(result.values());
}
window.ConfigEngine = { own, clone, normalizeString, normalizeEmptyString, defaultValue, numberInputValue, selectActionConfiguration, customConfigPath, customConfigConflict, groupPath, fieldPath, pendingAt, pendingLocations, get, valueAt, empty, equal, size, containsPlaceholder, isSpringPlaceholder, dynamicOptionValues, duration, durationParts, durationInputUnits, durationInputValue, compare, condition, formatCheck, checkRule, jsonWrite, mergeIssues, isDuplicateSpringIssue, currentServerIssues, groupSuggestions, groupConflicts, conflictSelection, sharedIssues, withDefaults };
