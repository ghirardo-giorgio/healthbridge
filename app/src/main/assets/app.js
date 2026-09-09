"use strict";

/*
  Il pannello, costruito su /api/* e su nient'altro: se un giorno qui servisse
  un dato che un `curl` non sa ottenere, vorrebbe dire che manca una rotta.
*/

// La chiave di solito e' gia' nel cookie, posato dal server quando ha servito
// la pagina. Resta nell'indirizzo solo il tempo di una prima visita dal QR, e
// per quel giro va rimessa a mano: il cookie c'e' ma il browser potrebbe
// averlo rifiutato, e scoprirlo con un 403 sarebbe scoprirlo tardi.
const COOKIE = "healthbridge";

const cookieKey = () => {
  const found = document.cookie.split(";").map((p) => p.trim())
    .find((p) => p.startsWith(COOKIE + "="));
  return found ? decodeURIComponent(found.slice(COOKIE.length + 1)) : null;
};

const KEY = new URLSearchParams(location.search).get("t");
const TOKEN = KEY || cookieKey();

const WINDOWS = [60, 360, 1440];
const REFRESH_MS = 60000;

// Sotto questa ampiezza il grafico non stringe: un polso fermo varia di due
// battiti, e adattare la scala a due battiti trasforma il rumore in una
// montagna russa.
const MIN_SPAN_BPM = 20;

let minutes = 60;
let timer = null;

const $ = (id) => document.getElementById(id);

// -- le lingue ---------------------------------------------------------------

/*
  Il pannello segue il browser, non il telefono: chi guarda da un PC in inglese
  un telefono in italiano deve leggere in inglese. L'API invece parla inglese e
  basta, e le poche frasi che arrivano da li' — le descrizioni delle rotte —
  hanno qui la loro traduzione, con il testo del server come ripiego.
*/
const LANG = (navigator.language || "en").toLowerCase().startsWith("it") ? "it" : "en";

const STRINGS = {
  it: {
    reading: "lettura in corso…",
    reload: "Rileggi",
    reloadTitle: "Rileggi adesso",
    heart: "Battito",
    heartOverTime: "Battito nel tempo",
    today: "Oggi",
    sleep: "Sonno",
    whatsInside: "Cosa c'è in Health Connect",
    openToRead: "apri per leggere",
    footerNote:
      "Il dato più fresco ha di solito 15-30 minuti: l'app Fitbit travasa in " +
      "Health Connect a blocchi.",

    never: "mai",
    now: "adesso",
    minAgo: "%s min fa",
    hAgo: "%s h fa",
    dAgo: "%s g fa",
    hAndMin: "%s h %s min",
    min: "%s min",

    noBeats: "nessun battito in questa finestra",
    measuredAt: "misurato %s, alle %s",
    cannotRead: "non ho potuto leggere",
    answered: "il telefono ha risposto %s",

    lowest: "minimo",
    average: "medio",
    highest: "massimo",

    steps: "passi",
    distance: "distanza",
    calories: "calorie",
    activeCalories: "di cui attive",
    bpmAvg: "battito medio",
    resting: "a riposo",
    hrv: "variabilità",
    breathing: "respiro",
    oxygen: "ossigeno",
    vo2max: "vo2 max",
    weight: "peso",
    skin: "pelle, scarto",

    noSleep: "nessun sonno registrato nelle ultime 36 ore",
    sleepFromTo: "dalle %s alle %s · %s",
    stageDeep: "profondo",
    stageRem: "rem",
    stageLight: "leggero",
    stageAwake: "sveglio",
    stageOther: "altro",

    missingPerms: "mancano i permessi: %s. Concedili dall'app sul telefono.",
    probeHead: "Health Connect: %s. Ultime 24 ore.",
    colType: "tipo",
    colRecords: "record",
    colLatest: "più recente",
    colFrom: "da",
    samples: "(%s campioni)",

    quietSchedule:
      "Il telefono tace dalle %s alle %s, e va a riposo dopo %s minuti senza " +
      "richieste. Mentre dorme non si perde niente: la prima lettura al " +
      "risveglio rilegge all'indietro.",
    alwaysOn: "Sempre in ascolto. A riposo dopo %s minuti senza richieste.",

    apiTitle: "Come usare l'API",
    apiIntro:
      "Tutto quello che vedi in questa pagina esce da queste rotte, e da " +
      "nient'altro: qualunque cosa parli HTTP può leggere gli stessi dati. " +
      "L'indirizzo di base è %s, e le risposte sono in inglese.",
    apiAuth:
      "La chiave si presenta in tre modi: nell'indirizzo con <code>?t=</code>, " +
      "nel cookie <code>healthbridge</code> (il browser ce l'ha già), oppure " +
      "nell'header <code>X-Healthbridge-Token</code> — che è l'unico posto " +
      "dove non finisce nella cronologia o nei log.",
    apiVersion: "Versione del vocabolario: %s.",
    apiColRoute: "rotta",
    apiColWhat: "cosa risponde",
    apiColParams: "parametri",
    apiExamples: "Da copiare e incollare",
    apiCopy: "Copia",
    apiCopied: "Copiato",
    apiSecret:
      "La chiave qui sotto è quella vera: chi apre questa pagina la ha già, ma " +
      "un comando incollato altrove se la porta dietro.",
    apiFields:
      "Ogni risposta ha <code>state</code> (<code>ok</code> oppure " +
      "<code>error</code>), <code>seq</code>, <code>action</code>, " +
      "<code>ts</code> e <code>tookMs</code>. Lo stato HTTP segue l'errore — " +
      "403 la chiave, 404 la rotta, 503 Health Connect — perché un errore " +
      "restituito con 200 verrà scambiato per un dato buono almeno una volta.",
    apiUnavailable: "l'elenco delle rotte non si è caricato: %s",

    exPing: "C'è qualcuno, con la versione dell'API e lo stato del ciclo di vita",
    exList: "L'elenco delle rotte, servito dall'app stessa",
    exHeart: "Il battito delle ultime due ore, un punto al minuto",
    exRaw: "I campioni singoli invece delle medie",
    exToday: "Passi, calorie, sonno e il resto di oggi",
    exProbe: "Cosa c'è in Health Connect, chi l'ha scritto e quanto è fresco",
    exChanges: "Cos'è comparso da quando l'hai chiesto l'ultima volta",
    exStatus: "L'ultima risposta, senza rifare la lettura",
    exSchedule: "Quando il telefono tace e quando torna",
    exSleep: "Mandalo a riposo subito invece di aspettare l'inattività",
    exHeader: "La chiave in un header, che è più pulito nei log",

    "/api": "questo elenco",
    "/api/ping": "c'è qualcuno, versione e stato del ciclo di vita",
    "/api/heart": "il battito, un punto per intervallo",
    "/api/today": "passi, calorie, sonno e il resto della giornata",
    "/api/probe": "cosa c'è in Health Connect, chi l'ha scritto, quanto è fresco",
    "/api/changes": "cos'è comparso da quando l'hai chiesto l'ultima volta",
    "/api/status": "l'ultima risposta, senza rifare la lettura",
    "/api/schedule": "quando il telefono tace e quando torna",
    "/api/sleep": "mandalo a riposo subito invece di aspettare l'inattività",
    "params:/api/heart": "minutes (60), bucket (60), raw (on per i campioni singoli)",
  },

  en: {
    reading: "reading…",
    reload: "Reload",
    reloadTitle: "Read again now",
    heart: "Heart rate",
    heartOverTime: "Heart rate over time",
    today: "Today",
    sleep: "Sleep",
    whatsInside: "What is in Health Connect",
    openToRead: "open to read",
    footerNote:
      "The freshest sample is usually 15-30 minutes old: the Fitbit app hands " +
      "data to Health Connect in batches.",

    never: "never",
    now: "just now",
    minAgo: "%s min ago",
    hAgo: "%s h ago",
    dAgo: "%s d ago",
    hAndMin: "%s h %s min",
    min: "%s min",

    noBeats: "no heartbeats in this window",
    measuredAt: "measured %s, at %s",
    cannotRead: "could not read",
    answered: "the phone answered %s",

    lowest: "lowest",
    average: "average",
    highest: "highest",

    steps: "steps",
    distance: "distance",
    calories: "calories",
    activeCalories: "of which active",
    bpmAvg: "average heart rate",
    resting: "resting",
    hrv: "variability",
    breathing: "breathing",
    oxygen: "oxygen",
    vo2max: "vo2 max",
    weight: "weight",
    skin: "skin, delta",

    noSleep: "no sleep recorded in the last 36 hours",
    sleepFromTo: "from %s to %s · %s",
    stageDeep: "deep",
    stageRem: "rem",
    stageLight: "light",
    stageAwake: "awake",
    stageOther: "other",

    missingPerms: "missing permissions: %s. Grant them from the app on the phone.",
    probeHead: "Health Connect: %s. Last 24 hours.",
    colType: "type",
    colRecords: "records",
    colLatest: "most recent",
    colFrom: "from",
    samples: "(%s samples)",

    quietSchedule:
      "The phone goes quiet from %s to %s, and rests after %s minutes without " +
      "requests. Nothing is lost while it sleeps: the first read on waking " +
      "reads backwards.",
    alwaysOn: "Always listening. At rest after %s minutes without requests.",

    apiTitle: "How to use the API",
    apiIntro:
      "Everything on this page comes out of these routes and nothing else: " +
      "anything that speaks HTTP can read the same data. The base address is " +
      "%s, and the answers are in English.",
    apiAuth:
      "The key is presented in three ways: in the address with " +
      "<code>?t=</code>, in the <code>healthbridge</code> cookie (your browser " +
      "already has it), or in the <code>X-Healthbridge-Token</code> header — " +
      "the only place where it does not end up in history or logs.",
    apiVersion: "Vocabulary version: %s.",
    apiColRoute: "route",
    apiColWhat: "what it answers",
    apiColParams: "parameters",
    apiExamples: "Ready to copy",
    apiCopy: "Copy",
    apiCopied: "Copied",
    apiSecret:
      "The key below is the real one: whoever opens this page already has it, " +
      "but a command pasted elsewhere carries it along.",
    apiFields:
      "Every answer carries <code>state</code> (<code>ok</code> or " +
      "<code>error</code>), <code>seq</code>, <code>action</code>, " +
      "<code>ts</code> and <code>tookMs</code>. The HTTP status follows the " +
      "error — 403 the key, 404 the route, 503 Health Connect — because an " +
      "error returned with 200 will be taken for good data at least once.",
    apiUnavailable: "the route list did not load: %s",

    exPing: "Anybody there, with the API version and the lifecycle state",
    exList: "The list of routes, served by the app itself",
    exHeart: "The last two hours of heart rate, one point per minute",
    exRaw: "Single samples instead of averages",
    exToday: "Steps, calories, sleep and the rest of today",
    exProbe: "What is in Health Connect, who wrote it and how fresh it is",
    exChanges: "What has appeared since you last asked",
    exStatus: "The last answer, without reading again",
    exSchedule: "When the phone goes quiet and when it comes back",
    exSleep: "Send it to rest now instead of waiting for idleness",
    exHeader: "The key in a header, which is cleaner in logs",

    "/api": "this list",
    "/api/ping": "anybody there, API version and lifecycle state",
    "/api/heart": "heart rate, one point per bucket",
    "/api/today": "steps, calories, sleep and the rest of the day",
    "/api/probe": "what is in Health Connect, who wrote it, how fresh it is",
    "/api/changes": "what has appeared since you last asked",
    "/api/status": "the last answer, without reading again",
    "/api/schedule": "when the phone goes quiet and when it comes back",
    "/api/sleep": "send it to rest now instead of waiting for idleness",
    "params:/api/heart": "minutes (60), bucket (60), raw (on for single samples)",
  },
};

const DICT = STRINGS[LANG];

function t(key, ...args) {
  const raw = DICT[key];
  if (raw === undefined) return key;
  let at = 0;
  return raw.replace(/%s/g, () => (at < args.length ? args[at++] : "%s"));
}

const esc = (value) =>
  String(value).replace(/[&<>"]/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

function translate() {
  document.documentElement.lang = LANG;
  for (const node of document.querySelectorAll("[data-i18n]")) {
    node.textContent = t(node.dataset.i18n);
  }
  for (const node of document.querySelectorAll("[data-i18n-title]")) {
    node.title = t(node.dataset.i18nTitle);
  }
  for (const node of document.querySelectorAll("[data-i18n-label]")) {
    node.setAttribute("aria-label", t(node.dataset.i18nLabel));
  }
}

// -- l'API -------------------------------------------------------------------

async function api(path, params) {
  const url = new URL(path, location.href);
  if (KEY) url.searchParams.set("t", KEY);
  for (const [k, v] of Object.entries(params || {})) url.searchParams.set(k, v);

  const answer = await fetch(url, { credentials: "same-origin" });
  const data = await answer.json().catch(() => null);

  if (!answer.ok || !data) {
    // Il corpo dell'errore vale piu' del codice: l'app distingue «Health
    // Connect non c'e'» da «mancano i permessi», e sono due gesti diversi.
    throw new Error((data && data.error) || t("answered", answer.status));
  }

  return data;
}

// -- il tempo ---------------------------------------------------------------

const clock = (seconds) =>
  new Date(seconds * 1000).toLocaleTimeString(navigator.language || LANG, {
    hour: "2-digit",
    minute: "2-digit",
  });

function ago(seconds) {
  if (seconds === null || seconds === undefined) return t("never");
  if (seconds < 60) return t("now");
  const m = Math.round(seconds / 60);
  if (m < 60) return t("minAgo", m);
  const h = seconds / 3600;
  return h < 24 ? t("hAgo", h.toFixed(1)) : t("dAgo", Math.round(h / 24));
}

const hm = (seconds) => {
  const h = Math.floor(seconds / 3600);
  const m = Math.round((seconds % 3600) / 60);
  return h ? t("hAndMin", h, m) : t("min", m);
};

// -- il grafico -------------------------------------------------------------

/*
  Il battito, con i buchi lasciati buchi.

  Health Connect restituisce solo gli intervalli che hanno campioni, e una linea
  che unisce i due estremi del vuoto disegnerebbe mezz'ora di polso che nessuno
  ha misurato; uno zero al posto del vuoto racconterebbe un arresto cardiaco.
*/
function chart(data) {
  const svg = $("chart");
  const buckets = (data.buckets || []).filter((b) => b[1] !== null);
  svg.textContent = "";

  if (!buckets.length) {
    svg.insertAdjacentHTML(
      "beforeend",
      `<text x="360" y="110" fill="#4A5568" font-size="15" text-anchor="middle">${esc(t("noBeats"))}</text>`
    );
    return;
  }

  const W = 720, H = 220, PAD = 26;
  const step = data.bucketSeconds || 60;
  const first = data.now - data.minutes * 60;
  const last = data.now;

  let low = Math.min(...buckets.map((b) => b[2]));
  let high = Math.max(...buckets.map((b) => b[3]));

  const span = high - low;
  if (span < MIN_SPAN_BPM) {
    const grow = (MIN_SPAN_BPM - span) / 2;
    low -= grow;
    high += grow;
  }

  const x = (t) => PAD + ((t - first) / (last - first)) * (W - PAD * 2);
  const y = (v) => H - PAD - ((v - low) / (high - low)) * (H - PAD * 2);

  // Linee orizzontali di riferimento, con il valore scritto sopra: senza, un
  // grafico senza scala e' un disegno.
  const marks = [low, (low + high) / 2, high].map(Math.round);
  for (const v of marks) {
    svg.insertAdjacentHTML(
      "beforeend",
      `<line x1="${PAD}" x2="${W - PAD}" y1="${y(v)}" y2="${y(v)}" stroke="#222933" stroke-width="1"/>` +
        `<text x="2" y="${y(v) - 4}" fill="#4A5568" font-size="11">${v}</text>`
    );
  }

  // Un tratto per ogni sequenza continua: appena il salto fra due intervalli
  // supera il passo, quello che segue e' un'altra linea.
  const runs = [];
  let run = [];

  for (let i = 0; i < buckets.length; i++) {
    if (i > 0 && buckets[i][0] - buckets[i - 1][0] > step * 1.5) {
      runs.push(run);
      run = [];
    }
    run.push(buckets[i]);
  }
  runs.push(run);

  for (const piece of runs) {
    if (!piece.length) continue;

    // La banda min-max prima della linea, cosi' la media resta leggibile
    // sopra: dentro un minuto il battito ha un'escursione, e mostrarne solo la
    // media la nasconde.
    const top = piece.map((b) => `${x(b[0])},${y(b[3])}`).join(" ");
    const bottom = piece.slice().reverse().map((b) => `${x(b[0])},${y(b[2])}`).join(" ");

    if (piece.length > 1) {
      svg.insertAdjacentHTML(
        "beforeend",
        `<polygon points="${top} ${bottom}" fill="#7CC4FF" fill-opacity="0.16"/>`
      );
    }

    const line = piece.map((b) => `${x(b[0])},${y(b[1])}`).join(" ");

    svg.insertAdjacentHTML(
      "beforeend",
      piece.length > 1
        ? `<polyline points="${line}" fill="none" stroke="#7CC4FF" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>`
        : `<circle cx="${x(piece[0][0])}" cy="${y(piece[0][1])}" r="2.5" fill="#7CC4FF"/>`
    );
  }

  const hours = [first, (first + last) / 2, last].map((v) => Math.round(v));
  for (let i = 0; i < hours.length; i++) {
    const anchor = i === 0 ? "start" : i === hours.length - 1 ? "end" : "middle";
    svg.insertAdjacentHTML(
      "beforeend",
      `<text x="${x(hours[i])}" y="${H - 6}" fill="#4A5568" font-size="11" text-anchor="${anchor}">${clock(hours[i])}</text>`
    );
  }
}

// -- le sezioni -------------------------------------------------------------

async function loadHeart() {
  const data = await api("api/heart", { minutes, bucket: minutes > 360 ? 300 : 60 });

  $("bpm").textContent = data.latest ? Math.round(data.latest.bpm) : "—";

  // Il ritardo va detto sempre e non solo quando e' grande: un battito di
  // mezz'ora fa presentato come «adesso» e' una bugia.
  $("freshness").textContent = data.latest
    ? t("measuredAt", ago(data.lagSeconds), clock(data.latest.t))
    : t("noBeats");

  chart(data);

  const known = (data.buckets || []).filter((b) => b[1] !== null);
  $("span").innerHTML = known.length
    ? [
        [t("lowest"), Math.min(...known.map((b) => b[2]))],
        [t("average"), known.reduce((s, b) => s + b[1], 0) / known.length],
        [t("highest"), Math.max(...known.map((b) => b[3]))],
      ]
        .map(([name, v]) => `<div><dt>${esc(name)}</dt><dd>${Math.round(v)}<span class="unit">bpm</span></dd></div>`)
        .join("")
    : "";
}

const number = (v, unit, digits = 0) =>
  v === null || v === undefined
    ? `<dd class="empty">—</dd>`
    : `<dd>${Number(v).toFixed(digits)}${unit ? `<span class="unit">${unit}</span>` : ""}</dd>`;

async function loadToday() {
  const d = await api("api/today");

  const rows = [
    [t("steps"), d.steps, "", 0],
    [t("distance"), d.distanceMeters === null ? null : d.distanceMeters / 1000, "km", 2],
    [t("calories"), d.calories, "kcal", 0],
    [t("activeCalories"), d.activeCalories, "kcal", 0],
    [t("bpmAvg"), d.bpmAvg, "bpm", 0],
    [t("resting"), d.restingBpm && d.restingBpm.bpm, "bpm", 0],
    [t("hrv"), d.hrv && d.hrv.rmssdMillis, "ms", 0],
    [t("breathing"), d.respiratoryRate && d.respiratoryRate.breathsPerMinute, "/min", 1],
    [t("oxygen"), d.oxygen && d.oxygen.percent, "%", 0],
    [t("vo2max"), d.vo2Max && d.vo2Max.mlPerMinPerKg, "", 1],
    [t("weight"), d.weight && d.weight.kilograms, "kg", 1],
    // La temperatura cutanea arriva come scostamento da una linea di base:
    // scriverla come gradi assoluti la farebbe leggere per una febbre.
    [t("skin"), d.skinTemperature && d.skinTemperature.deltaCelsius, "°C", 2],
  ];

  $("today").innerHTML = rows
    .map(([name, v, unit, digits]) => `<div><dt>${esc(name)}</dt>${number(v, unit, digits)}</div>`)
    .join("");

  sleep(d.sleep);
}

// Le chiavi sono quelle dell'API, in inglese; il nome mostrato viene dal
// dizionario, cosi' una fase resta leggibile anche in italiano.
const STAGES = {
  deep: { colour: "#4C6FE7", name: "stageDeep" },
  rem: { colour: "#7CC4FF", name: "stageRem" },
  light: { colour: "#5FD68B", name: "stageLight" },
  awake: { colour: "#F0A868", name: "stageAwake" },
  other: { colour: "#4A5568", name: "stageOther" },
};

const stage = (key) => STAGES[key] || STAGES.other;

function sleep(night) {
  const box = $("sleep");

  if (!night) {
    box.innerHTML = `<p class="dim">${esc(t("noSleep"))}</p>`;
    return;
  }

  const stages = Object.entries(night.stages || {}).filter(([, s]) => s > 0);
  const total = stages.reduce((sum, [, s]) => sum + s, 0) || night.seconds;

  const bar = stages
    .map(([key, s]) => {
      const it = stage(key);
      return `<span style="width:${(s / total) * 100}%;background:${it.colour}" title="${esc(t(it.name))}"></span>`;
    })
    .join("");

  const legend = stages
    .map(([key, s]) => {
      const it = stage(key);
      return `<span><i style="background:${it.colour}"></i>${esc(t(it.name))} ${esc(hm(s))}</span>`;
    })
    .join("");

  box.innerHTML =
    `<p class="dim">${esc(t("sleepFromTo", clock(night.start), clock(night.end), hm(night.seconds)))}</p>` +
    (stages.length ? `<div class="stages">${bar}</div><div class="legend">${legend}</div>` : "");
}

/*
  La diagnosi, che si legge solo quando la si apre: e' una lettura da cinque
  secondi come tutte le altre, e farla a ogni giro per una scheda quasi sempre
  chiusa vorrebbe dire tenere sveglio il telefono per niente.
*/
async function loadProbe() {
  const box = $("probe");

  try {
    const d = await api("api/probe");

    const missing = (d.missing || []).length
      ? `<p class="trouble">${esc(t("missingPerms", d.missing.join(", ")))}</p>`
      : "";

    const rows = Object.entries(d.types || {})
      .map(([name, type]) => {
        if (type.error) {
          return `<tr><td>${esc(name)}</td><td colspan="3" class="none">${esc(type.error)}</td></tr>`;
        }

        const stale = type.lagSeconds === null || type.lagSeconds > 3600;
        const count =
          type.count +
          (type.truncated ? "+" : "") +
          (type.samples ? " " + t("samples", type.samples) : "");
        const who = (type.origins || []).map((o) => o.split(".").pop()).join(", ") || "—";

        return `<tr><td>${esc(name)}</td><td>${esc(count)}</td>` +
          `<td class="${stale ? "stale" : ""}">${esc(ago(type.lagSeconds))}</td>` +
          `<td class="dim">${esc(who)}</td></tr>`;
      })
      .join("");

    box.innerHTML =
      missing +
      `<p class="dim">${esc(t("probeHead", d.sdk))}</p>` +
      `<div class="scroll"><table><thead><tr>` +
      `<th>${esc(t("colType"))}</th><th>${esc(t("colRecords"))}</th>` +
      `<th>${esc(t("colLatest"))}</th><th>${esc(t("colFrom"))}</th>` +
      `</tr></thead><tbody>${rows}</tbody></table></div>`;
  } catch (trouble) {
    box.innerHTML = `<p class="trouble">${esc(trouble.message)}</p>`;
  }
}

/*
  Quando il telefono tace.

  Un pannello che smette di aggiornarsi all'una di notte senza dirlo sembra
  rotto, e chi lo guarda va a cercare un guasto che non c'e'.
*/
async function loadSchedule() {
  try {
    const d = await api("api/schedule");

    $("schedule").textContent = d.quiet
      ? t("quietSchedule", d.from, d.to, d.restAfterMinutes)
      : t("alwaysOn", d.restAfterMinutes);
  } catch (trouble) {
    $("schedule").textContent = "";
  }
}

// -- come usare l'API --------------------------------------------------------

/*
  La sezione si costruisce da `/api`, cioe' dall'elenco che l'app serve di se
  stessa: aggiungere una rotta la fa comparire qui senza toccare questo file.
  Le descrizioni tradotte stanno nel dizionario e sono indicizzate per
  percorso; quando manca la traduzione si mostra quella del server.
*/
function examples(base, token) {
  const q = token ? `?t=${token}` : "";
  const amp = token ? "&" : "?";

  return [
    [t("exPing"), `curl "${base}/api/ping${q}"`],
    [t("exList"), `curl "${base}/api${q}"`],
    [t("exHeart"), `curl "${base}/api/heart${q}${amp}minutes=120&bucket=60"`],
    [t("exRaw"), `curl "${base}/api/heart${q}${amp}minutes=30&raw=on"`],
    [t("exToday"), `curl "${base}/api/today${q}"`],
    [t("exProbe"), `curl "${base}/api/probe${q}"`],
    [t("exChanges"), `curl "${base}/api/changes${q}"`],
    [t("exStatus"), `curl "${base}/api/status${q}"`],
    [t("exSchedule"), `curl "${base}/api/schedule${q}"`],
    [t("exSleep"), `curl -X POST "${base}/api/sleep${q}"`],
    [
      t("exHeader"),
      `curl -H "X-Healthbridge-Token: ${token || "YOUR_KEY"}" "${base}/api/today"`,
    ],
  ];
}

async function loadApi() {
  const box = $("apibody");
  const base = location.origin;

  let routes = "";
  let version = "";

  try {
    const d = await api("api");

    version = `<p class="dim">${esc(t("apiVersion", d.api))}</p>`;
    routes =
      `<div class="scroll"><table><thead><tr>` +
      `<th>${esc(t("apiColRoute"))}</th><th>${esc(t("apiColWhat"))}</th>` +
      `<th>${esc(t("apiColParams"))}</th>` +
      `</tr></thead><tbody>` +
      (d.routes || [])
        .map((route) => {
          const what = DICT[route.path] || route.what || "";
          const params = DICT["params:" + route.path] || route.params || "—";
          return `<tr><td><code>${esc(route.method)} ${esc(route.path)}</code></td>` +
            `<td>${esc(what)}</td><td class="dim">${esc(params)}</td></tr>`;
        })
        .join("") +
      `</tbody></table></div>`;
  } catch (trouble) {
    routes = `<p class="trouble">${esc(t("apiUnavailable", trouble.message))}</p>`;
  }

  const blocks = examples(base, TOKEN)
    .map(
      ([what, command], i) =>
        `<div class="snippet">` +
        `<p class="dim">${esc(what)}</p>` +
        `<div class="cmd"><pre><code id="cmd${i}">${esc(command)}</code></pre>` +
        `<button type="button" class="copy" data-copy="cmd${i}">${esc(t("apiCopy"))}</button></div>` +
        `</div>`
    )
    .join("");

  box.innerHTML =
    `<p class="dim">${esc(t("apiIntro", base))}</p>` +
    `<p class="dim">${t("apiAuth")}</p>` +
    version +
    routes +
    `<h3>${esc(t("apiExamples"))}</h3>` +
    `<p class="dim">${esc(t("apiSecret"))}</p>` +
    blocks +
    `<p class="dim">${t("apiFields")}</p>`;
}

// Il clipboard moderno vuole un contesto sicuro, e questa pagina arriva in
// chiaro da un IP di casa: senza il ripiego il pulsante non farebbe niente e
// non lo direbbe.
async function copy(text, button) {
  const done = await navigator.clipboard?.writeText(text).then(() => true).catch(() => false);

  if (!done) {
    const box = document.createElement("textarea");
    box.value = text;
    box.setAttribute("readonly", "");
    box.style.position = "fixed";
    box.style.opacity = "0";
    document.body.appendChild(box);
    box.select();
    document.execCommand("copy");
    box.remove();
  }

  const was = button.textContent;
  button.textContent = t("apiCopied");
  setTimeout(() => { button.textContent = was; }, 1200);
}

$("apibody").addEventListener("click", (event) => {
  const button = event.target.closest("button.copy");
  if (!button) return;
  const source = $(button.dataset.copy);
  if (source) copy(source.textContent, button);
});

// -- il giro ----------------------------------------------------------------

async function refresh() {
  try {
    await loadHeart();
    await loadToday();
    $("trouble").hidden = true;
  } catch (trouble) {
    $("trouble").textContent = trouble.message;
    $("trouble").hidden = false;
    $("freshness").textContent = t("cannotRead");
  }

  if ($("diagnosis").open) await loadProbe();
}

function schedule() {
  clearInterval(timer);
  // Fermo quando la scheda e' nascosta: non ha senso tenere sveglio un
  // telefono per una pagina che nessuno sta guardando — ed e' anche cio' che
  // lo lascia scendere a riposo da solo.
  if (!document.hidden) timer = setInterval(refresh, REFRESH_MS);
}

function pickWindow(chosen) {
  minutes = chosen;
  for (const button of $("windows").children) {
    button.setAttribute("aria-pressed", String(Number(button.dataset.minutes) === chosen));
  }
  refresh();
}

$("windows").addEventListener("click", (event) => {
  const button = event.target.closest("button");
  if (button) pickWindow(Number(button.dataset.minutes));
});

$("reload").addEventListener("click", refresh);
$("diagnosis").addEventListener("toggle", () => { if ($("diagnosis").open) loadProbe(); });

document.addEventListener("visibilitychange", () => {
  schedule();
  if (!document.hidden) refresh();
});

translate();
pickWindow(WINDOWS[0]);
loadSchedule();
// Una volta sola: e' contenuto che non cambia, e non deve svegliare il telefono
// a ogni giro.
loadApi();
schedule();
