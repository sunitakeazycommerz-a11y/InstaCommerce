"""Slack alerting plugin for data pipeline failures using modern Block Kit format (Issue #666)."""
import json
import logging
import urllib.request
from typing import Optional

from airflow.hooks.base import BaseHook

logger = logging.getLogger(__name__)

SLACK_CONN_ID = "slack_data_alerts"
DEFAULT_CHANNEL = "#data-alerts"


def build_block_kit_payload(
    message: Optional[str],
    context: dict,
    level: str = "error",
    channel: str = DEFAULT_CHANNEL,
) -> dict:
    """Build modern Slack Block Kit JSON payload for alerts.

    Includes structured header, metadata fields, exception trace, log action button,
    and context footer.
    """
    LEVEL_EMOJI = {
        "info": ":information_source:",
        "warning": ":warning:",
        "error": ":x:",
        "critical": ":rotating_light:",
    }
    emoji = LEVEL_EMOJI.get(level, ":x:")

    task_instance = context.get("task_instance") or context.get("ti")
    dag_id = (
        getattr(task_instance, "dag_id", "unknown")
        if task_instance
        else context.get("dag_id", "unknown")
    )
    task_id = (
        getattr(task_instance, "task_id", "unknown")
        if task_instance
        else context.get("task_id", "unknown")
    )
    execution_date = str(context.get("execution_date") or context.get("ds", "unknown"))
    exception = str(context.get("exception", "No exception info"))
    log_url = getattr(task_instance, "log_url", "") if task_instance else ""

    header_text = f"{emoji} Airflow Pipeline Notification: {level.upper()}"
    fallback_text = message or f"{emoji} Airflow Task Failure: {dag_id}.{task_id}"

    blocks = [
        {
            "type": "header",
            "text": {
                "type": "plain_text",
                "text": header_text[:150],
                "emoji": True,
            },
        },
        {
            "type": "section",
            "fields": [
                {"type": "mrkdwn", "text": f"*DAG:*\n`{dag_id}`"},
                {"type": "mrkdwn", "text": f"*Task:*\n`{task_id}`"},
                {"type": "mrkdwn", "text": f"*Execution Date:*\n`{execution_date}`"},
                {"type": "mrkdwn", "text": f"*Severity:*\n`{level.upper()}`"},
            ],
        },
    ]

    if message:
        blocks.append({
            "type": "section",
            "text": {"type": "mrkdwn", "text": message},
        })

    if exception and exception != "No exception info":
        blocks.append({
            "type": "section",
            "text": {
                "type": "mrkdwn",
                "text": f"*Exception:*\n```{exception[:2000]}```",
            },
        })

    if log_url:
        blocks.append({
            "type": "actions",
            "elements": [
                {
                    "type": "button",
                    "text": {"type": "plain_text", "text": "View Task Logs", "emoji": True},
                    "url": log_url,
                    "style": "danger" if level in ("error", "critical") else "primary",
                }
            ],
        })

    blocks.append({
        "type": "context",
        "elements": [
            {
                "type": "mrkdwn",
                "text": f"InstaCommerce Data Platform SRE • Alert Channel: `{channel}`",
            }
        ],
    })

    return {
        "channel": channel,
        "text": fallback_text,
        "blocks": blocks,
    }


def send_slack_alert(
    context: dict,
    message: Optional[str] = None,
    level: str = "error",
    channel: str = DEFAULT_CHANNEL,
) -> dict:
    """Send a Slack alert for pipeline events formatted with Slack Block Kit.

    Args:
        context: Airflow task context dictionary.
        message: Optional custom message.
        level: Alert severity — "info", "warning", "error", or "critical".
        channel: Slack channel to post to.

    Returns:
        The generated Block Kit payload dict.
    """
    payload = build_block_kit_payload(message, context, level, channel)

    try:
        from airflow.providers.slack.hooks.slack_webhook import SlackWebhookHook

        slack_hook = SlackWebhookHook(slack_webhook_conn_id=SLACK_CONN_ID)
        slack_hook.send(
            text=payload["text"],
            blocks=payload["blocks"],
            channel=channel,
        )
        logger.info("Slack alert sent to %s via SlackWebhookHook", channel)
    except Exception:
        # Fall back to connection-based approach with JSON Block Kit payload
        try:
            connection = BaseHook.get_connection(SLACK_CONN_ID)
            webhook_url = connection.get_uri()

            req_payload = json.dumps(payload).encode("utf-8")
            req = urllib.request.Request(
                webhook_url,
                data=req_payload,
                headers={"Content-Type": "application/json"},
            )
            urllib.request.urlopen(req)
            logger.info("Slack alert sent via fallback webhook to %s", channel)
        except Exception as e:
            logger.error("Failed to send Slack alert: %s", str(e))
            # In testing/mock environments, do not crash worker
            pass

    return payload
