"""
Parser for Time Off Viewer pages.
Extracts:
- Employee Profile (ID & Name)
- Entitlement Bank items (Vacation, Sick Leave, Option Week, Option Day, etc.)
- Available time, time basis (Hours/Days), and next earn dates.
"""
from typing import Dict, Any, List
from bs4 import BeautifulSoup
from .parser import parse_float


def parse_timeoff_html(html_content: str) -> Dict[str, Any]:
    soup = BeautifulSoup(html_content, "html.parser")

    # Employee Profile
    emp_id_el = soup.select_one(".empId")
    emp_name_el = soup.select_one(".empName")

    profile = {
        "emp_id": emp_id_el.get_text(strip=True) if emp_id_el else "",
        "emp_name": emp_name_el.get_text(strip=True) if emp_name_el else ""
    }

    entitlements: List[Dict[str, Any]] = []

    # Parse list view table rows
    rows = soup.select(".timeoff-list-view table tbody tr")
    for tr in rows:
        timeoff_type = tr.get("data-timeoff-type", "").strip()
        cells = tr.find_all("td")
        if len(cells) >= 4:
            title = cells[0].get_text(strip=True)
            avail_str = cells[1].get_text(strip=True)
            time_basis = cells[2].get_text(strip=True)
            earn_date = cells[3].get_text(strip=True)

            entitlements.append({
                "timeoff_type": timeoff_type or title[:3].upper(),
                "title": title,
                "available_time": parse_float(avail_str),
                "time_basis": time_basis,
                "next_earn_date": earn_date if earn_date not in ("", "N/A") else None
            })

    # If list view was empty, fallback to grid cards
    if not entitlements:
        cards = soup.select(".timeoff-grid-view .timeoff-card")
        for card in cards:
            timeoff_type = card.get("data-timeoff-type", "").strip()
            title_el = card.select_one(".timeoff-card--header span")
            title = title_el.get_text(strip=True) if title_el else timeoff_type

            # Parse data divs
            avail_val = 0.0
            time_basis = "Hours"
            earn_date = None

            text = card.get_text()
            # Find Available Time:
            avail_el = card.find(lambda e: "Available Time:" in e.text and e.name == "p")
            if avail_el:
                spans = avail_el.find_all("span")
                if len(spans) >= 2:
                    avail_val = parse_float(spans[1].get_text(strip=True))

            basis_el = card.find(lambda e: "Time Basis:" in e.text and e.name == "p")
            if basis_el:
                spans = basis_el.find_all("span")
                if len(spans) >= 2:
                    time_basis = spans[1].get_text(strip=True)

            date_el = card.select_one("[data-timeoff-date]")
            if date_el:
                earn_date = date_el.get_text(strip=True)

            entitlements.append({
                "timeoff_type": timeoff_type or title[:3].upper(),
                "title": title,
                "available_time": avail_val,
                "time_basis": time_basis,
                "next_earn_date": earn_date if earn_date and "N/A" not in earn_date else None
            })

    return {
        "profile": profile,
        "entitlements": entitlements
    }

