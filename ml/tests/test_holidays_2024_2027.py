from __future__ import annotations

import pandas as pd

from ml.train.demand_forecast.train import INDIAN_HOLIDAYS, build_indian_holidays


def test_indian_holidays_covers_2024_through_2027() -> None:
    assert {"holiday", "ds", "lower_window", "upper_window"}.issubset(INDIAN_HOLIDAYS.columns)
    years = set(pd.to_datetime(INDIAN_HOLIDAYS["ds"]).dt.year.unique())
    assert {2024, 2025, 2026, 2027}.issubset(years)

    for year in (2024, 2025, 2026, 2027):
        year_rows = INDIAN_HOLIDAYS[pd.to_datetime(INDIAN_HOLIDAYS["ds"]).dt.year == year]
        names = set(year_rows["holiday"])
        assert "republic_day" in names
        assert "independence_day" in names
        assert "christmas" in names
        assert "new_years_eve" in names
        assert any("diwali" in n for n in names)
        assert any("holi" in n for n in names)
        assert any("ipl" in n for n in names)

    extended = build_indian_holidays(2025, 2026)
    ext_years = set(pd.to_datetime(extended["ds"]).dt.year.unique())
    assert ext_years == {2025, 2026}
