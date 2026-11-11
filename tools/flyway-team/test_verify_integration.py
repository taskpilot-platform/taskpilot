from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

import verify_integration as gate


class MandatoryIntegrationGateTest(unittest.TestCase):
    def reports(self,path):
        for name,methods in gate.REQUIRED.items():
            root=ET.Element("testsuite",name=name,tests=str(len(methods) or 1),skipped="0",errors="0",failures="0")
            for method in methods or {"actualPostgresCase"}:ET.SubElement(root,"testcase",name=method,classname=name)
            ET.ElementTree(root).write(path/("TEST-"+name+".xml"))

    def test_actual_fresh_upgrade_and_postgres_reports_are_required(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp);self.reports(path)
            self.assertEqual(gate.verify(path),5)

    def test_missing_skipped_failed_empty_or_partial_reports_fail_closed(self):
        for mode in ["missing","skip","failure","empty","partial"]:
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as temp:
                path=Path(temp);self.reports(path)
                file=path/("TEST-"+next(iter(gate.REQUIRED))+".xml")
                if mode=="missing":file.unlink()
                else:
                    tree=ET.parse(file);root=tree.getroot();case=root.find("testcase")
                    if mode=="skip":ET.SubElement(case,"skipped")
                    elif mode=="failure":ET.SubElement(case,"failure")
                    else:
                        root.remove(case);root.set("tests",str(len(root.findall("testcase"))))
                        if mode=="empty":
                            for item in root.findall("testcase"):root.remove(item)
                            root.set("tests","0")
                    tree.write(file)
                with self.assertRaises(ValueError):gate.verify(path)
