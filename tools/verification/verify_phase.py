"""Phase acceptance gate for TaskPilot remediation verification.

Validates Maven Surefire XML reports against a per-phase requirements manifest,
enforcing current run identifier (`remediation.runId`) and file mtime freshness
relative to `started_at`, plus optional XML timestamp freshness, strict integrity, and
isolated module report discovery (`reports` directly or immediate
`taskpilot-*/target/surefire-reports` directories only).

Local naive Surefire `timestamp` attributes are resolved deterministically:
1. If XML `<properties>` provides `<property name="user.timezone" value="..."/>`,
   explicit UTC/GMT offsets are parsed directly and IANA zone names are resolved
   via `zoneinfo.ZoneInfo` when host timezone data is available.
2. Otherwise (absent property, or host lacking `tzdata` for IANA lookups), the
   current host local timezone (`datetime.now().astimezone().tzinfo`) is used.
"""

import argparse
from datetime import datetime, timedelta, timezone, tzinfo
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo


_OFFSET_TZ_RE = re.compile(r"^(?:GMT|UTC)?([+-])(\d{1,2})(?::?(\d{2}))?$", re.IGNORECASE)
_NON_NEG_INT_RE = re.compile(r"^\d+$")
_JAVA_FQN_RE = re.compile(r"^[A-Za-z_][\w$]*(?:\.[A-Za-z_][\w$]*)+$")
_SIMPLE_IDENT_RE = re.compile(r"^[A-Za-z_][\w$]*$")


def _is_valid_testcase_classname(suite_class: str, case_class: str | None) -> bool:
    if not case_class or not case_class.strip():
        return False
    cleaned = case_class.strip()
    if cleaned == suite_class:
        return True
    if cleaned.startswith(f"{suite_class}$") and len(cleaned) > len(suite_class) + 1:
        return True
    if _JAVA_FQN_RE.match(cleaned):
        return False
    if _SIMPLE_IDENT_RE.match(cleaned):
        return cleaned == suite_class.rsplit(".", 1)[-1]
    return True


def _host_local_timezone() -> tzinfo:
    return datetime.now().astimezone().tzinfo or timezone.utc


def _resolve_timezone(tz_name: str | None) -> tzinfo:
    if tz_name is None or not tz_name.strip():
        return _host_local_timezone()

    tz = tz_name.strip()
    if tz.upper() in ("UTC", "Z", "GMT"):
        return timezone.utc

    match = _OFFSET_TZ_RE.match(tz)
    if match:
        sign = -1 if match.group(1) == "-" else 1
        hours = int(match.group(2))
        minutes = int(match.group(3) or "0")
        if hours > 23 or minutes > 59:
            raise ValueError(f"INVALID_USER_TIMEZONE_OFFSET: {tz}")
        return timezone(sign * timedelta(hours=hours, minutes=minutes))

    try:
        return ZoneInfo(tz)
    except Exception:
        try:
            ZoneInfo("UTC")
        except Exception:
            # Windows Python without tzdata package cannot resolve IANA keys.
            return _host_local_timezone()
        raise ValueError(f"INVALID_USER_TIMEZONE: {tz}")


def _parse_iso_datetime(
    raw: str,
    *,
    fallback_tz: tzinfo | None = None,
    require_aware: bool = False,
) -> datetime:
    if not isinstance(raw, str) or not raw.strip():
        raise ValueError("EMPTY_TIMESTAMP")
    normalized = raw.strip()
    if normalized.endswith(("Z", "z")):
        normalized = normalized[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise ValueError(f"INVALID_TIMESTAMP: {raw}") from exc

    if parsed.tzinfo is None or parsed.utcoffset() is None:
        if require_aware:
            raise ValueError(f"NAIVE_TIMESTAMP_NOT_ALLOWED: {raw}")
        tz = fallback_tz if fallback_tz is not None else _host_local_timezone()
        parsed = parsed.replace(tzinfo=tz)

    return parsed.astimezone(timezone.utc)


def _load_required_suites(manifest_path: Path, phase: str) -> dict[str, list[str]]:
    if not isinstance(phase, str) or not phase.strip() or phase != phase.strip():
        raise ValueError(f"INVALID_PHASE: {phase!r}")
    if not manifest_path.is_file():
        raise ValueError(f"MISSING_MANIFEST: {manifest_path}")

    try:
        data = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"MALFORMED_MANIFEST: {manifest_path}") from exc

    if not isinstance(data, dict) or not isinstance(data.get("phases"), dict):
        raise ValueError("MALFORMED_MANIFEST_PHASES")

    phases = data["phases"]
    if phase not in phases:
        raise ValueError(f"UNKNOWN_PHASE: {phase}")

    phase_cfg = phases[phase]
    if not isinstance(phase_cfg, dict) or not isinstance(phase_cfg.get("required"), dict):
        raise ValueError(f"MALFORMED_PHASE_ENTRY: {phase}")

    required = phase_cfg["required"]
    if not required:
        raise ValueError(f"EMPTY_REQUIRED_SUITES: {phase}")

    validated: dict[str, list[str]] = {}
    for class_name, methods in required.items():
        if not isinstance(class_name, str) or not class_name.strip() or any(ch.isspace() for ch in class_name):
            raise ValueError(f"INVALID_REQUIRED_CLASS: {class_name!r}")
        if not isinstance(methods, list) or not methods:
            raise ValueError(f"EMPTY_OR_INVALID_REQUIRED_METHODS: {class_name}")
        seen_methods: set[str] = set()
        cleaned_methods: list[str] = []
        for method in methods:
            if not isinstance(method, str) or not method.strip() or any(ch.isspace() for ch in method):
                raise ValueError(f"INVALID_REQUIRED_METHOD: {class_name}: {method!r}")
            if method in seen_methods:
                raise ValueError(f"DUPLICATE_REQUIRED_METHOD: {class_name}: {method}")
            seen_methods.add(method)
            cleaned_methods.append(method)
        validated[class_name] = cleaned_methods

    return validated


def _discover_report_dirs(reports_root: Path) -> list[Path]:
    if not reports_root.is_dir():
        raise ValueError(f"MISSING_REPORTS_DIRECTORY: {reports_root}")

    direct_xmls = list(reports_root.glob("TEST-*.xml"))
    module_dirs = [
        child / "target" / "surefire-reports"
        for child in sorted(reports_root.iterdir(), key=lambda p: p.name)
        if child.is_dir()
        and child.name.startswith("taskpilot-")
        and (child / "target" / "surefire-reports").is_dir()
    ]
    if direct_xmls and module_dirs:
        raise ValueError(f"AMBIGUOUS_REPORTS_ROOT: {reports_root}")
    return module_dirs if module_dirs else [reports_root]


def _parse_non_negative_int(element: ET.Element, attr: str, class_name: str) -> int:
    raw = element.get(attr)
    if raw is None or not _NON_NEG_INT_RE.match(raw.strip()):
        raise ValueError(f"INVALID_SUITE_COUNTER({attr}): {class_name}")
    return int(raw.strip())


def _extract_properties(root: ET.Element, class_name: str) -> tuple[list[str], str | None]:
    props_el = root.find("properties")
    if props_el is None:
        return [], None

    run_ids: list[str] = []
    user_timezones: list[str] = []
    for prop in props_el.findall("property"):
        prop_name = prop.get("name")
        if prop_name == "remediation.runId":
            run_ids.append(prop.get("value") or "")
        elif prop_name == "user.timezone":
            user_timezones.append(prop.get("value") or "")

    if len(user_timezones) > 1:
        raise ValueError(f"DUPLICATE_USER_TIMEZONE: {class_name}")
    return run_ids, (user_timezones[0] if user_timezones else None)


def _verify_suite_xml(
    xml_path: Path,
    class_name: str,
    required_methods: list[str],
    run_id: str,
    started_at_utc: datetime,
) -> int:
    mtime_utc = datetime.fromtimestamp(xml_path.stat().st_mtime, tz=timezone.utc)
    if mtime_utc < started_at_utc:
        raise ValueError(f"STALE_REPORT_MTIME: {class_name}")

    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError as exc:
        raise ValueError(f"MALFORMED_SUREFIRE_XML: {class_name}") from exc

    if root.tag != "testsuite" or root.get("name") != class_name:
        raise ValueError(f"INVALID_SUITE_ROOT: {class_name}")

    run_ids, user_timezone = _extract_properties(root, class_name)
    if len(run_ids) != 1 or not run_ids[0].strip() or run_ids[0] != run_id:
        raise ValueError(f"RUN_ID_MISMATCH: {class_name}")

    raw_ts = root.get("timestamp")
    # Surefire's standard 3.0.2 XML omits timestamp. Never manufacture it:
    # require both a fresh file and this invocation's unique run ID instead.
    # Other producers may supply timestamp; reject stale/malformed values.
    if raw_ts is not None:
        report_ts_utc = _parse_iso_datetime(raw_ts, fallback_tz=_resolve_timezone(user_timezone))
        if report_ts_utc < started_at_utc:
            raise ValueError(f"STALE_REPORT_TIMESTAMP: {class_name}")

    tests_count = _parse_non_negative_int(root, "tests", class_name)
    failures_count = _parse_non_negative_int(root, "failures", class_name)
    errors_count = _parse_non_negative_int(root, "errors", class_name)
    skipped_count = _parse_non_negative_int(root, "skipped", class_name)

    cases = root.findall("testcase")
    if tests_count <= 0 or not cases:
        raise ValueError(f"EMPTY_SUITE: {class_name}")
    if tests_count != len(cases):
        raise ValueError(f"SUITE_COUNT_MISMATCH: {class_name}")

    actual_failures = sum(1 for case in cases if case.find("failure") is not None)
    actual_errors = sum(1 for case in cases if case.find("error") is not None)
    actual_skipped = sum(1 for case in cases if case.find("skipped") is not None)
    if (
        failures_count != actual_failures
        or errors_count != actual_errors
        or skipped_count != actual_skipped
    ):
        raise ValueError(f"CONTRADICTORY_SUITE_COUNTERS: {class_name}")

    if failures_count or errors_count or skipped_count:
        raise ValueError(f"SUITE_NOT_PASSED: {class_name}")

    observed_methods: set[str] = set()
    for case in cases:
        if not _is_valid_testcase_classname(class_name, case.get("classname")):
            raise ValueError(f"TESTCASE_CLASSNAME_MISMATCH: {class_name}")
        raw_name = (case.get("name") or "").strip()
        if not raw_name:
            raise ValueError(f"EMPTY_TESTCASE_NAME: {class_name}")
        observed_methods.add(raw_name.split("(", 1)[0].split("[", 1)[0])

    missing = [method for method in required_methods if method not in observed_methods]
    if missing:
        raise ValueError(f"MISSING_REQUIRED_METHODS: {class_name}: {missing}")

    return tests_count


def verify_phase(
    phase: str,
    reports: Path,
    manifest: Path,
    run_id: str,
    started_at: datetime,
) -> dict:
    """Verify all required Surefire reports for `phase` and return aggregate counts."""
    if not isinstance(run_id, str) or not run_id.strip():
        raise ValueError("INVALID_RUN_ID")
    if not isinstance(started_at, datetime) or started_at.tzinfo is None or started_at.utcoffset() is None:
        raise ValueError("STARTED_AT_MUST_BE_TIMEZONE_AWARE")

    started_at_utc = started_at.astimezone(timezone.utc)
    reports_root = Path(reports)
    manifest_path = Path(manifest)

    required_suites = _load_required_suites(manifest_path, phase)
    report_dirs = _discover_report_dirs(reports_root)

    total_suites = 0
    total_tests = 0
    verified_paths: set[Path] = set()
    seen_classes: set[str] = set()

    for class_name, required_methods in required_suites.items():
        matches = [
            report_dir / f"TEST-{class_name}.xml"
            for report_dir in report_dirs
            if (report_dir / f"TEST-{class_name}.xml").is_file()
        ]
        if not matches:
            raise ValueError(f"MISSING_REPORT: {class_name}")
        if len(matches) > 1:
            raise ValueError(f"DUPLICATE_REPORT: {class_name}")

        suite_tests = _verify_suite_xml(
            matches[0],
            class_name,
            required_methods,
            run_id,
            started_at_utc,
        )
        verified_paths.add(matches[0].resolve())
        seen_classes.add(class_name)
        total_suites += 1
        total_tests += suite_tests

    # Also enforce validity, freshness, run ID, and zero failures/errors/skips
    # across all other current-run TEST-*.xml files in discovered report dirs,
    # while ignoring old unrelated reports (stale mtime + non-matching runId).
    for report_dir in report_dirs:
        for xml_path in sorted(report_dir.glob("TEST-*.xml"), key=lambda p: p.name):
            resolved = xml_path.resolve()
            if resolved in verified_paths:
                continue

            class_name = xml_path.stem[len("TEST-") :]
            if not class_name:
                raise ValueError(f"INVALID_REPORT_FILENAME: {xml_path.name}")

            mtime_utc = datetime.fromtimestamp(xml_path.stat().st_mtime, tz=timezone.utc)
            try:
                root = ET.parse(xml_path).getroot()
            except ET.ParseError as exc:
                raise ValueError(f"MALFORMED_SUREFIRE_XML: {class_name}") from exc

            run_ids, _ = _extract_properties(root, class_name)
            if mtime_utc < started_at_utc and run_ids != [run_id]:
                continue

            if class_name in seen_classes:
                raise ValueError(f"DUPLICATE_REPORT: {class_name}")

            suite_tests = _verify_suite_xml(
                xml_path,
                class_name,
                [],
                run_id,
                started_at_utc,
            )
            verified_paths.add(resolved)
            seen_classes.add(class_name)
            total_suites += 1
            total_tests += suite_tests

    return {
        "phase": phase,
        "run_id": run_id,
        "suites": total_suites,
        "tests": total_tests,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify TaskPilot phase acceptance test reports.")
    parser.add_argument("--phase", required=True, help="Phase identifier defined in manifest (e.g. P0)")
    parser.add_argument("--reports", required=True, type=Path, help="Direct surefire-reports dir or backend root")
    parser.add_argument("--manifest", required=True, type=Path, help="Path to phase-requirements.json")
    parser.add_argument("--run-id", required=True, help="Expected remediation.runId passed to Maven")
    parser.add_argument(
        "--started-at",
        required=True,
        help="ISO-8601 run start timestamp (UTC/offset recommended; naive uses host local timezone)",
    )
    args = parser.parse_args(argv)

    try:
        started_at = _parse_iso_datetime(args.started_at)
        summary = verify_phase(args.phase, args.reports, args.manifest, args.run_id, started_at)
        print(
            "PHASE_VERIFICATION_PASSED="
            f"{summary['tests']};SUITES={summary['suites']};PHASE={summary['phase']};"
            f"RUN_ID={summary['run_id']};FAILURES=0;ERRORS=0;SKIPPED=0"
        )
        return 0
    except (ValueError, OSError, ET.ParseError) as exc:
        print(str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
