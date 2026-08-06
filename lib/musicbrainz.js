/*
 * The one MusicBrainz rate gate, shared by every caller in the app.
 *
 * MusicBrainz asks for at most ONE request per second per application, and
 * throttles or blocks an IP that ignores it. This module exists because we
 * were not honouring that: lib/albumart.js, lib/albuminfo.js and lib/labels.js
 * each held their OWN `mbLast` timestamp and their own 1.1s gap, so three
 * concurrent lookups could fire three requests in the same second and each one
 * would believe it had waited. Smart Picks makes that considerably worse — a
 * daily build asks for ~24 artist MBIDs and 3 tag rosters in one run — so the
 * gate moved here rather than becoming a fourth private copy.
 *
 * `mbWait()` serialises on a single promise chain rather than comparing
 * timestamps: two callers awaiting at the same moment both saw the same "last"
 * value and both went, which is exactly the race the per-module version had.
 * Chaining means the Nth caller waits for the (N-1)th to have had its turn.
 */
"use strict";

const MB_UA = process.env.MB_USER_AGENT ||
  "MusicD-LMS-Remote/1.0 (https://github.com/meltface-80/MusicD-LMS-Remote)";

const MB_GAP_MS = 1100;

let chain = Promise.resolve();
let last = 0;

// Take the next slot. Every caller across the app queues on the same chain, so
// the requests leave at most one per MB_GAP_MS however many are in flight.
function mbWait() {
  const mine = chain.then(async () => {
    const gap = Date.now() - last;
    if (gap < MB_GAP_MS) await new Promise(r => setTimeout(r, MB_GAP_MS - gap));
    last = Date.now();
  });
  // A rejection must not poison the queue for everyone behind it.
  chain = mine.catch(() => {});
  return mine;
}

// Lucene escaping for a search term.
const mbQuote = (s) => String(s).replace(/([+\-!(){}\[\]^"~*?:\\/])/g, "\\$1");

module.exports = { mbWait, mbQuote, MB_UA, MB_GAP_MS };
