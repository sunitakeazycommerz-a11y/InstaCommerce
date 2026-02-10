from __future__ import annotations

import json
from typing import Dict, List

from data_platform_jobs.config import load_settings
from data_platform_jobs.job_registry import JobDefinition, list_jobs


def list_schedules() -> List[Dict[str, str]]:
    settings = load_settings()
    schedules: List[Dict[str, str]] = []
    for job in list_jobs():
        schedules.append(_schedule_payload(job, settings.timezone))
    return schedules


def export_schedules(format: str = "json") -> str:
    schedules = list_schedules()
    if format == "json":
        return json.dumps(schedules, indent=2)
    if format == "text":
        return "\n".join(
            f"{item['name']} ({item['cron']} {item['timezone']}) - {item['description']}" for item in schedules
        )
    raise ValueError(f"Unsupported format: {format}")


def _schedule_payload(job: JobDefinition, timezone: str) -> Dict[str, str]:
    return {
        "name": job.name,
        "cron": job.schedule.cron,
        "timezone": timezone or job.schedule.timezone,
        "description": job.description,
    }
