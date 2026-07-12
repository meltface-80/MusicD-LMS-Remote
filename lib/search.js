// ---------------------------------------------------------------------------
// In-memory album search index — the whole-library instant search that powers
// the PWA's search box and artist rows. Lifted verbatim (algorithmically) from
// the proven Roon build's scorer so ranking behaviour is identical; the only
// change is that a record is built from an LMS album row ({id, offset, title,
// subtitle, year, coverId}) and carries `image_key` (= LMS coverId) plus the
// LMS album `id` used to play it.
//
// Pure functions + a small index object — no I/O, unit-testable in isolation.
// ---------------------------------------------------------------------------
"use strict";

function normalize(s) {
  return String(s || "").toLowerCase()
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

// Split a multi-artist string into individual normalized names, once at
// index-build time (mirrors the v1.6.34 perf change on the Roon side).
function splitArtistNames(subtitle) {
  if (!subtitle) return [];
  return subtitle
    .split(/ \/ | feat\.? | featuring | ft\.? /i)
    .map(s => s.trim())
    .filter(Boolean)
    .map(name => ({ name, n: normalize(name) }));
}

// Build a search record from an LMS album row. Keeps `id` (LMS album id, for
// play) and `offset` (stable library position, for deep-links), and exposes
// `image_key` so the /api response shape matches the Roon build exactly.
function indexRecord(album) {
  const title    = album.title    || "";
  const subtitle = album.subtitle || "";
  const nTitle   = normalize(title);
  const nArtist  = normalize(subtitle);
  return {
    id:        album.id,
    offset:    album.offset,
    title, subtitle,
    year:      album.year != null ? album.year : null,
    image_key: album.coverId || null,
    source:    album.source || null,
    nTitle, nArtist,
    tTitle:  nTitle  ? nTitle.split(" ")  : [],
    tArtist: nArtist ? nArtist.split(" ") : [],
    jTitle:  nTitle.replace(/ /g, ""),
    jArtist: nArtist.replace(/ /g, ""),
    artistNames: splitArtistNames(subtitle)
  };
}

function consecutivePrefixStart(tokens, qTokens) {
  const last = tokens.length - qTokens.length;
  for (let i = 0; i <= last; i++) {
    let ok = true;
    for (let k = 0; k < qTokens.length; k++) {
      if (!tokens[i + k].startsWith(qTokens[k])) { ok = false; break; }
    }
    if (ok) return i;
  }
  return -1;
}

function allTokensPrefixSomewhere(tokens, qTokens) {
  const used = new Array(tokens.length).fill(false);
  for (const qt of qTokens) {
    let found = false;
    for (let i = 0; i < tokens.length; i++) {
      if (!used[i] && tokens[i].startsWith(qt)) { used[i] = true; found = true; break; }
    }
    if (!found) return false;
  }
  return true;
}

function isSubsequence(q, s) {
  let i = 0;
  for (let j = 0; j < s.length && i < q.length; j++) {
    if (s[j] === q[i]) i++;
  }
  return i === q.length;
}

function scoreAlbum(al, q, qTokens, qJoined, singleChar) {
  let s = 0;

  if (al.nTitle === q) return 1000;
  if (al.nTitle.startsWith(q)) {
    s = Math.max(s, 920 - Math.min(al.nTitle.length - q.length, 60));
  }
  {
    const start = consecutivePrefixStart(al.tTitle, qTokens);
    if (start === 0)                   s = Math.max(s, 900 - Math.min(al.tTitle.length, 40));
    else if (start > 0 && !singleChar) s = Math.max(s, 820 - start * 4);
  }
  if (al.jTitle.startsWith(qJoined)) {
    s = Math.max(s, 870 - Math.min(al.jTitle.length - qJoined.length, 60));
  }
  if (!singleChar) {
    if (s < 760 && qTokens.length > 1 && allTokensPrefixSomewhere(al.tTitle, qTokens)) {
      s = Math.max(s, 760);
    }
    if (s < 650 && al.nTitle.includes(q)) {
      s = Math.max(s, 650 - Math.min(al.nTitle.indexOf(q), 40));
    }
  }

  if (al.nArtist) {
    if (al.nArtist === q)         s = Math.max(s, 770);
    if (al.nArtist.startsWith(q)) s = Math.max(s, 740 - Math.min(al.nArtist.length - q.length, 60));
    {
      const start = consecutivePrefixStart(al.tArtist, qTokens);
      if (start === 0)                   s = Math.max(s, 720 - Math.min(al.tArtist.length, 40));
      else if (start > 0 && !singleChar) s = Math.max(s, 660 - start * 4);
    }
    if (al.jArtist.startsWith(qJoined)) s = Math.max(s, 700 - Math.min(al.jArtist.length - qJoined.length, 60));
    if (!singleChar) {
      if (s < 600 && qTokens.length > 1 && allTokensPrefixSomewhere(al.tArtist, qTokens)) s = Math.max(s, 600);
      if (s < 520 && al.nArtist.includes(q)) s = Math.max(s, 520 - Math.min(al.nArtist.indexOf(q), 40));
    }
  }

  if (s === 0 && !singleChar && qJoined.length >= 4) {
    if (isSubsequence(qJoined, al.jTitle))       s = 300;
    else if (isSubsequence(qJoined, al.jArtist)) s = 260;
  }

  return s;
}

// A tiny index holder. `.records` is the array of indexRecord()s.
function makeIndex() {
  return {
    records: [],
    builtAt: 0,
    byId: new Map(),      // LMS album id → record
    byOffset: new Map()   // offset → record
  };
}

function loadRecords(index, albumRows) {
  index.records = albumRows.map(indexRecord);
  index.byId = new Map();
  index.byOffset = new Map();
  for (const r of index.records) {
    index.byId.set(String(r.id), r);
    index.byOffset.set(r.offset, r);
  }
  index.builtAt = Date.now();
  return index;
}

function searchAlbums(index, query, limit = 40) {
  const q = normalize(query);
  if (!q) return [];
  const qTokens    = q.split(" ").filter(Boolean);
  const qJoined    = q.replace(/ /g, "");
  const singleChar = qJoined.length <= 1;

  const out = [];
  for (const al of index.records) {
    const score = scoreAlbum(al, q, qTokens, qJoined, singleChar);
    if (score > 0) out.push({ al, score });
  }
  out.sort((a, b) =>
    b.score - a.score ||
    a.al.nTitle.localeCompare(b.al.nTitle) ||
    a.al.nArtist.localeCompare(b.al.nArtist)
  );
  return out.slice(0, limit).map(({ al, score }) => ({
    offset:    al.offset,
    title:     al.title,
    subtitle:  al.subtitle,
    image_key: al.image_key,
    source:    al.source || null,
    score
  }));
}

function searchArtists(index, query, limit = 8) {
  const q = normalize(query);
  if (!q || !index.records.length) return [];
  const seen = new Map();
  for (const al of index.records) {
    const names = al.artistNames;
    if (!names || !names.length) continue;
    for (const { name, n } of names) {
      if (!n.includes(q)) continue;
      if (seen.has(n)) seen.get(n).count++;
      else seen.set(n, { name, n, count: 1 });
    }
  }
  return [...seen.values()]
    .sort((a, b) => {
      const aq = a.n.startsWith(q) ? 0 : 1;
      const bq = b.n.startsWith(q) ? 0 : 1;
      return aq - bq || b.count - a.count;
    })
    .slice(0, limit)
    .map(x => x.name);
}

module.exports = {
  normalize, splitArtistNames, indexRecord, scoreAlbum,
  makeIndex, loadRecords, searchAlbums, searchArtists
};
