"""
HTML parser for Time Card Viewer pages.
Extracts:
- Available week options
- Current week summary (Gross Pay, Total Hours, Last Updated)
- Daily shifts (punches, meal, total hours, daily pay, late flags)
- Accordion shift breakdown details (ST, OT, DT, activity codes)
- Corrections table entries
"""
import re
from typing import Dict, Any, Optional
from bs4 import BeautifulSoup
from .time_utils import parse_decimal_time


def parse_float(val: Any, default: float = 0.0) -> float:
    if val is None:
        return default
    if isinstance(val, (int, float)):
        return float(val)
    # Strip currency signs, commas, extra whitespace
    cleaned = re.sub(r"[^\d.-]", "", str(val))
    try:
        return float(cleaned) if cleaned else default
    except ValueError:
        return default


def parse_timecard_html(html_content: str, selected_week_end: Optional[str] = None) -> Dict[str, Any]:
    soup = BeautifulSoup(html_content, "html.parser")

    # 1. Available Weeks from dropdown
    available_weeks = []
    select_el = soup.select_one(".timecard-date-dropdown select")
    active_week = selected_week_end

    if select_el:
        for opt in select_el.find_all("option"):
            val = opt.get("value", "").strip()
            text = opt.get_text(strip=True)
            status = "Pending" if "Pending" in text else "Final"
            if val:
                available_weeks.append({
                    "week_end_date": val,
                    "label": text,
                    "status": status,
                    "is_selected": opt.has_attr("selected") or (active_week == val)
                })
        if not active_week and select_el.find("option"):
            # First option is usually the current selected one
            first_opt = select_el.find("option")
            active_week = first_opt.get("value", "").strip()

    # If no dropdown found, fallback
    if not active_week:
        active_week = "unknown"

    # 2. Week Summary
    gross_el = soup.select_one(".weekGrossPay")
    hours_el = soup.select_one(".weekTotalHours")
    updated_el = soup.select_one(".timecard-wrapper__lastUpdated span")

    gross_pay = parse_float(gross_el.get_text(strip=True)) if gross_el else 0.0
    total_hours = parse_float(hours_el.get_text(strip=True)) if hours_el else 0.0
    last_updated = updated_el.get_text(strip=True) if updated_el else ""

    # Status of current week
    week_status = "Pending"
    for w in available_weeks:
        if w["week_end_date"] == active_week:
            week_status = w["status"]
            break

    week_summary = {
        "week_end_date": active_week,
        "status": week_status,
        "gross_pay": gross_pay,
        "total_hours": total_hours,
        "last_updated_source": last_updated
    }

    # 3. Daily Shifts & Detail Accordions
    shifts = []
    # Primary view is the list view table
    rows = soup.select(".timecard-list-view table tbody tr.timecard-card")
    for tr in rows:
        date_str = tr.get("data-date", "").strip()
        cells = tr.find_all("td")
        if len(cells) < 8:
            continue

        raw_date_text = cells[0].get_text(strip=True)
        # Parse day name (e.g. "Mon" from "Mon 11/02/2026")
        day_match = re.search(r"^([A-Za-z]{3})", raw_date_text)
        day_name = day_match.group(1) if day_match else ""

        pay_code = cells[1].get_text(strip=True)
        raw_start = cells[2].get_text(strip=True)
        raw_end = cells[3].get_text(strip=True)
        raw_meal = cells[4].get_text(strip=True)
        raw_rate = cells[5].get_text(strip=True)
        raw_hours = cells[6].get_text(strip=True)
        raw_gross = cells[7].get_text(strip=True)

        start_dec, start_clock, is_late = parse_decimal_time(raw_start)
        end_dec, end_clock, _ = parse_decimal_time(raw_end)
        meal_dec, _, _ = parse_decimal_time(raw_meal)

        shift_item = {
            "week_end_date": active_week,
            "date": date_str,
            "day_name": day_name,
            "pay_code": pay_code,
            "start_time_dec": start_dec,
            "start_time_clock": start_clock,
            "is_late": 1 if is_late else 0,
            "end_time_dec": end_dec,
            "end_time_clock": end_clock,
            "meal_unpaid_hours": meal_dec if meal_dec is not None else 0.0,
            "pay_rate": parse_float(raw_rate),
            "total_hours": parse_float(raw_hours),
            "gross_pay": parse_float(raw_gross),
            "details": []
        }

        # Look for the accordion row right after this row
        acc_class = f"acc{date_str}"
        acc_tr = soup.select_one(f"tr.{acc_class}")
        if acc_tr:
            detail_tbody = acc_tr.select_one("tbody")
            if detail_tbody:
                for det_row in detail_tbody.find_all("tr"):
                    # Check for summary or correction rows
                    row_classes = det_row.get("class", [])
                    if "viewCorrectionRow" in row_classes:
                        continue
                    if "dailyTotalRow" in row_classes:
                        continue

                    d_cells = det_row.find_all("td")
                    if len(d_cells) >= 14:
                        rate_val = parse_float(d_cells[5].get_text(strip=True))
                        if shift_item["pay_rate"] == 0.0 and rate_val > 0:
                            shift_item["pay_rate"] = rate_val

                        detail_entry = {
                            "trans_type": d_cells[0].get_text(strip=True),
                            "op_type": d_cells[1].get_text(strip=True),
                            "center": d_cells[2].get_text(strip=True),
                            "sort": d_cells[3].get_text(strip=True),
                            "activity": d_cells[4].get_text(strip=True),
                            "rate": rate_val,
                            "st_hrs": parse_float(d_cells[6].get_text(strip=True)),
                            "st_amt": parse_float(d_cells[7].get_text(strip=True)),
                            "ot_hrs": parse_float(d_cells[8].get_text(strip=True)),
                            "ot_amt": parse_float(d_cells[9].get_text(strip=True)),
                            "dt_hrs": parse_float(d_cells[10].get_text(strip=True)),
                            "dt_amt": parse_float(d_cells[11].get_text(strip=True)),
                            "eq_type": d_cells[12].get_text(strip=True),
                            "griev_num": d_cells[13].get_text(strip=True) if len(d_cells) > 13 else "",
                            "flat_amt": parse_float(d_cells[14].get_text(strip=True)) if len(d_cells) > 14 else 0.0
                        }
                        shift_item["details"].append(detail_entry)

        shifts.append(shift_item)

    # 4. Corrections Viewer table
    corrections = []
    corr_tbody = soup.select_one(".viewcorrections-data__data")
    if corr_tbody:
        for c_row in corr_tbody.find_all("tr"):
            c_cells = c_row.find_all("td")
            if len(c_cells) >= 8:
                corrections.append({
                    "timestamp": c_cells[0].get_text(strip=True),
                    "card_type": c_cells[1].get_text(strip=True),
                    "action": c_cells[2].get_text(strip=True),
                    "item": c_cells[3].get_text(strip=True),
                    "old_val": c_cells[4].get_text(strip=True),
                    "new_val": c_cells[5].get_text(strip=True),
                    "edited_by": c_cells[6].get_text(strip=True),
                    "reason_code": c_cells[7].get_text(strip=True),
                })

    return {
        "available_weeks": available_weeks,
        "week_summary": week_summary,
        "shifts": shifts,
        "corrections": corrections
    }

