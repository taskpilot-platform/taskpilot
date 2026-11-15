"""Behavior tests for the phase verification gate (tools/verification/verify_phase.py)."""

from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from verify_phase import main as verify_phase_main, verify_phase


STARTED_AT = datetime(2026, 11, 15, 14, 0, 0, tzinfo=timezone.utc)
FRESH_TIME = STARTED_AT + timedelta(seconds=5)
STALE_TIME = STARTED_AT - timedelta(seconds=15)
RUN_ID = "remediation-run-20261115-001"


def _iso_utc(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _write_manifest(root: Path, phases: dict) -> Path:
    manifest_path = root / "phase-requirements.json"
    manifest_path.write_text(json.dumps({"phases": phases}), encoding="utf-8")
    return manifest_path


def _write_surefire_xml(
    reports_dir: Path,
    class_name: str,
    methods: list[str],
    *,
    run_id: str | None = RUN_ID,
    xml_timestamp: str | None = None,
    mtime: datetime = FRESH_TIME,
    declared_tests: int | None = None,
    declared_failures: int = 0,
    declared_errors: int = 0,
    declared_skipped: int = 0,
    suite_name: str | None = None,
    testcase_class_override: str | None = None,
    case_outcomes: dict[str, str] | None = None,
) -> Path:
    """Create a Surefire TEST-<class>.xml fixture with deterministic mtime and properties."""
    reports_dir.mkdir(parents=True, exist_ok=True)
    xml_path = reports_dir / f"TEST-{class_name}.xml"

    attrs = {
        "name": suite_name or class_name,
        "tests": str(len(methods) if declared_tests is None else declared_tests),
        "failures": str(declared_failures),
        "errors": str(declared_errors),
        "skipped": str(declared_skipped),
    }
    ts_value = _iso_utc(FRESH_TIME) if xml_timestamp is None else xml_timestamp
    if ts_value != "__OMIT__":
        attrs["timestamp"] = ts_value

    suite = ET.Element("testsuite", **attrs)
    props = ET.SubElement(suite, "properties")
    if run_id is not None:
        ET.SubElement(props, "property", name="remediation.runId", value=run_id)

    outcomes = case_outcomes or {}
    for method in methods:
        case = ET.SubElement(
            suite,
            "testcase",
            name=method,
            classname=testcase_class_override or class_name,
            time="0.01",
        )
        outcome = outcomes.get(method)
        if outcome in ("skipped", "failure", "error"):
            ET.SubElement(case, outcome, message=f"simulated {outcome}")

    ET.ElementTree(suite).write(xml_path, encoding="utf-8", xml_declaration=True)
    epoch = mtime.timestamp()
    os.utime(xml_path, (epoch, epoch))
    return xml_path


class VerifyPhaseTest(unittest.TestCase):
    def test_valid_fresh_direct_report_passes_and_returns_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports_dir = root / "surefire-reports"
            manifest = _write_manifest(
                root,
                {
                    "P0": {
                        "required": {
                            "com.taskpilot.app.migration.MigrationSchemaIntegrationTest": [
                                "freshPublicMigrationStartsRealBackend",
                                "upgradedPublicMigrationStartsRealBackend",
                            ]
                        }
                    }
                },
            )
            _write_surefire_xml(
                reports_dir,
                "com.taskpilot.app.migration.MigrationSchemaIntegrationTest",
                [
                    "freshPublicMigrationStartsRealBackend",
                    "upgradedPublicMigrationStartsRealBackend",
                ],
            )

            result = verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)
            self.assertEqual(result["suites"], 1)
            self.assertEqual(result["tests"], 2)
            self.assertEqual(result["failures"], 0)
            self.assertEqual(result["errors"], 0)
            self.assertEqual(result["skipped"], 0)

    def test_recursive_module_report_discovery_and_node_modules_exclusion(self):
        with tempfile.TemporaryDirectory() as tmp:
            backend_root = Path(tmp)
            manifest = _write_manifest(
                backend_root,
                {
                    "P0": {
                        "required": {
                            "com.taskpilot.app.MigrationTest": ["freshMigration"],
                            "com.taskpilot.ai.RagLocalTest": ["parsesDocx"],
                        }
                    }
                },
            )
            _write_surefire_xml(
                backend_root / "taskpilot-app" / "target" / "surefire-reports",
                "com.taskpilot.app.MigrationTest",
                ["freshMigration"],
            )
            _write_surefire_xml(
                backend_root / "taskpilot-ai" / "target" / "surefire-reports",
                "com.taskpilot.ai.RagLocalTest",
                ["parsesDocx"],
            )
            # Poisoned report under node_modules must be ignored, not counted or failing
            _write_surefire_xml(
                backend_root / "node_modules" / "pkg" / "target" / "surefire-reports",
                "com.taskpilot.fake.IgnoredTest",
                ["broken"],
                run_id="wrong-run",
                declared_failures=1,
                case_outcomes={"broken": "failure"},
            )

            result = verify_phase("P0", backend_root, manifest, RUN_ID, STARTED_AT)
            self.assertEqual(result["suites"], 2)
            self.assertEqual(result["tests"], 2)
            self.assertEqual(result["failures"], 0)
            self.assertEqual(result["errors"], 0)
            self.assertEqual(result["skipped"], 0)

    def test_reports_only_under_node_modules_or_non_module_dir_are_not_discovered(self):
        with tempfile.TemporaryDirectory() as tmp:
            backend_root = Path(tmp)
            manifest = _write_manifest(
                backend_root,
                {"P0": {"required": {"com.taskpilot.app.MigrationTest": ["freshMigration"]}}},
            )
            _write_surefire_xml(
                backend_root / "node_modules" / "taskpilot-app" / "target" / "surefire-reports",
                "com.taskpilot.app.MigrationTest",
                ["freshMigration"],
            )
            with self.assertRaises(ValueError):
                verify_phase("P0", backend_root, manifest, RUN_ID, STARTED_AT)

    def test_missing_report_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports_dir = root / "surefire-reports"
            reports_dir.mkdir()
            manifest = _write_manifest(
                root,
                {"P0": {"required": {"com.taskpilot.app.MissingTest": ["testCase"]}}},
            )
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)

    def test_zero_tests_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports_dir = root / "surefire-reports"
            manifest = _write_manifest(
                root,
                {"P0": {"required": {"com.taskpilot.app.EmptyTest": ["testCase"]}}},
            )
            _write_surefire_xml(
                reports_dir,
                "com.taskpilot.app.EmptyTest",
                [],
                declared_tests=0,
            )
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)

    def test_skipped_mandatory_case_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports_dir = root / "surefire-reports"
            manifest = _write_manifest(
                root,
                {"P0": {"required": {"com.taskpilot.app.SampleTest": ["mandatoryCase"]}}},
            )
            _write_surefire_xml(
                reports_dir,
                "com.taskpilot.app.SampleTest",
                ["mandatoryCase"],
                declared_skipped=1,
                case_outcomes={"mandatoryCase": "skipped"},
            )
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)

    def test_assertion_failure_and_error_rejected(self):
        for outcome in ("failure", "error"):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                reports_dir = root / "surefire-reports"
                manifest = _write_manifest(
                    root,
                    {"P0": {"required": {"com.taskpilot.app.SampleTest": ["mandatoryCase"]}}},
                )
                _write_surefire_xml(
                    reports_dir,
                    "com.taskpilot.app.SampleTest",
                    ["mandatoryCase"],
                    declared_failures=1 if outcome == "failure" else 0,
                    declared_errors=1 if outcome == "error" else 0,
                    case_outcomes={"mandatoryCase": outcome},
                )
                with self.assertRaises(ValueError):
                    verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)

    def test_stale_file_timestamp_and_stale_xml_timestamp_rejected(self):
        cases = [
            ("stale_mtime", _iso_utc(FRESH_TIME), STALE_TIME),
            ("stale_xml_timestamp", _iso_utc(STALE_TIME), FRESH_TIME),
        ]
        for label, xml_ts, mtime in cases:
            with self.subTest(case=label), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                reports_dir = root / "surefire-reports"
                manifest = _write_manifest(
                    root,
                    {"P0": {"required": {"com.taskpilot.app.SampleTest": ["mandatoryCase"]}}},
                )
                _write_surefire_xml(
                    reports_dir,
                    "com.taskpilot.app.SampleTest",
                    ["mandatoryCase"],
                    xml_timestamp=xml_ts,
                    mtime=mtime,
                )
                with self.assertRaises(ValueError):
                    verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)

    def test_valid_surefire_schema_without_optional_timestamp_passes(self):
        # Surefire's real 3.0.2 reports have no timestamp attribute. The fresh
        # filesystem mtime AND matching unique run ID are still mandatory.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports_dir = root / "surefire-reports"
            manifest = _write_manifest(
                root,
                {"P0": {"required": {"com.taskpilot.app.SampleTest": ["mandatoryCase"]}}},
            )
            _write_surefire_xml(
                reports_dir,
                "com.taskpilot.app.SampleTest",
                ["mandatoryCase"],
                xml_timestamp="__OMIT__",
            )
            self.assertEqual(
                verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)["tests"], 1
            )

    def test_old_or_missing_run_id_rejected(self):
        for bad_run_id in ("old-run-id-000", None):
            with self.subTest(run_id=bad_run_id), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                reports_dir = root / "surefire-reports"
                manifest = _write_manifest(
                    root,
                    {"P0": {"required": {"com.taskpilot.app.SampleTest": ["mandatoryCase"]}}},
                )
                _write_surefire_xml(
                    reports_dir,
                    "com.taskpilot.app.SampleTest",
                    ["mandatoryCase"],
                    run_id=bad_run_id,
                )
                with self.assertRaises(ValueError):
                    verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)

    def test_wrong_suite_counts_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports_dir = root / "surefire-reports"
            manifest = _write_manifest(
                root,
                {"P0": {"required": {"com.taskpilot.app.SampleTest": ["mandatoryCase"]}}},
            )
            _write_surefire_xml(
                reports_dir,
                "com.taskpilot.app.SampleTest",
                ["mandatoryCase"],
                declared_tests=2,
            )
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)

    def test_missing_required_method_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports_dir = root / "surefire-reports"
            manifest = _write_manifest(
                root,
                {
                    "P0": {
                        "required": {
                            "com.taskpilot.app.SampleTest": [
                                "freshCase",
                                "upgradeCase",
                            ]
                        }
                    }
                },
            )
            _write_surefire_xml(
                reports_dir,
                "com.taskpilot.app.SampleTest",
                ["freshCase"],
            )
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)

    def test_required_class_name_and_testcase_classname_must_match(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports_dir = root / "surefire-reports"
            manifest = _write_manifest(
                root,
                {"P0": {"required": {"com.taskpilot.app.ExpectedTest": ["mandatoryCase"]}}},
            )
            _write_surefire_xml(
                reports_dir,
                "com.taskpilot.app.ExpectedTest",
                ["mandatoryCase"],
                testcase_class_override="com.taskpilot.app.OtherTest",
            )
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)

    def test_unknown_phase_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports_dir = root / "surefire-reports"
            manifest = _write_manifest(
                root,
                {"P0": {"required": {"com.taskpilot.app.SampleTest": ["mandatoryCase"]}}},
            )
            _write_surefire_xml(
                reports_dir,
                "com.taskpilot.app.SampleTest",
                ["mandatoryCase"],
            )
            with self.assertRaises(ValueError):
                verify_phase("P99", reports_dir, manifest, RUN_ID, STARTED_AT)

    def test_malformed_empty_or_duplicate_manifest_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports_dir = root / "surefire-reports"
            reports_dir.mkdir()

            bad_json = root / "bad.json"
            bad_json.write_text("{not json", encoding="utf-8")
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, bad_json, RUN_ID, STARTED_AT)

            empty_suites = _write_manifest(root, {"P0": {"required": {}}})
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, empty_suites, RUN_ID, STARTED_AT)

            empty_methods = _write_manifest(
                root, {"P0": {"required": {"com.taskpilot.app.SampleTest": []}}}
            )
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, empty_methods, RUN_ID, STARTED_AT)

            dup_methods = _write_manifest(
                root,
                {"P0": {"required": {"com.taskpilot.app.SampleTest": ["m1", "m1"]}}},
            )
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, dup_methods, RUN_ID, STARTED_AT)

    def test_duplicate_reports_across_modules_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            backend_root = Path(tmp)
            manifest = _write_manifest(
                backend_root,
                {"P0": {"required": {"com.taskpilot.app.DupTest": ["caseA"]}}},
            )
            _write_surefire_xml(
                backend_root / "taskpilot-app" / "target" / "surefire-reports",
                "com.taskpilot.app.DupTest",
                ["caseA"],
            )
            _write_surefire_xml(
                backend_root / "taskpilot-ai" / "target" / "surefire-reports",
                "com.taskpilot.app.DupTest",
                ["caseA"],
            )
            with self.assertRaises(ValueError):
                verify_phase("P0", backend_root, manifest, RUN_ID, STARTED_AT)

    def test_contradictory_counters_and_malformed_xml_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports_dir = root / "surefire-reports"
            manifest = _write_manifest(
                root,
                {"P0": {"required": {"com.taskpilot.app.SampleTest": ["caseA"]}}},
            )

            # Failure tag present while suite attribute claims failures="0"
            _write_surefire_xml(
                reports_dir,
                "com.taskpilot.app.SampleTest",
                ["caseA"],
                declared_failures=0,
                case_outcomes={"caseA": "failure"},
            )
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)

            # Suite attribute claims failures="1" with passing testcase
            _write_surefire_xml(
                reports_dir,
                "com.taskpilot.app.SampleTest",
                ["caseA"],
                declared_failures=1,
            )
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)

            # Malformed XML
            xml_path = reports_dir / "TEST-com.taskpilot.app.SampleTest.xml"
            xml_path.write_text("<testsuite broken", encoding="utf-8")
            epoch = FRESH_TIME.timestamp()
            os.utime(xml_path, (epoch, epoch))
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)

    def test_naive_surefire_timestamp_uses_user_timezone_property(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports_dir = root / "surefire-reports"
            manifest = _write_manifest(
                root,
                {"P0": {"required": {"com.taskpilot.app.SampleTest": ["caseA"]}}},
            )
            xml_path = _write_surefire_xml(
                reports_dir,
                "com.taskpilot.app.SampleTest",
                ["caseA"],
                xml_timestamp=FRESH_TIME.strftime("%Y-%m-%dT%H:%M:%S"),
            )
            tree = ET.parse(xml_path)
            props = tree.getroot().find("properties")
            ET.SubElement(props, "property", name="user.timezone", value="UTC")
            tree.write(xml_path, encoding="utf-8", xml_declaration=True)
            epoch = FRESH_TIME.timestamp()
            os.utime(xml_path, (epoch, epoch))

            result = verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)
            self.assertEqual(result["tests"], 1)

            # Offset timezone GMT+07:00 where 14:00:05+07:00 == 07:00:05Z (stale vs 14:00:00Z)
            tree = ET.parse(xml_path)
            for prop in tree.getroot().find("properties").findall("property"):
                if prop.get("name") == "user.timezone":
                    prop.set("value", "GMT+07:00")
            tree.write(xml_path, encoding="utf-8", xml_declaration=True)
            os.utime(xml_path, (epoch, epoch))
            with self.assertRaises(ValueError):
                verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)

    def test_cli_entrypoint_exit_codes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports_dir = root / "surefire-reports"
            manifest = _write_manifest(
                root,
                {"P0": {"required": {"com.taskpilot.app.SampleTest": ["caseA"]}}},
            )
            _write_surefire_xml(
                reports_dir,
                "com.taskpilot.app.SampleTest",
                ["caseA"],
            )
            ok_code = verify_phase_main(
                [
                    "--phase",
                    "P0",
                    "--reports",
                    str(reports_dir),
                    "--manifest",
                    str(manifest),
                    "--run-id",
                    RUN_ID,
                    "--started-at",
                    _iso_utc(STARTED_AT),
                ]
            )
            self.assertEqual(ok_code, 0)

            bad_code = verify_phase_main(
                [
                    "--phase",
                    "P0",
                    "--reports",
                    str(reports_dir),
                    "--manifest",
                    str(manifest),
                    "--run-id",
                    "wrong-run-id",
                    "--started-at",
                    _iso_utc(STARTED_AT),
                ]
            )
            self.assertEqual(bad_code, 1)

    def test_failing_errored_skipped_or_stale_nonmanifest_current_run_suite_rejected(self):
        scenarios = [
            ("failure", {"run_id": RUN_ID, "mtime": FRESH_TIME, "declared_failures": 1, "case_outcomes": {"extraCase": "failure"}}),
            ("error", {"run_id": RUN_ID, "mtime": FRESH_TIME, "declared_errors": 1, "case_outcomes": {"extraCase": "error"}}),
            ("skipped", {"run_id": RUN_ID, "mtime": FRESH_TIME, "declared_skipped": 1, "case_outcomes": {"extraCase": "skipped"}}),
            ("stale_current_run", {"run_id": RUN_ID, "mtime": STALE_TIME}),
            ("fresh_wrong_run_id", {"run_id": "different-run-id", "mtime": FRESH_TIME}),
        ]
        for label, extra_kwargs in scenarios:
            with self.subTest(scenario=label), tempfile.TemporaryDirectory() as tmp:
                backend_root = Path(tmp)
                manifest = _write_manifest(
                    backend_root,
                    {"P0": {"required": {"com.taskpilot.app.MigrationTest": ["freshMigration"]}}},
                )
                _write_surefire_xml(
                    backend_root / "taskpilot-app" / "target" / "surefire-reports",
                    "com.taskpilot.app.MigrationTest",
                    ["freshMigration"],
                )
                _write_surefire_xml(
                    backend_root / "taskpilot-ai" / "target" / "surefire-reports",
                    "com.taskpilot.ai.NonManifestTest",
                    ["extraCase"],
                    **extra_kwargs,
                )
                with self.assertRaises(ValueError):
                    verify_phase("P0", backend_root, manifest, RUN_ID, STARTED_AT)

    def test_old_unrelated_report_ignored_and_fresh_nonmanifest_suite_counted(self):
        with tempfile.TemporaryDirectory() as tmp:
            backend_root = Path(tmp)
            manifest = _write_manifest(
                backend_root,
                {"P0": {"required": {"com.taskpilot.app.MigrationTest": ["freshMigration"]}}},
            )
            _write_surefire_xml(
                backend_root / "taskpilot-app" / "target" / "surefire-reports",
                "com.taskpilot.app.MigrationTest",
                ["freshMigration"],
            )
            _write_surefire_xml(
                backend_root / "taskpilot-ai" / "target" / "surefire-reports",
                "com.taskpilot.ai.FreshExtraTest",
                ["extraOne", "extraTwo"],
            )
            _write_surefire_xml(
                backend_root / "taskpilot-users" / "target" / "surefire-reports",
                "com.taskpilot.users.OldUnrelatedTest",
                ["oldOne", "oldTwo", "oldThree"],
                run_id="prior-run-id",
                xml_timestamp=_iso_utc(STALE_TIME),
                mtime=STALE_TIME,
            )

            result = verify_phase("P0", backend_root, manifest, RUN_ID, STARTED_AT)
            self.assertEqual(result["suites"], 2)
            self.assertEqual(result["tests"], 3)
            self.assertEqual(result["failures"], 0)
            self.assertEqual(result["errors"], 0)
            self.assertEqual(result["skipped"], 0)

    def test_nested_and_display_named_testcase_classname_allowed_while_foreign_rejected(self):
        # Real Surefire 3.0.2 OAuth2OneTimeCodeStoreTest emits @DisplayName classname
        # ("OAuth2OneTimeCodeStore Tests"), and JUnit @Nested emits Outer$Inner.
        for suite_cls, case_cls in (
            (
                "com.taskpilot.infrastructure.config.security.oauth2.OAuth2OneTimeCodeStoreTest",
                "OAuth2OneTimeCodeStore Tests",
            ),
            (
                "com.taskpilot.app.OuterTest",
                "com.taskpilot.app.OuterTest$NestedCases",
            ),
        ):
            with self.subTest(allowed=case_cls), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                reports_dir = root / "surefire-reports"
                manifest = _write_manifest(root, {"P0": {"required": {suite_cls: ["caseA"]}}})
                _write_surefire_xml(
                    reports_dir,
                    suite_cls,
                    ["caseA"],
                    xml_timestamp="__OMIT__",
                    testcase_class_override=case_cls,
                )
                result = verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)
                self.assertEqual(result["tests"], 1)

        for foreign_cls in (
            "com.taskpilot.app.OtherTest$NestedCases",
            "OtherTest",
        ):
            with self.subTest(rejected=foreign_cls), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                reports_dir = root / "surefire-reports"
                manifest = _write_manifest(
                    root,
                    {"P0": {"required": {"com.taskpilot.app.ExpectedTest": ["caseA"]}}},
                )
                _write_surefire_xml(
                    reports_dir,
                    "com.taskpilot.app.ExpectedTest",
                    ["caseA"],
                    xml_timestamp="__OMIT__",
                    testcase_class_override=foreign_cls,
                )
                with self.assertRaises(ValueError):
                    verify_phase("P0", reports_dir, manifest, RUN_ID, STARTED_AT)


if __name__ == "__main__":
    unittest.main()
