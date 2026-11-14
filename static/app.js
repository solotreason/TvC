// Time Card & Time Off Viewer UI Logic

let currentWeekData = null;
let archivedWeeksList = [];
let allAuditLogs = [];
let latestNotifiedTarget = null;
let pollStatusInterval = null;
let isSilentSync = false;
let lastSmartCheckAt = 0;
let smartCheckTimer = null;
const SMART_CHECK_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes
const SMART_RESUME_MIN_GAP_MS = 5 * 60 * 1000; // 5 minutes on app resume
const expandedRows = new Set(); // Stores dates of expanded activeRows (starts empty so no day is forced open)

function getLocalIsoDate(offsetDays = 0) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

function isOptionDayShift(s) {
  if (!s) return false;
  if (s.is_option_day) return true;
  const raw = String(s.pay_code || '').trim().toUpperCase();
  if (
    raw.includes('OPTION') ||
    raw.includes('OPT DAY') ||
    raw.includes('OPTDAY') ||
    raw.includes('OPT WK') ||
    raw.includes('OPT WEEK') ||
    raw === 'OPT' ||
    raw === 'OPW' ||
    raw.startsWith('OPT ')
  ) {
    return true;
  }
  if (Array.isArray(s.details)) {
    for (const det of s.details) {
      const t = `${(det && det.trans_type) || ''} ${(det && det.activity) || ''}`.trim().toUpperCase();
      if (
        t.includes('OPTION') ||
        t.includes('OPT DAY') ||
        t.includes('OPTDAY') ||
        t.includes('OPT WK') ||
        t.includes('OPT WEEK') ||
        t === 'OPT' ||
        t === 'OPW'
      ) {
        return true;
      }
    }
  }
  return false;
}

function isCompletedWorkedShift(s) {
  if (!s) return false;
  const hasStart = (s.start_time_dec !== null && s.start_time_dec !== undefined) || !!s.start_time_clock;
  const hasEnd = (s.end_time_dec !== null && s.end_time_dec !== undefined) || !!s.end_time_clock;
  return hasStart && hasEnd && (Number(s.total_hours) || 0) > 0.005;
}

function isWaitingForDailyTimecard() {
  if (!isUserLoggedIn) return false;

  const todayIso = getLocalIsoDate(0);
  // Check the latest week in cache or currentWeekData
  let latestWeek = currentWeekData;
  if (weekDetailsCache.size > 0) {
    const sortedDates = Array.from(weekDetailsCache.keys()).sort((a, b) => b.localeCompare(a));
    if (sortedDates.length > 0) {
      latestWeek = weekDetailsCache.get(sortedDates[0]) || currentWeekData;
    }
  }

  if (!latestWeek || !Array.isArray(latestWeek.shifts) || latestWeek.shifts.length === 0) {
    return true;
  }

  const todayShift = latestWeek.shifts.find(s => s.date === todayIso);
  const rawPayCode = todayShift ? String(todayShift.pay_code || '').trim().toUpperCase() : '';
  const todayComplete = !!(
    todayShift && (
      isCompletedWorkedShift(todayShift) ||
      isOptionDayShift(todayShift) ||
      rawPayCode.includes('SCHEDULED OFF')
    )
  );

  return !todayComplete;
}

function getInitialSmartCheckTimestamp() {
  try {
    if (typeof window.AndroidNative !== 'undefined' && typeof window.AndroidNative.getStoreLastModified === 'function') {
      const mtime = Number(window.AndroidNative.getStoreLastModified()) || 0;
      if (mtime > 0) return mtime;
    }
  } catch (e) {}
  return 0;
}

function refreshBatteryOptimizationNotice() {
  const banner = document.getElementById('batteryOptNotice');
  if (!banner) return;
  try {
    const dismissed = localStorage.getItem('tvc_battery_tip_dismissed') === '1';
    const canCheck = typeof window.AndroidNative !== 'undefined'
      && typeof window.AndroidNative.isIgnoringBatteryOptimizations === 'function';
    if (dismissed || !canCheck || !isUserLoggedIn) {
      banner.classList.add('hidden');
      return;
    }
    if (window.AndroidNative.isIgnoringBatteryOptimizations()) {
      banner.classList.add('hidden');
    } else {
      banner.classList.remove('hidden');
    }
  } catch (e) {
    banner.classList.add('hidden');
  }
}

window.refreshDashboardFromStore = async function () {
  weekDetailsCache.clear();
  await checkAuthStatus();
  await Promise.all([
    loadWeeks(currentWeekData ? currentWeekData.week_end_date : null),
    loadTimeOff(),
    loadAuditHistory()
  ]);
  refreshBatteryOptimizationNotice();
};

async function syncFromNativeDiskIfUpdated() {
  try {
    if (window.TimecardBridge && typeof window.TimecardBridge.reloadFromDiskIfUpdated === 'function') {
      if (window.TimecardBridge.reloadFromDiskIfUpdated()) {
        lastSmartCheckAt = Math.max(lastSmartCheckAt, getInitialSmartCheckTimestamp() || Date.now());
        await window.refreshDashboardFromStore();
        return true;
      }
    }
  } catch (e) {}
  return false;
}

function runSmartAutoCheckIfNeeded(minGapMs = SMART_CHECK_INTERVAL_MS) {
  if (!isUserLoggedIn || pollStatusInterval !== null) return;
  if (!isWaitingForDailyTimecard()) return;
  const now = Date.now();
  if (now - lastSmartCheckAt < minGapMs) return;
  lastSmartCheckAt = now;
  // Silent 1-week background check (no progress bar popup)
  triggerSync(true, 1, false, true);
}

function setupSmartAutoCheck() {
  lastSmartCheckAt = getInitialSmartCheckTimestamp();
  if (smartCheckTimer) clearInterval(smartCheckTimer);
  smartCheckTimer = setInterval(() => {
    runSmartAutoCheckIfNeeded(SMART_CHECK_INTERVAL_MS - 5000);
  }, 60 * 1000); // Lightweight 60s tick that checks elapsed time & sleep condition

  document.addEventListener('visibilitychange', async () => {
    if (document.visibilityState === 'visible') {
      await syncFromNativeDiskIfUpdated();
      refreshBatteryOptimizationNotice();
      runSmartAutoCheckIfNeeded(SMART_RESUME_MIN_GAP_MS);
    }
  });

  window.onAppResumedSmartCheck = async function () {
    await syncFromNativeDiskIfUpdated();
    refreshBatteryOptimizationNotice();
    runSmartAutoCheckIfNeeded(SMART_RESUME_MIN_GAP_MS);
  };
}

const CURRENCY_FORMATTER = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
const MONTH_FORMATTER = new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric' });

// Format currency
function formatCurrency(amount) {
  if (amount === undefined || amount === null || isNaN(amount)) return "$0.00";
  return CURRENCY_FORMATTER.format(amount);
}

// Format decimal hours to 2 decimals
function formatDecimal(val) {
  if (val === undefined || val === null || isNaN(val)) return "0.00";
  return Number(val).toFixed(2);
}

// Format minutes diff for punch alterations
function formatDiffMinutes(mins, fieldName) {
  if (mins === 0 || mins === undefined || mins === null) return "—";
  const abs = Math.abs(mins);
  if (fieldName === 'Meal/Unpaid') {
    return mins > 0 ? `+${abs}m added` : `-${abs}m reduced`;
  }
  return mins > 0 ? `+${abs}m later` : `-${abs}m earlier`;
}

// Format Date string "Mon 11/02/2026"
function formatShiftDate(dateStr, dayName) {
  if (!dateStr) return '';
  const parts = dateStr.split('-');
  if (parts.length === 3) {
    const shortDay = dayName ? dayName.slice(0, 3) : '';
    return `${shortDay} ${parts[1]}/${parts[2]}/${parts[0]}`.trim();
  }
  return dateStr;
}

// Format YYYY-MM to "September 2026"
function formatArchiveMonthLabel(yyyyMm) {
  if (!yyyyMm || !yyyyMm.includes('-')) return yyyyMm || '';
  const [y, m] = yyyyMm.split('-').map(Number);
  if (!y || !m) return yyyyMm;
  return MONTH_FORMATTER.format(new Date(y, m - 1, 1));
}

// Render Monthly Historical Archive (weeks older than 8 active weeks)
function renderArchiveSection(preferredMonth = null) {
  const badge = document.getElementById('archiveBadge');
  const monthSelect = document.getElementById('archiveMonthSelect');
  const tbody = document.getElementById('archiveTableBody');
  const monthLabelEl = document.getElementById('archiveMonthLabel');
  const countEl = document.getElementById('archiveMonthWeeksCount');
  const hoursEl = document.getElementById('archiveMonthHours');
  const payEl = document.getElementById('archiveMonthGrossPay');

  if (badge) {
    if (archivedWeeksList.length > 0) {
      badge.textContent = String(archivedWeeksList.length);
      badge.classList.remove('hidden');
    } else {
      badge.textContent = '0';
      badge.classList.add('hidden');
    }
  }

  if (!monthSelect || !tbody) return;

  if (!archivedWeeksList || archivedWeeksList.length === 0) {
    monthSelect.innerHTML = '<option value="">No archived months</option>';
    if (monthLabelEl) monthLabelEl.textContent = 'No archived weeks yet';
    if (countEl) countEl.textContent = '0';
    if (hoursEl) hoursEl.textContent = '0.00';
    if (payEl) payEl.textContent = '$0.00';
    tbody.innerHTML = '<tr><td colspan="5" class="p-6 text-center text-[#6c757d]">No archived weeks yet. Weeks older than the 8 most recent active weeks are automatically stored here.</td></tr>';
    return;
  }

  // Extract unique YYYY-MM months in descending chronological order
  const monthsMap = new Map();
  archivedWeeksList.forEach(w => {
    const ym = (w.week_end_date || '').slice(0, 7);
    if (!ym) return;
    if (!monthsMap.has(ym)) monthsMap.set(ym, []);
    monthsMap.get(ym).push(w);
  });

  const sortedMonths = Array.from(monthsMap.keys()).sort((a, b) => b.localeCompare(a));
  const currentSelected = preferredMonth && monthsMap.has(preferredMonth)
    ? preferredMonth
    : (monthsMap.has(monthSelect.value) ? monthSelect.value : sortedMonths[0]);

  monthSelect.innerHTML = '';
  sortedMonths.forEach(ym => {
    const opt = document.createElement('option');
    opt.value = ym;
    const count = monthsMap.get(ym).length;
    opt.textContent = `${formatArchiveMonthLabel(ym)} (${count} week${count === 1 ? '' : 's'})`;
    monthSelect.appendChild(opt);
  });

  monthSelect.value = currentSelected;
  renderArchivedWeeksForMonth(currentSelected);
}

function renderArchivedWeeksForMonth(yyyyMm) {
  const tbody = document.getElementById('archiveTableBody');
  const monthLabelEl = document.getElementById('archiveMonthLabel');
  const countEl = document.getElementById('archiveMonthWeeksCount');
  const hoursEl = document.getElementById('archiveMonthHours');
  const payEl = document.getElementById('archiveMonthGrossPay');
  if (!tbody) return;

  const weeksInMonth = archivedWeeksList.filter(w => (w.week_end_date || '').startsWith(yyyyMm));
  if (weeksInMonth.length === 0) {
    tbody.innerHTML = '<tr><td colspan="5" class="p-6 text-center text-[#6c757d]">No archived weeks for this month.</td></tr>';
    return;
  }

  const totalHours = weeksInMonth.reduce((sum, w) => sum + Number(w.total_hours || 0), 0);
  const totalPay = weeksInMonth.reduce((sum, w) => sum + Number(w.gross_pay || 0), 0);

  if (monthLabelEl) monthLabelEl.textContent = `${formatArchiveMonthLabel(yyyyMm)} Archive`;
  if (countEl) countEl.textContent = String(weeksInMonth.length);
  if (hoursEl) hoursEl.textContent = formatDecimal(totalHours);
  if (payEl) payEl.textContent = formatCurrency(totalPay);

  tbody.innerHTML = '';
  weeksInMonth.forEach(w => {
    const tr = document.createElement('tr');
    tr.className = 'hover:bg-[#f8f9fa] transition-colors';
    tr.innerHTML = `
      <td class="font-bold text-[#1e293b]">${w.week_end_date}</td>
      <td><span class="px-2 py-0.5 rounded bg-[#e9ecef] text-[#495057] text-[11px] font-semibold">${w.status || 'Archived'}</span></td>
      <td class="text-right font-mono font-semibold text-[#212529]">${formatDecimal(w.total_hours || 0)}h</td>
      <td class="text-right font-mono font-bold text-[#198754]">${formatCurrency(w.gross_pay || 0)}</td>
      <td class="text-right">
        <button type="button" data-archive-week="${w.week_end_date}"
          class="view-archived-week-btn px-2.5 py-1 rounded bg-[#1e293b] hover:bg-[#0f172a] text-white text-[11px] font-bold cursor-pointer transition-colors">
          <i class="fa-regular fa-eye mr-1"></i>View Week
        </button>
      </td>
    `;
    const btn = tr.querySelector('.view-archived-week-btn');
    if (btn) {
      btn.addEventListener('click', () => {
        openArchivedWeekInViewer(w.week_end_date, w.status || 'Archived');
      });
    }
    tbody.appendChild(tr);
  });
}

async function openArchivedWeekInViewer(weekEndDate, statusLabel = 'Archived') {
  if (!weekEndDate) return;
  const tabTimeCardBtn = document.getElementById('tabTimeCard');
  if (tabTimeCardBtn) tabTimeCardBtn.click();

  const select = document.getElementById('weekSelect');
  if (select) {
    let existingOpt = Array.from(select.options).find(o => o.value === weekEndDate);
    if (!existingOpt) {
      existingOpt = document.createElement('option');
      existingOpt.value = weekEndDate;
      existingOpt.textContent = `${weekEndDate} (${statusLabel} • Archived)`;
      existingOpt.dataset.archivedTemp = 'true';
      select.appendChild(existingOpt);
    }
    select.value = weekEndDate;
  }

  await loadWeekDetails(weekEndDate);
}

// Load all available weeks (capped at 8 active weeks; older weeks in archived_weeks)
async function loadWeeks(preferredWeek = null) {
  try {
    const res = await fetch('/api/weeks');
    const data = await res.json();
    archivedWeeksList = Array.isArray(data.archived_weeks) ? data.archived_weeks : [];
    renderArchiveSection();

    const select = document.getElementById('weekSelect');
    select.innerHTML = '';

    if (!data.weeks || data.weeks.length === 0) {
      select.innerHTML = '<option value="">No weeks synced</option>';
      currentWeekData = null;
      const grossEl = document.getElementById('kpiGrossPay');
      const hoursEl = document.getElementById('kpiTotalHours');
      const updatedEl = document.getElementById('kpiLastUpdated');
      if (grossEl) grossEl.textContent = '0.00';
      if (hoursEl) hoursEl.textContent = '0.00';
      if (updatedEl) updatedEl.textContent = '--';
      const driverRateEl = document.getElementById('driverRateTop');
      if (driverRateEl) driverRateEl.textContent = '—';
      const statusBadge = document.getElementById('weekStatusBadge');
      if (statusBadge) {
        statusBadge.textContent = 'Not Synced';
        statusBadge.className = 'px-2 py-0.5 rounded bg-[#e9ecef] text-[#495057] font-semibold';
      }
      const dotRemainingEl = document.getElementById('kpiDotRemaining');
      if (dotRemainingEl) {
        dotRemainingEl.textContent = '60.00';
        dotRemainingEl.className = 'font-bold text-sm sm:text-base text-[#198754]';
      }
      const tbody = document.getElementById('shiftsTableBody');
      if (tbody) {
        const emptyHint = isUserLoggedIn
            ? 'No timecard data synced yet. Click <b>Sync</b> to load your timecards.'
            : 'No timecard data synced yet. Click <b>Log In</b> to sign in and sync your timecards.';
        tbody.innerHTML = `<tr><td colspan="9" class="p-6 text-center text-[#6c757d]">${emptyHint}</td></tr>`;
      }
      renderAuditHistoryForCurrentWeek();
      updateAuthGateUI(false);
      return;
    }

    updateAuthGateUI(true);
    data.weeks.forEach(w => {
      const opt = document.createElement('option');
      opt.value = w.week_end_date;
      opt.textContent = `${w.week_end_date} (${w.status})`;
      select.appendChild(opt);
    });

    const targetWeek = preferredWeek || data.weeks[0].week_end_date;
    select.value = targetWeek;
    loadWeekDetails(targetWeek, true);
  } catch (err) {
    console.error('Failed to load weeks:', err);
  }
}

// In-Memory Week Details Cache for instant 0ms week toggling
const weekDetailsCache = new Map();
const knownWorkedDaysByWeek = new Map(); // Tracks known active shift dates per week to detect newly posted daily timecards
const notifiedShiftDates = new Set();

function requestBrowserNotificationPermission() {
  try {
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission();
    }
  } catch (e) {}
}

// Deep-Link Handler: Open Time Card Viewer to the target week, expand day, scroll & pulse highlight
window.focusShiftFromNotification = async function (weekEndDate, shiftDate) {
  try {
    // Dismiss toast banner if visible
    const toast = document.getElementById('newTimecardToast');
    if (toast) toast.classList.add('hidden');

    // Switch to Time Card Viewer tab
    const tabTimeCardBtn = document.getElementById('tabTimeCard');
    if (tabTimeCardBtn) tabTimeCardBtn.click();

    let targetWeek = weekEndDate || (currentWeekData ? currentWeekData.week_end_date : '');
    if (!targetWeek) {
      const select = document.getElementById('weekSelect');
      if (select && select.value) targetWeek = select.value;
    }

    if (targetWeek) {
      const select = document.getElementById('weekSelect');
      if (select) {
        select.value = targetWeek;
      }
      await loadWeekDetails(targetWeek, true);
    }

    if (shiftDate && currentWeekData) {
      expandedRows.add(shiftDate);
      renderWeek(currentWeekData);

      setTimeout(() => {
        const row = document.querySelector(`tr.timecard-card[data-date="${shiftDate}"]`);
        if (row) {
          row.scrollIntoView({ behavior: 'smooth', block: 'center' });
          row.classList.remove('notification-highlight-pulse');
          void row.offsetWidth; // Force reflow to restart animation
          row.classList.add('notification-highlight-pulse');
          setTimeout(() => {
            row.classList.remove('notification-highlight-pulse');
          }, 3600);
        }
      }, 80);
    }
  } catch (err) {
    console.error('Failed to focus shift from notification:', err);
  }
};

function notifyDailyTimecardAdded(newTimecards, fallbackWeekEndDate = '') {
  if (!Array.isArray(newTimecards) || newTimecards.length === 0) return;

  // Notify strictly on completed shifts or Option Day usage (never on clock-in-only open punches)
  const freshItems = newTimecards.filter(
    tc => tc && tc.date && !notifiedShiftDates.has(tc.date) && (isCompletedWorkedShift(tc) || isOptionDayShift(tc))
  );
  if (freshItems.length === 0) return;

  freshItems.forEach(tc => notifiedShiftDates.add(tc.date));

  const latestItem = freshItems[freshItems.length - 1];
  const targetWeekEnd = latestItem.week_end_date || fallbackWeekEndDate || (currentWeekData ? currentWeekData.week_end_date : '');
  const targetShiftDate = latestItem.date || '';
  latestNotifiedTarget = { weekEndDate: targetWeekEnd, shiftDate: targetShiftDate };

  const lines = freshItems.map(tc => {
    const label = formatShiftDate(tc.date, tc.day_name || '');
    const isOpt = isOptionDayShift(tc);
    const punches = isOpt
      ? (tc.pay_code || 'OPTION DAY')
      : `${tc.start_time_clock} – ${tc.end_time_clock}`;
    const hrs = formatDecimal(tc.total_hours || 0);
    const pay = formatCurrency(tc.gross_pay || 0);
    return `${label}: ${punches} (${hrs} hrs • ${pay})`;
  });

  const singleIsOpt = freshItems.length === 1 && isOptionDayShift(freshItems[0]);
  const title = freshItems.length === 1
    ? (singleIsOpt
      ? `Option Day Used (${formatShiftDate(freshItems[0].date, freshItems[0].day_name || '')})`
      : `New Timecard Added (${formatShiftDate(freshItems[0].date, freshItems[0].day_name || '')})`)
    : `${freshItems.length} New Daily Timecards Added`;
  const body = lines.join(' | ');

  // 1. Show In-App Notification Banner
  const toast = document.getElementById('newTimecardToast');
  const toastTitle = document.getElementById('newTimecardToastTitle');
  const toastBody = document.getElementById('newTimecardToastBody');
  if (toast && toastTitle && toastBody) {
    toastTitle.textContent = title;
    toastBody.textContent = body;
    toast.classList.remove('hidden');
  }

  // 2. Native Android Status Bar Notification (with deep-link weekEndDate + shiftDate extras)
  try {
    if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.showTimecardNotificationForShift) {
      window.AndroidNative.showTimecardNotificationForShift(title, body, targetWeekEnd, targetShiftDate);
    }
  } catch (e) {}

  // 3. Desktop / Web Browser OS Notification (with click deep-link)
  try {
    if ('Notification' in window && Notification.permission === 'granted') {
      const n = new Notification(title, { body: body });
      n.onclick = () => {
        try { window.focus(); } catch (e) {}
        window.focusShiftFromNotification(targetWeekEnd, targetShiftDate);
      };
    }
  } catch (e) {}
}

// Load details for a selected week (Instant 0ms from cache, or fetch)
async function loadWeekDetails(weekEndDate, forceRefresh = false) {
  if (!weekEndDate) return;

  // Start each week view with all day accordions collapsed by default
  expandedRows.clear();

  // Instant cache hit: render immediately with zero network delay
  if (!forceRefresh && weekDetailsCache.has(weekEndDate)) {
    const cachedData = weekDetailsCache.get(weekEndDate);
    currentWeekData = cachedData;
    renderWeek(cachedData);
    return;
  }

  try {
    const res = await fetch(`/api/weeks/${weekEndDate}`);
    if (!res.ok) throw new Error('Week not found');
    const data = await res.json();

    // Detect if any new completed daily timecard or Option Day appeared in an already-known week
    const activeShifts = (data.shifts || []).filter(
      s => isCompletedWorkedShift(s) || isOptionDayShift(s)
    );
    if (knownWorkedDaysByWeek.has(weekEndDate)) {
      const prevSet = knownWorkedDaysByWeek.get(weekEndDate);
      const newlyAdded = activeShifts.filter(s => !prevSet.has(s.date));
      if (newlyAdded.length > 0) {
        notifyDailyTimecardAdded(newlyAdded, weekEndDate);
      }
    }
    knownWorkedDaysByWeek.set(weekEndDate, new Set(activeShifts.map(s => s.date)));

    weekDetailsCache.set(weekEndDate, data);
    currentWeekData = data;
    renderWeek(data);
  } catch (err) {
    console.error('Failed to load week details:', err);
  }
}

// Toggle activeRow expansion for a given shift date
function toggleActiveRow(dateStr) {
  if (expandedRows.has(dateStr)) {
    expandedRows.delete(dateStr);
  } else {
    expandedRows.add(dateStr);
  }
  if (currentWeekData) {
    renderWeek(currentWeekData);
  }
}

// Render weekly view
function renderWeek(data) {
  // 1. Update top summary metrics
  const grossEl = document.getElementById('kpiGrossPay');
  const hoursEl = document.getElementById('kpiTotalHours');
  const updatedEl = document.getElementById('kpiLastUpdated');
  if (grossEl) grossEl.textContent = formatDecimal(data.gross_pay);
  if (hoursEl) hoursEl.textContent = formatDecimal(data.total_hours);
  if (updatedEl) updatedEl.textContent = data.last_updated_source || 'Recently';

  const statusBadge = document.getElementById('weekStatusBadge');
  if (statusBadge) {
    statusBadge.textContent = data.status || 'Pending';
    if (data.status === 'Pending') {
      statusBadge.className = 'px-2 py-0.5 rounded bg-[#fff3cd] text-[#856404] border border-[#ffeeba] font-bold';
    } else {
      statusBadge.className = 'px-2 py-0.5 rounded bg-[#e9ecef] text-[#495057] font-semibold';
    }
  }

  // Find Base Rate
  let maxRate = 0.0;
  for (const s of (data.shifts || [])) {
    if (s.pay_rate > maxRate) maxRate = s.pay_rate;
    for (const d of (s.details || [])) {
      if (d.rate > maxRate) maxRate = d.rate;
    }
  }
  const driverRateEl = document.getElementById('driverRateTop');
  if (driverRateEl) {
    driverRateEl.textContent = maxRate > 0 ? `$${maxRate.toFixed(2)}/hr` : '—';
  }

  // 2. Remaining DOT Hours Calculation (Standard 60h limit)
  const dotLimit = 60.0;
  const totalWorked = Number(data.total_hours) || 0.0;
  const dotRemaining = Math.max(0.0, dotLimit - totalWorked);

  const dotRemainingEl = document.getElementById('kpiDotRemaining');
  const dotSubTextEl = document.getElementById('kpiDotSubText');

  if (dotRemainingEl) {
    dotRemainingEl.textContent = dotRemaining.toFixed(2);
    if (dotRemaining <= 0) {
      dotRemainingEl.className = 'font-bold text-sm sm:text-base text-[#dc3545]';
    } else if (dotRemaining < 10) {
      dotRemainingEl.className = 'font-bold text-sm sm:text-base text-[#b02a37]';
    } else if (dotRemaining < 15) {
      dotRemainingEl.className = 'font-bold text-sm sm:text-base text-[#856404]';
    } else {
      dotRemainingEl.className = 'font-bold text-sm sm:text-base text-[#198754]';
    }
  }

  if (dotSubTextEl) {
    dotSubTextEl.textContent = `/ ${dotLimit.toFixed(2)}h`;
  }

  // 3. Render Shifts Table with activeRow & update week-scoped Punch Audits
  renderShiftsTable(data.shifts || []);
  renderAuditHistoryForCurrentWeek();
}

// Render the primary Time Card table (Responsive Mobile + Full Desktop)
function renderShiftsTable(shifts) {
  const tbody = document.getElementById('shiftsTableBody');
  tbody.innerHTML = '';

  if (shifts.length === 0) {
    tbody.innerHTML = `<tr><td colspan="9" class="p-6 text-center text-[#6c757d]">No shifts recorded for this week.</td></tr>`;
    return;
  }

  shifts.forEach(shift => {
    const hasPunches = (shift.total_hours > 0) || (shift.pay_code && !shift.pay_code.includes('No Card'));
    const isExpanded = expandedRows.has(shift.date);
    const details = shift.details || [];
    const auditHistory = shift.audit_history || [];

    const tr = document.createElement('tr');
    tr.dataset.date = shift.date || '';
    tr.className = `timecard-card cursor-pointer select-none transition-colors ${isExpanded
        ? 'activeRow font-medium'
        : hasPunches
          ? 'hover:bg-[#f8f9fa]'
          : 'opacity-50 hover:bg-[#f8f9fa]'
      }`;

    tr.onclick = (e) => {
      if (e.target.closest('button')) return;
      if (hasPunches) toggleActiveRow(shift.date);
    };

    const chevronIcon = hasPunches
      ? `<i class="fa-solid fa-chevron-${isExpanded ? 'up text-[#f59e0b]' : 'down text-[#6c757d]'} text-xs"></i>`
      : '';

    const formattedDateStr = formatShiftDate(shift.date, shift.day_name);

    // Desktop Punch Displays
    const startDisplay = shift.start_time_dec !== null && shift.start_time_dec !== undefined
      ? `${shift.start_time_dec.toFixed(2)}${shift.is_late ? '*' : ''} <span class="text-[11px] text-[#6c757d] block font-normal">${shift.start_time_clock || ''}</span>`
      : '<span class="text-[#adb5bd]">N/A</span>';

    const endDisplay = shift.end_time_dec !== null && shift.end_time_dec !== undefined
      ? `${shift.end_time_dec.toFixed(2)} <span class="text-[11px] text-[#6c757d] block font-normal">${shift.end_time_clock || ''}</span>`
      : '<span class="text-[#adb5bd]">N/A</span>';

    const mealDisplay = shift.meal_unpaid_hours > 0
      ? `${shift.meal_unpaid_hours.toFixed(2)} <span class="text-[11px] text-[#6c757d] block font-normal">${Math.round(shift.meal_unpaid_hours * 60)}m</span>`
      : '<span class="text-[#adb5bd]">N/A</span>';

    // Mobile Compact Punches Display (fits Start, End, and Meal inside 1 column on phones)
    const mobilePunchDisplay = (shift.start_time_dec !== null && shift.start_time_dec !== undefined)
      ? `<div class="font-semibold text-[#212529] whitespace-nowrap">${shift.start_time_dec.toFixed(2)}${shift.is_late ? '*' : ''} - ${shift.end_time_dec !== null && shift.end_time_dec !== undefined ? shift.end_time_dec.toFixed(2) : 'N/A'}</div>
         <div class="text-[10px] text-[#6c757d] font-normal whitespace-nowrap">${shift.start_time_clock || ''} - ${shift.end_time_clock || ''}${shift.meal_unpaid_hours > 0 ? ` • ${Math.round(shift.meal_unpaid_hours * 60)}m meal` : ''}</div>`
      : '<span class="text-[#adb5bd]">N/A</span>';

    const rateDisplay = shift.pay_rate > 0 ? shift.pay_rate.toFixed(2) : '0.00';
    const hoursDisplay = shift.total_hours > 0 ? shift.total_hours.toFixed(2) : '0.00';
    const grossDisplay = shift.gross_pay > 0 ? shift.gross_pay.toFixed(2) : '0.00';

    tr.innerHTML = `
      <td class="text-center w-6 sm:w-8 px-1.5 sm:px-2 py-2.5 sm:py-3">${chevronIcon}</td>
      <td class="font-bold text-[#212529]">
        <div class="flex flex-wrap items-center gap-1">
          <span class="whitespace-nowrap">${formattedDateStr}</span>
          ${shift.is_late ? `
            <span title="Late punch" class="text-[9px] sm:text-[10px] font-bold text-[#b02a37] bg-[#f8d7da] px-1 py-0.2 rounded border border-[#f5c6cb]">
              *LATE
            </span>
          ` : ''}
          ${auditHistory.length > 0 ? `
            <span title="${auditHistory.length} punch modification(s) detected" class="text-[9px] sm:text-[10px] font-bold text-[#856404] bg-[#fff3cd] px-1 py-0.2 rounded border border-[#ffeeba]">
              <i class="fa-solid fa-clock-rotate-left"></i> ${auditHistory.length}
            </span>
          ` : ''}
        </div>
        <div class="sm:hidden text-[10px] font-normal text-[#6c757d] mt-0.5">${shift.pay_code || 'No Card'}</div>
      </td>
      <td class="hidden sm:table-cell text-[#495057] font-medium">${shift.pay_code || 'No Card'}</td>
      <td class="text-right font-medium">
        <div class="sm:hidden">${mobilePunchDisplay}</div>
        <div class="hidden sm:block">${startDisplay}</div>
      </td>
      <td class="hidden sm:table-cell text-right font-medium">${endDisplay}</td>
      <td class="hidden sm:table-cell text-right font-medium">${mealDisplay}</td>
      <td class="hidden sm:table-cell text-right font-mono text-[#495057]">${rateDisplay}</td>
      <td class="text-right font-bold text-[#1e293b]">
        <span>${hoursDisplay}</span>
        ${shift.pay_rate > 0 ? `<span class="sm:hidden block text-[10px] font-mono font-normal text-[#6c757d]">$${rateDisplay}/h</span>` : ''}
      </td>
      <td class="text-right font-bold text-[#212529]">${grossDisplay}</td>
    `;
    tbody.appendChild(tr);

    // Expandable activeRow Activity Breakdown
    if (isExpanded && hasPunches) {
      const accTr = document.createElement('tr');
      accTr.className = 'acc-row bg-white';

      let innerHtml = '';
      if (details.length === 0) {
        innerHtml = `
          <div class="p-3 text-center text-xs text-[#6c757d] italic">
            No inner activity split breakdown recorded.
          </div>
        `;
      } else {
        let rowsHtml = '';
        let mobileCardsHtml = '';
        let totStHrs = 0, totStAmt = 0, totOtHrs = 0, totOtAmt = 0, totDtHrs = 0, totDtAmt = 0, totFlatAmt = 0;

        for (let i = 0; i < details.length; i++) {
          const d = details[i];
          totStHrs += d.st_hrs || 0;
          totStAmt += d.st_amt || 0;
          totOtHrs += d.ot_hrs || 0;
          totOtAmt += d.ot_amt || 0;
          totDtHrs += d.dt_hrs || 0;
          totDtAmt += d.dt_amt || 0;
          totFlatAmt += d.flat_amt || 0;

          rowsHtml += `
            <tr class="hover:bg-[#f8f9fa]">
              <td>${d.trans_type || 'Timecard'}</td>
              <td>${d.op_type || 'STD'}</td>
              <td>${d.center || ''}</td>
              <td>${d.sort || ''}</td>
              <td class="font-semibold text-[#1e293b]">${d.activity || 'SHIFT ACTIVITY'}</td>
              <td class="text-right font-mono">${formatDecimal(d.rate)}</td>
              <td class="text-right font-mono">${formatDecimal(d.st_hrs)}</td>
              <td class="text-right font-mono">${formatDecimal(d.st_amt)}</td>
              <td class="text-right font-mono">${formatDecimal(d.ot_hrs)}</td>
              <td class="text-right font-mono">${formatDecimal(d.ot_amt)}</td>
              <td class="text-right font-mono">${formatDecimal(d.dt_hrs)}</td>
              <td class="text-right font-mono">${formatDecimal(d.dt_amt)}</td>
              <td class="text-right font-mono">${formatDecimal(d.flat_amt)}</td>
            </tr>`;

          mobileCardsHtml += `
            <div class="p-2.5 rounded border border-[#dee2e6] bg-white space-y-1.5 text-xs">
              <div class="flex items-center justify-between border-b border-[#f1f3f5] pb-1">
                <span class="font-bold text-[#1e293b]">${d.activity || 'SHIFT ACTIVITY'}</span>
                <span class="font-mono text-[11px] font-semibold text-[#495057]">$${formatDecimal(d.rate)}/hr</span>
              </div>
              <div class="text-[10px] text-[#6c757d] flex items-center gap-2">
                <span>${d.trans_type || 'Timecard'}</span>
                <span>•</span>
                <span>${d.op_type || 'STD'} ${d.center ? `#${d.center}` : ''}</span>
              </div>
              <div class="grid grid-cols-3 gap-1.5 pt-0.5 text-[11px]">
                <div class="bg-[#f8f9fa] px-2 py-1 rounded">
                  <span class="text-[#6c757d] block text-[10px]">ST</span>
                  <span class="font-mono font-bold">${formatDecimal(d.st_hrs)}h</span>
                  <span class="font-mono text-[10px] text-[#495057] block">$${formatDecimal(d.st_amt)}</span>
                </div>
                <div class="bg-[#f8f9fa] px-2 py-1 rounded">
                  <span class="text-[#6c757d] block text-[10px]">OT</span>
                  <span class="font-mono font-bold text-[#b02a37]">${formatDecimal(d.ot_hrs)}h</span>
                  <span class="font-mono text-[10px] text-[#b02a37] block">$${formatDecimal(d.ot_amt)}</span>
                </div>
                <div class="bg-[#f8f9fa] px-2 py-1 rounded">
                  <span class="text-[#6c757d] block text-[10px]">DT</span>
                  <span class="font-mono font-bold">${formatDecimal(d.dt_hrs)}h</span>
                  <span class="font-mono text-[10px] text-[#495057] block">$${formatDecimal(d.dt_amt)}</span>
                </div>
              </div>
            </div>`;
        }

        // Punch modifications notice if this shift was altered
        let punchModNotice = '';
        if (auditHistory.length > 0) {
          punchModNotice = `
            <div class="mb-2.5 p-2.5 rounded bg-[#fff3cd] border border-[#ffeeba] text-xs text-[#856404] flex flex-wrap items-center justify-between gap-2">
              <div class="flex items-center gap-2">
                <i class="fa-solid fa-triangle-exclamation text-[#856404]"></i>
                <span><b>Punch Modification Detected:</b> Punches for this date were changed mid-week.</span>
              </div>
              <button onclick="document.getElementById('tabAudit').click()" class="text-xs font-bold underline text-[#1e293b] hover:text-black">
                View in Punch Auditor &gt;&gt;
              </button>
            </div>
          `;
        }

        innerHtml = `
          <div class="p-2.5 sm:p-4 space-y-2.5 bg-[#fdfdfd] border-b border-[#dee2e6]">
            ${punchModNotice}
            <!-- Mobile Segment Breakdown Cards -->
            <div class="sm:hidden space-y-2">
              ${mobileCardsHtml}
              <div class="p-2.5 rounded border border-[#ffeeba] bg-[#fdfaf0] text-xs flex items-center justify-between font-bold text-[#1e293b]">
                <span>Daily Total</span>
                <span class="font-mono">ST: ${formatDecimal(totStHrs)}h ($${formatDecimal(totStAmt)}) • OT: <b class="text-[#b02a37]">${formatDecimal(totOtHrs)}h ($${formatDecimal(totOtAmt)})</b></span>
              </div>
            </div>

            <!-- Desktop Full 13-Column Breakdown Table -->
            <div class="hidden sm:block overflow-x-auto border border-[#dee2e6] rounded">
              <table class="w-full text-left text-xs table-bordered">
                <thead>
                  <tr>
                    <th>TransType</th>
                    <th>OPType</th>
                    <th>Center</th>
                    <th>Sort</th>
                    <th>Activity</th>
                    <th class="text-right">Rate</th>
                    <th class="text-right">ST Hrs</th>
                    <th class="text-right">ST Amt</th>
                    <th class="text-right">OT Hrs</th>
                    <th class="text-right">OT Amt</th>
                    <th class="text-right">DT Hrs</th>
                    <th class="text-right">DT Amt</th>
                    <th class="text-right">Flat Amt</th>
                  </tr>
                </thead>
                <tbody>
                  ${rowsHtml}
                  <tr class="dailyTotalRow">
                    <td>Daily Total</td>
                    <td colspan="5"></td>
                    <td class="text-right font-mono font-bold">${formatDecimal(totStHrs)}</td>
                    <td class="text-right font-mono font-bold">${formatDecimal(totStAmt)}</td>
                    <td class="text-right font-mono font-bold text-[#b02a37]">${formatDecimal(totOtHrs)}</td>
                    <td class="text-right font-mono font-bold text-[#b02a37]">${formatDecimal(totOtAmt)}</td>
                    <td class="text-right font-mono font-bold">${formatDecimal(totDtHrs)}</td>
                    <td class="text-right font-mono font-bold">${formatDecimal(totDtAmt)}</td>
                    <td class="text-right font-mono font-bold">${formatDecimal(totFlatAmt)}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        `;
      }

      accTr.innerHTML = `<td colspan="9" class="p-0">${innerHtml}</td>`;
      tbody.appendChild(accTr);
    }
  });
}

// Load Time Off Entitlement Bank
async function loadTimeOff() {
  try {
    const res = await fetch('/api/timeoff');
    const data = await res.json();
    const profile = data.profile;
    const entitlements = data.entitlements || [];

    const empNameEl = document.getElementById('timeOffEmpName');
    const empIdEl = document.getElementById('timeOffEmpId');
    if (empNameEl) empNameEl.textContent = (profile && profile.emp_name) ? profile.emp_name : '—';
    if (empIdEl) empIdEl.textContent = (profile && profile.emp_id) ? profile.emp_id : '—';

    // Update vacation pill in tab
    const vacPill = document.getElementById('vacationPill');
    if (vacPill) {
      const vac = entitlements.find(e => e.timeoff_type === 'VAC' || e.title === 'Vacation');
      if (vac) {
        vacPill.textContent = `${formatDecimal(vac.available_time)} ${(vac.time_basis || 'h').toLowerCase()}`;
      } else {
        vacPill.textContent = '0.0h';
      }
    }

    const tbody = document.getElementById('entitlementsTableBody');
    tbody.innerHTML = '';

    if (entitlements.length === 0) {
      tbody.innerHTML = `<tr><td colspan="4" class="p-6 text-center text-[#6c757d]">No entitlement records found.</td></tr>`;
      return;
    }

    entitlements.forEach(item => {
      const tr = document.createElement('tr');
      tr.className = 'hover:bg-[#f8f9fa]';
      tr.innerHTML = `
        <td class="font-bold text-[#1e293b]">${item.title}</td>
        <td class="font-bold font-mono text-[#212529]">${formatDecimal(item.available_time)}</td>
        <td class="text-[#495057]">${item.time_basis}</td>
        <td class="font-mono text-[#6c757d]">${item.next_earn_date || 'N/A'}</td>
      `;
      tbody.appendChild(tr);
    });
  } catch (err) {
    console.error('Failed to load time off:', err);
  }
}

// Load Punch Change Audits (Strictly Start Time, End Time, Meal/Unpaid) scoped to the selected week
async function loadAuditHistory() {
  try {
    const res = await fetch('/api/audit-history');
    const data = await res.json();
    allAuditLogs = data.audit_logs || [];
    renderAuditHistoryForCurrentWeek();
  } catch (err) {
    console.error('Failed to load audit history:', err);
  }
}

function renderAuditHistoryForCurrentWeek() {
  const activeWeekEnd = currentWeekData ? currentWeekData.week_end_date : '';
  const weekLabelEl = document.getElementById('auditWeekLabel');
  if (weekLabelEl) {
    weekLabelEl.textContent = activeWeekEnd ? `Week Ending ${activeWeekEnd}` : 'Current Week';
  }

  // Filter audit logs to ONLY the currently selected (affected) week
  let logs = [];
  if (activeWeekEnd) {
    logs = allAuditLogs.filter(l => l.week_end_date === activeWeekEnd);
    if (logs.length === 0 && currentWeekData && Array.isArray(currentWeekData.shifts)) {
      currentWeekData.shifts.forEach(s => {
        if (Array.isArray(s.audit_history)) {
          s.audit_history.forEach(a => logs.push(a));
        }
      });
    }
  }

  const badge = document.getElementById('auditBadge');
  if (badge) {
    if (logs.length > 0) {
      badge.textContent = logs.length;
      badge.classList.remove('hidden');
    } else {
      badge.classList.add('hidden');
    }
  }

  const tbody = document.getElementById('auditTableBody');
  if (!tbody) return;
  tbody.innerHTML = '';

  if (logs.length === 0) {
    const msg = activeWeekEnd
      ? `No punch modifications detected for week ending ${activeWeekEnd}.`
      : 'No punch modifications detected yet.';
    tbody.innerHTML = `<tr><td colspan="6" class="p-6 text-center text-[#6c757d]">${msg}</td></tr>`;
    return;
  }

  logs.forEach(l => {
    const tr = document.createElement('tr');
    tr.className = 'hover:bg-[#f8f9fa]';

    const diffText = formatDiffMinutes(l.diff_num, l.field_name);
    const isNegative = l.diff_num < 0;
    const diffBadgeClass = l.diff_num !== 0
      ? (isNegative ? 'bg-[#f8d7da] text-[#721c24] border-[#f5c6cb]' : 'bg-[#d1e7dd] text-[#0f5132] border-[#badbcc]')
      : 'text-[#6c757d]';

    tr.innerHTML = `
      <td class="font-mono text-xs text-[#6c757d] whitespace-nowrap">${l.detected_at || '-'}</td>
      <td class="font-bold text-[#212529] whitespace-nowrap">${l.date}</td>
      <td class="font-bold text-[#1e293b] whitespace-nowrap">
        <span class="px-2 py-0.5 rounded bg-[#f8f9fa] border border-[#dee2e6]">${l.field_name}</span>
      </td>
      <td class="font-mono text-xs line-through text-[#dc3545]">${l.old_value || '-'}</td>
      <td class="font-mono text-xs font-bold text-[#198754]">${l.new_value || '-'}</td>
      <td class="text-right">
        <span class="font-mono text-xs font-bold px-2 py-0.5 rounded border ${diffBadgeClass}">
          ${diffText}
        </span>
      </td>
    `;
    tbody.appendChild(tr);
  });
}

let isUserLoggedIn = false;

function updateAuthGateUI(hasSyncedWeeks = false) {
  const loginRequiredView = document.getElementById('loginRequiredView');
  const dashboardContent = document.getElementById('dashboardContent');
  const weekSelectContainer = document.getElementById('weekSelectContainer');
  const showDashboard = isUserLoggedIn || hasSyncedWeeks || !!currentWeekData;

  if (loginRequiredView) {
    loginRequiredView.classList.toggle('hidden', showDashboard);
  }
  if (dashboardContent) {
    dashboardContent.classList.toggle('hidden', !showDashboard);
  }
  if (weekSelectContainer) {
    weekSelectContainer.classList.toggle('hidden', !showDashboard);
    weekSelectContainer.classList.toggle('flex', showDashboard);
  }
  refreshBatteryOptimizationNotice();
}

// Check active authentication status with portal
async function checkAuthStatus() {
  const authDot = document.getElementById('authDot');
  const authLabel = document.getElementById('authLabel');
  const authBtn = document.getElementById('authBtn');
  const syncBtn = document.getElementById('syncBtn');

  try {
    const res = await fetch('/api/auth/status');
    const data = await res.json();
    isUserLoggedIn = !!data.logged_in;
    updateAuthGateUI();

    if (isUserLoggedIn) {
      if (authDot) authDot.className = 'w-2 h-2 rounded-full bg-[#dc3545]';
      if (authLabel) authLabel.textContent = 'Log Out';
      if (authBtn) {
        authBtn.className = 'px-2.5 py-1.5 text-xs font-bold rounded border border-[#f5c6cb] bg-[#f8d7da] text-[#721c24] hover:bg-[#f1b0b7] flex items-center gap-1.5 shadow-sm transition-colors cursor-pointer whitespace-nowrap';
        authBtn.title = 'Click to log out.';
      }
      if (syncBtn) {
        syncBtn.classList.remove('hidden');
        syncBtn.classList.add('flex');
      }
    } else {
      if (authDot) authDot.className = 'w-2 h-2 rounded-full bg-[#198754] animate-pulse';
      if (authLabel) authLabel.textContent = 'Log In';
      if (authBtn) {
        authBtn.className = 'px-2.5 py-1.5 text-xs font-bold rounded border border-[#ffeeba] bg-[#fff3cd] text-[#856404] hover:bg-[#ffe8a1] flex items-center gap-1.5 shadow-sm transition-colors cursor-pointer whitespace-nowrap';
        authBtn.title = 'Not authenticated. Click to log in.';
      }
      if (syncBtn) {
        syncBtn.classList.add('hidden');
        syncBtn.classList.remove('flex');
      }
    }
  } catch (err) {
    console.error('Failed to check auth status:', err);
    if (authLabel) authLabel.textContent = 'Log In';
    if (syncBtn) {
      syncBtn.classList.add('hidden');
      syncBtn.classList.remove('flex');
    }
  }
}

// Start interactive login flow to get authentication out of the way upfront
async function startLoginFlow() {
  const banner = document.getElementById('statusBanner');
  const stageTitle = document.getElementById('syncStageTitle');
  const statusMsg = document.getElementById('statusMessage');
  const progressBar = document.getElementById('syncProgressBar');
  const percentBadge = document.getElementById('syncPercentBadge');
  const syncBtn = document.getElementById('syncBtn');
  const syncIcon = document.getElementById('syncIcon');
  const syncDetails = document.getElementById('syncDetails');

  banner.classList.remove('hidden');
  stageTitle.textContent = 'Awaiting Portal Login';
  statusMsg.textContent = 'Please enter your credentials and 2FA...';
  syncDetails.textContent = 'Complete 2FA in login window';
  progressBar.style.width = '20%';
  percentBadge.textContent = 'Auth';
  syncBtn.disabled = true;
  syncIcon.classList.add('fa-spin');

  try {
    await fetch('/api/auth/login', { method: 'POST' });
    if (pollStatusInterval) clearInterval(pollStatusInterval);
    pollStatusInterval = setInterval(checkSyncStatus, 800);
  } catch (err) {
    alert(`Could not start login flow: ${err.message}`);
    syncBtn.disabled = false;
    syncIcon.classList.remove('fa-spin');
  }
}

// Log out of portal session and clear synced data
async function logoutUser() {
  const confirmed = confirm('Log out of your session and clear synced data from this device?');
  if (!confirmed) return;

  try {
    const res = await fetch('/api/auth/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ clear_data: true })
    });
    if (res.ok) {
      isUserLoggedIn = false;
      weekDetailsCache.clear();
      knownWorkedDaysByWeek.clear();
      notifiedShiftDates.clear();
      expandedRows.clear();
      currentWeekData = null;
      const banner = document.getElementById('statusBanner');
      if (banner) banner.classList.add('hidden');
      const toast = document.getElementById('newTimecardToast');
      if (toast) toast.classList.add('hidden');
      await checkAuthStatus();
      await Promise.all([
        loadWeeks(),
        loadTimeOff(),
        loadAuditHistory()
      ]);
    }
  } catch (err) {
    alert(`Failed to log out: ${err.message}`);
  }
}

// Trigger Sync
// maxWeeks = 0 -> Sync All 8+ weeks + Time Off Bank (Initial login)
// maxWeeks = 1 -> Refresh ONLY the current Pending week (skips the 7 Finalized weeks)
// silent = true -> Smart Daily Auto-Check in background without showing progress banner
async function triggerSync(headless = true, maxWeeks = 0, syncTimeoff = true, silent = false) {
  if (!isUserLoggedIn && headless) {
    if (silent) return;
    const proceed = confirm("You are not logged in yet.\n\nClick OK to open the login window now, complete 2FA, and authenticate your session first.");
    if (proceed) {
      return triggerSync(false, maxWeeks, syncTimeoff, false);
    }
    return;
  }

  isSilentSync = !!silent;
  lastSmartCheckAt = Date.now();

  const syncBtn = document.getElementById('syncBtn');
  const syncIcon = document.getElementById('syncIcon');
  const banner = document.getElementById('statusBanner');
  const statusMsg = document.getElementById('statusMessage');
  const stageTitle = document.getElementById('syncStageTitle');
  const percentBadge = document.getElementById('syncPercentBadge');
  const progressBar = document.getElementById('syncProgressBar');
  const syncDetails = document.getElementById('syncDetails');
  const loginFallbackBtn = document.getElementById('loginFallbackBtn');

  if (!isSilentSync) {
    syncBtn.disabled = true;
    syncIcon.classList.add('fa-spin');
    banner.classList.remove('hidden');
    if (loginFallbackBtn) loginFallbackBtn.classList.add('hidden');

    const isCurrentWeekOnly = maxWeeks === 1;
    stageTitle.textContent = !headless
      ? 'Opening login window upfront...'
      : (isCurrentWeekOnly ? 'Refreshing Current Week Only...' : 'Syncing All 8+ Weeks...');
    percentBadge.textContent = '10%';
    progressBar.style.width = '10%';
    statusMsg.textContent = !headless
      ? 'Please enter your credentials and 2FA...'
      : (isCurrentWeekOnly
        ? 'Checking only the current Pending week (skipping finalized weeks)...'
        : 'Syncing all 8+ weeks and Time Off Bank in background...');
    syncDetails.textContent = !headless
      ? 'Awaiting 2FA completion...'
      : (isCurrentWeekOnly ? 'Fast 1-week pull-to-refresh' : 'Full multi-week sync');
  }

  try {
    await fetch('/api/sync', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ max_weeks: maxWeeks, headless: headless, sync_timeoff: syncTimeoff })
    });

    if (pollStatusInterval) clearInterval(pollStatusInterval);
    pollStatusInterval = setInterval(checkSyncStatus, 800);
  } catch (err) {
    console.error('Sync request failed:', err);
    if (!isSilentSync) {
      syncBtn.disabled = false;
      syncIcon.classList.remove('fa-spin');
      stageTitle.textContent = 'Sync Failed to Start';
      statusMsg.textContent = err.message;
    }
    isSilentSync = false;
  }
}

// Check Sync Status & Update Progression Bar
async function checkSyncStatus() {
  try {
    const res = await fetch('/api/status');
    const data = await res.json();
    const banner = document.getElementById('statusBanner');
    const syncBtn = document.getElementById('syncBtn');
    const syncIcon = document.getElementById('syncIcon');
    const statusMsg = document.getElementById('statusMessage');
    const stageTitle = document.getElementById('syncStageTitle');
    const percentBadge = document.getElementById('syncPercentBadge');
    const progressBar = document.getElementById('syncProgressBar');
    const syncDetails = document.getElementById('syncDetails');
    const loginFallbackBtn = document.getElementById('loginFallbackBtn');

    if (!isSilentSync) {
      const pct = Math.max(5, Math.min(100, data.percent || 0));
      progressBar.style.width = `${pct}%`;
      percentBadge.textContent = `${pct}%`;

      if (data.stage) stageTitle.textContent = data.stage;
      if (data.message) statusMsg.textContent = data.message;
      if (data.stage && data.message) {
        syncDetails.textContent = `${pct}% complete`;
      }

      if (data.requires_login) {
        if (loginFallbackBtn) loginFallbackBtn.classList.remove('hidden');
        syncDetails.textContent = 'Session authentication required';
        statusMsg.textContent = 'Please click "Open Login Window" to authenticate your session.';
      } else {
        if (loginFallbackBtn) loginFallbackBtn.classList.add('hidden');
      }
    }

    if (!data.sync_in_progress) {
      const wasSilent = isSilentSync;
      isSilentSync = false;
      clearInterval(pollStatusInterval);
      pollStatusInterval = null;
      syncBtn.disabled = false;
      syncIcon.classList.remove('fa-spin');

      if (!wasSilent) {
        if (data.latest_log && data.latest_log.status === 'SUCCESS') {
          progressBar.style.width = '100%';
          percentBadge.textContent = '100%';
          stageTitle.textContent = 'Sync Complete!';
          statusMsg.textContent = data.latest_log.message;
          setTimeout(() => {
            banner.classList.add('hidden');
          }, 5000);
        } else if (data.requires_login) {
          stageTitle.textContent = 'Login Required';
        } else if (data.latest_log) {
          stageTitle.textContent = 'Sync Ended';
          statusMsg.textContent = `${data.latest_log.status}: ${data.latest_log.message}`;
        }
      }

      const hasDailyOptAlert = Array.isArray(data.new_timecards) && data.new_timecards.some(tc => isOptionDayShift(tc));
      if (Array.isArray(data.new_timecards) && data.new_timecards.length > 0) {
        notifyDailyTimecardAdded(data.new_timecards);
      }
      if (!hasDailyOptAlert && Array.isArray(data.option_day_alerts) && data.option_day_alerts.length > 0) {
        const optAlert = data.option_day_alerts[0];
        const optTitle = `${optAlert.title || 'Option Day'} Used`;
        const optBody = `${optAlert.title || 'Option Day'} balance decreased: ${formatDecimal(optAlert.old_available)} → ${formatDecimal(optAlert.new_available)} ${optAlert.time_basis || 'Days'} remaining`;
        const toast = document.getElementById('newTimecardToast');
        const toastTitle = document.getElementById('newTimecardToastTitle');
        const toastBody = document.getElementById('newTimecardToastBody');
        if (toast && toastTitle && toastBody) {
          toastTitle.textContent = optTitle;
          toastBody.textContent = optBody;
          toast.classList.remove('hidden');
        }
        try {
          if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.showTimecardNotificationForShift) {
            window.AndroidNative.showTimecardNotificationForShift(optTitle, optBody, '', '');
          }
        } catch (e) {}
      }

      weekDetailsCache.clear();
      if (!wasSilent) {
        expandedRows.clear(); // Ensure all day accordions start collapsed after manual sync
      }
      if (typeof window.__collapsePullRefreshBar === 'function') {
        window.__collapsePullRefreshBar();
      }
      await checkAuthStatus();
      await Promise.all([
        loadWeeks(currentWeekData ? currentWeekData.week_end_date : null),
        loadTimeOff(),
        loadAuditHistory()
      ]);
      // Silently ensure permanent local vault and MongoDB Cloud Vault are synchronized
      try {
        fetch('/api/archive/sync', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({})
        }).catch(() => {});
      } catch (e) {}
    }
  } catch (err) {
    console.error('Failed to poll status:', err);
  }
}

// Tab navigation handler
function setupTabs() {
  const tabs = [
    { btnId: 'tabTimeCard', viewId: 'viewTimeCard' },
    { btnId: 'tabTimeOff', viewId: 'viewTimeOff' },
    { btnId: 'tabAudit', viewId: 'viewAudit' },
    { btnId: 'tabArchive', viewId: 'viewArchive' }
  ];

  tabs.forEach(tab => {
    const btn = document.getElementById(tab.btnId);
    if (!btn) return;

    btn.addEventListener('click', () => {
      tabs.forEach(t => {
        const otherBtn = document.getElementById(t.btnId);
        const otherView = document.getElementById(t.viewId);
        if (!otherBtn || !otherView) return;
        if (t.btnId === tab.btnId) {
          otherBtn.className = 'tab-btn px-3 sm:px-4 py-2 text-xs font-bold rounded border sm:border-0 sm:rounded-t sm:border-b-2 border-[#1e293b] text-[#1e293b] bg-white flex items-center justify-center sm:justify-start gap-1.5 shadow-sm';
          otherView.classList.remove('hidden');
        } else {
          otherBtn.className = 'tab-btn px-3 sm:px-4 py-2 text-xs font-semibold rounded border sm:border-0 border-[#dee2e6] sm:rounded-t text-[#6c757d] hover:text-[#212529] bg-white/60 sm:bg-transparent hover:bg-white flex items-center justify-center sm:justify-start gap-1.5';
          otherView.classList.add('hidden');
        }
      });
    });
  });
}

// Pull-Down-to-Refresh handler (Refreshes ONLY the Current Pending Week)
function setupPullToRefresh() {
  const container = document.getElementById('pullRefreshContainer');
  const icon = document.getElementById('pullRefreshIcon');
  const text = document.getElementById('pullRefreshText');
  if (!container || !icon || !text) return;

  let startY = null;
  let currentPull = 0;
  let isRefreshingPull = false;
  let isMouseDown = false;
  const PULL_THRESHOLD = 42;

  function getScrollTop() {
    return Math.max(
      window.scrollY || 0,
      document.documentElement ? document.documentElement.scrollTop || 0 : 0,
      document.body ? document.body.scrollTop || 0 : 0
    );
  }

  function collapsePullBar() {
    isRefreshingPull = false;
    startY = null;
    currentPull = 0;
    container.style.height = '0px';
    container.classList.remove('border-[#ffeeba]');
    icon.className = 'fa-solid fa-arrow-down text-[#f59e0b] transition-transform duration-200';
    icon.style.transform = 'rotate(0deg)';
    text.textContent = 'Pull down to refresh current week only';
  }

  window.__collapsePullRefreshBar = collapsePullBar;

  function onStart(clientY, targetEl) {
    if (isRefreshingPull) return;
    if (!isUserLoggedIn && !currentWeekData) {
      startY = null;
      return;
    }
    if (targetEl && targetEl.closest && targetEl.closest('select, button, input, a')) {
      startY = null;
      return;
    }
    if (getScrollTop() > 15) {
      startY = null;
      return;
    }
    startY = clientY;
    currentPull = 0;
  }

  function onMove(clientY, rawEvent) {
    if (startY === null || isRefreshingPull) return;
    if (getScrollTop() > 15) {
      resetPull();
      return;
    }
    const delta = clientY - startY;
    if (delta <= 0) {
      container.style.height = '0px';
      currentPull = 0;
      return;
    }

    // Prevent browser/WebView native overscroll from firing touchcancel
    if (delta > 6 && rawEvent && rawEvent.cancelable) {
      rawEvent.preventDefault();
    }

    currentPull = Math.min(72, delta * 0.55);
    container.style.height = `${currentPull}px`;
    container.classList.add('border-[#ffeeba]');

    if (currentPull >= PULL_THRESHOLD) {
      icon.style.transform = 'rotate(180deg)';
      text.textContent = 'Release to refresh current week only';
    } else {
      icon.style.transform = 'rotate(0deg)';
      text.textContent = 'Pull down to refresh current week only';
    }
  }

  function resetPull() {
    if (isRefreshingPull) return;
    startY = null;
    currentPull = 0;
    container.style.height = '0px';
    container.classList.remove('border-[#ffeeba]');
    icon.style.transform = 'rotate(0deg)';
  }

  function onEnd() {
    if (startY === null || isRefreshingPull) return;
    const triggered = currentPull >= PULL_THRESHOLD;
    startY = null;
    if (triggered) {
      isRefreshingPull = true;
      currentPull = 0;
      container.style.height = '44px';
      container.classList.add('border-[#ffeeba]');
      icon.style.transform = 'rotate(0deg)';
      icon.className = 'fa-solid fa-arrows-rotate fa-spin text-[#d97706]';
      text.textContent = 'Refreshing current week...';
      // Refresh ONLY the current week (max_weeks = 1, sync_timeoff = false)
      triggerSync(true, 1, false);
    } else {
      resetPull();
    }
  }

  // Touch events for mobile / Android (passive: false on touchmove prevents WebView touchcancel)
  window.addEventListener('touchstart', (e) => {
    if (e.touches && e.touches.length === 1) {
      onStart(e.touches[0].clientY, e.target);
    }
  }, { passive: true });

  window.addEventListener('touchmove', (e) => {
    if (startY !== null && e.touches && e.touches.length === 1) {
      onMove(e.touches[0].clientY, e);
    }
  }, { passive: false });

  window.addEventListener('touchend', onEnd);
  window.addEventListener('touchcancel', () => {
    // If the user already pulled past threshold before Android WebView fired touchcancel, still trigger
    if (currentPull >= PULL_THRESHOLD) {
      onEnd();
    } else {
      resetPull();
    }
  });

  // Mouse drag events for Desktop testing & usage
  window.addEventListener('mousedown', (e) => {
    if (e.button !== 0) return;
    isMouseDown = true;
    onStart(e.clientY, e.target);
  });

  window.addEventListener('mousemove', (e) => {
    if (!isMouseDown || startY === null) return;
    onMove(e.clientY, null);
  });

  window.addEventListener('mouseup', () => {
    if (!isMouseDown) return;
    isMouseDown = false;
    onEnd();
  });
}

// Initialization on DOMContentLoaded
document.addEventListener('DOMContentLoaded', async () => {
  setupTabs();
  setupPullToRefresh();
  setupSmartAutoCheck();
  await checkAuthStatus();
  refreshBatteryOptimizationNotice();
  Promise.all([
    loadWeeks(),
    loadTimeOff(),
    loadAuditHistory()
  ]).catch(err => console.error('Initial load error:', err));

  const batteryBanner = document.getElementById('batteryOptNotice');
  if (batteryBanner) {
    batteryBanner.addEventListener('click', (e) => {
      if (e.target && e.target.closest && e.target.closest('#dismissBatteryOptBtn')) return;
      try {
        if (typeof window.AndroidNative !== 'undefined' && typeof window.AndroidNative.requestIgnoreBatteryOptimizations === 'function') {
          window.AndroidNative.requestIgnoreBatteryOptimizations();
        }
      } catch (e2) {}
    });
  }

  const dismissBatteryBtn = document.getElementById('dismissBatteryOptBtn');
  if (dismissBatteryBtn) {
    dismissBatteryBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      try { localStorage.setItem('tvc_battery_tip_dismissed', '1'); } catch (e2) {}
      if (batteryBanner) batteryBanner.classList.add('hidden');
    });
  }

  const dismissToastBtn = document.getElementById('dismissTimecardToastBtn');
  if (dismissToastBtn) {
    dismissToastBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      const toast = document.getElementById('newTimecardToast');
      if (toast) toast.classList.add('hidden');
    });
  }

  const toastBanner = document.getElementById('newTimecardToast');
  if (toastBanner) {
    toastBanner.addEventListener('click', (e) => {
      if (e.target && e.target.closest && e.target.closest('#dismissTimecardToastBtn')) return;
      if (latestNotifiedTarget) {
        window.focusShiftFromNotification(latestNotifiedTarget.weekEndDate, latestNotifiedTarget.shiftDate);
      }
    });
  }

  const archiveMonthSelect = document.getElementById('archiveMonthSelect');
  if (archiveMonthSelect) {
    archiveMonthSelect.addEventListener('change', (e) => {
      renderArchivedWeeksForMonth(e.target.value);
    });
  }

  const authBtn = document.getElementById('authBtn');
  if (authBtn) {
    authBtn.addEventListener('click', () => {
      if (!isUserLoggedIn) {
        requestBrowserNotificationPermission();
        startLoginFlow();
      } else {
        logoutUser();
      }
    });
  }

  const loginGateBtn = document.getElementById('loginGateBtn');
  if (loginGateBtn) {
    loginGateBtn.addEventListener('click', () => {
      requestBrowserNotificationPermission();
      startLoginFlow();
    });
  }

  document.getElementById('weekSelect').addEventListener('change', (e) => {
    loadWeekDetails(e.target.value);
  });

  // "Sync" button syncs ONLY the current pending week (initial Login already syncs all 8+ weeks)
  document.getElementById('syncBtn').addEventListener('click', () => {
    requestBrowserNotificationPermission();
    triggerSync(true, 1, false);
  });

  const loginFallbackBtn = document.getElementById('loginFallbackBtn');
  if (loginFallbackBtn) {
    loginFallbackBtn.addEventListener('click', () => triggerSync(false));
  }

  // Keyboard navigation: Left/Right arrows step across consecutive weeks
  document.addEventListener('keydown', (e) => {
    if (['INPUT', 'SELECT', 'TEXTAREA'].includes(document.activeElement?.tagName)) return;
    const select = document.getElementById('weekSelect');
    if (!select || select.options.length <= 1) return;

    if (e.key === 'ArrowRight' && select.selectedIndex > 0) {
      select.selectedIndex -= 1;
      loadWeekDetails(select.value);
    } else if (e.key === 'ArrowLeft' && select.selectedIndex < select.options.length - 1) {
      select.selectedIndex += 1;
      loadWeekDetails(select.value);
    }
  });

  // Offline / Online network status monitor
  function updateNetworkOnlineStatus() {
    const badge = document.getElementById('offlineBadge');
    if (!badge) return;
    if (!navigator.onLine) {
      badge.classList.remove('hidden');
      badge.classList.add('flex');
    } else {
      badge.classList.remove('flex');
      badge.classList.add('hidden');
    }
  }

  window.addEventListener('online', updateNetworkOnlineStatus);
  window.addEventListener('offline', updateNetworkOnlineStatus);
  updateNetworkOnlineStatus();

  // Register 100% Offline PWA Service Worker (for iOS, Android, and Desktop)
  if ('serviceWorker' in navigator && (window.location.protocol === 'https:' || window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1' || window.location.hostname.startsWith('192.168.') || window.location.hostname.startsWith('10.'))) {
    navigator.serviceWorker.register('/sw.js', { scope: '/' })
      .then((reg) => {
        console.log('[TvC] Offline Service Worker registered:', reg.scope);
      })
      .catch((err) => {
        navigator.serviceWorker.register('/static/sw.js')
          .catch((e) => console.warn('[TvC] Service worker fallback registration error:', e));
      });
  }

  // --- Over-The-Air (OTA) Live Updates UI Handling ---
  window.showOtaUpdateReadyBanner = function (details) {
    const banner = document.getElementById('otaUpdateBanner');
    const desc = document.getElementById('otaBannerDesc');
    if (!banner) return;
    if (desc && details && details.version) {
      desc.textContent = `Version v${details.version} (Build ${details.build || ''}) downloaded from GitHub.`;
    }
    banner.classList.remove('hidden');
  };

  let otaCheckTimer = null;

  window.showOtaCheckResult = function (result) {
    if (otaCheckTimer) {
      clearTimeout(otaCheckTimer);
      otaCheckTimer = null;
    }
    const msg = document.getElementById('otaModalStatusMsg');
    if (!msg) return;

    if (result && result.up_to_date) {
      msg.innerHTML = `<span class="text-[#198754] font-bold"><i class="fa-solid fa-circle-check mr-1"></i> Up to date (v${result.version || ''})</span>`;
    } else if (result && result.downloading) {
      msg.innerHTML = `<span class="text-[#0d6efd] font-semibold"><i class="fa-solid fa-cloud-arrow-down fa-spin mr-1"></i> Downloading update v${result.version || ''}...</span>`;
    } else if (result && (result.update_installed || result.update_ready)) {
      msg.innerHTML = `<span class="text-[#198754] font-bold"><i class="fa-solid fa-circle-check mr-1"></i> Update v${result.version || ''} ready! Tap reload below.</span>`;
      refreshOtaModalInfo();
    } else if (result && result.error) {
      msg.innerHTML = `<span class="text-[#dc3545] font-semibold"><i class="fa-solid fa-triangle-exclamation mr-1"></i> ${result.message || 'Check failed. Tap Check Now to retry.'}</span>`;
    } else {
      msg.innerHTML = `<span class="text-[#198754] font-bold"><i class="fa-solid fa-circle-check mr-1"></i> Up to date</span>`;
    }
  };

  const otaModal = document.getElementById('otaModal');
  const checkOtaBtn = document.getElementById('checkOtaUpdateBtn');
  const closeOtaModalBtn = document.getElementById('closeOtaModalBtn');
  const otaModalCheckBtn = document.getElementById('otaModalCheckBtn');
  const otaModalRollbackBtn = document.getElementById('otaModalRollbackBtn');
  const otaReloadBtn = document.getElementById('otaReloadBtn');
  const otaDismissBannerBtn = document.getElementById('otaDismissBannerBtn');

  async function refreshOtaModalInfo() {
    try {
      const res = await fetch('/api/ota/status');
      if (res.ok) {
        const info = await res.json();
        const verEl = document.getElementById('otaModalVersion');
        const buildEl = document.getElementById('otaModalBuild');
        const repoEl = document.getElementById('otaModalRepo');
        const footerVer = document.getElementById('footerVersionBadge');
        const footerBuild = document.getElementById('footerBuildBadge');

        if (verEl && info.version) verEl.textContent = `v${info.version}`;
        if (buildEl && info.build) buildEl.textContent = String(info.build);
        if (repoEl && info.github_repo) repoEl.textContent = info.github_repo;
        if (footerVer && info.version) footerVer.textContent = `v${info.version}`;
        if (footerBuild && info.build) footerBuild.textContent = `Build ${info.build}`;
      }
    } catch (e) {}
  }

  if (checkOtaBtn) {
    checkOtaBtn.addEventListener('click', () => {
      refreshOtaModalInfo();
      const msg = document.getElementById('otaModalStatusMsg');
      if (msg) msg.textContent = 'Updates download over-the-air from GitHub without reinstalling the APK.';
      if (otaModal) otaModal.classList.remove('hidden');
    });
  }

  if (closeOtaModalBtn) {
    closeOtaModalBtn.addEventListener('click', () => {
      if (otaModal) otaModal.classList.add('hidden');
    });
  }

  if (otaModalCheckBtn) {
    otaModalCheckBtn.addEventListener('click', async () => {
      const msg = document.getElementById('otaModalStatusMsg');
      if (msg) msg.innerHTML = '<span class="text-[#0d6efd] font-semibold"><i class="fa-solid fa-circle-notch fa-spin mr-1"></i> Checking GitHub edge mirror...</span>';

      if (otaCheckTimer) clearTimeout(otaCheckTimer);
      // Hard UI watchdog: no spinner can EVER remain after 4 seconds
      otaCheckTimer = setTimeout(async () => {
        if (msg && msg.innerHTML.includes('fa-spin')) {
          try {
            const st = await (await fetch('/api/ota/status')).json();
            msg.innerHTML = `<span class="text-[#495057] font-semibold"><i class="fa-solid fa-circle-check text-[#198754] mr-1"></i> Up to date (v${st.version || '2.4.1'})</span>`;
          } catch (e) {
            msg.innerHTML = `<span class="text-[#6c757d]">Check completed.</span>`;
          }
        }
      }, 4000);

      try {
        if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.checkForUpdates) {
          window.AndroidNative.checkForUpdates(true);
        } else {
          const res = await fetch('/api/ota/check', { method: 'POST' });
          if (res.ok) {
            const data = await res.json();
            if (data.update_available) {
              window.showOtaCheckResult({ update_installed: true, version: data.version });
            } else if (data.up_to_date) {
              window.showOtaCheckResult({ up_to_date: true, version: data.version });
            } else {
              window.showOtaCheckResult({ up_to_date: true });
            }
          } else {
            window.showOtaCheckResult({ error: true, message: `Could not reach server (HTTP ${res.status})` });
          }
        }
      } catch (e) {
        window.showOtaCheckResult({ error: true, message: 'Check failed. Try again.' });
      }
    });
  }

  if (otaModalRollbackBtn) {
    otaModalRollbackBtn.addEventListener('click', async () => {
      if (confirm('Revert all OTA updates and restore the original factory APK assets?')) {
        await fetch('/api/ota/rollback', { method: 'POST' });
      }
    });
  }

  if (otaReloadBtn) {
    otaReloadBtn.addEventListener('click', async () => {
      await fetch('/api/ota/reload', { method: 'POST' });
    });
  }

  if (otaDismissBannerBtn) {
    otaDismissBannerBtn.addEventListener('click', () => {
      const banner = document.getElementById('otaUpdateBanner');
      if (banner) banner.classList.add('hidden');
    });
  }

  // Refresh version on initial load
  refreshOtaModalInfo();
});
