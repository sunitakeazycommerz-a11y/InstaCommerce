#!/usr/bin/env python3
"""
InstaCommerce Milestone & Issue Publisher Runner
Publishes 207 issues across Milestones 17 to 24 using the GitHub CLI API.
"""

import json
import subprocess
import time
import sys
from issues_data_w0_w1 import WAVE_0_ISSUES, WAVE_1_ISSUES
from issues_data_w2_w3 import WAVE_2_ISSUES, WAVE_3_ISSUES
from issues_data_w4_w5 import WAVE_4_ISSUES, WAVE_5_ISSUES
from issues_data_w6_w7 import WAVE_6_ISSUES, WAVE_7_ISSUES

ALL_ISSUES = (
    WAVE_0_ISSUES +
    WAVE_1_ISSUES +
    WAVE_2_ISSUES +
    WAVE_3_ISSUES +
    WAVE_4_ISSUES +
    WAVE_5_ISSUES +
    WAVE_6_ISSUES +
    WAVE_7_ISSUES
)

def create_issue(issue, index, total):
    cmd = [
        "gh", "api", "repos/:owner/:repo/issues",
        "-f", f"title={issue['title']}",
        "-F", f"milestone={issue['milestone']}",
        "-f", f"body={issue['body']}"
    ]
    for label in issue.get("labels", []):
        cmd.extend(["-f", f"labels[]={label}"])

    try:
        res = subprocess.run(cmd, capture_output=True, text=True, check=True)
        data = json.loads(res.stdout)
        print(f"[{index}/{total}] Created #{data['number']}: {issue['title'][:65]} (M{issue['milestone']})")
        return data['number']
    except subprocess.CalledProcessError as e:
        print(f"[{index}/{total}] ERROR creating {issue['title']}: {e.stderr.strip()}", file=sys.stderr)
        return None
    except Exception as ex:
        print(f"[{index}/{total}] UNEXPECTED ERROR: {ex}", file=sys.stderr)
        return None

def main():
    print(f"============================================================")
    print(f"  InstaCommerce Staff-Grade Issue Generator")
    print(f"  Total Issues to Publish: {len(ALL_ISSUES)}")
    print(f"  Milestones: 17 to 24 (Waves 0 to 7)")
    print(f"============================================================")

    created = []
    failed = []
    start_time = time.time()

    for idx, issue in enumerate(ALL_ISSUES, start=1):
        num = create_issue(issue, idx, len(ALL_ISSUES))
        if num:
            created.append((num, issue))
        else:
            failed.append(issue)
        time.sleep(0.35)  # Respect GitHub secondary rate limit

    duration = time.time() - start_time
    print(f"\n============================================================")
    print(f"  Publishing Complete in {duration:.1f}s")
    print(f"  Successfully Created: {len(created)}")
    print(f"  Failed: {len(failed)}")
    print(f"============================================================")

if __name__ == "__main__":
    main()
