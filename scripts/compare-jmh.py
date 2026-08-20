#!/usr/bin/env python3
"""Compare paired JMH JSON results with a small, fail-closed regression gate."""

import argparse
import json
import math
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path


PASS = "PASS"
REGRESSION = "REGRESSION"
INCOMPARABLE = "INCOMPARABLE"
EXIT_CODES = {PASS: 0, REGRESSION: 1, INCOMPARABLE: 2}
ALLOCATION_METRIC = "gc.alloc.rate.norm"


class ComparisonError(ValueError):
    pass


def reject_non_finite_json(value):
    raise ComparisonError(f"non-finite JSON value: {value}")


def load_results(path):
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as error:
        raise ComparisonError(f"cannot read {path}: {error}") from error
    if not raw.strip():
        raise ComparisonError(f"{path} is empty")
    try:
        document = json.loads(raw, parse_constant=reject_non_finite_json)
    except (json.JSONDecodeError, ComparisonError) as error:
        raise ComparisonError(f"invalid JSON in {path}: {error}") from error
    if not isinstance(document, list) or not document:
        raise ComparisonError(f"{path} must contain a non-empty JSON array")

    indexed = {}
    for index, row in enumerate(document):
        if not isinstance(row, dict):
            raise ComparisonError(f"{path} result {index} is not an object")
        benchmark = require_text(row, "benchmark", path, index)
        mode = require_text(row, "mode", path, index)
        params = row.get("params", {})
        if not isinstance(params, dict):
            raise ComparisonError(f"{path} result {index} has non-object params")
        key = (benchmark, canonical_params(params), mode)
        if key in indexed:
            raise ComparisonError(f"{path} contains duplicate result {format_key(key)}")
        if "error" in row and row["error"] is not None:
            raise ComparisonError(
                f"{path} result {format_key(key)} reports error: {row['error']!r}"
            )
        indexed[key] = row
    return indexed


def require_text(row, field, path, index):
    value = row.get(field)
    if not isinstance(value, str) or not value:
        raise ComparisonError(f"{path} result {index} has invalid {field}")
    return value


def canonical_params(params):
    try:
        return json.dumps(params, sort_keys=True, separators=(",", ":"), allow_nan=False)
    except (TypeError, ValueError) as error:
        raise ComparisonError(f"invalid benchmark params: {error}") from error


def format_key(key):
    benchmark, params, mode = key
    return f"{benchmark} params={params} mode={mode}"


def metric(row, key, metric_name):
    if metric_name == "primaryMetric":
        value = row.get("primaryMetric")
        expected_unit = "us/op"
    else:
        secondary = row.get("secondaryMetrics")
        if not isinstance(secondary, dict):
            raise ComparisonError(f"{format_key(key)} has invalid secondaryMetrics")
        value = secondary.get(metric_name)
        expected_unit = "B/op"
    if not isinstance(value, dict):
        raise ComparisonError(f"{format_key(key)} is missing {metric_name}")

    unit = value.get("scoreUnit")
    if unit != expected_unit:
        raise ComparisonError(
            f"{format_key(key)} {metric_name} unit must be {expected_unit}, was {unit!r}"
        )
    score = positive_decimal(value.get("score"), f"{format_key(key)} {metric_name}")
    return score, unit


def positive_decimal(value, label):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ComparisonError(f"{label} score is not numeric")
    if isinstance(value, float) and not math.isfinite(value):
        raise ComparisonError(f"{label} score is not finite")
    try:
        score = Decimal(str(value))
    except InvalidOperation as error:
        raise ComparisonError(f"{label} score is invalid") from error
    if not score.is_finite() or score <= 0:
        raise ComparisonError(f"{label} score must be finite and positive")
    return score


def compare(baseline, candidate, threshold):
    baseline_keys = set(baseline)
    candidate_keys = set(candidate)
    if baseline_keys != candidate_keys:
        missing = sorted(baseline_keys - candidate_keys)
        extra = sorted(candidate_keys - baseline_keys)
        details = []
        if missing:
            details.append("missing candidate results: " + "; ".join(map(format_key, missing)))
        if extra:
            details.append("unexpected candidate results: " + "; ".join(map(format_key, extra)))
        raise ComparisonError("; ".join(details))

    comparisons = []
    limit = Decimal(1) + threshold
    for key in sorted(baseline_keys):
        if key[2] != "avgt":
            raise ComparisonError(f"{format_key(key)} mode must be avgt")
        for metric_name, label in (
            ("primaryMetric", "time"),
            (ALLOCATION_METRIC, "allocation"),
        ):
            baseline_score, baseline_unit = metric(baseline[key], key, metric_name)
            candidate_score, candidate_unit = metric(candidate[key], key, metric_name)
            if baseline_unit != candidate_unit:
                raise ComparisonError(
                    f"{format_key(key)} {metric_name} unit mismatch: "
                    f"{baseline_unit} vs {candidate_unit}"
                )
            ratio = candidate_score / baseline_score
            status = REGRESSION if ratio > limit else PASS
            comparisons.append(
                {
                    "benchmark": key[0],
                    "params": json.loads(key[1]),
                    "mode": key[2],
                    "metric": label,
                    "unit": baseline_unit,
                    "baseline": float(baseline_score),
                    "candidate": float(candidate_score),
                    "ratio": float(ratio),
                    "changePercent": float((ratio - Decimal(1)) * Decimal(100)),
                    "status": status,
                }
            )
    status = REGRESSION if any(row["status"] == REGRESSION for row in comparisons) else PASS
    return status, comparisons


def validate_required(results, required, label):
    present = {key[0] for key in results}
    missing = sorted(set(required) - present)
    if missing:
        raise ComparisonError(f"{label} is missing required benchmarks: {'; '.join(missing)}")


def render_markdown(summary):
    lines = [
        f"## JMH comparison: {summary['status']}",
        "",
        f"Threshold: candidate must not exceed baseline by more than {summary['thresholdPercent']:g}%.",
        "",
    ]
    if summary["errors"]:
        lines.extend(["| Error |", "| --- |"])
        lines.extend(f"| {escape_markdown(error)} |" for error in summary["errors"])
        return "\n".join(lines) + "\n"

    lines.extend(
        [
            "| Benchmark | Params | Metric | Baseline | Candidate | Change | Verdict |",
            "| --- | --- | --- | ---: | ---: | ---: | --- |",
        ]
    )
    for row in summary["comparisons"]:
        params = ", ".join(f"{key}={value}" for key, value in sorted(row["params"].items())) or "-"
        lines.append(
            "| {benchmark} | {params} | {metric} ({unit}) | {baseline:.6g} | "
            "{candidate:.6g} | {change:+.2f}% | {status} |".format(
                benchmark=escape_markdown(row["benchmark"]),
                params=escape_markdown(params),
                metric=row["metric"],
                unit=row["unit"],
                baseline=row["baseline"],
                candidate=row["candidate"],
                change=row["changePercent"],
                status=row["status"],
            )
        )
    return "\n".join(lines) + "\n"


def escape_markdown(value):
    return str(value).replace("|", "\\|").replace("\n", " ")


def write_output(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--threshold", type=Decimal, default=Decimal("0.20"))
    parser.add_argument("--markdown-out", type=Path, required=True)
    parser.add_argument("--json-out", type=Path, required=True)
    parser.add_argument("--baseline-sha", default="unknown")
    parser.add_argument("--candidate-sha", default="unknown")
    parser.add_argument("--require-benchmark", action="append", default=[])
    return parser.parse_args()


def main():
    args = arguments()
    errors = []
    comparisons = []
    status = INCOMPARABLE
    if not args.threshold.is_finite() or args.threshold < 0:
        errors.append("threshold must be finite and non-negative")
    else:
        try:
            baseline = load_results(args.baseline)
            candidate = load_results(args.candidate)
            validate_required(baseline, args.require_benchmark, "baseline")
            validate_required(candidate, args.require_benchmark, "candidate")
            status, comparisons = compare(baseline, candidate, args.threshold)
        except ComparisonError as error:
            errors.append(str(error))

    summary = {
        "status": status,
        "thresholdPercent": float(args.threshold * Decimal(100)),
        "baselineSha": args.baseline_sha,
        "candidateSha": args.candidate_sha,
        "requiredBenchmarks": sorted(set(args.require_benchmark)),
        "comparisons": comparisons,
        "errors": errors,
    }
    markdown = render_markdown(summary)
    write_output(args.markdown_out, markdown)
    write_output(args.json_out, json.dumps(summary, indent=2, sort_keys=True) + "\n")
    sys.stdout.write(markdown)
    return EXIT_CODES[status]


if __name__ == "__main__":
    raise SystemExit(main())
