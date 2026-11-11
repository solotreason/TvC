/**
 * Standalone On-Device Android Engine & Bridge for Time Card & Time Off Tracker.
 * Runs 100% on the Android phone without needing a PC or external server:
 * - On-device persistent database (Android local file store + localStorage)
 * - In-browser DOMParser for Time Card Viewer & Time Off Viewer HTML
 * - Hundredths-of-an-hour decimal punch converter & late flag detector
 * - Strict Punch Modification Auditor (Start Time, End Time, Meal/Unpaid minute diffs)
 * - Seamless fetch() interceptor for /api/* endpoints when running on Android
 */
(function () {
  const STORAGE_KEY = 'timecard_ondevice_db_v4';
  let cachedDb = null;

  const isStandaloneAndroid = () => {
    return typeof window.AndroidNative !== 'undefined' || window.location.protocol === 'file:';
  };

  // Sync progress state for /api/status
  const syncState = {
    sync_in_progress: false,
    percent: 0,
    stage: 'Idle',
    message: '',
    requires_login: false,
    new_timecards: [],
    latest_log: null
  };

  function getEmptyDatabase() {
    return {
      weeks: [],
      week_details: {},
      timeoff: { profile: { emp_id: '', emp_name: '' }, entitlements: [] },
      audit_logs: [],
      corrections: [],
      latest_log: null
    };
  }

  function loadDatabase() {
    if (cachedDb) return cachedDb;
    try {
      // If running on Android and user is not logged in, ensure a clean empty state
      if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.isLoggedIn && !window.AndroidNative.isLoggedIn()) {
        cachedDb = getEmptyDatabase();
        return cachedDb;
      }
      let raw = null;
      if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.loadStateJson) {
        raw = window.AndroidNative.loadStateJson();
      }
      if (!raw) {
        raw = localStorage.getItem(STORAGE_KEY);
      }
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && Array.isArray(parsed.weeks)) {
          cachedDb = parsed;
          return cachedDb;
        }
      }
    } catch (e) {
      console.warn('Failed to read on-device store, using empty store:', e);
    }

    const initialDb = getEmptyDatabase();
    saveDatabase(initialDb);
    return initialDb;
  }

  function saveDatabase(dbObj) {
    cachedDb = dbObj;
    try {
      const serialized = JSON.stringify(dbObj);
      localStorage.setItem(STORAGE_KEY, serialized);
      if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.saveStateJson) {
        window.AndroidNative.saveStateJson(serialized);
      }
    } catch (e) {
      console.error('Failed to save on-device store:', e);
    }
  }

  function loadPermanentArchiveVault() {
    try {
      let raw = null;
      if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.loadArchiveJson) {
        raw = window.AndroidNative.loadArchiveJson();
      }
      if (!raw) {
        raw = localStorage.getItem('timecard_archive_vault_permanent');
      }
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && Array.isArray(parsed.archived_weeks)) {
          return parsed;
        }
      }
    } catch (e) {}
    return { archived_weeks: [], week_details: {} };
  }

  function parseFloatClean(val, def = 0.0) {
    if (val === null || val === undefined) return def;
    if (typeof val === 'number') return isNaN(val) ? def : val;
    const cleaned = String(val).replace(/[^\d.-]/g, '');
    const num = parseFloat(cleaned);
    return isNaN(num) ? def : num;
  }

  // Converts hundredths decimal time ("9.28", "9.35*") -> { val, clock12h, isLate }
  function parseDecimalTime(timeStr) {
    if (!timeStr) return { val: null, clock12h: null, isLate: false };
    const clean = String(timeStr).trim();
    if (!clean || ['N/A', '--', 'null'].includes(clean)) {
      return { val: null, clock12h: null, isLate: false };
    }
    const isLate = clean.includes('*');
    const numericStr = clean.replace(/\*/g, '').trim();
    const val = parseFloat(numericStr);
    if (isNaN(val)) return { val: null, clock12h: null, isLate: false };

    const totalMinutes = Math.round(val * 60);
    const hours24 = Math.floor(totalMinutes / 60) % 24;
    const minutes = totalMinutes % 60;
    const period = hours24 < 12 ? 'AM' : 'PM';
    let displayHour = hours24 % 12;
    if (displayHour === 0) displayHour = 12;

    const clock12h = `${displayHour}:${String(minutes).padStart(2, '0')} ${period}`;
    return { val, clock12h, isLate };
  }

  // Port of backend/parser.py: Parses Time Card Viewer HTML
  function parseTimecardHtml(htmlContent, selectedWeekEnd = null) {
    const parser = new DOMParser();
    const doc = parser.parseFromString(htmlContent, 'text/html');

    const availableWeeks = [];
    const selectEl = doc.querySelector('.timecard-date-dropdown select');
    let activeWeek = selectedWeekEnd;

    if (selectEl) {
      const options = selectEl.querySelectorAll('option');
      options.forEach(opt => {
        const val = (opt.getAttribute('value') || '').trim();
        const text = (opt.textContent || '').trim();
        const status = text.includes('Pending') ? 'Pending' : 'Final';
        if (val) {
          availableWeeks.push({
            week_end_date: val,
            label: text,
            status: status,
            is_selected: opt.hasAttribute('selected') || (activeWeek === val)
          });
        }
      });
      if (!activeWeek && options.length > 0) {
        activeWeek = (options[0].getAttribute('value') || '').trim();
      }
    }

    if (!activeWeek) activeWeek = 'unknown';

    const grossEl = doc.querySelector('.weekGrossPay');
    const hoursEl = doc.querySelector('.weekTotalHours');
    const updatedEl = doc.querySelector('.timecard-wrapper__lastUpdated span');

    const grossPay = grossEl ? parseFloatClean(grossEl.textContent) : 0.0;
    const totalHours = hoursEl ? parseFloatClean(hoursEl.textContent) : 0.0;
    const lastUpdated = updatedEl ? updatedEl.textContent.trim() : '';

    let weekStatus = 'Pending';
    for (const w of availableWeeks) {
      if (w.week_end_date === activeWeek) {
        weekStatus = w.status;
        break;
      }
    }

    const weekSummary = {
      week_end_date: activeWeek,
      status: weekStatus,
      gross_pay: grossPay,
      total_hours: totalHours,
      last_updated_source: lastUpdated
    };

    const shifts = [];
    const rows = doc.querySelectorAll('.timecard-list-view table tbody tr.timecard-card');
    rows.forEach(tr => {
      const dateStr = (tr.getAttribute('data-date') || '').trim();
      const cells = tr.querySelectorAll('td');
      if (cells.length < 8) return;

      const rawDateText = cells[0].textContent.trim();
      const dayMatch = rawDateText.match(/^([A-Za-z]{3})/);
      const dayName = dayMatch ? dayMatch[1] : '';

      const payCode = cells[1].textContent.trim();
      const rawStart = cells[2].textContent.trim();
      const rawEnd = cells[3].textContent.trim();
      const rawMeal = cells[4].textContent.trim();
      const rawRate = cells[5].textContent.trim();
      const rawHours = cells[6].textContent.trim();
      const rawGross = cells[7].textContent.trim();

      const startParsed = parseDecimalTime(rawStart);
      const endParsed = parseDecimalTime(rawEnd);
      const mealParsed = parseDecimalTime(rawMeal);

      const shiftItem = {
        week_end_date: activeWeek,
        date: dateStr,
        day_name: dayName,
        pay_code: payCode,
        start_time_dec: startParsed.val,
        start_time_clock: startParsed.clock12h,
        is_late: startParsed.isLate ? 1 : 0,
        end_time_dec: endParsed.val,
        end_time_clock: endParsed.clock12h,
        meal_unpaid_hours: mealParsed.val !== null ? mealParsed.val : 0.0,
        pay_rate: parseFloatClean(rawRate),
        total_hours: parseFloatClean(rawHours),
        gross_pay: parseFloatClean(rawGross),
        details: []
      };

      const accTr = doc.querySelector(`tr.acc${CSS.escape(dateStr)}`) || doc.querySelector(`tr[class*="acc${dateStr}"]`);
      if (accTr) {
        const detailTbody = accTr.querySelector('tbody');
        if (detailTbody) {
          detailTbody.querySelectorAll('tr').forEach(detRow => {
            if (detRow.classList.contains('viewCorrectionRow') || detRow.classList.contains('dailyTotalRow')) {
              return;
            }
            const dCells = detRow.querySelectorAll('td');
            if (dCells.length >= 14) {
              const rateVal = parseFloatClean(dCells[5].textContent);
              if (shiftItem.pay_rate === 0.0 && rateVal > 0) {
                shiftItem.pay_rate = rateVal;
              }
              shiftItem.details.push({
                trans_type: dCells[0].textContent.trim(),
                op_type: dCells[1].textContent.trim(),
                center: dCells[2].textContent.trim(),
                sort: dCells[3].textContent.trim(),
                activity: dCells[4].textContent.trim(),
                rate: rateVal,
                st_hrs: parseFloatClean(dCells[6].textContent),
                st_amt: parseFloatClean(dCells[7].textContent),
                ot_hrs: parseFloatClean(dCells[8].textContent),
                ot_amt: parseFloatClean(dCells[9].textContent),
                dt_hrs: parseFloatClean(dCells[10].textContent),
                dt_amt: parseFloatClean(dCells[11].textContent),
                eq_type: dCells[12].textContent.trim(),
                griev_num: dCells.length > 13 ? dCells[13].textContent.trim() : '',
                flat_amt: dCells.length > 14 ? parseFloatClean(dCells[14].textContent) : 0.0
              });
            }
          });
        }
      }

      shifts.push(shiftItem);
    });

    const corrections = [];
    const corrTbody = doc.querySelector('.viewcorrections-data__data');
    if (corrTbody) {
      corrTbody.querySelectorAll('tr').forEach(cRow => {
        const cCells = cRow.querySelectorAll('td');
        if (cCells.length >= 8) {
          corrections.push({
            timestamp: cCells[0].textContent.trim(),
            card_type: cCells[1].textContent.trim(),
            action: cCells[2].textContent.trim(),
            item: cCells[3].textContent.trim(),
            old_val: cCells[4].textContent.trim(),
            new_val: cCells[5].textContent.trim(),
            edited_by: cCells[6].textContent.trim(),
            reason_code: cCells[7].textContent.trim()
          });
        }
      });
    }

    return {
      available_weeks: availableWeeks,
      week_summary: weekSummary,
      shifts: shifts,
      corrections: corrections
    };
  }

  // Port of backend/timeoff_parser.py: Parses Time Off Viewer HTML
  function parseTimeoffHtml(htmlContent) {
    const parser = new DOMParser();
    const doc = parser.parseFromString(htmlContent, 'text/html');

    const empIdEl = doc.querySelector('.empId');
    const empNameEl = doc.querySelector('.empName');

    const profile = {
      emp_id: empIdEl ? empIdEl.textContent.trim() : '',
      emp_name: empNameEl ? empNameEl.textContent.trim() : ''
    };

    const entitlements = [];
    const rows = doc.querySelectorAll('.timeoff-list-view table tbody tr');
    rows.forEach(tr => {
      const timeoffType = (tr.getAttribute('data-timeoff-type') || '').trim();
      const cells = tr.querySelectorAll('td');
      if (cells.length >= 4) {
        const title = cells[0].textContent.trim();
        const availStr = cells[1].textContent.trim();
        const timeBasis = cells[2].textContent.trim();
        const earnDate = cells[3].textContent.trim();

        entitlements.push({
          timeoff_type: timeoffType || title.slice(0, 3).toUpperCase(),
          title: title,
          available_time: parseFloatClean(availStr),
          time_basis: timeBasis,
          next_earn_date: (earnDate && earnDate !== 'N/A') ? earnDate : null
        });
      }
    });

    return { profile, entitlements };
  }

  // Upserts parsed timecard data & runs Punch Modification Auditor (Start Time, End Time, Meal/Unpaid)
  function upsertParsedWeek(parsedData) {
    const db = loadDatabase();
    const summary = parsedData.week_summary;
    if (!summary || summary.week_end_date === 'unknown') {
      return parsedData.available_weeks || [];
    }

    const weekDate = summary.week_end_date;
    const nowStamp = new Date().toISOString().replace('T', ' ').slice(0, 19);

    // 1. Update weeks list
    const existingWeekIdx = db.weeks.findIndex(w => w.week_end_date === weekDate);
    const weekRecord = {
      week_end_date: weekDate,
      status: summary.status,
      gross_pay: summary.gross_pay,
      total_hours: summary.total_hours,
      last_updated_source: summary.last_updated_source,
      synced_at: nowStamp
    };
    if (existingWeekIdx >= 0) {
      db.weeks[existingWeekIdx] = weekRecord;
    } else {
      db.weeks.push(weekRecord);
    }
    db.weeks.sort((a, b) => b.week_end_date.localeCompare(a.week_end_date));

    // 2. Audit punch modifications & update shifts
    const prevWeekDetail = db.week_details[weekDate] || { shifts: [] };
    const weekAlreadyHadShifts = Array.isArray(prevWeekDetail.shifts) && prevWeekDetail.shifts.length > 0;
    const prevShiftMap = new Map();
    (prevWeekDetail.shifts || []).forEach(s => prevShiftMap.set(s.date, s));

    const updatedShifts = parsedData.shifts.map(newShift => {
      const existing = prevShiftMap.get(newShift.date);
      const shiftAudits = existing && Array.isArray(existing.audit_history) ? [...existing.audit_history] : [];

      const wasEmpty = !existing || (
        (existing.start_time_dec === null || existing.start_time_dec === undefined) &&
        (existing.total_hours || 0) <= 0.005
      );
      const hasTimecardNow = (
        (newShift.start_time_dec !== null && newShift.start_time_dec !== undefined) ||
        (newShift.total_hours || 0) > 0.005
      );
      if (weekAlreadyHadShifts && wasEmpty && hasTimecardNow) {
        if (!Array.isArray(syncState.new_timecards)) syncState.new_timecards = [];
        syncState.new_timecards.push({
          week_end_date: weekDate,
          date: newShift.date,
          day_name: newShift.day_name,
          start_time_clock: newShift.start_time_clock,
          end_time_clock: newShift.end_time_clock,
          total_hours: newShift.total_hours || 0.0,
          gross_pay: newShift.gross_pay || 0.0
        });
      }

      if (existing) {
        // Check 1: Start Time
        const oldStart = existing.start_time_dec;
        const newStart = newShift.start_time_dec;
        if (oldStart !== null && oldStart !== undefined && newStart !== null && newStart !== undefined) {
          if (Math.abs(oldStart - newStart) > 0.005) {
            const oldClock = existing.start_time_clock || oldStart.toFixed(2);
            const newClock = newShift.start_time_clock || newStart.toFixed(2);
            const diffMins = Math.round((newStart - oldStart) * 60);
            const entry = {
              id: Date.now() + Math.floor(Math.random() * 1000),
              week_end_date: weekDate,
              date: newShift.date,
              field_name: 'Start Time',
              old_value: `${oldClock} (${oldStart.toFixed(2)})`,
              new_value: `${newClock} (${newStart.toFixed(2)})`,
              diff_num: diffMins,
              detected_at: nowStamp
            };
            db.audit_logs.unshift(entry);
            shiftAudits.unshift(entry);
          }
        }

        // Check 2: End Time
        const oldEnd = existing.end_time_dec;
        const newEnd = newShift.end_time_dec;
        if (oldEnd !== null && oldEnd !== undefined && newEnd !== null && newEnd !== undefined) {
          if (Math.abs(oldEnd - newEnd) > 0.005) {
            const oldClock = existing.end_time_clock || oldEnd.toFixed(2);
            const newClock = newShift.end_time_clock || newEnd.toFixed(2);
            const diffMins = Math.round((newEnd - oldEnd) * 60);
            const entry = {
              id: Date.now() + Math.floor(Math.random() * 1000),
              week_end_date: weekDate,
              date: newShift.date,
              field_name: 'End Time',
              old_value: `${oldClock} (${oldEnd.toFixed(2)})`,
              new_value: `${newClock} (${newEnd.toFixed(2)})`,
              diff_num: diffMins,
              detected_at: nowStamp
            };
            db.audit_logs.unshift(entry);
            shiftAudits.unshift(entry);
          }
        }

        // Check 3: Meal/Unpaid (only when both old and new records are active punch cards)
        const oldMeal = existing.meal_unpaid_hours || 0.0;
        const newMeal = newShift.meal_unpaid_hours || 0.0;
        if (oldStart !== null && oldStart !== undefined && newStart !== null && newStart !== undefined) {
          if (Math.abs(oldMeal - newMeal) > 0.005) {
            const diffMins = Math.round((newMeal - oldMeal) * 60);
            const entry = {
              id: Date.now() + Math.floor(Math.random() * 1000),
              week_end_date: weekDate,
              date: newShift.date,
              field_name: 'Meal/Unpaid',
              old_value: `${oldMeal.toFixed(2)}h (${Math.round(oldMeal * 60)}m)`,
              new_value: `${newMeal.toFixed(2)}h (${Math.round(newMeal * 60)}m)`,
              diff_num: diffMins,
              detected_at: nowStamp
            };
            db.audit_logs.unshift(entry);
            shiftAudits.unshift(entry);
          }
        }
      }

      newShift.audit_history = shiftAudits;
      return newShift;
    });

    db.week_details[weekDate] = {
      ...weekRecord,
      shifts: updatedShifts
    };

    // 3. Deduplicate and store corrections
    const corrKeys = new Set((db.corrections || []).map(c => `${c.timestamp}|${c.item}|${c.old_val}|${c.new_val}`));
    (parsedData.corrections || []).forEach(c => {
      const k = `${c.timestamp}|${c.item}|${c.old_val}|${c.new_val}`;
      if (!corrKeys.has(k)) {
        corrKeys.add(k);
        db.corrections.unshift(c);
      }
    });

    saveDatabase(db);

    // Persist monthly archived weeks (prior months, finalized weeks, and weeks > 8)
    const newestMonth = db.weeks.length > 0 ? (db.weeks[0].week_end_date || '').slice(0, 7) : '';
    const archivedWeeks = db.weeks.filter((w, idx) =>
      idx >= 8 ||
      (w.week_end_date || '').slice(0, 7) < newestMonth ||
      (w.status !== 'Pending' && db.weeks.length > 1)
    );
    if (archivedWeeks.length > 0 || (db.audit_logs && db.audit_logs.length > 0) || (db.corrections && db.corrections.length > 0)) {
      try {
        const vault = loadPermanentArchiveVault();
        // Merge week details
        const weekDetails = Object.assign({}, vault.week_details || {});
        archivedWeeks.forEach(w => {
          if (db.week_details && db.week_details[w.week_end_date]) {
            weekDetails[w.week_end_date] = db.week_details[w.week_end_date];
          }
        });

        // Merge audit logs
        const auditMap = new Map();
        (vault.audit_logs || []).forEach(a => auditMap.set(`${a.week_end_date}|${a.date}|${a.field_name}|${a.old_value}|${a.new_value}`, a));
        (db.audit_logs || []).forEach(a => auditMap.set(`${a.week_end_date}|${a.date}|${a.field_name}|${a.old_value}|${a.new_value}`, a));

        // Merge corrections
        const corrMap = new Map();
        (vault.corrections || []).forEach(c => corrMap.set(`${c.timestamp}|${c.item}|${c.old_val}|${c.new_val}`, c));
        (db.corrections || []).forEach(c => corrMap.set(`${c.timestamp}|${c.item}|${c.old_val}|${c.new_val}`, c));

        // Merge archived weeks
        const weeksMap = new Map();
        (vault.archived_weeks || []).forEach(w => weeksMap.set(w.week_end_date, w));
        archivedWeeks.forEach(w => weeksMap.set(w.week_end_date, w));

        const archivePayload = {
          emp_id: (db.timeoff && db.timeoff.profile && db.timeoff.profile.emp_id) || vault.emp_id || '',
          archived_weeks: Array.from(weeksMap.values()).sort((a, b) => b.week_end_date.localeCompare(a.week_end_date)),
          week_details: weekDetails,
          audit_logs: Array.from(auditMap.values()),
          corrections: Array.from(corrMap.values())
        };
        const serializedArchive = JSON.stringify(archivePayload);
        // Save to native Android vault file AND localStorage archive key for Apple / Web PWA persistence
        if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.saveArchiveJson) {
          window.AndroidNative.saveArchiveJson(serializedArchive);
        }
        localStorage.setItem('timecard_archive_vault_permanent', serializedArchive);

        // Auto-sync with MongoDB cloud vault endpoint
        try {
          fetch('/api/archive/sync', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: serializedArchive
          }).catch(() => {});
        } catch (e) {}
      } catch (e) {}
    }

    return parsedData.available_weeks || [];
  }

  function upsertParsedTimeoff(parsedTimeoff) {
    const db = loadDatabase();
    if (parsedTimeoff.profile && parsedTimeoff.profile.emp_id) {
      db.timeoff.profile = parsedTimeoff.profile;
    }
    if (parsedTimeoff.entitlements && parsedTimeoff.entitlements.length > 0) {
      db.timeoff.entitlements = parsedTimeoff.entitlements;
    }
    saveDatabase(db);
  }

  let lastLoadedStoreMtime = (
    typeof window.AndroidNative !== 'undefined' &&
    typeof window.AndroidNative.getStoreLastModified === 'function'
  ) ? Number(window.AndroidNative.getStoreLastModified()) || 0 : 0;

  function syncStoreMtimeFromNative() {
    try {
      if (typeof window.AndroidNative !== 'undefined' && typeof window.AndroidNative.getStoreLastModified === 'function') {
        const m = Number(window.AndroidNative.getStoreLastModified()) || 0;
        if (m > 0) lastLoadedStoreMtime = m;
      }
    } catch (e) {}
  }

  // Expose callbacks for Android MainActivity WebView scraper & closed-app TimecardSyncJobService
  window.TimecardBridge = {
    ingestWeekHtml: function (rawHtml, selectedWeekEnd) {
      const parsed = parseTimecardHtml(rawHtml, selectedWeekEnd || null);
      const avail = upsertParsedWeek(parsed);
      syncStoreMtimeFromNative();
      return JSON.stringify(avail.map(w => w.week_end_date));
    },
    ingestTimeoffHtml: function (rawHtml) {
      const parsed = parseTimeoffHtml(rawHtml);
      upsertParsedTimeoff(parsed);
      syncStoreMtimeFromNative();
      return 'OK';
    },
    reloadFromDiskIfUpdated: function () {
      if (typeof window.AndroidNative === 'undefined' || typeof window.AndroidNative.loadStateJson !== 'function') {
        return false;
      }
      const diskMtime = typeof window.AndroidNative.getStoreLastModified === 'function'
        ? Number(window.AndroidNative.getStoreLastModified()) || 0
        : 0;
      if (diskMtime > 0 && diskMtime <= lastLoadedStoreMtime && cachedDb !== null) {
        return false;
      }
      const raw = window.AndroidNative.loadStateJson();
      if (!raw || !raw.trim()) return false;
      try {
        const parsed = JSON.parse(raw);
        if (parsed && typeof parsed === 'object') {
          cachedDb = parsed;
          try { localStorage.setItem(STORAGE_KEY, raw); } catch (e) {}
          if (diskMtime > 0) lastLoadedStoreMtime = diskMtime;
          return true;
        }
      } catch (e) {}
      return false;
    },
    getNewTimecardsJson: function () {
      return JSON.stringify(syncState.new_timecards || []);
    },
    recordBackgroundSyncComplete: function (message, statusLabel) {
      const db = loadDatabase();
      const nowStamp = new Date().toISOString().replace('T', ' ').slice(0, 19);
      db.latest_log = {
        status: statusLabel || 'Background Sync',
        message: message || 'Checked for new timecards in background.',
        timestamp: nowStamp
      };
      saveDatabase(db);
      syncState.latest_log = db.latest_log;
      syncStoreMtimeFromNative();
      return 'OK';
    },
    updateProgress: function (percent, stage, message, requiresLogin) {
      syncState.sync_in_progress = true;
      syncState.percent = percent;
      syncState.stage = stage;
      syncState.message = message;
      syncState.requires_login = !!requiresLogin;
    },
    finishSync: function (success, message, weeksSynced, requiresLogin) {
      syncState.sync_in_progress = false;
      syncState.percent = success ? 100 : 0;
      syncState.stage = success ? 'Sync Complete!' : (requiresLogin ? 'Login Required' : 'Sync Ended');
      syncState.message = message;
      syncState.requires_login = !!requiresLogin;
      syncState.latest_log = {
        status: success ? 'SUCCESS' : 'FAILED',
        message: message,
        weeks_synced: weeksSynced || 0
      };
      const db = loadDatabase();
      db.latest_log = syncState.latest_log;
      saveDatabase(db);
    },
    onOtaUpdateReady: function (details) {
      if (typeof window.showOtaUpdateReadyBanner === 'function') {
        window.showOtaUpdateReadyBanner(details);
      }
    },
    onOtaCheckResult: function (result) {
      if (typeof window.showOtaCheckResult === 'function') {
        window.showOtaCheckResult(result);
      }
    }
  };

  // Intercept /api/* calls when running as a standalone Android app
  const originalFetch = window.fetch.bind(window);
  window.fetch = async function (input, init) {
    const url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
    if (!isStandaloneAndroid() || !url.includes('/api/')) {
      return originalFetch(input, init);
    }

    const db = loadDatabase();
    const makeJsonResponse = (obj, status = 200) => {
      return new Response(JSON.stringify(obj), {
        status: status,
        headers: { 'Content-Type': 'application/json' }
      });
    };

    if (url.endsWith('/api/ota/status')) {
      if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.getOtaStatusJson) {
        try {
          return makeJsonResponse(JSON.parse(window.AndroidNative.getOtaStatusJson()));
        } catch (e) {}
      }
      return makeJsonResponse({ version: '2.4.2', build: 2026110909, github_repo: 'solotreason/TvC' });
    }

    if (url.endsWith('/api/ota/check')) {
      if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.checkForUpdates) {
        window.AndroidNative.checkForUpdates(true);
        return makeJsonResponse({ message: 'Update check initiated' });
      }
      return makeJsonResponse({ message: 'Running on web' });
    }

    if (url.endsWith('/api/ota/reload')) {
      if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.reloadApp) {
        window.AndroidNative.reloadApp();
      } else {
        window.location.reload();
      }
      return makeJsonResponse({ message: 'Reloading...' });
    }

    if (url.endsWith('/api/ota/rollback')) {
      if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.resetOtaToFactory) {
        window.AndroidNative.resetOtaToFactory();
      }
      return makeJsonResponse({ message: 'Rollback initiated' });
    }

    const getArchivedWeeksFromList = (list) => {
      const newestMonth = list.length > 0 ? (list[0].week_end_date || '').slice(0, 7) : '';
      const currentArchived = list.filter((w, idx) =>
        idx >= 8 ||
        (w.week_end_date || '').slice(0, 7) < newestMonth ||
        (w.status !== 'Pending' && list.length > 1)
      );

      // Cumulatively merge with permanent archive vault so past months are never lost
      const vault = loadPermanentArchiveVault();
      const map = new Map();
      (vault.archived_weeks || []).forEach(w => map.set(w.week_end_date, w));
      currentArchived.forEach(w => map.set(w.week_end_date, w));
      return Array.from(map.values()).sort((a, b) => b.week_end_date.localeCompare(a.week_end_date));
    };

    if (url.endsWith('/api/weeks')) {
      const sortedWeeks = db.weeks || [];
      return makeJsonResponse({
        weeks: sortedWeeks.slice(0, 8),
        archived_weeks: getArchivedWeeksFromList(sortedWeeks)
      });
    }

    if (url.endsWith('/api/archive/sync')) {
      let body = { archived_weeks: [], week_details: {} };
      try {
        if (init && init.body) body = Object.assign(body, JSON.parse(init.body));
      } catch (e) {}

      const vault = loadPermanentArchiveVault();
      const map = new Map();
      (vault.archived_weeks || []).forEach(w => map.set(w.week_end_date, w));
      (body.archived_weeks || []).forEach(w => {
        if (w && w.week_end_date) map.set(w.week_end_date, w);
      });

      const mergedWeeks = Array.from(map.values()).sort((a, b) => b.week_end_date.localeCompare(a.week_end_date));
      const mergedDetails = Object.assign({}, vault.week_details || {}, body.week_details || {});

      // Merge audit logs
      const auditMap = new Map();
      (vault.audit_logs || []).forEach(a => auditMap.set(`${a.week_end_date}|${a.date}|${a.field_name}|${a.old_value}|${a.new_value}`, a));
      (body.audit_logs || []).forEach(a => auditMap.set(`${a.week_end_date}|${a.date}|${a.field_name}|${a.old_value}|${a.new_value}`, a));

      // Merge corrections
      const corrMap = new Map();
      (vault.corrections || []).forEach(c => corrMap.set(`${c.timestamp}|${c.item}|${c.old_val}|${c.new_val}`, c));
      (body.corrections || []).forEach(c => corrMap.set(`${c.timestamp}|${c.item}|${c.old_val}|${c.new_val}`, c));

      const updatedVault = {
        emp_id: body.emp_id || vault.emp_id || '',
        archived_weeks: mergedWeeks,
        week_details: mergedDetails,
        audit_logs: Array.from(auditMap.values()),
        corrections: Array.from(corrMap.values())
      };

      const serialized = JSON.stringify(updatedVault);
      localStorage.setItem('timecard_archive_vault_permanent', serialized);
      if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.saveArchiveJson) {
        window.AndroidNative.saveArchiveJson(serialized);
      }

      return makeJsonResponse({
        success: true,
        total_archived_weeks: mergedWeeks.length,
        archived_weeks: mergedWeeks
      });
    }

    if (url.endsWith('/api/archive')) {
      const sortedWeeks = db.weeks || [];
      return makeJsonResponse({
        archived_weeks: getArchivedWeeksFromList(sortedWeeks),
        active_week_limit: 8
      });
    }

    const weekMatch = url.match(/\/api\/weeks\/([^/?#]+)/);
    if (weekMatch) {
      const weekDate = decodeURIComponent(weekMatch[1]);
      let detail = db.week_details && db.week_details[weekDate];
      if (!detail) {
        // Fallback to permanent archive vault
        const vault = loadPermanentArchiveVault();
        detail = vault.week_details && vault.week_details[weekDate];
      }
      if (!detail) {
        return makeJsonResponse({ detail: 'Week not found' }, 404);
      }

      let stHrs = 0, otHrs = 0, dtHrs = 0, stAmt = 0, otAmt = 0, dtAmt = 0, lateCount = 0;
      (detail.shifts || []).forEach(s => {
        if (s.is_late) lateCount += 1;
        (s.details || []).forEach(det => {
          stHrs += det.st_hrs || 0;
          otHrs += det.ot_hrs || 0;
          dtHrs += det.dt_hrs || 0;
          stAmt += det.st_amt || 0;
          otAmt += det.ot_amt || 0;
          dtAmt += det.dt_amt || 0;
        });
      });

      detail.totals = {
        st_hrs: Number(stHrs.toFixed(2)),
        ot_hrs: Number(otHrs.toFixed(2)),
        dt_hrs: Number(dtHrs.toFixed(2)),
        st_amt: Number(stAmt.toFixed(2)),
        ot_amt: Number(otAmt.toFixed(2)),
        dt_amt: Number(dtAmt.toFixed(2)),
        late_count: lateCount
      };
      return makeJsonResponse(detail);
    }

    if (url.endsWith('/api/timeoff')) {
      return makeJsonResponse(db.timeoff || { profile: null, entitlements: [] });
    }

    if (url.endsWith('/api/audit-history')) {
      const vault = loadPermanentArchiveVault();
      const auditMap = new Map();
      (db.audit_logs || []).forEach(a => auditMap.set(`${a.week_end_date}|${a.date}|${a.field_name}|${a.old_value}|${a.new_value}`, a));
      (vault.audit_logs || []).forEach(a => {
        const k = `${a.week_end_date}|${a.date}|${a.field_name}|${a.old_value}|${a.new_value}`;
        if (!auditMap.has(k)) auditMap.set(k, a);
      });
      const combinedAudits = Array.from(auditMap.values()).sort((a, b) => String(b.detected_at || b.date || '').localeCompare(String(a.detected_at || a.date || '')));
      return makeJsonResponse({ audit_logs: combinedAudits });
    }

    if (url.endsWith('/api/corrections')) {
      const vault = loadPermanentArchiveVault();
      const corrMap = new Map();
      (db.corrections || []).forEach(c => corrMap.set(`${c.timestamp}|${c.item}|${c.old_val}|${c.new_val}`, c));
      (vault.corrections || []).forEach(c => {
        const k = `${c.timestamp}|${c.item}|${c.old_val}|${c.new_val}`;
        if (!corrMap.has(k)) corrMap.set(k, c);
      });
      const combinedCorrs = Array.from(corrMap.values()).sort((a, b) => String(b.timestamp || '').localeCompare(String(a.timestamp || '')));
      return makeJsonResponse({ corrections: combinedCorrs });
    }

    if (url.endsWith('/api/auth/status')) {
      let loggedIn = false;
      if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.isLoggedIn) {
        loggedIn = !!window.AndroidNative.isLoggedIn();
      }
      return makeJsonResponse({
        logged_in: loggedIn,
        message: loggedIn ? 'Logged In' : 'Not logged in'
      });
    }

    if (url.endsWith('/api/auth/login')) {
      syncState.sync_in_progress = true;
      syncState.percent = 15;
      syncState.stage = 'Awaiting Portal Login';
      syncState.message = 'Complete your login & 2FA in the login sheet...';
      if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.openLoginSheet) {
        window.AndroidNative.openLoginSheet();
      }
      return makeJsonResponse({ message: 'Login sheet opened' });
    }

    if (url.endsWith('/api/auth/logout')) {
      saveDatabase(getEmptyDatabase());
      if (typeof window.AndroidNative !== 'undefined' && window.AndroidNative.logoutSession) {
        window.AndroidNative.logoutSession();
      }
      syncState.sync_in_progress = false;
      syncState.percent = 0;
      syncState.stage = 'Logged Out';
      syncState.message = 'Logged out of portal session.';
      syncState.requires_login = false;
      return makeJsonResponse({ success: true, logged_in: false, message: 'Successfully logged out.' });
    }

    if (url.endsWith('/api/sync')) {
      let body = { max_weeks: 0, headless: true, sync_timeoff: true };
      try {
        if (init && init.body) body = Object.assign(body, JSON.parse(init.body));
      } catch (e) {}

      const maxWeeks = Number(body.max_weeks) || 0;
      const syncTimeoff = body.sync_timeoff !== false;

      syncState.sync_in_progress = true;
      syncState.percent = 5;
      syncState.new_timecards = [];
      syncState.stage = maxWeeks === 1 ? 'Refreshing Current Week' : 'Starting';
      syncState.message = maxWeeks === 1
        ? 'Refreshing current pending week only...'
        : 'Initializing on-device background sync (all 8+ weeks)...';
      syncState.requires_login = false;

      if (typeof window.AndroidNative !== 'undefined') {
        if (body.headless === false && window.AndroidNative.openLoginSheet) {
          window.AndroidNative.openLoginSheet();
        } else if (window.AndroidNative.startBackgroundSyncWithOptions) {
          window.AndroidNative.startBackgroundSyncWithOptions(maxWeeks, syncTimeoff);
        } else if (window.AndroidNative.startBackgroundSync) {
          window.AndroidNative.startBackgroundSync();
        }
      }
      return makeJsonResponse({
        message: 'Android background sync started',
        max_weeks: maxWeeks,
        sync_timeoff: syncTimeoff
      });
    }

    if (url.endsWith('/api/status')) {
      return makeJsonResponse({
        sync_in_progress: syncState.sync_in_progress,
        percent: syncState.percent,
        stage: syncState.stage,
        message: syncState.message,
        requires_login: syncState.requires_login,
        new_timecards: syncState.new_timecards || [],
        latest_log: syncState.latest_log || db.latest_log
      });
    }

    return originalFetch(input, init);
  };
})();
