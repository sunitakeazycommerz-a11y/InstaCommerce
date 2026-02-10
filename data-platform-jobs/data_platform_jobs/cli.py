from __future__ import annotations

import argparse
import logging
import sys

from data_platform_jobs.hooks.scheduler import export_schedules
from data_platform_jobs.job_registry import build_context, get_job, list_jobs


def configure_logging(log_level: str) -> None:
    level = getattr(logging, log_level.upper(), logging.INFO)
    logging.basicConfig(level=level, format="%(asctime)s %(levelname)s %(name)s %(message)s")


def main() -> None:
    parser = argparse.ArgumentParser(description="Data platform jobs CLI")
    parser.add_argument("--log-level", default="INFO", help="Logging level (default: INFO)")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("list", help="List available jobs")

    run_parser = subparsers.add_parser("run", help="Run a job")
    run_parser.add_argument("--job", required=True, help="Job name to run")
    run_parser.add_argument("--dry-run", action="store_true", help="Log actions without executing queries")

    schedule_parser = subparsers.add_parser("schedules", help="Export schedules")
    schedule_parser.add_argument("--format", default="json", choices=("json", "text"), help="Output format")

    args = parser.parse_args()
    configure_logging(args.log_level)

    if args.command == "list":
        for job in list_jobs():
            print(f"{job.name}: {job.description} ({job.schedule.cron})")
        return

    if args.command == "schedules":
        print(export_schedules(args.format))
        return

    if args.command == "run":
        job = get_job(args.job)
        if not job:
            print(f"Unknown job: {args.job}", file=sys.stderr)
            for available in list_jobs():
                print(f" - {available.name}", file=sys.stderr)
            sys.exit(1)
        context = build_context(dry_run=args.dry_run)
        job.handler(context)
        return

    parser.print_help()
