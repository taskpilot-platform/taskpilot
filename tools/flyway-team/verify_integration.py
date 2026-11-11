"""Fail CI if mandatory integration reports/cases are absent, skipped or failed.

Run after Maven CLEAN test, including when Maven fails. Never accept stale reports.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

REQUIRED = {
    "com.taskpilot.app.migration.MigrationSchemaIntegrationTest": {
        "freshPublicMigrationStartsRealBackend", "upgradedPublicMigrationStartsRealBackend"},
    "com.taskpilot.app.migration.ReconciledFlywayIntegrationTest": {
        "normalForwardMigrationDoesNotRequireChangingJavaRecoveryPolicy",
        "unqualifiedForwardObjectsCannotPassCompleteValidation",
        "metadataObjectsAreRejectedEvenBeforeHistoryExists"},
}


def verify(directory):
    total=0
    for name,methods in REQUIRED.items():
        path=Path(directory)/("TEST-"+name+".xml")
        if not path.is_file():
            raise ValueError("MISSING_MANDATORY_INTEGRATION_REPORT: "+name)
        root=ET.parse(path).getroot()
        cases=root.findall("testcase")
        if root.get("name")!=name or not cases or int(root.get("tests","0"))!=len(cases):
            raise ValueError("EMPTY_OR_INVALID_INTEGRATION_REPORT: "+name)
        if any(int(root.get(key,"0")) for key in ("failures","errors","skipped")):
            raise ValueError("MANDATORY_INTEGRATION_FAILED_OR_SKIPPED: "+name)
        if any(case.find(tag) is not None for case in cases for tag in ("skipped","failure","error")):
            raise ValueError("MANDATORY_INTEGRATION_CASE_NOT_PASSED: "+name)
        names={case.get("name","").split("(",1)[0].split("[",1)[0] for case in cases}
        if not methods.issubset(names):
            raise ValueError("MISSING_FRESH_OR_UPGRADE_BACKEND_CASE: "+name)
        total+=len(cases)
    return total


if __name__=="__main__":
    try:
        count=verify(sys.argv[1] if len(sys.argv)>1 else "taskpilot-app/target/surefire-reports")
        print(f"MANDATORY_MIGRATION_INTEGRATION_PASSED={count};SKIPPED=0;FRESH_AND_UPGRADE_BACKEND=true")
    except (ValueError,OSError,ET.ParseError) as error:
        print(str(error),file=sys.stderr);sys.exit(1)
