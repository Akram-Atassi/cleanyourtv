/**
 * TV list: fill the sheet's "Today" tab from your Google Calendar, free, with no assistant involved.
 *
 * Install once: open the sheet, Extensions > Apps Script, replace the editor's contents with this file, save, pick
 * "setup" in the function menu and press Run. Google asks you to allow access to your calendars and this sheet;
 * allow it. setup fills the tab now and every 15 minutes from then on, on Google's servers: your computer can be off.
 * To stop: run "removeTriggers", or delete the trigger under Triggers (the clock icon).
 *
 * The sheet is readable by anyone with its link, so anything that looks private is never written there: an event
 * marked Private in Calendar, or one whose title or calendar matches PRIVATE_WORDS, shows as "Private appointment"
 * (or not at all, with HIDE_PRIVATE). Only the time and the title are written; never descriptions, places or guests.
 */

// Words that make an event private on the TV (matched in the title and the calendar's name, any case).
var PRIVATE_WORDS = ['doctor', 'dentist', 'therapy', 'therapist', 'clinic', 'health', 'medical', 'appointment',
                     'pharmacy', 'counsel', 'hospital', 'vaccine', 'shot', 'physical', 'psych'];
var HIDE_PRIVATE = false;           // true leaves private events off the TV entirely
// Events whose title STARTS with one of these words are left off. Only the first word counts, so "Lab 3" matches
// and "Labor Day" does not. Empty: classes are shown (and leave the TV when they end).
var SKIP_FIRST_WORDS = [];
// Calendar names (or parts, any case) to leave out entirely. The Lakers get their own spot on the TV, with the
// channel, so their calendar would only repeat it.
var SKIP_CALENDARS = ['Holidays', 'Week Numbers', 'Office hours', 'Lakers'];
// Calendars that hold classes and also one-minute homework reminders (at 11:59 PM): from these, only events lasting
// at least MIN_CLASS_MINUTES are written, which keeps the classes and drops the reminders.
var CLASS_CALENDARS = ['GW classes'];
var MIN_CLASS_MINUTES = 20;
var TAB = 'Today';
var MAX_EVENTS = 8;                 // the TV shows at most eight

function setup() {
  removeTriggers();
  ScriptApp.newTrigger('fillToday').timeBased().everyMinutes(15).create();
  fillToday();
}

function removeTriggers() {
  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'fillToday') ScriptApp.deleteTrigger(t);
  });
}

function fillToday() {
  var tz = Session.getScriptTimeZone();
  var start = new Date();
  start.setHours(0, 0, 0, 0);
  var end = new Date(start.getTime() + 24 * 3600 * 1000);

  var now = new Date();
  var rows = [];
  CalendarApp.getAllCalendars().forEach(function (cal) {
    var classes = matches(cal.getName(), CLASS_CALENDARS);
    if (cal.isHidden() || !cal.isSelected() || matches(cal.getName(), SKIP_CALENDARS)) return;
    cal.getEvents(start, end).forEach(function (ev) {
      if (declined(ev)) return;
      var title = ev.getTitle() || '(No title)';
      // Birthdays get "Birthday" as their time: the TV shows them in red under the date, not in the list.
      if (isBirthday(cal, ev, title)) {
        rows.push({ sort: -1, time: 'Birthday', ends: '',
          title: title.replace(/[’']s birthday$/i, '').replace(/^birthday:?\s*/i, '') });
        return;
      }
      if (startsWithWord(title, SKIP_FIRST_WORDS)) return;
      var allDay = ev.isAllDayEvent();
      var minutes = (ev.getEndTime().getTime() - ev.getStartTime().getTime()) / 60000;
      if (classes && !allDay && minutes < MIN_CLASS_MINUTES) return;   // a homework reminder, not a class
      if (!allDay && ev.getEndTime().getTime() <= now.getTime()) return;   // already over
      var isPrivate = ev.getVisibility() === CalendarApp.Visibility.PRIVATE
          || matches(title, PRIVATE_WORDS) || matches(cal.getName(), PRIVATE_WORDS);
      if (isPrivate && HIDE_PRIVATE) return;
      rows.push({
        sort: allDay ? 0 : ev.getStartTime().getTime(),
        time: allDay ? 'All day' : Utilities.formatDate(ev.getStartTime(), tz, 'h:mm a'),
        title: isPrivate ? 'Private appointment' : title,
        // When it ends, so the TV can take it off the list that minute.
        ends: allDay ? '' : Utilities.formatDate(ev.getEndTime(), tz, 'h:mm a')
      });
    });
  });
  rows.sort(function (a, b) { return a.sort - b.sort; });
  rows = rows.slice(0, MAX_EVENTS);

  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(TAB);
  if (!sheet) throw new Error('No tab named "' + TAB + '" in this sheet');
  var last = Math.max(sheet.getLastRow(), 2);
  sheet.getRange(2, 1, last - 1, 3).clearContent();
  sheet.getRange(1, 1, 1, 3).setValues([['Time', 'Event', 'Ends']]);
  if (rows.length) {
    var range = sheet.getRange(2, 1, rows.length, 3);
    // Plain text, so the TV reads "9:00 AM" and "All day" as written (see the add-on's README).
    range.setNumberFormat('@');
    range.setValues(rows.map(function (r) { return [r.time, r.title, r.ends]; }));
  }
}

/** A contact's birthday (Google's Birthdays calendar or event type), or an all-day event called "... birthday". */
function isBirthday(cal, ev, title) {
  if (/birthday/i.test(cal.getName())) return true;
  try {
    if (String(ev.getEventType()) === String(CalendarApp.EventType.BIRTHDAY)) return true;
  } catch (e) {
    // Older Apps Script runtimes have no event types.
  }
  return ev.isAllDayEvent() && /birthday/i.test(title);
}

/** True when you declined the event: it stays in Calendar but is not part of your day. */
function declined(ev) {
  try {
    return ev.getMyStatus() === CalendarApp.GuestStatus.NO;
  } catch (e) {
    return false;
  }
}

/** True when the title's first word is one of {@code words} ("Lab 3" yes, "Labor Day" no), any case. */
function startsWithWord(title, words) {
  var first = String(title).trim().split(/[^A-Za-z]+/)[0].toLowerCase();
  return words.some(function (w) { return first === String(w).toLowerCase(); });
}

function matches(text, words) {
  var t = String(text).toLowerCase();
  return words.some(function (w) { return t.indexOf(String(w).toLowerCase()) >= 0; });
}
