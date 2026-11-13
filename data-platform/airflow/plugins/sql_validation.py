"""BigQuery SQL identifier validation helpers for Airflow DAGs."""
import re

BQ_IDENTIFIER_RE = re.compile(r"^[a-zA-Z0-9_-]+(?:\.[a-zA-Z0-9_-]+)*$")


def validate_bq_identifier(value: str, field_name: str = "identifier") -> str:
    """Validate BigQuery project, dataset, or table identifier before SQL interpolation."""
    if not isinstance(value, str) or not BQ_IDENTIFIER_RE.fullmatch(value):
        raise ValueError(f"Invalid BigQuery {field_name}: {value!r}")
    return value
