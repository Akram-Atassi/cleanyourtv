# TV list

A system screensaver (Android's "Daydream") and a Home card that show your to-do list and today's events from a
Google Sheet: a large clock and the date at the top, **Today** on the left (time and event) and **To-do** on the
right, with open tasks first and done ones struck through at the end. The sheet is read every time the screen opens
and every minute while it shows, so a change made from your phone, or by an assistant that can edit the sheet, is
on the TV the next time you look. The last good copy shows at once, and stays up while the network is down. Against
burn-in the whole picture drifts 6 dp round a small circle, one slow step every two minutes.

Any key wakes the TV back to where it was. On cleanyourtv's Home it is also a module card, **TV list**: OK shows the
list at once, and any key goes back to Home.

### One button between the list and your own screensaver

You can keep the TV's own screensaver (Google's Ambient mode, say) and put the list on a remote button instead. Map
the button to launch **TV list**: on Google TV, Settings > Remotes & Accessories > the star-button shortcut >
Favorite app > TV list (Button Mapper, or the launcher's `app_keys`, work too). Then the button flips between the two:

| On screen | Press the button |
|---|---|
| The screensaver, Home or an app | The list fades in, re-reading the sheet |
| The list | The list fades out and the TV's own screensaver starts |

Any other key leaves the list. Opened over the screensaver, the list wakes the TV out of it rather than opening
behind it. In this mode `setup.sh` is not needed for the screensaver, only to set the sheet; leave the TV's
screensaver as it is.

Package `com.gathin.tvlist`. You see it as **TV list** in the TV's screensaver settings.

## The sheet

One Google Sheet with two tabs, named exactly:

| Tab | Column A | Column B |
|---|---|---|
| `To-do` | Task | Done: a ticked checkbox (`TRUE`), or `yes`, `x`, `done`, `1` or `✓` |
| `Today` | Time, e.g. `9:00 AM` or `All day` (may be empty) | Event |

Row 1 is a header and is skipped. Empty rows are skipped. Format the Time column as plain text (Format > Number >
Plain text): Google's CSV export gives a column one type, so in a column of times a word like `All day` comes out
empty. The screen shows at most 12 tasks and 8 events, each cut
to one or two lines.

**Share it so the TV can read it:** in Google Sheets, Share > General access > **Anyone with the link**, role
**Viewer**. The TV reads the tabs as CSV from that link, with no account on the TV. Anyone who has the link can read
the list too, so keep anything private off it. A school or work Google account may not allow link sharing; then make
the sheet in a personal Google account. If the sheet is not shared, the screen says so at the bottom.

### Today from your calendar, by itself

[`calendar.gs`](calendar.gs) is a Google Apps Script that fills the `Today` tab from your Google Calendar every 15
minutes, on Google's servers, free: nothing to run on a computer and no assistant to ask. Open the sheet,
Extensions > Apps Script, paste the file in place of what is there, save, choose `setup` in the function menu and
press Run, then allow access when Google asks. Because the sheet is readable by link, the script writes only times
and titles, and an event marked Private, or whose title or calendar matches its `PRIVATE_WORDS` list (doctor,
dentist, health and the like), shows as "Private appointment" (or not at all with `HIDE_PRIVATE = true`). Run
`removeTriggers` to stop it.

### Keeping it up to date with an assistant

Any assistant that can edit your Google Sheets can keep the list: tell it the sheet's name and the layout above, and
ask it to add tasks to `To-do`, tick them off in column B, and refresh `Today` from your calendar each morning. For
example:

> My TV shows the Google Sheet "TV list". When I say "add X to my TV list", add a row to the To-do tab
> (Task in column A, FALSE in column B). When I say I did something, set its Done cell to TRUE. Every morning, clear
> rows 2 and down of the Today tab and write today's calendar events there: time in column A (or "All day"), title in
> column B. Keep anything private off this sheet: it is readable by link.

## Install

JDK 17 and Android SDK 34, as for the launcher, and the `tools/venv` from the main README. The small `gradlew`
scripts reuse the launcher's pinned Gradle wrapper with this folder as the project, so keep this folder inside the
repository when building.

```sh
TV_HOST=192.168.1.50 addons/tv-list/build-and-install.sh
TV_HOST=192.168.1.50 addons/tv-list/setup.sh 'https://docs.google.com/spreadsheets/d/<id>/edit'
```

`setup.sh` saves the sheet's id on the TV and makes TV list the screensaver; it remembers the screensaver from
before. Options: `IDLE_MINUTES=10` sets how long the TV waits before the screensaver starts, `START=1` shows it now.
Google TV has no settings entry for other screensavers, so `setup.sh` is how it is chosen there.

Undo:

```sh
TV_HOST=192.168.1.50 addons/tv-list/setup.sh --off               # the previous screensaver back
TV_HOST=192.168.1.50 addons/tv-list/build-and-install.sh --uninstall
```

`adb logcat -s TvList` says why a refresh failed.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | To read the two tabs of your sheet from `docs.google.com`, over HTTPS, while the screensaver or the card is on screen. |

That is all. What goes out is a plain GET for each tab, with the user agent `cleanyourtv-tvlist/1.0`; Google sees the
TV's IP address, as with any visit. No account, no telemetry, no ads, no background service, no location. The sheet
id and the last copy of the two tabs stay in the app's own storage (backups off). The id can only be changed through
`setup.sh`: the provider it writes through needs `WRITE_SECURE_SETTINGS`, which the ADB shell has and apps don't.

## Tested on

onn. Full HD Streaming Device (XNA), Google TV, Android 12, build `SGZ3.231226.096.A1.12865554`: the list from a
link-shared sheet, the star-button toggle with Google's Ambient mode, and the fades, with the real remote. The CSV,
sheet-link and list logic is covered by the unit tests in `app/src/test`.
