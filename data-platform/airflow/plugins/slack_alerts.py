"""Slack alerting plugin for data pipeline failures."""
import logging
from typing import Optional

from airflow.hooks.base import BaseHook

logger = logging.getLogger(__name__)

SLACK_CONN_ID = "slack_data_alerts"
DEFAULT_CHANNEL = "#data-alerts"


def send_slack_alert(
    context: dict,
    message: Optional[str] = None,
    level: str = "error",
    channel: str = DEFAULT_CHANNEL,
) -> None:
    """Send a Slack alert for pipeline events.

    Args:
        context: Airflow task context dictionary.
        message: Optional custom message. If None, a default failure message
                 is generated from the task context.
        level: Alert severity — "info", "warning", "error", or "critical".
        channel: Slack channel to post to.
    """
    LEVEL_EMOJI = {
        "info": ":information_source:",
        "warning": ":warning:",
        "error": ":x:",
        "critical": ":rotating_light:",
    }

    emoji = LEVEL_EMOJI.get(level, ":x:")

    if message is None:
        task_instance = context.get("task_instance") or context.get("ti")
        dag_id = context.get("dag", getattr(task_instance, "dag_id", "unknown"))
        task_id = getattr(task_instance, "task_id", "unknown")
        execution_date = context.get("execution_date", "unknown")
        exception = context.get("exception", "No exception info")
        log_url = getattr(task_instance, "log_url", "")

        message = (
            f"{emoji} *Airflow Task Failure*\n"
            f"• *DAG:* `{dag_id}`\n"
            f"• *Task:* `{task_id}`\n"
            f"• *Execution Date:* `{execution_date}`\n"
            f"• *Exception:* ```{exception}```\n"
            f"• <{log_url}|View Logs>"
        )
    else:
        message = f"{emoji} {message}"

    try:
        from airflow.providers.slack.hooks.slack_webhook import SlackWebhookHook

        slack_hook = SlackWebhookHook(slack_webhook_conn_id=SLACK_CONN_ID)
        slack_hook.send(
            text=message,
            channel=channel,
        )
        logger.info("Slack alert sent to %s", channel)
    except Exception:
        # Fall back to connection-based approach
        try:
            connection = BaseHook.get_connection(SLACK_CONN_ID)
            webhook_url = connection.get_uri()

            import json
            import urllib.request

            payload = json.dumps({"channel": channel, "text": message}).encode()
            req = urllib.request.Request(
                webhook_url,
                data=payload,
                headers={"Content-Type": "application/json"},
            )
            urllib.request.urlopen(req)
            logger.info("Slack alert sent via fallback to %s", channel)
        except Exception as e:
            logger.error("Failed to send Slack alert: %s", str(e))
            raise
