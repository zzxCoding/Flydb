#!/usr/bin/env python3
"""Rebuild this offline comparison from the three supplied source files.

This deliberately accepts only the CREATE statements present in this fixture.
It does not connect to a database or execute SQL.
"""
import hashlib
import json
import platform
import re
from pathlib import Path

INPUT = Path('/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift')
OUTPUT = Path(__file__).resolve().parent
SKILL_VERSION = '0.1.0-draft.2'
REVISION = '56bc3baef4a4e5c9eefe440644a4343aa960d0a4'
FILES = ('context.md', 'baseline.sql', 'observed.sql')
raw = {name: (INPUT / name).read_text() for name in FILES}
digest = {name: hashlib.sha256((INPUT / name).read_bytes()).hexdigest() for name in FILES}

def dump(name, value):
    (OUTPUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')

def location(name, start, end=None):
    return {'path': str(INPUT / name), 'startLine': start, 'endLine': end or start}

def source(name):
    return {
        'id': name, 'kind': 'scope-context' if name.endswith('.md') else 'provided-ddl',
        'path': str(INPUT / name), 'sha256': digest[name], 'capturedAt': None,
        'live': False, 'repositoryRevision': REVISION, 'trackedAtRevision': False,
        'limitations': ['输入目录未跟踪；内容身份以 SHA-256 为准。', '数据库采集时间与当前性未知。']
    }

def identity(name):
    return {'catalog': None, 'schema': 'SALES', 'table': 'CUSTOMER', 'column': name,
            'quoted': False}

def snapshot(name):
    lines = raw[name].splitlines()
    assert lines[0] == 'CREATE TABLE SALES.CUSTOMER ('
    assert lines[4] == ');'
    columns = []
    for line_number, line in enumerate(lines[1:4], 2):
        match = re.fullmatch(r'\s*([A-Z_]+) (NUMBER|VARCHAR2)\((\d+)\)(?: (NOT NULL|NULL))?,?', line)
        assert match, f'Unsupported input at {name}:{line_number}'
        column, type_name, argument, null_clause = match.groups()
        columns.append({
            'identity': identity(column), 'ordinal': len(columns) + 1,
            'dataType': {'name': type_name, 'arguments': [int(argument)],
                         'declaration': f'{type_name}({argument})'},
            'nullable': null_clause != 'NOT NULL',
            'nullabilityDeclaration': null_clause or 'UNSPECIFIED',
            'defaultExpression': None, 'source': location(name, line_number)
        })
    table = {'identity': identity(None), 'definitionComplete': True,
             'columns': columns, 'tableConstraints': [], 'source': location(name, 1, 5)}
    is_baseline = name == 'baseline.sql'
    views = []
    indexes = []
    if is_baseline:
        assert lines[5] == 'CREATE VIEW SALES.V_CUSTOMER AS SELECT ID, CUST_TYPE FROM SALES.CUSTOMER;'
        views.append({'catalog': None, 'schema': 'SALES', 'name': 'V_CUSTOMER',
                      'quoted': False, 'definition': lines[5],
                      'referencedColumns': [identity('ID'), identity('CUST_TYPE')],
                      'source': location(name, 6)})
    else:
        assert lines[5] == 'CREATE INDEX SALES.IDX_CUSTOMER_TYPE ON SALES.CUSTOMER(CUST_TYPE);'
        indexes.append({'catalog': None, 'schema': 'SALES', 'name': 'IDX_CUSTOMER_TYPE',
                        'quoted': False, 'table': identity(None), 'columns': ['CUST_TYPE'],
                        'definition': lines[5], 'source': location(name, 6)})
    assert len(lines) == 6, 'Unprocessed lines'
    return {
        'schemaVersion': 'offline-schema-snapshot-v1',
        'formatOrigin': '本次试用自定义格式；指定 Skill 未规定 Schema 快照契约。',
        'snapshotId': 'baseline' if is_baseline else 'observed',
        'database': {'product': 'Oracle', 'version': None, 'mode': 'default',
                     'catalog': None, 'targetSchema': 'SALES', 'environment': None},
        'identifierRules': {'unquotedCaseFold': 'UPPER', 'source': location('context.md', 3)},
        'sources': [source(name), source('context.md')],
        'scope': {'wholeSchemaComplete': False,
                  'tables': {'collectionComplete': False, 'completeDefinitions': ['SALES.CUSTOMER']},
                  'views': {'exportStatus': 'partial' if is_baseline else 'unavailable',
                            'collectionComplete': False},
                  'indexes': {'exportStatus': 'unavailable' if is_baseline else 'partial',
                              'collectionComplete': False},
                  'otherObjectTypes': {'exportStatus': 'unavailable', 'collectionComplete': False}},
        'tables': [table], 'views': views, 'indexes': indexes,
        'interpretation': [
            '只对完整给出的 SALES.CUSTOMER 列集合，将缺失解释为该文件定义中没有该列。',
            '其他对象集合中的空数组表示没有导出材料，不能解释为数据库中不存在。',
            '未写 NULL/NOT NULL 的列按所提供 Oracle DDL 的通常声明语义记为 nullable=true，并保留 UNSPECIFIED。',
            '未显式指定的 VARCHAR2 长度单位、数据库默认配置及运行行为不由本快照推断。'
        ],
        'provenance': {'skillVersion': SKILL_VERSION, 'host': 'Codex', 'model': 'unknown',
                       'method': '读取本次 DDL 并用限制语法的本地脚本提取；未执行 SQL。'}
    }

baseline = snapshot('baseline.sql')
observed = snapshot('observed.sql')
dump('baseline.snapshot.json', baseline)
dump('observed.snapshot.json', observed)

evidence = [
    {'id': 'E-CONTEXT', 'sourceId': 'context.md', 'location': location('context.md', 2, 7),
     'excerpt': raw['context.md'].splitlines()[2]},
    {'id': 'E-BASE-TABLE', 'sourceId': 'baseline.sql', 'location': location('baseline.sql', 1, 5),
     'excerpt': '\n'.join(raw['baseline.sql'].splitlines()[:5])},
    {'id': 'E-OBS-TABLE', 'sourceId': 'observed.sql', 'location': location('observed.sql', 1, 5),
     'excerpt': '\n'.join(raw['observed.sql'].splitlines()[:5])},
    {'id': 'E-BASE-VIEW', 'sourceId': 'baseline.sql', 'location': location('baseline.sql', 6),
     'excerpt': raw['baseline.sql'].splitlines()[5]},
    {'id': 'E-OBS-INDEX', 'sourceId': 'observed.sql', 'location': location('observed.sql', 6),
     'excerpt': raw['observed.sql'].splitlines()[5]}
]
bcols = {c['identity']['column']: c for c in baseline['tables'][0]['columns']}
ocols = {c['identity']['column']: c for c in observed['tables'][0]['columns']}
findings = []

def finding(column, kind, before, after, claim, impact):
    findings.append({
        'id': f'D{len(findings) + 1:03}', 'target': identity(column), 'changeKind': kind,
        'before': before, 'after': after, 'claim': claim, 'basis': 'confirmed', 'impact': impact,
        'conditions': ['仅比较两份给定完整建表定义；不代表实时数据库或已执行的迁移。'],
        'evidenceIds': ['E-CONTEXT', 'E-BASE-TABLE', 'E-OBS-TABLE'],
        'nextActions': ['如需确认环境漂移，获取同一目标数据库、明确时间和范围的结构导出后复查。']
    })

for name in sorted(ocols.keys() - bcols.keys()):
    finding(name, 'column-added', None, ocols[name],
            f'观测文件的 SALES.CUSTOMER 定义包含 {name}，基线完整定义中没有该列。',
            '两份材料的列集合不同；不判断业务用途或部署行为。')
for name in sorted(bcols.keys() - ocols.keys()):
    finding(name, 'column-removed', bcols[name], None,
            f'基线 SALES.CUSTOMER 定义包含 {name}，观测完整定义中没有该列。',
            '两份材料的列集合不同；不能据此证明线上数据被删除。')
for name in sorted(bcols.keys() & ocols.keys()):
    if bcols[name]['dataType'] != ocols[name]['dataType']:
        finding(name, 'column-type-changed', bcols[name]['dataType'], ocols[name]['dataType'],
                'CUST_TYPE 的声明由 VARCHAR2(16) 变为 VARCHAR2(32)。',
                '声明长度参数变化已确认；具体长度单位和运行影响取决于数据库配置及使用方式。')
    if bcols[name]['nullable'] != ocols[name]['nullable']:
        finding(name, 'column-nullability-changed', bcols[name]['nullable'], ocols[name]['nullable'],
                'CUST_TYPE 的声明由 NULL 变为 NOT NULL。',
                '观测定义要求非空；缺少真实数据，未判断现有数据是否满足条件。')

unknowns = [
    {'id': 'U001', 'gap': 'observed.sql 没有导出任何视图。',
     'affectedClaims': ['不能确认 SALES.V_CUSTOMER 是否仍存在、是否变化或已删除。'],
     'nextCheck': '补充同一目标范围的观测视图清单与定义，并声明导出完整性。',
     'evidenceIds': ['E-CONTEXT', 'E-BASE-VIEW']},
    {'id': 'U002', 'gap': 'baseline.sql 没有索引导出。',
     'affectedClaims': ['不能把 SALES.IDX_CUSTOMER_TYPE 判为新增索引，也不能比较其基线定义。'],
     'nextCheck': '补充基线索引清单与定义，并声明导出完整性。',
     'evidenceIds': ['E-CONTEXT', 'E-OBS-INDEX']},
    {'id': 'U003', 'gap': '两份文件采集时间、环境及是否对应当前数据库均未知；没有数据库连接。',
     'affectedClaims': ['已确认的是材料间结构差异，不能宣称当前数据库或生产环境已经漂移。'],
     'nextCheck': '补充两份材料的环境身份和采集时间；需要实时结论时再取得获授权的结构读取结果。',
     'evidenceIds': ['E-CONTEXT']},
    {'id': 'U004', 'gap': '两份文件都不是整个 schema 的完整导出，其他对象类型的材料未提供。',
     'affectedClaims': ['不能扩展为整个 SALES schema 的完整漂移结论。'],
     'nextCheck': '若要扩大范围，补充 schema 对象清单、对象类型导出范围与完整性说明。',
     'evidenceIds': ['E-CONTEXT']},
    {'id': 'U005', 'gap': 'Oracle 版本、字符长度配置、运行数据和应用引用材料未提供。',
     'affectedClaims': ['不能判断变更执行可行性、真实数据兼容性或完整应用影响。'],
     'nextCheck': '仅在后续需要执行或影响分析时，补充相关版本、配置、数据核验及应用材料。',
     'evidenceIds': ['E-CONTEXT']}
]

drift = {
    'schemaVersion': 'flydb-impact-report-v1',
    'formatNote': '复用指定 Skill 的分析报告契约；changeKind、before、after、comparison 是本次比较的扩展字段。该 Skill 未定义专门的漂移契约。',
    'analysisStatus': 'completed',
    'request': {'kind': 'offline-schema-snapshot-comparison', 'proposedChange': None,
                'target': identity(None), 'description': '生成两份可复用快照，比较结构，区分确定差异与材料缺失。'},
    'comparison': {'baseline': 'baseline.snapshot.json', 'observed': 'observed.snapshot.json',
                   'direction': 'baseline-to-observed', 'confirmedDifferenceCount': len(findings),
                   'comparableScope': '两份完整的 SALES.CUSTOMER 表定义',
                   'wholeSchemaComparable': False, 'liveDatabaseVerified': False},
    'sources': [source(name) for name in FILES], 'findings': findings, 'evidence': evidence,
    'coverage': [
        {'scope': '提供的 3 个输入文件', 'status': 'inspected',
         'checked': ['context.md 7 行', 'baseline.sql 6 行', 'observed.sql 6 行'],
         'limitations': ['只枚举和处理用户指定输入目录。']},
        {'scope': 'SALES.CUSTOMER 表定义', 'status': 'inspected',
         'checked': ['schema/表身份', '全部列及顺序', '声明类型及参数', '可空性', '显式默认表达式和表级约束'],
         'limitations': ['只比较静态声明，不验证真实数据、运行效果或未导出的其他对象。']},
        {'scope': '视图', 'status': 'partial',
         'checked': ['基线 SALES.V_CUSTOMER 的完整文本及对 CUSTOMER.ID/CUST_TYPE 的引用'],
         'limitations': ['观测视图范围 unavailable，不能推导视图删除。']},
        {'scope': '索引', 'status': 'partial',
         'checked': ['观测 SALES.IDX_CUSTOMER_TYPE 的完整文本及对 CUSTOMER.CUST_TYPE 的引用'],
         'limitations': ['基线索引范围 unavailable，不能推导索引新增。']},
        {'scope': '实时数据库及整个 schema', 'status': 'unavailable',
         'checked': [], 'limitations': ['没有连接；没有 schema 完整导出。']}
    ],
    'unknowns': unknowns,
    'excluded': [
        {'candidate': 'LEGACY_TAG 重命名为 EMAIL', 'reason': '没有重命名证据；按身份分别记录删除和新增。',
         'evidenceIds': ['E-BASE-TABLE', 'E-OBS-TABLE']},
        {'candidate': 'ID 列发生变化', 'reason': '两份定义都是 NUMBER(10) NOT NULL，位置均为第 1 列。',
         'evidenceIds': ['E-BASE-TABLE', 'E-OBS-TABLE']}
    ],
    'provenance': {'skillVersion': SKILL_VERSION, 'host': 'Codex', 'model': 'unknown',
                   'tools': [{'name': 'Python', 'version': platform.python_version()},
                             {'name': 'exec_command', 'version': 'unknown'}],
                   'method': '完整读取三个输入文件，提取结构并按明确相同身份比较。'}
}
dump('drift.json', drift)

required = {'schemaVersion', 'analysisStatus', 'request', 'sources', 'findings', 'evidence',
            'coverage', 'unknowns', 'excluded', 'provenance'}
assert required <= drift.keys()
assert drift['analysisStatus'] in {'completed', 'partial', 'blocked'}
assert len(findings) == 4
assert {f['changeKind'] for f in findings} == {
    'column-added', 'column-removed', 'column-type-changed', 'column-nullability-changed'}
source_ids = {s['id'] for s in drift['sources']}
assert len(source_ids) == len(drift['sources'])
evidence_ids = {e['id'] for e in evidence}
assert len(evidence_ids) == len(evidence)
assert len({f['id'] for f in findings}) == len(findings)
for e in evidence:
    assert e['sourceId'] in source_ids
    loc = e['location']
    lines = raw[e['sourceId']].splitlines()
    assert 1 <= loc['startLine'] <= loc['endLine'] <= len(lines)
    assert e['excerpt'] in '\n'.join(lines[loc['startLine'] - 1:loc['endLine']])
for f in findings:
    assert f['basis'] in {'confirmed', 'inferred'}
for item in findings + unknowns + drift['excluded']:
    assert set(item['evidenceIds']) <= evidence_ids
for item in drift['coverage']:
    assert item['status'] in {'inspected', 'partial', 'unavailable', 'not-applicable'}
for file in ('baseline.snapshot.json', 'observed.snapshot.json', 'drift.json'):
    json.loads((OUTPUT / file).read_text())
assert digest == {name: hashlib.sha256((INPUT / name).read_bytes()).hexdigest() for name in FILES}

dump('validation-result.json', {
    'result': 'PASS',
    'checks': ['三个 JSON 均可解析', '报告必需字段存在及枚举合法', '来源/证据/发现 id 唯一',
               '全部证据引用可解析且摘录与输入行号匹配', '严格语法提取处理全部 SQL 输入行',
               '检测到四项表结构差异，未将视图/索引材料缺口作为确定差异',
               '生成与检查期间三份输入 SHA-256 未变化'],
    'limitations': ['这是本地结构检查，不能认证业务语义或数据库兼容性。'],
    'sourceSha256': digest
})
print('PASS: generated 2 snapshots, 4 confirmed table differences, 5 unknowns; inputs unchanged.')
