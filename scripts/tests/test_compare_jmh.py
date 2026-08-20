#!/usr/bin/env python3

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
COMPARATOR = ROOT / "scripts" / "compare-jmh.py"


def result(
    benchmark="com.salesforce.revoman.benchmark.RuntimeLifecycleBenchmark.scriptFreeOneStep",
    time_score=100.0,
    allocation_score=1_000.0,
    time_unit="us/op",
    allocation_unit="B/op",
    params=None,
):
    return {
        "benchmark": benchmark,
        "mode": "avgt",
        "params": params or {},
        "primaryMetric": {"score": time_score, "scoreUnit": time_unit},
        "secondaryMetrics": {
            "gc.alloc.rate.norm": {
                "score": allocation_score,
                "scoreUnit": allocation_unit,
            }
        },
    }


class CompareJmhTest(unittest.TestCase):
    def compare(self, baseline, candidate, extra_args=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            baseline_path = root / "baseline.json"
            candidate_path = root / "candidate.json"
            markdown_path = root / "summary.md"
            json_path = root / "summary.json"
            self.write_json(baseline_path, baseline)
            self.write_json(candidate_path, candidate)

            command = [
                sys.executable,
                str(COMPARATOR),
                str(baseline_path),
                str(candidate_path),
                "--markdown-out",
                str(markdown_path),
                "--json-out",
                str(json_path),
                "--baseline-sha",
                "baseline-sha",
                "--candidate-sha",
                "candidate-sha",
            ]
            command.extend(extra_args or [])
            completed = subprocess.run(
                command,
                text=True,
                capture_output=True,
                check=False,
            )
            summary = json.loads(json_path.read_text()) if json_path.exists() else None
            markdown = markdown_path.read_text() if markdown_path.exists() else ""
            return completed, summary, markdown

    @staticmethod
    def write_json(path, value):
        if isinstance(value, str):
            path.write_text(value)
        else:
            path.write_text(json.dumps(value))

    def assert_status(self, baseline, candidate, exit_code, status):
        completed, summary, markdown = self.compare(baseline, candidate)
        self.assertEqual(completed.returncode, exit_code, completed.stderr)
        self.assertEqual(summary["status"], status)
        self.assertIn(status, markdown)
        self.assertEqual(summary["baselineSha"], "baseline-sha")
        self.assertEqual(summary["candidateSha"], "candidate-sha")

    def test_passes_change_within_limit(self):
        self.assert_status([result()], [result(time_score=119.9, allocation_score=1_199)], 0, "PASS")

    def test_passes_improvement(self):
        self.assert_status([result()], [result(time_score=80, allocation_score=700)], 0, "PASS")

    def test_passes_exact_twenty_percent_boundary(self):
        self.assert_status([result()], [result(time_score=120, allocation_score=1_200)], 0, "PASS")

    def test_fails_material_regression(self):
        self.assert_status([result()], [result(time_score=120.1)], 1, "REGRESSION")

    def test_rejects_missing_benchmark(self):
        self.assert_status([result()], [], 2, "INCOMPARABLE")

    def test_rejects_required_benchmark_missing_from_both_files(self):
        completed, summary, _ = self.compare(
            [result()],
            [result()],
            ["--require-benchmark", "example.RequiredBenchmark.operation"],
        )
        self.assertEqual(completed.returncode, 2, completed.stderr)
        self.assertEqual(summary["status"], "INCOMPARABLE")
        self.assertIn("missing required benchmarks", summary["errors"][0])

    def test_rejects_mismatched_unit(self):
        self.assert_status([result()], [result(time_unit="ns/op")], 2, "INCOMPARABLE")

    def test_rejects_mismatched_parameters_or_mode(self):
        wrong_params = result(params={"size": "large"})
        wrong_mode = result()
        wrong_mode["mode"] = "thrpt"
        for candidate in ([wrong_params], [wrong_mode]):
            with self.subTest(candidate=candidate):
                self.assert_status([result()], candidate, 2, "INCOMPARABLE")

    def test_rejects_missing_allocation_metric(self):
        candidate = result()
        candidate["secondaryMetrics"] = {}
        self.assert_status([result()], [candidate], 2, "INCOMPARABLE")

    def test_rejects_malformed_and_empty_json(self):
        for candidate in ("{", [], ""):
            with self.subTest(candidate=candidate):
                self.assert_status([result()], candidate, 2, "INCOMPARABLE")

    def test_rejects_non_finite_or_non_positive_score(self):
        for score in (float("nan"), float("inf"), 0, -1):
            with self.subTest(score=score):
                self.assert_status([result()], [result(time_score=score)], 2, "INCOMPARABLE")

    def test_rejects_duplicate_records_and_benchmark_errors(self):
        duplicate = [result(), result()]
        for error in ("benchmark failed", "", False, 0):
            failed = result()
            failed["error"] = error
            with self.subTest(error=error):
                self.assert_status([result()], [failed], 2, "INCOMPARABLE")
        self.assert_status([result()], duplicate, 2, "INCOMPARABLE")


if __name__ == "__main__":
    unittest.main()
