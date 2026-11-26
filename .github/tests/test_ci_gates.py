# Copyright (C) 2026 Mercury Li
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 3 as
# published by the Free Software Foundation.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

try:
    import yaml
except ModuleNotFoundError:
    raise SystemExit("The CI gate tests require PyYAML. Install python3-yaml or PyYAML for python3.") from None


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = {"ci": "CI result", "device": "Device result", "kernels": "Kernels result"}


class CiGateTests(unittest.TestCase):
    def read_workflow(self, name):
        path = ROOT / ".github" / "workflows" / f"{name}.yml"
        return yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)

    def test_pull_requests_and_merge_groups_report_stable_results(self):
        for name, result_name in WORKFLOWS.items():
            with self.subTest(workflow=name):
                workflow = self.read_workflow(name)
                for event in ("pull_request", "merge_group"):
                    self.assertIn(event, workflow["on"])
                    self.assertFalse(workflow["on"][event])

                jobs = workflow["jobs"]
                result = jobs["result"]
                self.assertEqual(result["name"], result_name)
                self.assertEqual(result["if"], "always()")
                self.assertEqual(set(result["needs"]), jobs.keys() - {"result"})
                self.assertEqual(
                    result["steps"][0]["if"],
                    "contains(needs.*.result, 'failure') || contains(needs.*.result, 'cancelled')",
                )
                self.assertEqual(result["steps"][0]["run"], "exit 1")

                build = jobs["selftest" if name == "kernels" else "build"]
                self.assertEqual(build["needs"], "changes")
                self.assertEqual(build["if"], "needs.changes.outputs.run == 'true'")
                if name == "device":
                    self.assertEqual(jobs["check"]["needs"], ["changes", "build"])
                    self.assertEqual(
                        jobs["check"]["strategy"]["matrix"]["part"],
                        "${{ fromJson(needs.changes.outputs.parts) }}",
                    )

    def test_changed_file_steps_use_the_existing_path_rules(self):
        cases = (
            (("README.md", "bbd/AGENTS.md", "android/app/AGENTS.md", "docs/diagram.png"), (), False),
            (("bbd/src/lib.rs",), ("ci", "device", "kernels"), True),
            (("testdata/permissions/matrix.tsv",), ("ci", "device"), True),
            (("android/helper/src/main/kotlin/Example.kt",), ("ci", "device"), True),
            (("android/app/build.gradle.kts",), ("ci", "device"), False),
            (("android/app/src/main/kotlin/Example.kt",), ("ci", "device"), False),
            (("android/core/model/build.gradle.kts",), ("ci",), False),
            ((".github/tests/test_ci_gates.py", "deny.toml"), ("ci",), False),
            ((".github/workflows/kernels.yml",), ("ci", "kernels"), False),
        )
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            output = repo / "output"

            def git(*args):
                return subprocess.check_output(
                    [
                        "git", "-c", "user.name=CI test", "-c", "user.email=ci@example.com",
                        "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", *args,
                    ],
                    cwd=repo,
                    text=True,
                ).strip()

            git("init", "--quiet", "--initial-branch=main")
            git("commit", "--quiet", "--allow-empty", "-m", "Base")
            fork_point = git("rev-parse", "HEAD")

            # A stale pull request should only match its own changes.
            (repo / "bbd").mkdir()
            (repo / "bbd" / "upstream.rs").touch()
            git("add", "bbd")
            git("commit", "--quiet", "-m", "Advance main")
            base = git("rev-parse", "HEAD")

            for paths, selected, device_permissions in cases:
                git("switch", "--quiet", "--detach", fork_point)
                for name in paths:
                    path = repo / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.touch()
                git("add", "--", *paths)
                git("commit", "--quiet", "-m", "Change files")
                head = git("rev-parse", "HEAD")
                git("switch", "--quiet", "--detach", base)
                git("merge", "--quiet", "--no-ff", "--no-edit", head)
                merge_group = git("rev-parse", "HEAD")

                for workflow_name in WORKFLOWS:
                    with self.subTest(paths=paths, workflow=workflow_name):
                        jobs = self.read_workflow(workflow_name)["jobs"]
                        self.assertIn("changes", jobs)
                        step = next(step for step in jobs["changes"]["steps"] if step.get("id") == "paths")
                        expected_match = workflow_name in selected
                        for diff_range, expected_run, expected_perms in (
                            (f"{base}...{head}", expected_match, device_permissions),
                            (f"{base}..{merge_group}", expected_match, device_permissions),
                            ("", True, True),
                        ):
                            output.unlink(missing_ok=True)
                            subprocess.run(
                                ["bash", "-euo", "pipefail", "-c", step["run"]],
                                cwd=repo,
                                check=True,
                                env={**os.environ, "GITHUB_OUTPUT": str(output), "DIFF_RANGE": diff_range},
                            )
                            parsed = dict(
                                line.split("=", 1)
                                for line in output.read_text(encoding="utf-8").splitlines()
                            )
                            self.assertEqual(parsed["run"], str(expected_run).lower())
                            if workflow_name == "device":
                                expected_parts = (
                                    '["checks","permissions-1","permissions-2"]'
                                    if expected_perms
                                    else '["checks"]'
                                )
                                expected_legs = "12" if expected_perms else "4"
                                self.assertEqual(parsed["parts"], expected_parts)
                                self.assertEqual(parsed["expected_legs"], expected_legs)

    def test_device_result_checks_individual_matrix_conclusions(self):
        jobs = self.read_workflow("device")["jobs"]
        apis = jobs["check"]["strategy"]["matrix"]["api"]
        job_name = jobs["check"]["name"]
        steps = jobs["result"]["steps"]
        self.assertEqual(len(steps), 2)
        verify_matrix = steps[1]
        self.assertEqual(verify_matrix["if"], "needs.check.result == 'success'")
        self.assertEqual(verify_matrix["env"]["EXPECTED_LEGS"], "${{ needs.changes.outputs.expected_legs }}")

        for parts in (["checks", "permissions-1", "permissions-2"], ["checks"]):
            legs = [
                {
                    "name": job_name.replace("${{ matrix.api }}", api).replace("${{ matrix.part }}", part),
                    "status": "completed",
                    "conclusion": "success",
                    "run_attempt": 1,
                }
                for api in apis
                for part in parts
            ]
            expected_legs = len(legs)
            last = legs[-1]
            skipped = {**last, "conclusion": "skipped"}
            failed = {**last, "conclusion": "failure"}
            cancelled = {**last, "conclusion": "cancelled"}
            running = {**last, "status": "in_progress", "conclusion": None}
            rerun_success = {**last, "run_attempt": 2}
            rerun_cancelled = {**cancelled, "run_attempt": 2}
            unrelated_job = {**failed, "name": "Build"}

            def pages(*last_legs):
                # A partial rerun can be on a different page from the original jobs.
                return [{"jobs": list(last_legs[1:]) + [unrelated_job]}, {"jobs": legs[:-1] + list(last_legs[:1])}]

            for case, responses, succeeds in (
                ("all passed", [pages(last)], True),
                ("one skipped", [pages(skipped)], True),
                ("one failed", [pages(failed)], False),
                ("one cancelled", [pages(cancelled)], False),
                ("one missing", [pages()], False),
                ("successful rerun", [pages(failed, rerun_success)], True),
                ("cancelled rerun", [pages(last, rerun_cancelled)], False),
                ("eventually completed", [pages(running), pages(last)], True),
                ("still running", [pages(running)] * 12, False),
            ):
                with self.subTest(expected_legs=expected_legs, case=case):
                    self.assert_device_result(verify_matrix["run"], responses, succeeds, expected_legs)

    def assert_device_result(self, script, responses, succeeds, expected_legs):
        mocks = """
gh() {
    local response
    IFS= read -r response || return 1
    printf '%s\\n' "$response"
}
sleep() { printf 'Retrying the jobs API\\n' >&2; }
"""
        result = subprocess.run(
            ["bash", "-euo", "pipefail", "-c", mocks + script],
            env={
                **os.environ,
                "GITHUB_REPOSITORY": "example/repo",
                "GITHUB_RUN_ID": "1",
                "EXPECTED_LEGS": str(expected_legs),
            },
            input="".join(json.dumps(pages) + "\n" for pages in responses),
            text=True,
            capture_output=True,
        )
        self.assertEqual(result.returncode, 0 if succeeds else 1, result.stderr)
        self.assertEqual(result.stderr.splitlines(), ["Retrying the jobs API"] * (len(responses) - 1))


if __name__ == "__main__":
    unittest.main()
