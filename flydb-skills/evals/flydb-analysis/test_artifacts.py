"""Run with python3 -m unittest discover -s flydb-skills/evals/flydb-analysis -p 'test_*.py'."""

import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "skills/flydb-analysis/scripts/analysis_artifacts.py"
SPEC = importlib.util.spec_from_file_location("artifacts", SCRIPT)
artifacts = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(artifacts)


def snapshot(names=("STATUS",), status="complete"):
    return {
        "schemaVersion": artifacts.SNAPSHOT,
        "source": {"label": "fixture", "kind": "ddl", "location": "schema.sql",
                   "environment": "test", "capturedAt": None, "revision": None},
        "database": {"product": "Oracle", "mode": "default", "version": None,
                     "identifierPolicy": "upper-unquoted"},
        "scope": [{"catalog": None, "schema": "APP", "kind": "column", "parent": "ORDERS",
                   "names": None, "status": status, "limitations": []}],
        "objects": [{"catalog": None, "schema": "APP", "kind": "column", "parent": "ORDERS", "name": name,
                     "properties": {"dataType": "VARCHAR2(16)", "nullable": True, "default": None},
                     "unknownProperties": [], "evidence": [{"path": "schema.sql", "lineStart": 1, "lineEnd": 1}]}
                    for name in names]}


class ArtifactTests(unittest.TestCase):
    def test_same_known_facts(self):
        result = artifacts.compare(snapshot(), snapshot())
        self.assertEqual((result["changes"], result["unknowns"], result["analysisStatus"]), ([], [], "completed"))

    def test_complete_absence_proves_add_remove(self):
        result = artifacts.compare(snapshot(("OLD",)), snapshot(("NEW",)))
        self.assertEqual({(c["kind"], c["target"]["name"]) for c in result["changes"]},
                         {("removed", "OLD"), ("added", "NEW")})

    def test_incomplete_absence_is_unknown(self):
        result = artifacts.compare(snapshot(), snapshot((), "partial"))
        self.assertEqual(result["changes"], [])
        self.assertEqual(result["analysisStatus"], "partial")
        self.assertTrue(result["unknowns"])

    def test_known_null_is_different_from_unknown(self):
        old, new = snapshot(), snapshot()
        new["objects"][0]["properties"]["default"] = "'NEW'"
        result = artifacts.compare(old, new)
        self.assertEqual(result["changes"][0]["before"], None)
        del old["objects"][0]["properties"]["default"]
        result = artifacts.compare(old, new)
        self.assertEqual(result["changes"], [])
        self.assertEqual(result["analysisStatus"], "partial")

    def test_unknown_in_both_inputs_is_reported(self):
        old, new = snapshot(), snapshot()
        for doc in (old, new):
            doc["objects"][0]["unknownProperties"] = ["collation"]
        self.assertTrue(artifacts.compare(old, new)["unknowns"])

    def test_quoted_case_stays_distinct(self):
        result = artifacts.compare(snapshot(("Status",)), snapshot(("STATUS",)))
        self.assertEqual(len(result["changes"]), 2)

    def test_incomparable_product_and_unknown_policy(self):
        old, new = snapshot(), snapshot(("NEW",))
        new["database"]["product"] = "PostgreSQL"
        self.assertEqual(artifacts.compare(old, new)["changes"], [])
        new["database"] = copy.deepcopy(old["database"])
        new["database"]["identifierPolicy"] = "unknown"
        self.assertEqual(artifacts.compare(old, new)["analysisStatus"], "partial")

    def test_empty_or_different_scopes_do_not_prove_equality(self):
        old, new = snapshot(()), snapshot(())
        new["scope"] = []
        self.assertEqual(artifacts.compare(old, new)["analysisStatus"], "partial")
        new = snapshot(())
        new["scope"][0]["parent"] = "CUSTOMER"
        self.assertEqual(artifacts.compare(old, new)["analysisStatus"], "partial")

    def test_name_subset_cannot_prove_absence_outside_subset(self):
        old, new = snapshot(), snapshot(())
        new["scope"][0]["names"] = ["ID"]
        result = artifacts.compare(old, new)
        self.assertEqual(result["changes"], [])
        self.assertEqual(result["analysisStatus"], "partial")

    def test_repeated_gaps_preserve_each_affected_scope(self):
        old, new = snapshot((), "partial"), snapshot((), "partial")
        for doc in (old, new):
            scope = copy.deepcopy(doc["scope"][0])
            scope["parent"] = "CUSTOMER"
            doc["scope"].append(scope)
        result = artifacts.compare(old, new)
        gaps = [u for u in result["unknowns"] if u["gap"] == "baseline 存在未完整列举的对象范围"]
        self.assertEqual(len(gaps), 1)
        self.assertEqual({json.loads(s)["parent"] for s in gaps[0]["affectedClaims"]}, {"ORDERS", "CUSTOMER"})

    def test_duplicate_identity_and_overlapping_scope_rejected(self):
        doc = snapshot()
        doc["objects"].append(copy.deepcopy(doc["objects"][0]))
        with self.assertRaises(ValueError):
            artifacts.validate(doc)
        doc = snapshot()
        doc["scope"].append(copy.deepcopy(doc["scope"][0]))
        with self.assertRaises(ValueError):
            artifacts.validate(doc)

    def test_missing_scope_or_evidence_rejected(self):
        doc = snapshot()
        doc["scope"] = []
        with self.assertRaises(ValueError):
            artifacts.validate(doc)
        doc = snapshot()
        doc["objects"][0]["evidence"][0]["lineStart"] = 0
        with self.assertRaises(ValueError):
            artifacts.validate(doc)

    def test_dictionary_order_is_not_drift_but_list_order_is(self):
        old, new = snapshot(), snapshot()
        old["objects"][0]["properties"] = {"nested": {"a": 1, "b": 2}, "order": [1, 2]}
        new["objects"][0]["properties"] = {"nested": {"b": 2, "a": 1}, "order": [2, 1]}
        self.assertEqual([c["property"] for c in artifacts.compare(old, new)["changes"]], ["order"])

    def test_unresolved_evidence_and_graph_endpoint_rejected(self):
        doc = artifacts.compare(snapshot(), snapshot(("NEW",)))
        doc["evidence"][0]["sourceId"] = "missing"
        with self.assertRaises(ValueError):
            artifacts.validate(doc)
        doc = artifacts.compare(snapshot(), snapshot())
        doc.update(task="dependencies", nodes=[{"id": "a", "kind": "table", "label": "A"}],
                   edges=[{"id": "edge", "from": "a", "to": "missing", "relation": "reads",
                           "basis": "confirmed", "evidenceIds": [doc["evidence"][0]["id"]]}])
        with self.assertRaises(ValueError):
            artifacts.validate(doc)

    def test_cli_diff_preserves_inputs_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            before, after, output = [directory / p for p in ("before.json", "after.json", "diff.json")]
            before.write_text(json.dumps(snapshot()), encoding="utf-8")
            after.write_text(json.dumps(snapshot(("NEW",))), encoding="utf-8")
            original = (before.read_bytes(), after.read_bytes())
            command = [sys.executable, str(SCRIPT), "diff", str(before), str(after), "--out", str(output)]
            first = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            artifacts.load(output)
            saved = output.read_bytes()
            second = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(second.returncode, 2)
            self.assertEqual(output.read_bytes(), saved)
            self.assertEqual((before.read_bytes(), after.read_bytes()), original)

    def test_cli_rejects_duplicate_keys_and_invalid_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "invalid.json"
            for content in ('{"schemaVersion":"x","schemaVersion":"y"}', '{', '{"schemaVersion":NaN}'):
                path.write_text(content, encoding="utf-8")
                result = subprocess.run([sys.executable, str(SCRIPT), "validate", str(path)],
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 2)
                self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
