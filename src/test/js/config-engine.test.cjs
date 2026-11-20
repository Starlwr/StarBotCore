const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const context = {window: {}, URL};
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/config-center/engine.js'), 'utf8'), context);
const engine = context.window.ConfigEngine;
const cases = JSON.parse(fs.readFileSync(path.join(__dirname, '../resources/config-center/rules-cases.json'), 'utf8'));

test('动态选项保留键名、列表下标和值的原有类型，来源为占位符时暂缓校验', () => {
    const parent = {connections:{local121:'localhost'}, catalog:[{zero:0, disabled:false}], variable:'${SOURCE}'};
    const ctx = {parent, document:{catalog:parent.catalog}, parentPath:['starbot','sample']};
    assert.deepEqual(Array.from(engine.dynamicOptionValues({path:['connections'],source:'keys'}, ctx)), ['local121']);
    assert.deepEqual(Array.from(engine.dynamicOptionValues({path:['catalog'],source:'keys'}, ctx)), ['0']);
    assert.deepEqual(Array.from(engine.dynamicOptionValues({scope:'document',path:['catalog','0'],source:'values'}, ctx)), [0, false]);
    assert.deepEqual(Array.from(engine.dynamicOptionValues({path:['unknown'],source:'keys'}, ctx)), []);
    const options = {path:['variable'],source:'keys'};
    assert.deepEqual(Array.from(engine.dynamicOptionValues(options, ctx)), []);
    ctx.springPaths = [['starbot','sample','variable']];
    assert.equal(engine.dynamicOptionValues(options, ctx), null);
    parent.variable = '${SOURCE';
    assert.deepEqual(Array.from(engine.dynamicOptionValues(options, ctx)), []);
});

test('重复 Spring 属性提示在编辑其他字段或修改值后仍保留，移除其中一处后消失', () => {
    const issue = {code: 'duplicateSpringProperty', path: ['advanced', 'example.server', 'host-name'],
        relatedPath: ['advanced', 'example.server.host-name']};
    const advanced = {'example.server': {'host-name': 'localhost'}, 'example.server.host-name': 'backup'};
    const draft = {advanced};
    assert.deepEqual(engine.currentServerIssues([issue], draft), [issue]);
    advanced['server.address'] = '127.0.0.1';
    assert.deepEqual(engine.currentServerIssues([issue], draft), [issue]);
    advanced['example.server.host-name'] = null;
    assert.deepEqual(engine.currentServerIssues([issue], draft), [issue]);
    delete advanced['example.server.host-name'];
    assert.deepEqual(engine.currentServerIssues([issue], draft), []);
    advanced['example.server.host-name'] = 'backup';
    advanced['example.server']['host-name'] = {other: 'value'};
    assert.deepEqual(engine.currentServerIssues([issue], draft), []);
});

test('配置动作只提交声明的路径和键名前缀，不包含其他配置', () => {
    const document = {starbot:{core:{mail:{enabled:true,'default-to':'receiver@example.test'},'config-center':{password:'unrelated-secret'}}},
        advanced:{'spring.mail.host':'smtp.example.test','spring.mail.port':25,'spring.datasource.password':'unrelated-secret','spring.mailbox.password':'unrelated-secret'}};
    const parameters = {mail:{path:['starbot','core','mail']},smtp:{springProperties:true,keyPrefix:'spring.mail.'}};
    const selected = engine.selectActionConfiguration(document, parameters);
    assert.deepEqual(JSON.parse(JSON.stringify(selected)), {starbot:{core:{mail:{enabled:true,'default-to':'receiver@example.test'}}},
        advanced:{'spring.mail.host':'smtp.example.test','spring.mail.port':25}});
    assert.equal(JSON.stringify(selected).includes('unrelated-secret'), false);
});

test('配置动作未声明参数时提交空对象，缺失路径不读取其他配置', () => {
    assert.equal(JSON.stringify(engine.selectActionConfiguration({secret:123}, undefined)), '{}');
    assert.equal(JSON.stringify(engine.selectActionConfiguration({secret:123}, {missing:{path:['missing']}})), '{}');
});

test('Spring 属性参数只提交完整键名匹配的高级配置，保留所选对象结构', () => {
    const document = {advanced:{'spring.mail.port':null,'spring.mail.properties':{'mail.smtp.timeout':5000},
        'spring.mail.properties-extra':true,'spring.mail.password':'unrelated-secret'}};
    const parameters = {smtp:{springProperties:true,keys:['spring.mail.port','spring.mail.properties','missing']}};
    const selected = engine.selectActionConfiguration(document, parameters);
    assert.deepEqual(JSON.parse(JSON.stringify(selected)), {advanced:{'spring.mail.port':null,
        'spring.mail.properties':{'mail.smtp.timeout':5000}}});
});

test('配置动作选择保留空值与集合，且不会修改页面草稿', () => {
    const document = {settings:{enabled:false,port:0,text:'',nothing:null,items:[],options:{}}};
    const selected = engine.selectActionConfiguration(document, {settings:{path:['settings']}});
    assert.deepEqual(JSON.parse(JSON.stringify(selected)), document);
    selected.settings.items.push('changed');
    selected.settings.options.changed = true;
    assert.equal(document.settings.items.length, 0);
    assert.deepEqual(document.settings.options, {});
});

test('同一路径的多个前缀选择会合并，字面点号键不拆分', () => {
    const document = {advanced:{'spring.mail.port':25,'logging.level':{example:'DEBUG'},other:123}};
    const selected = engine.selectActionConfiguration(document, {mail:{path:['advanced'],keyPrefix:'spring.mail.'},log:{path:['advanced'],keyPrefix:'logging.'}});
    assert.deepEqual(JSON.parse(JSON.stringify(selected)), {advanced:{'spring.mail.port':25,'logging.level':{example:'DEBUG'}}});
    assert.equal(selected.advanced.spring, undefined);
});

test('配置动作筛选安全处理原型键，错误的父节点类型会阻止提交', () => {
    const document = JSON.parse('{"__proto__":{"polluted":true},"settings":{"constructor":{"prototype":{"value":1}}}}');
    const selected = engine.selectActionConfiguration(document, {first:{path:['__proto__']},second:{path:['settings','constructor']}});
    assert.equal(Object.hasOwn(selected, '__proto__'), true);
    assert.equal(Object.prototype.polluted, undefined);
    assert.equal(Object.prototype.value, undefined);
    assert.throws(() => engine.selectActionConfiguration({parent:null}, {child:{path:['parent','child']}}), /上级需要是对象/);
    assert.throws(() => engine.selectActionConfiguration({parent:[]}, {child:{path:['parent'],keyPrefix:'key.'}}), /需要是对象/);
});

test('待重启状态按字段实际路径归入对应模块和分组，Map 子项沿用父字段位置', () => {
    const locations = [
        {path:['advanced','logging.level'],tab:'core',group:'log'},
        {path:['advanced','spring.mail.host'],tab:'core',group:'mail'},
        {path:['starbot','bilibili','live','auto-save-live-report-image'],tab:'bilibili',group:'live'}
    ];
    const result = engine.pendingLocations([['advanced','logging.level','com.example'],
        ['starbot','bilibili','live','auto-save-live-report-image']], locations);
    assert.deepEqual(Array.from(result.tabs).sort(), ['bilibili','core']);
    assert.deepEqual(Array.from(result.groups).sort(), ['["bilibili","live"]','["core","log"]']);
});

test('未描述和已删除的未知待重启项标记自定义，不误标同一父路径中的已知字段', () => {
    const locations = [{path:['starbot','core','mail','enabled'],tab:'core',group:'mail'}];
    const result = engine.pendingLocations([['starbot','core','mail','test'], ['advanced','custom.flag']], locations);
    assert.deepEqual(Array.from(result.tabs), ['custom']);
    assert.equal(result.groups.size, 0);
    assert.equal(engine.pendingLocations(undefined, locations).tabs.size, 0);
});

test('仅明确声明清空恢复默认的字段使用默认值，其他空字符串仍是显式值', () => {
    const field = {type:'string',default:'0.0.0.0',ui:{emptyAsDefault:true}};
    const path = ['advanced','server.address'];
    assert.equal(engine.valueAt({advanced:{'server.address':''}}, field, path, () => ({})), '0.0.0.0');
    assert.equal(engine.valueAt({advanced:{'server.address':'127.0.0.1'}}, field, path, () => ({})), '127.0.0.1');
    assert.equal(engine.valueAt({advanced:{'server.address':''}}, {...field,ui:{}}, path, () => ({})), '');
});

test('空字符串只在声明转换的字段中成为 null，空格和原草稿保留', () => {
    const field = {key:'address',type:'string',nullable:true,emptyAsNull:true,default:'receiver@example.com'};
    const path = ['plugin','address'];
    const meta = [{basePath:['plugin'],groups:[{path:[],fields:[field]}]}];
    const draft = {plugin:{address:''}};
    assert.equal(engine.valueAt(draft,field,path,() => ({})),null);
    assert.equal(engine.withDefaults(draft,meta).plugin.address,null);
    assert.equal(draft.plugin.address,'');
    assert.equal(engine.valueAt({plugin:{address:' '}},field,path,() => ({})),' ');
    assert.equal(engine.valueAt({plugin:{}},field,path,() => ({})),'receiver@example.com');
    assert.equal(engine.valueAt(draft,{...field,emptyAsNull:false},path,() => ({})),'');
});

test('时长输入框简写 ISO 时长并保持精度，毫秒数和已有简写保持原样', () => {
    for (const [value, expected] of [
        ['PT30S', '30s'], ['PT2M', '2m'], ['PT1M30S', '90s'], ['PT1.5S', '1500ms'],
        ['PT0.000001S', '1us'], ['PT0.000000001S', '1ns'], ['PT24H', '1d'], ['PT0S', '0s'],
        ['-PT2M', '-2m'], [30000, 30000], ['30000', '30000'], ['30s', '30s'], ['P1DT', 'P1DT'],
        ['PT9007199254740997S', 'PT9007199254740997S'], [null, null]
    ]) {
        assert.equal(engine.displayDuration(value), expected);
    }
});

test('待重启标记匹配实际分段路径，同时标记父容器与其子项', () => {
    const paths = [['advanced','logging.level','com.example'], ['starbot','core','network','connect-timeout']];
    for (const path of [['advanced','logging.level'], ['advanced','logging.level','com.example'],
        ['starbot','core','network'], ['starbot','core','network','connect-timeout']]) {
        assert.equal(engine.pendingAt(paths, path), true);
    }
    for (const path of [['advanced','logging.level','com.other'], ['advanced','logging','level','com.example'],
        ['starbot','core','network','read-timeout'], ['starbot','core','network-extra']]) {
        assert.equal(engine.pendingAt(paths, path), false);
    }
    assert.equal(engine.pendingAt(undefined, ['advanced','server.port']), false);
    assert.equal(engine.pendingAt([], ['advanced','server.port']), false);
    assert.equal(engine.pendingAt([['starbot','items']], ['starbot','items',0,'name']), true);
});

test('合并表单中的业务字段和高级字段各自使用实际配置路径', () => {
    const moduleMeta = {basePath:['starbot','core']};
    const group = {path:['mail'],configPath:['starbot','core','mail']};
    const parent = engine.groupPath(moduleMeta, group);
    const enabled = {key:'enabled',configPath:['starbot','core','mail','enabled']};
    const smtp = {key:'spring.mail.host',configPath:['advanced','spring.mail.host']};
    const data = {starbot:{core:{mail:{enabled:true}}},advanced:{'spring.mail.host':'smtp.example.com'}};
    assert.equal(engine.get(data, engine.fieldPath(enabled, parent)), true);
    assert.equal(engine.get(data, engine.fieldPath(smtp, parent)), 'smtp.example.com');
    assert.deepEqual(engine.groupPath(moduleMeta, {path:['advanced'],configPath:['advanced']}), ['advanced']);
    assert.deepEqual(Array.from(engine.fieldPath({key:'name'}, ['items',0])), ['items',0,'name']);
});

test('邮件参数校验随总开关和 SMTP 身份认证分别启停', () => {
    const meta = JSON.parse(fs.readFileSync(path.join(__dirname, '../../main/resources/config/config.json'), 'utf8'));
    const fields = meta.groups.find(group => group.id === 'mail').fields;
    const port = fields.find(field => field.key === 'spring.mail.port');
    const username = fields.find(field => field.key === 'spring.mail.username');
    const password = fields.find(field => field.key === 'spring.mail.password');
    assert.equal(engine.condition(meta.conditions[port.validateWhen], {document:{},parent:{},defaults:{}}), false);
    for (const enabled of [false, true]) {
        for (const auth of [false, true]) {
            const document = {starbot:{core:{mail:{enabled}}},advanced:{'spring.mail.properties':{'mail.smtp.auth':auth}}};
            assert.equal(engine.condition(meta.conditions[port.validateWhen], {document}), enabled);
            assert.equal(engine.condition(meta.conditions[username.requiredWhen], {document}), enabled);
            assert.equal(engine.condition(password.requiredWhen, {document}), enabled && auth);
        }
    }
});

test('根据完整键自动归类，业务配置分层，高级配置保留完整 Spring 键名', () => {
    const path = text => Array.from(engine.customConfigPath(text));
    assert.deepEqual(path('test'), ['advanced', 'test']);
    assert.deepEqual(path('starbot.example.enabled'), ['starbot', 'example', 'enabled']);
    assert.deepEqual(path(' starbot . core . command . prefix '), ['starbot', 'core', 'command', 'prefix']);
    assert.deepEqual(path('server.port'), ['advanced', 'server.port']);
    assert.deepEqual(path('advanced.server.port'), ['advanced', 'server.port']);
    assert.deepEqual(path('starbot-extra.enabled'), ['advanced', 'starbot-extra.enabled']);
    assert.deepEqual(path('spring.mail.properties.mail.smtp.auth'), ['advanced', 'spring.mail.properties.mail.smtp.auth']);
});

test('自定义路径不能只填区域，也不能包含空层级', () => {
    for (const text of ['', 'starbot', 'advanced', 'advanced.', 'test..name', '.test', 'test.', 'starbot. .name']) {
        assert.throws(() => engine.customConfigPath(text));
    }
});

test('元数据中的已有配置即使未落盘，也不能重复新增', () => {
    const claims = [['advanced', 'server.port'], ['advanced', 'logging.level'], ['starbot', 'core', 'command', 'prefix']];
    const fieldMetaAt = path => ({type: path[1] === 'server.port' ? 'integer' : path[1] === 'logging.level' ? 'map' : 'string'});
    for (const path of [['advanced', 'server.port'],
        ['advanced', 'logging.level.root'], ['advanced', 'logging.level[root]'],
        ['starbot', 'core', 'command', 'prefix'], ['starbot', 'core', 'command']]) {
        assert.match(engine.customConfigConflict({}, path, claims, fieldMetaAt), /表单入口/);
    }
    assert.equal(engine.customConfigConflict({}, ['advanced', 'server.port.a'], claims, fieldMetaAt),
        '"server.port" 只能设置一个值, 不能添加子配置');
    assert.equal(engine.customConfigConflict({}, ['advanced', 'server.port-extra'], claims, fieldMetaAt), '');
    assert.equal(engine.customConfigConflict({}, ['starbot', 'core', 'commander'], claims, fieldMetaAt), '');
    assert.equal(engine.customConfigConflict({}, ['advanced', 'example.enabled'], claims, fieldMetaAt), '');
});

test('自定义项的 false、0、空文本、null 和对象都算已存在', () => {
    for (const value of [false, 0, '', null, {}, []]) {
        assert.match(engine.customConfigConflict({starbot:{test:value}}, ['starbot','test'], []), /已存在/);
    }
    assert.match(engine.customConfigConflict({starbot:{test:false}}, ['starbot','test','child'], []), /父级路径/);
    assert.equal(engine.customConfigConflict({starbot:{example:{a:1}}}, ['starbot','example','b'], []), '');
});

test('高级配置不能通过嵌套和点号或方括号写法重复声明', () => {
    const data = {advanced:{'example.options':{'timeout':10},'example.properties[token.name]':'value'}};
    for (const path of [['advanced','example.options.timeout'], ['advanced','example.options[timeout]'],
        ['advanced','example.properties[token.name]'], ['advanced','example.properties']]) {
        assert.match(engine.customConfigConflict(data, path, []), /已存在|重叠/);
    }
    assert.equal(engine.customConfigConflict(data, ['advanced','example.properties.token.name'], []), '');
    assert.equal(engine.customConfigConflict(data, ['advanced','example.other'], []), '');
});

test('重命名高级对象的子项时，父对象不算重叠，其他同名配置仍算重复', () => {
    const data = {advanced:{'test.g':{taken:'value'}}};
    assert.equal(engine.customConfigConflict(data, ['advanced', 'test.g', 'test'], []), '');
    assert.match(engine.customConfigConflict(data, ['advanced', 'test.g', 'taken'], []), /已存在/);
    data.advanced['test.g.test'] = 'value';
    assert.match(engine.customConfigConflict(data, ['advanced', 'test.g', 'test'], []), /重叠/);
});

test('高级配置的列表下标与带点号的子键按各自的 Spring 键比较', () => {
    const data = {advanced:{'test.g':[{}], 'test.g[0].a.b':'value'}};
    assert.equal(engine.customConfigConflict(data, ['advanced', 'test.g', 0, 'a.b'], []), '');
    data.advanced['test.g[0][a.b]'] = 'value';
    assert.match(engine.customConfigConflict(data, ['advanced', 'test.g', 0, 'a.b'], []), /重叠/);
});

test('数字输入保留未完成和无效文本，不当作未配置或旧值', () => {
    for (const value of ['-', '+', '.', '1e', '1e-', 'abc', '0x10', 'Infinity', '1e309']) {
        assert.equal(engine.numberInputValue(value, 'number'), value);
        assert.equal(engine.numberInputValue(value, 'integer'), value);
    }
    assert.equal(engine.numberInputValue('1.5', 'integer'), '1.5');
    assert.equal(engine.numberInputValue('9007199254740992', 'integer'), '9007199254740992');
});

test('有效数字正确转换，空输入可恢复默认值', () => {
    for (const [text, expected] of [['0', 0], ['-12', -12], ['+12', 12], ['1e2', 100], [' 42 ', 42]]) {
        assert.equal(engine.numberInputValue(text, 'integer'), expected);
    }
    assert.equal(engine.numberInputValue('.5', 'number'), 0.5);
    assert.equal(engine.numberInputValue('-1.5', 'number'), -1.5);
    assert.equal(engine.numberInputValue('', 'integer'), undefined);
    assert.equal(engine.numberInputValue('   ', 'number'), undefined);
});

for (const item of cases.rules) {
    test(item.name, () => assert.equal(engine.checkRule(item.value, item.rule, cases.context), item.expected));
}
const accessPassword = JSON.parse(fs.readFileSync(path.join(__dirname, '../../main/resources/config/config.json'), 'utf8'))
    .groups.find(group => group.id === 'config-center').fields.find(field => field.key === 'password');
for (const item of cases.accessPasswords) {
    test(item.name, () => {
        const error = typeof item.value !== 'string' ? '访问密码必须为文本'
            : accessPassword.validators.find(rule => !engine.checkRule(item.value, rule, {current: item.value}))?.message;
        assert.equal(error, item.message);
    });
}

test('Map 键的普通规则根据当前键名判断 when，条件规则继续读取原上下文', () => {
    const context = {parent:{enabled:true}, current:{ab:1}, document:{}};
    const keyDocument = {key:'ab'};
    const whenContext = {parent:keyDocument, current:'ab', document:keyDocument};
    const lengthRule = {rule:'minLength', value:4, when:{scope:'current', path:[], op:'startsWith', value:'a'}};
    assert.equal(engine.checkRule('ab', lengthRule, context, whenContext), false);
    const requiredRule = {rule:'requiredWhen', when:{path:['enabled'], op:'=', value:true}};
    assert.equal(engine.checkRule(undefined, requiredRule, context, whenContext), false);
});
for (const item of cases.operators) {
    test('比较 ' + item.op + ' ' + JSON.stringify(item.left), () => assert.equal(engine.compare(item.left, item.op, item.right), item.expected));
}
for (const item of cases.formats) {
    test('格式 ' + item.format + ' ' + JSON.stringify(item.value), () => assert.equal(engine.formatCheck(item.value, item.format), item.expected));
}
for (const item of cases.placeholders) {
    test('占位符 ' + JSON.stringify(item.value), () => assert.equal(engine.containsPlaceholder(item.value), item.expected));
}
test('只有映射到 Spring 的字段及其子项可以暂缓校验', () => {
    const springPaths = [['starbot','sample','ports']];
    assert.equal(engine.isSpringPlaceholder('${PORT:7827}', ['advanced','server.port'], springPaths), true);
    assert.equal(engine.isSpringPlaceholder('${PORT}', ['starbot','sample','ports',0], springPaths), true);
    assert.equal(engine.isSpringPlaceholder('${PORT}', ['starbot','sample','ports-local'], springPaths), false);
    assert.equal(engine.isSpringPlaceholder('${PORT', ['advanced','server.port'], springPaths), false);
    assert.equal(engine.formatCheck('${EMAIL}', 'email'), false);
});
test('占位符条件和引用规则根据实际读取的配置路径判断', () => {
    const rule = {path:['port'], op:'>', value:1};
    const ctx = {parent:{port:'${PORT}'}, parentPath:['starbot','sample'],
        springPaths:[['starbot','sample','port']]};
    assert.equal(engine.condition(rule, ctx), null);
    assert.equal(engine.condition(rule, {...ctx, springPaths:[]}), false);
    assert.equal(engine.condition(rule, {...ctx, parent:{port:'${PORT'}}), false);
    const comparison = {rule:'compare', scope:'parent', path:['port'], op:'>', value:1};
    assert.equal(engine.checkRule(100, comparison, ctx), true);
    assert.equal(engine.checkRule(100, comparison, {...ctx, springPaths:[]}), false);
});
test('false、0 与未设置保持区别', () => {
    assert.equal(engine.empty(false), false);
    assert.equal(engine.empty(0), false);
    assert.equal(engine.compare(null, 'exists'), true);
    assert.equal(engine.compare(undefined, 'exists'), false);
});
test('条件使用同级字段缺省值', () => {
    assert.equal(engine.condition({path:['enabled'], op:'=', value:true}, {parent:{}, defaults:{enabled:true}}), true);
    assert.equal(engine.condition({scope:'document',path:['enabled'],op:'=',value:true},{document:{},defaults:{enabled:true}}), false);
});

test('Maven 列表子项读取父列表默认地址，且不写入草稿', () => {
    const field = {type:'array',default:['https://maven.aliyun.com/repository/public','https://repo1.maven.org/maven2'],items:{type:'string'}};
    const parent = ['starbot','core','plugin','maven-base-urls'];
    const fieldMetaAt = path => JSON.stringify(path) === JSON.stringify(parent) ? field : {type:'any'};
    const data = {};
    for (let i = 0; i < field.default.length; i++) {
        assert.equal(engine.valueAt(data, field.items, parent.concat(i), fieldMetaAt), field.default[i]);
    }
    const values = engine.valueAt(data, field, parent, fieldMetaAt);
    values[0] = 'https://changed.example.com';
    assert.equal(field.default[0], 'https://maven.aliyun.com/repository/public');
    assert.deepEqual(data, {});
});

test('默认对象列表中的嵌套列表和动态键值可逐层读取', () => {
    const records = {type:'array',default:[{name:'main',tags:['first','second'],headers:{'X-Client':'StarBot'},enabled:false,count:0,nothing:null}]};
    const fieldMetaAt = path => path.length === 1 && path[0] === 'records' ? records : {type:'any'};
    assert.equal(engine.valueAt({}, {type:'string'}, ['records',0,'name'], fieldMetaAt), 'main');
    assert.equal(engine.valueAt({}, {type:'string'}, ['records',0,'tags',1], fieldMetaAt), 'second');
    assert.equal(engine.valueAt({}, {type:'string'}, ['records',0,'headers','X-Client'], fieldMetaAt), 'StarBot');
    assert.equal(engine.valueAt({}, {type:'boolean',default:true}, ['records',0,'enabled'], fieldMetaAt), false);
    assert.equal(engine.valueAt({}, {type:'integer',default:8}, ['records',0,'count'], fieldMetaAt), 0);
    assert.equal(engine.valueAt({}, {type:'any',default:'fallback'}, ['records',0,'nothing'], fieldMetaAt), null);
    assert.equal(engine.valueAt({}, {type:'string',default:'fallback'}, ['records',0,'absent'], fieldMetaAt), 'fallback');
});

test('配置中的显式列表和空列表覆盖默认列表，不补回已删除的条目', () => {
    const field = {type:'array',default:['first','second'],items:{type:'string'}};
    const fieldMetaAt = path => path.length === 1 ? field : field.items;
    assert.equal(engine.valueAt({items:['custom']}, field.items, ['items',0], fieldMetaAt), 'custom');
    assert.equal(engine.valueAt({items:['custom']}, field.items, ['items',1], fieldMetaAt), undefined);
    assert.equal(engine.valueAt({items:['']}, field.items, ['items',0], fieldMetaAt), '');
    assert.equal(engine.valueAt({items:[]}, field, ['items'], fieldMetaAt).length, 0);
    assert.equal(engine.valueAt({items:null}, field, ['items'], fieldMetaAt), null);
});

test('显式对象不使用父对象默认内容，数字列表子项仍按字符串转换', () => {
    const object = {type:'object',default:{name:'parent-default'}};
    assert.equal(engine.valueAt({settings:{}}, {type:'string'}, ['settings','name'], () => object), undefined);
    assert.equal(engine.valueAt({}, {type:'string'}, ['numbers',0], () => ({type:'array',default:[123]})), '123');
});
test('JSON 字符串值保持字符串类型', () => {
    assert.equal(JSON.parse(engine.jsonWrite({value:'true'})).value, 'true');
});

test('字符串字段只转换数字，并继续执行文本规则', () => {
    for (const value of [123, 0, -12, 12.5]) {
        assert.equal(engine.normalizeString(value, 'string'), String(value));
    }
    const text = engine.normalizeString(12345, 'string');
    assert.equal(engine.checkRule(text, {rule:'minLength', value:3}, {}), true);
    assert.equal(engine.checkRule(text, {rule:'pattern', value:'^[0-9]+$'}, {}), true);
    assert.equal(engine.checkRule(engine.normalizeString(12, 'string'), {rule:'minLength', value:3}, {}), false);
    for (const value of [true, false, null, undefined, {}, [], '00123']) {
        assert.equal(engine.normalizeString(value, 'string'), value);
    }
});

test('允许数字的联合类型和任意类型保留数字', () => {
    for (const type of ['any', 'integer', 'number', ['string','number'], ['string','integer'], ['string','any']]) {
        assert.equal(engine.normalizeString(123, type), 123);
    }
    assert.equal(engine.normalizeString(12.5, ['string','integer']), '12.5');
    assert.equal(engine.normalizeString(123, ['string','boolean']), '123');
});

test('前后端返回同一路径和消息时只保留一个错误并保留后端定位信息', () => {
    const local = [{path:['spring','datasource','password'], message:'数据库密码的数据类型不正确', tab:'system'}];
    const remote = [{...local[0], group:'datasource', source:'System'}];
    const issues = engine.mergeIssues(local, remote);
    assert.equal(issues.length, 1);
    assert.equal(issues[0].group, 'datasource');
    assert.equal(issues[0].source, 'System');
});

test('同一字段的不同错误和不同列表条目的错误均保留', () => {
    const issues = engine.mergeIssues([
        {path:['items',0], message:'类型不正确'},
        {path:['items',0], message:'值超出范围'},
        {path:['items',1], message:'类型不正确'}
    ], [{path:['items',0], message:'类型不正确', source:'插件'}]);
    assert.equal(issues.length, 3);
});

test('当前配置段的显式序号与隐式序号合并，其他配置段保持独立', () => {
    const issue = {path:['port'], message:'端口不正确'};
    const issues = engine.mergeIssues([issue], [{...issue, document:0}, {...issue, document:1}]);
    assert.equal(issues.length, 2);
});

test('相同建议值合并展示，并保留每个插件自己的说明和选择索引', () => {
    const definitions = [
        {source:'插件 A', value:false, reason:'关闭不需要的检查'},
        {source:'插件 B', value:true, reason:'启动时验证模板'},
        {source:'插件 C', value:false, reason:'使用自定义模板目录'}
    ];
    const groups = engine.groupSuggestions(definitions, 'boolean');
    assert.equal(groups.length, 2);
    assert.equal(groups[0].sources.length, 2);
    assert.equal(groups[0].sources[0].reason, '关闭不需要的检查');
    assert.equal(groups[0].sources[1].reason, '使用自定义模板目录');
    for (const group of groups) {
        assert.equal(definitions[group.index].value, group.value);
    }
    assert.equal(definitions.length, 3);
});

test('建议值按字段类型和对象内容比较，不把不同类型的值误合并', () => {
    assert.equal(engine.groupSuggestions([{value:123}, {value:'123'}], 'string').length, 1);
    assert.equal(engine.groupSuggestions([{value:0}, {value:false}, {value:'0'}], 'any').length, 3);
    assert.equal(engine.groupSuggestions([{source:'A', value:{a:1,b:2}}, {source:'B', value:{b:2,a:1}}], 'object').length, 1);
    assert.equal(engine.groupSuggestions([], 'boolean').length, 0);
});

test('共享配置错误关联到每个编辑入口，同一路径下的消息不会重复', () => {
    const path = ['advanced', 'example.port'];
    const locations = [{path, tab:'first', group:'main'}, {path, tab:'second', group:'native'}];
    const issues = engine.sharedIssues([{path, message:'端口不正确', tab:'first', group:'main'}], locations);
    assert.equal(issues.length, 2);
    assert.deepEqual(Array.from(issues, issue => issue.tab), ['first', 'second']);
});

test('建议优先于框架默认值，建议冲突不静默回退，明确配置保留 false 和 null', () => {
    const field = {type:'integer', default:8080, effectiveDefault:7827};
    assert.equal(engine.defaultValue(field), 7827);
    assert.equal(engine.valueAt({}, field, ['port'], () => ({})), 7827);
    assert.equal(engine.defaultValue({...field, suggestionConflict:true}), undefined);
    assert.equal(engine.valueAt({port:0}, field, ['port'], () => ({})), 0);
    assert.equal(engine.valueAt({port:null}, field, ['port'], () => ({})), null);
});

test('跨字段条件读取统一建议，空集合和草稿副本保持独立', () => {
    const meta = [{basePath:['starbot','demo'],groups:[{path:[],fields:[
        {key:'enabled',type:'boolean',default:false,configPath:['advanced','example.enabled'],effectiveDefault:true},
        {key:'items',type:'array',default:['default'],items:{type:'string'}}
    ]}]}];
    const original = {starbot:{demo:{items:[]}}};
    const view = engine.withDefaults(original, meta);
    assert.equal(view.advanced['example.enabled'], true);
    assert.equal(view.starbot.demo.items.length, 0);
    assert.equal(original.advanced, undefined);
});

test('声明有冲突的字段不补默认值，也不创建缺失的上级对象', () => {
    const meta = [{basePath:['starbot','demo'],groups:[
        {path:[],fields:[{key:'connection',type:'string',conflicted:true}]},
        {path:['connection'],fields:[{key:'port',type:'integer',default:7827,conflicted:true}]},
        {path:[],fields:[{key:'enabled',type:'boolean',default:true}]}
    ]}];
    assert.equal(engine.withDefaults({}, meta).starbot.demo.connection, undefined);
    const view = engine.withDefaults({starbot:{demo:{connection:{host:'localhost'}}}}, meta);
    assert.equal(view.starbot.demo.connection.host, 'localhost');
    assert.equal(view.starbot.demo.connection.port, undefined);
    assert.equal(view.starbot.demo.enabled, true);
});

test('冲突按插件标识组合分组，同名插件不误合并，来源顺序不影响分组', () => {
    const conflicts = [
        {path:['a'],definitions:[{module:'plugin:b',source:'插件 B'},{module:'plugin:a',source:'插件 A'}]},
        {path:['b'],definitions:[{module:'plugin:a',source:'插件 A'},{module:'plugin:b',source:'插件 B'}]},
        {path:['c'],definitions:[{module:'other:a',source:'插件 A'},{module:'plugin:b',source:'插件 B'}]}
    ];
    const groups = engine.groupConflicts(conflicts);
    assert.equal(groups.length, 2);
    assert.equal(groups.find(group => group.conflicts.includes(conflicts[0])).conflicts.length, 2);
    assert.equal(groups[0].fields.length, 0);
});

test('冲突选择区分待选择、未保存及已保存，false、0、空文本和 null 都是明确值', () => {
    for (const value of [false, 0, '', null, [], {}]) {
        assert.equal(engine.conflictSelection(['key'], {}, {}), 'pending');
        assert.equal(engine.conflictSelection(['key'], {key:value}, {}), 'draft');
        assert.equal(engine.conflictSelection(['key'], {key:value}, {key:value}), 'saved');
    }
});

test('共享字段在多个入口标记错误，汇总信息只展示一次', () => {
    const path = ['advanced','example.port'];
    const issues = engine.sharedIssues([{path,message:'请填写整数'}], [
        {path,tab:'first',group:'main'}, {path,tab:'second',group:'main'}, {path,tab:'conflicts',group:'sources'}
    ]);
    assert.equal(issues.length, 3);
    assert.equal(engine.mergeIssues(issues, []).length, 1);
});

test('没有建议值的类型冲突仍按所有声明来源分组', () => {
    const conflict = {path:['advanced','mixed'],typeConflict:true,definitions:[],participants:[
        {module:'plugin:a',source:'插件 A',type:'integer'}, {module:'plugin:b',source:'插件 B',type:'string'}
    ]};
    const groups = engine.groupConflicts([conflict]);
    assert.equal(groups.length, 1);
    assert.equal(groups[0].title, '插件 A / 插件 B');
    assert.equal(groups[0].conflicts.length, 1);
});

test('类型冲突的建议值保留原始类型，不按首个插件转换', () => {
    const groups = engine.groupSuggestions([
        {module:'plugin:a',source:'插件 A',type:'integer',value:7},
        {module:'plugin:b',source:'插件 B',type:'string',value:'7'}
    ], 'any');
    assert.equal(groups.length, 2);
    assert.equal(groups[0].value, 7);
    assert.equal(groups[1].value, '7');
});
