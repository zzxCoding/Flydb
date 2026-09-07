#!/usr/bin/env python3
"""Validate analysis artifacts and compare explicit schema facts. No DB access."""

import argparse
import hashlib
import json
from pathlib import Path
import sys


SNAPSHOT = "flydb-schema-snapshot-v1"
REPORT = "flydb-analysis-report-v1"
KINDS = {"table", "column", "view", "procedure", "trigger", "index", "constraint"}
KEY = ("catalog", "schema", "kind", "parent", "name")
TASKS = {"compatibility", "drift", "dependencies", "references", "impact"}


def check(condition, message):
    if not condition:
        raise ValueError(message)


def fields(value, required, label):
    check(isinstance(value, dict), label + " must be an object")
    check(set(required) <= value.keys(), label + " missing required fields: " +
          ", ".join(sorted(set(required) - value.keys())))


def string(value, label, nullable=False):
    check((nullable and value is None) or (isinstance(value, str) and bool(value.strip())),
          label + " must be a nonempty string" + (" or null" if nullable else ""))


def array(value, label):
    check(isinstance(value, list), label + " must be an array")
    return value


def strings(value, label):
    for item in array(value, label):
        string(item, label + " item")


def enum(value, choices, label):
    check(isinstance(value, str) and value in choices, label + " has an invalid value")


def unique_ids(items, label):
    ids = set()
    for item in array(items, label):
        fields(item, ("id",), label + " item")
        string(item["id"], label + ".id")
        check(item["id"] not in ids, label + " has duplicate id")
        ids.add(item["id"])
    return ids


def location(value):
    check(isinstance(value, dict), "evidence location must be an object")
    if "path" in value:
        fields(value, ("path", "lineStart", "lineEnd"), "file location")
        string(value["path"], "location.path")
        start, end = value["lineStart"], value["lineEnd"]
        check(type(start) is int and type(end) is int and 1 <= start <= end,
              "file evidence must have positive ordered line numbers")
    else:
        fields(value, ("tool", "locator"), "tool location")
        string(value["tool"], "location.tool")
        string(value["locator"], "location.locator")


def identity(value, with_name=True):
    fields(value, KEY if with_name else KEY[:-1], "object identity")
    for part in ("catalog", "schema", "parent"):
        string(value[part], part, nullable=True)
    enum(value["kind"], KINDS, "object kind")
    if value["kind"] == "column":
        string(value["parent"], "column parent")
    else:
        check(value["parent"] is None, "non-column parent must be null")
    if with_name:
        string(value["name"], "object name")


def key(value):
    return tuple(value[p] for p in KEY)


def contains(scope, obj):
    return (all(scope[p] == obj[p] for p in KEY[:-1]) and
            (scope["names"] is None or obj["name"] in scope["names"]))


def validate_snapshot(doc):
    fields(doc, ("schemaVersion", "source", "database", "scope", "objects"), "snapshot")
    source = doc["source"]
    fields(source, ("label", "kind", "location", "environment", "capturedAt", "revision"), "source")
    for part in ("label", "kind", "location"):
        string(source[part], "source." + part)
    for part in ("environment", "capturedAt", "revision"):
        string(source[part], "source." + part, nullable=True)
    db = doc["database"]
    fields(db, ("product", "mode", "version", "identifierPolicy"), "database")
    for part in ("product", "mode", "version"):
        string(db[part], "database." + part, nullable=True)
    enum(db["identifierPolicy"], {"upper-unquoted", "lower-unquoted", "exact", "unknown"},
         "identifierPolicy")
    scopes = array(doc["scope"], "scope")
    for i, scope in enumerate(scopes):
        identity(scope, with_name=False)
        fields(scope, ("names", "status", "limitations"), "scope")
        enum(scope["status"], {"complete", "partial", "unavailable"}, "scope status")
        strings(scope["limitations"], "scope limitations")
        if scope["names"] is not None:
            strings(scope["names"], "scope names")
            check(bool(scope["names"]), "scope names must be nonempty or null")
            check(len(scope["names"]) == len(set(scope["names"])), "duplicate scope name")
        for prior in scopes[:i]:
            if all(scope[p] == prior[p] for p in KEY[:-1]):
                overlap = (scope["names"] is None or prior["names"] is None or
                           bool(set(scope["names"]) & set(prior["names"])))
                check(not overlap, "overlapping snapshot scopes")
    seen = set()
    for obj in array(doc["objects"], "objects"):
        identity(obj)
        check(key(obj) not in seen, "duplicate object identity")
        seen.add(key(obj))
        fields(obj, ("properties", "unknownProperties", "evidence"), "object")
        check(isinstance(obj["properties"], dict), "properties must be an object")
        strings(obj["unknownProperties"], "unknownProperties")
        check(not (set(obj["properties"]) & set(obj["unknownProperties"])),
              "a property cannot be both known and unknown")
        check(any(s["status"] != "unavailable" and contains(s, obj) for s in scopes),
              "object is outside readable declared scope")
        check(bool(array(obj["evidence"], "object evidence")), "object evidence cannot be empty")
        for item in obj["evidence"]:
            location(item)


def validate_report(doc):
    fields(doc, ("schemaVersion", "task", "analysisStatus", "request", "sources", "evidence",
                 "findings", "coverage", "unknowns", "excluded", "provenance"), "report")
    enum(doc["task"], TASKS, "task")
    enum(doc["analysisStatus"], {"completed", "partial", "blocked"}, "analysisStatus")
    check(isinstance(doc["request"], dict), "request must be an object")
    fields(doc["provenance"], ("skillVersion", "host", "model"), "provenance")
    for part in ("skillVersion", "host", "model"):
        string(doc["provenance"][part], "provenance." + part)
    source_ids = unique_ids(doc["sources"], "sources")
    for source in doc["sources"]:
        fields(source, ("kind", "location"), "source")
        string(source["kind"], "source.kind")
        string(source["location"], "source.location")
    evidence_ids = unique_ids(doc["evidence"], "evidence")
    for item in doc["evidence"]:
        fields(item, ("sourceId", "location"), "evidence")
        check(isinstance(item["sourceId"], str) and item["sourceId"] in source_ids,
              "unresolved sourceId")
        location(item["location"])

    def evidence_refs(item):
        fields(item, ("evidenceIds",), "evidenced item")
        refs = array(item["evidenceIds"], "evidenceIds")
        check(bool(refs) and all(isinstance(r, str) and r in evidence_ids for r in refs),
              "empty or unresolved evidenceIds")

    unique_ids(doc["findings"], "findings")
    for finding in doc["findings"]:
        fields(finding, ("target", "claim", "basis", "impact", "conditions", "nextActions"), "finding")
        enum(finding["basis"], {"confirmed", "inferred"}, "finding basis")
        string(finding["claim"], "finding claim")
        string(finding["impact"], "finding impact")
        strings(finding["conditions"], "conditions")
        strings(finding["nextActions"], "nextActions")
        evidence_refs(finding)
    for item in array(doc["coverage"], "coverage"):
        fields(item, ("scope", "status", "checked", "limitations"), "coverage")
        enum(item["status"], {"inspected", "partial", "unavailable", "not-applicable"}, "coverage status")
        strings(item["checked"], "coverage.checked")
        strings(item["limitations"], "coverage.limitations")
    for item in array(doc["unknowns"], "unknowns"):
        fields(item, ("gap", "affectedClaims", "nextCheck"), "unknown")
        string(item["gap"], "unknown gap")
        string(item["nextCheck"], "unknown nextCheck")
        strings(item["affectedClaims"], "unknown affectedClaims")
    array(doc["excluded"], "excluded")
    if "sourcePlan" in doc:
        fields(doc["sourcePlan"], ("algorithm", "id"), "sourcePlan")
        string(doc["sourcePlan"]["algorithm"], "sourcePlan.algorithm")
        string(doc["sourcePlan"]["id"], "sourcePlan.id")
    if doc["task"] == "compatibility":
        fields(doc, ("diagnostics", "ruleCoverage"), "compatibility report")
        unique_ids(doc["diagnostics"], "diagnostics")
        for item in doc["diagnostics"]:
            fields(item, ("code", "severity", "basis", "ruleSource", "applicability",
                          "targetDatabase", "statementLocation", "message", "nextCheck"), "diagnostic")
            for part in ("code", "ruleSource", "message", "nextCheck"):
                string(item[part], "diagnostic." + part)
            enum(item["severity"], {"info", "warning", "error"}, "severity")
            enum(item["basis"], {"confirmed", "inferred"}, "diagnostic basis")
            enum(item["applicability"], {"applicable", "conditional", "unknown", "not-applicable"}, "applicability")
            check(isinstance(item["targetDatabase"], dict), "targetDatabase must be an object")
            location(item["statementLocation"])
            evidence_refs(item)
        for item in array(doc["ruleCoverage"], "ruleCoverage"):
            fields(item, ("code", "status", "reason"), "ruleCoverage")
            enum(item["status"], {"match", "not-matched", "unknown", "not-applicable"}, "rule status")
            string(item["code"], "rule code")
            string(item["reason"], "rule reason")
    elif doc["task"] in {"dependencies", "references"}:
        fields(doc, ("nodes", "edges"), "graph report")
        node_ids = unique_ids(doc["nodes"], "nodes")
        unique_ids(doc["edges"], "edges")
        for item in doc["nodes"]:
            fields(item, ("kind", "label"), "node")
            string(item["kind"], "node.kind")
            string(item["label"], "node.label")
        for item in doc["edges"]:
            fields(item, ("from", "to", "relation", "basis"), "edge")
            check(all(isinstance(item[p], str) and item[p] in node_ids for p in ("from", "to")),
                  "edge references unknown node")
            enum(item["basis"], {"confirmed", "inferred"}, "edge basis")
            string(item["relation"], "edge relation")
            evidence_refs(item)
    elif doc["task"] == "drift":
        fields(doc, ("changes",), "drift report")
        for item in array(doc["changes"], "changes"):
            fields(item, ("kind", "target", "property", "before", "after"), "change")
            enum(item["kind"], {"added", "removed", "changed"}, "change kind")
            identity(item["target"])
            string(item["property"], "change property", nullable=True)
            evidence_refs(item)


def validate(doc):
    fields(doc, ("schemaVersion",), "artifact")
    if doc["schemaVersion"] == SNAPSHOT:
        validate_snapshot(doc)
    elif doc["schemaVersion"] == REPORT:
        validate_report(doc)
    else:
        raise ValueError("unsupported schemaVersion")


def reject_duplicates(pairs):
    result = {}
    for k, value in pairs:
        check(k not in result, "duplicate JSON key")
        result[k] = value
    return result


def reject_constant(value):
    raise ValueError("non-finite JSON number is not supported")


def load(path):
    doc = json.loads(Path(path).read_text(encoding="utf-8-sig"),
                     object_pairs_hook=reject_duplicates, parse_constant=reject_constant)
    validate(doc)
    return doc


def compare(before, after, before_path="baseline.json", after_path="observed.json"):
    for doc in (before, after):
        validate(doc)
        check(doc["schemaVersion"] == SNAPSHOT, "diff inputs must be schema snapshots")
    report = {"schemaVersion": REPORT, "task": "drift", "analysisStatus": "completed",
              "request": {"baseline": str(before_path), "observed": str(after_path)},
              "sources": [], "evidence": [], "findings": [], "coverage": [], "unknowns": [],
              "excluded": [], "changes": [],
              "provenance": {"skillVersion": "0.2.0", "host": "analysis_artifacts.py", "model": "not-used"}}

    def unknown(gap, next_check, affected=None):
        report["unknowns"].append({"gap": gap, "affectedClaims": affected or [], "nextCheck": next_check})
        report["analysisStatus"] = "partial"

    evidence_map = {}
    for side, doc, path in (("baseline", before, before_path), ("observed", after, after_path)):
        report["sources"].append({"id": side, "kind": "snapshot", "location": str(path),
                                  "originalSource": doc["source"], "database": doc["database"]})
        if not doc["scope"]:
            unknown(side + " 未声明任何可比较范围", "补充 scope 与材料来源")
        for scope in doc["scope"]:
            report["coverage"].append({"scope": {"source": side, **scope},
                                       "status": "inspected" if scope["status"] == "complete" else scope["status"],
                                       "checked": ["按声明范围比较快照事实"], "limitations": scope["limitations"]})
            if scope["status"] != "complete":
                unknown(side + " 存在未完整列举的对象范围", "补充相应类型的对象清单和读取记录")
        for i, obj in enumerate(doc["objects"]):
            refs = []
            for j, loc in enumerate(obj["evidence"]):
                eid = f"{side}-{i + 1}-{j + 1}"
                report["evidence"].append({"id": eid, "sourceId": side, "location": loc})
                refs.append(eid)
            evidence_map[(side, key(obj))] = refs
    comparable = True
    for part in ("product", "mode", "identifierPolicy"):
        left, right = before["database"][part], after["database"][part]
        if left in (None, "unknown") or right in (None, "unknown") or left != right:
            comparable = False
            unknown("两份快照的 " + part + " 不同或未知", "确认产品和名称规则，记录显式对齐后重新比较")
    if not comparable:
        validate_report(report)
        return report
    for side, doc, other in (("baseline", before, after), ("observed", after, before)):
        for scope in doc["scope"]:
            matching = [s for s in other["scope"] if s["status"] == "complete" and
                        all(s[p] == scope[p] for p in KEY[:-1])]
            fully_covered = any(s["names"] is None for s in matching)
            if not fully_covered and scope["names"] is not None:
                available = {name for s in matching for name in s["names"]}
                fully_covered = set(scope["names"]) <= available
            if not fully_covered:
                unknown(side + " 的声明范围在另一侧没有完整对应范围", "对齐双方 scope 后重新比较")
    left = {key(obj): obj for obj in before["objects"]}
    right = {key(obj): obj for obj in after["objects"]}
    for oid in sorted(left.keys() | right.keys(), key=lambda k: json.dumps(k, ensure_ascii=False)):
        old, new = left.get(oid), right.get(oid)
        obj = old if old is not None else new
        target = {part: obj[part] for part in KEY}
        target_name = ".".join(str(p) for p in oid if p is not None)
        if obj["schema"] is None:
            unknown("对象 schema 未知：" + target_name, "补充明确对象身份")
            continue
        refs = evidence_map.get(("baseline", oid), []) + evidence_map.get(("observed", oid), [])
        if old is None or new is None:
            absent = before if old is None else after
            if not any(s["status"] == "complete" and contains(s, obj) for s in absent["scope"]):
                unknown("缺失一侧无法证明对象不存在：" + target_name, "完整列举缺失一侧的对应范围")
                continue
            report["changes"].append({"kind": "added" if old is None else "removed", "target": target,
                                       "property": None, "before": old["properties"] if old else None,
                                       "after": new["properties"] if new else None, "evidenceIds": refs})
            continue
        properties = (old["properties"].keys() | new["properties"].keys() |
                      set(old["unknownProperties"]) | set(new["unknownProperties"]))
        for prop in sorted(properties):
            if prop not in old["properties"] or prop not in new["properties"]:
                unknown("属性没有双侧已知值：" + target_name + "." + prop, "补充两侧属性；null 仅用于确定没有该值")
            elif json.dumps(old["properties"][prop], sort_keys=True, ensure_ascii=False) != json.dumps(
                    new["properties"][prop], sort_keys=True, ensure_ascii=False):
                report["changes"].append({"kind": "changed", "target": target, "property": prop,
                                           "before": old["properties"][prop], "after": new["properties"][prop],
                                           "evidenceIds": refs})
    validate_report(report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validator = commands.add_parser("validate", help="validate JSON artifacts; does not certify semantics")
    validator.add_argument("files", nargs="+")
    differ = commands.add_parser("diff", help="compare schema snapshot facts, without database access")
    differ.add_argument("baseline")
    differ.add_argument("observed")
    differ.add_argument("--out", required=True, help="new output file; existing files are preserved")
    args = parser.parse_args()
    try:
        if args.command == "validate":
            for filename in args.files:
                load(filename)
                print("VALID " + filename)
        else:
            before, after = load(args.baseline), load(args.observed)
            report = compare(before, after, args.baseline, args.observed)
            for source, filename in zip(report["sources"], (args.baseline, args.observed)):
                source["snapshotSha256"] = hashlib.sha256(Path(filename).read_bytes()).hexdigest()
            output = Path(args.out)
            output.parent.mkdir(parents=True, exist_ok=True)
            with output.open("x", encoding="utf-8") as handle:
                json.dump(report, handle, ensure_ascii=False, indent=2, allow_nan=False)
                handle.write("\n")
            print(f"{report['analysisStatus']}: {len(report['changes'])} changes, "
                  f"{len(report['unknowns'])} unknowns -> {output}")
        return 0
    except (ValueError, OSError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
