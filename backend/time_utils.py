"""
Utilities for timecard conversions and calculations.
Punch times are recorded in decimal hours (hundredths of an hour).
Example: 9.28 = 9 hours and (0.28 * 60) = 16.8 -> 9:17 AM.
"""
from typing import Optional, Tuple


def parse_decimal_time(time_str: Optional[str]) -> Tuple[Optional[float], Optional[str], bool]:
    """
    Parses a timecard time string such as "9.28", "9.35*", "19.68", "N/A".
    Returns:
        (decimal_value, clock_time_12h, is_late)
    """
    if not time_str or time_str.strip() in ("", "N/A", "--", "null"):
        return None, None, False

    clean_str = time_str.strip()
    is_late = "*" in clean_str
    clean_str = clean_str.replace("*", "").strip()

    try:
        val = float(clean_str)
    except ValueError:
        return None, None, False

    total_minutes = round(val * 60)
    hours = (total_minutes // 60) % 24
    minutes = total_minutes % 60

    # Format 12-hour AM/PM
    period = "AM" if hours < 12 else "PM"
    display_hour = hours % 12
    if display_hour == 0:
        display_hour = 12

    clock_12h = f"{display_hour}:{minutes:02d} {period}"
    return val, clock_12h, is_late


def decimal_hours_to_hhmm(hours_val: Optional[float]) -> str:
    """Converts a duration like 9.90 hours to '9h 54m'."""
    if hours_val is None:
        return "0h 0m"
    total_minutes = round(hours_val * 60)
    h = total_minutes // 60
    m = total_minutes % 60
    return f"{h}h {m}m"

