# HealthBridge

Read your Health Connect data from any device on your home network.

Your fitness band sends data to your phone, but it stays locked inside the app
that receives it. Health Connect is where that app republishes it — and Health
Connect can only be read from inside Android, by an app with the right
permissions.

HealthBridge is that app. It opens an HTTP port on your phone and answers in
JSON to anyone who has the key: a browser, a dashboard, `curl`, anything that
speaks HTTP. Nothing leaves your network, and no cloud service is involved.

It was built against a Fitbit band, but it reads Health Connect and not Fitbit:
any app that writes to Health Connect works the same way.

## Requirements

- **Android 14 or newer**, where Health Connect is part of the system.
- An app that writes your data into Health Connect (Fitbit, Samsung Health,
  Garmin Connect, …).
- A PC with `adb` and a JDK 17, to build and install.

## Install

Build and install over USB:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open the app on the phone and do three things, in this order:

1. **Grant the permissions.** The button opens the Health Connect dialog and
   asks for all 14 at once.
2. **Exclude from battery optimisation.** The card only appears while it is
   needed. Do not skip it: with the screen off for a few hours, Doze makes a
   read go from 5 seconds to 52 — measured — and callers time out first. From
   the PC it is one line:

   ```bash
   adb shell dumpsys deviceidle whitelist +com.oberon.healthbridge
   ```

3. **Start the server.** The address, the full link and a QR code appear. Scan
   the QR with the device you want to read from, or copy the link.

**Start the server again after every reinstall** — `adb install -r` stops the
running service. Reinstalling keeps your permissions, the key and the quiet
hours; uninstalling loses all three.

### Or let install.sh do it

```bash
./install.sh                 # the only phone connected
./install.sh "moto g24"      # by model, serial or part of the name
./install.sh --all           # every connected phone
./install.sh --no-build      # use the APK already built
./install.sh --release       # install the release variant
./install.sh --no-start      # install only, do not start the server
```

It builds, installs, grants the 14 permissions, whitelists the app from Doze,
starts the server by tapping the button on screen, reads the address and key
back off that screen, and finishes with a real read over the network — because
an `adb` "Success" does not tell you whether Health Connect answers.

```
Installing on moto g24 (192.168.1.42:5555)
  installed
  permissions: all 14 already granted
  battery optimisation: exempt
  server: listening on 192.168.1.42:8421
  key: abc23def4
  read succeeded over the network - missing permissions: none
```

It needs `adb` and `python3`; `curl` is only used for the final check. If the
phone is locked it stops and says so — nobody can type your PIN for you.

## Using the API

Take the address and the key from the QR code or from the app screen. The link
shown under the address is the whole thing: the key is whatever follows `?t=`.

```bash
H=http://192.168.1.42:8421
T=abc23def4

# Anybody there? Also returns the API version and the lifecycle state.
curl "$H/api/ping?t=$T"

# What this app can do — the route list, served by the app itself.
curl "$H/api?t=$T"

# The last two hours of heart rate, one point per minute.
curl "$H/api/heart?t=$T&minutes=120&bucket=60"

# Single samples instead of averages.
curl "$H/api/heart?t=$T&minutes=30&raw=on"

# Steps, calories, sleep and the rest of today.
curl "$H/api/today?t=$T"

# What is in Health Connect, who wrote it and how fresh it is.
curl "$H/api/probe?t=$T"

# What has appeared since you last asked.
curl "$H/api/changes?t=$T"

# The last answer, without reading again.
curl "$H/api/status?t=$T"

# When the phone goes quiet and when it comes back.
curl "$H/api/schedule?t=$T"

# Send it to rest now instead of waiting for idleness.
curl -X POST "$H/api/sleep?t=$T"

# The key can also travel in a header, which is cleaner in logs
# and in a browser's history.
curl -H "X-Healthbridge-Token: $T" "$H/api/today"
```

### The key

Three ways to present it, because callers come in three kinds:

| where | who uses it |
|---|---|
| `?t=KEY` in the URL | the QR code, and a first visit from it |
| the `healthbridge` cookie | the browser, set when the panel is served |
| the `X-Healthbridge-Token` header | scripts — the only place it does not end up in a history or a log |

Regenerating the key from the app locks out anyone already connected.

### Routes

| route | what it answers |
|---|---|
| `GET /api` | the list of routes, with parameters |
| `GET /api/ping` | liveness, API version, lifecycle state (`active` or `resting`) |
| `GET /api/heart` | heart rate over the last `minutes`, one point every `bucket` seconds with average, min and max. `raw=on` returns single samples |
| `GET /api/today` | steps, distance, calories, heart rate of the day, last night's sleep, skin temperature, oxygen, weight, HRV, resting heart rate, respiratory rate, VO2 max |
| `GET /api/probe` | how many records per type in the last 24 h, how fresh they are, and **which app wrote them** |
| `GET /api/changes` | what has appeared since the previous call — the only way to measure the delay data arrives with |
| `GET /api/status` | the last answer, without reading again |
| `GET /api/schedule` | quiet hours and rest timeout. Read only: change them on the phone |
| `POST /api/sleep` | drop to rest now instead of waiting for idleness |
| `GET /` | the web panel |

### Responses

Every answer carries `state` (`ok` or `error`), `seq`, `action`, `ts` and
`tookMs`, plus the fields of the action itself:

```json
{
  "state": "ok",
  "minutes": 60,
  "now": 1757251200,
  "bucketSeconds": 60,
  "count": 47,
  "buckets": [[1757247600, 62, 58, 70], [1757247660, 64, 60, 72]],
  "latest": { "t": 1757251080, "bpm": 66 },
  "lagSeconds": 120,
  "seq": 412,
  "action": "heart",
  "ts": 1757251200417,
  "tookMs": 4832
}
```

A bucket is `[timestamp, average, min, max]` — four numbers instead of four
repeated keys. **Buckets with no samples are absent, not zero.** A gap means the
band was off the wrist; draw it as a break in the line, because a zero there
would read as cardiac arrest.

Errors come back with the matching HTTP status — 403 for the key, 404 for the
route, 503 for Health Connect — so a script can check the status before the
body.

The API is in English, keys and prose, so an error message does not change with
the caller's language.

### API version

The mDNS TXT record carries `api=N`, which goes up when the **JSON keys**
change, not when the app changes.

**`api=2`** — sleep stages in `today.sleep.stages` are now `deep`, `rem`,
`light`, `awake`, `other`.

## The web panel

`GET /` serves a panel built on those routes and nothing else: heart rate,
today's numbers, sleep stages and a diagnostics section.

At the bottom there is a **How to use the API** section that builds itself from
`/api`: the route table, and the same `curl` commands as above but with your
real address and key already in them, each with a copy button.

## The delay, which is the thing to know

The band sends heart rate to its phone app over Bluetooth continuously, but
that app does **not** republish it immediately — it hands it to Health Connect
in batches.

Measured over an evening, polling every three minutes:

```
23:04   0 insertions
23:07   110 records, 390 samples — the newest 931 s old
23:10   0 insertions
```

Nothing for several minutes, then a batch carrying half an hour of measurements
at once. **The freshest sample you can read is usually 15 to 30 minutes old.**
This is between the band and its app; nothing here can fix it.

Everything follows from that: polling much more often is wasted effort, and
anything showing these numbers must say how old they are. That is why
`lagSeconds` is in every answer and at the top of the panel.

Different types arrive at different rates — calories almost by the minute,
steps every half hour, sleep once a night. `/api/probe` shows it type by type,
and is the first thing to look at when a number does not add up.

A read of Health Connect costs about 5 seconds regardless of the window
requested; `ping` and `status` cost about 17 ms, because they do not touch
Health Connect at all.

## Battery: rest and quiet hours

The app has no cycle of its own. It answers and nothing else — it never polls
and never reads Health Connect unless someone asks. What costs battery when
nobody is asking is not the work but the presence: the foreground service, the
WiFi kept awake, the open port.

Hence two ways of standing down:

- **Rest**, after 10 minutes without requests. It drops the Health Connect
  connection. That is memory, **not power** — the socket stays open on purpose,
  so the first read that arrives wakes everything up on its own. `ping` and
  `status` do not wake it, because they do not need Health Connect.
- **Quiet hours**, 01:00 to 07:00 by default. This shuts down the service, the
  WiFi lock, the port and the mDNS announcement. **This is what takes power to
  zero.** The phone comes back on its own with an alarm.

**Standing down costs no history.** Health Connect keeps collecting while
HealthBridge sleeps, and on waking a single read fetches the whole window
backwards. You only lose real time while nobody is watching.

## Finding the phone

The phone announces itself over mDNS as `_healthbridge._tcp`, so a client can
find it without a hardcoded address — a router-assigned IP changes on its own.

```bash
avahi-browse -rt _healthbridge._tcp
```

The TXT record carries only `api=N`. **The key is not in it**: mDNS
announcements are readable by anyone on the network.

## Languages

The app speaks English and Italian, and the two faces follow different signals
because the people looking at them may be in different places.

- **The phone screen** follows the system language. Strings live in
  `res/values/strings.xml` (English, also the fallback for every other
  language) and `res/values-it/strings.xml`. On Android 13 and later you can
  also set it per app, in *Settings → Apps → HealthBridge → Language*.
- **The web panel** follows `navigator.language`. A PC in English looking at a
  phone in Italian reads English. The dictionary is at the top of
  `assets/app.js`.

The API stays in English either way.

## Missing data?

Some apps declare Health Connect data types without ever writing them, because
the matching `WRITE_*` permission was never actually requested from the user.
With Fitbit this was the case for heart rate variability, blood oxygen, resting
heart rate, respiratory rate and VO2 max.

Grant them by hand in Health Connect, under the writing app's permissions:

```bash
adb shell am start -a android.health.connect.action.MANAGE_HEALTH_PERMISSIONS \
    --es android.intent.extra.PACKAGE_NAME com.fitbit.FitbitMobile
```

**Writing starts from that moment** — earlier history is not backfilled. Those
types stay empty until the next night's measurements come in. `/api/probe`
shows when they start arriving.

## Security and limitations

- **The port is protected by a key, with no TLS.** Anyone on your network who
  has the key reads everything, and anyone who can capture your network traffic
  can read it in transit. That is acceptable on a home network you control; on
  a shared or public one it is not. Never port-forward this to the internet.
- The last answer is kept in `status.json` in the app's external files
  directory, in the clear.
- The server must be started by hand the first time. After a reboot it comes
  back only if it was running when the phone shut down.
- Without the battery optimisation exemption the port stops answering after a
  few hours of an idle phone. This is the worst kind of fault to diagnose:
  everything works while you are watching.

## License

MIT — see [LICENSE](LICENSE).
