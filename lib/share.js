"use strict";

/* Playlist sharing — the `MDRP1:` interchange format.
 *
 * A share is a description of MUSIC, never the music itself: titles, artists,
 * albums and a few optional identifiers. Whoever imports it gets whatever their
 * own library can match.
 *
 * Wire format:  MDRP1:<base64url(gzip(JSON, level 9))>
 * Document:     JSPF / ListenBrainz dialect, `{ playlist: { … , track: [] } }`
 *
 * THIS FILE IS AN INTEROP CONTRACT with the sibling Roon build (MusicD Remote),
 * which reads and writes the same blobs. Every coercion here — the whitespace
 * collapsing, the field names, the pruning of empty values, the marker search,
 * the CRC shave — is matched to that implementation. Change the semantics and
 * shares stop round-tripping between the two apps. If the format ever needs to
 * mean something new, bump the magic to MDRP2 rather than redefining MDRP1: the
 * decoder's whole design is to reject what it cannot positively identify.
 *
 * Nothing in the document is app-specific. Local identity (LMS album ids, our
 * own offsets, image keys) never enters a blob — a share that carried them
 * would be describing OUR library rather than the music.
 */

const zlib = require("zlib");

const MAGIC       = "MDRP1";
const TRACK_MAX   = 2000;   // most tracks one blob will carry
const INPUT_MAX   = 5000;   // entries the encoder will WALK — higher than the
                            // output cap so a caller whose list holds untitled
                            // rows still gets everything it CAN share, rather
                            // than being refused over rows that never counted
const TEXT_MAX    = 500;
const NAME_MAX    = 200;
const URI_MAX     = 4;
const NS_TRACK    = "https://musicbrainz.org/doc/jspf#track";
const NS_PLAYLIST = "https://musicbrainz.org/doc/jspf#playlist";

// ---- coercion -------------------------------------------------------------

function shareText(v, max) {
  if (typeof v !== "string") return "";
  // Trimmed AFTER the clamp as well as before it: slicing mid-string can leave
  // a trailing space, and in a file that is never re-issued that is a different
  // canonical key from the same title without one.
  return v.replace(/\s+/g, " ").trim().slice(0, max || TEXT_MAX).trim();
}

function shareInt(v, min, max) {
  const n = parseInt(v, 10);
  return Number.isFinite(n) && n >= min && n <= max ? n : null;
}

function shareUriList(v) {
  if (!Array.isArray(v)) return [];
  const out = [];
  for (const item of v) {
    if (typeof item !== "string") continue;
    const s = item.trim();
    // A scheme is what makes it a URI rather than free text a reader would
    // silently mistake for one.
    if (!/^[a-z][a-z0-9+.-]*:/i.test(s)) continue;
    if (s.length > TEXT_MAX) continue;
    if (!out.includes(s)) out.push(s);
    if (out.length >= URI_MAX) break;
  }
  return out;
}

// Empty values are OMITTED, never emitted as "". In JSPF an empty string is a
// positive claim that the field is empty, which is a different fact from not
// knowing it.
function sharePrune(obj) {
  const out = {};
  for (const k of Object.keys(obj)) {
    const v = obj[k];
    if (v === null || v === undefined || v === "") continue;
    if (Array.isArray(v) && !v.length) continue;
    if (typeof v === "object" && !Array.isArray(v) && !Object.keys(v).length) continue;
    out[k] = v;
  }
  return out;
}

// ---- encode ---------------------------------------------------------------

// One track. Built as a fresh literal from a fixed field list — nothing is
// passed through from the caller, so no local identifier can ride along into a
// blob however the caller shaped its input.
function shareTrackEntry(t) {
  if (!t || typeof t !== "object") return null;
  const title = shareText(t.title, TEXT_MAX);
  if (!title) return null;

  // Service-neutral identifiers. We fill what LMS gives us; a reader that
  // doesn't understand one ignores it.
  const extra = sharePrune({
    isrc:           shareText(t.isrc, 32),
    upc:            shareText(t.upc, 32),
    qobuz_album_id: shareText(t.qobuz_album_id, 64),
    tidal_album_id: shareText(t.tidal_album_id, 64),
    year:           shareInt(t.year, 1000, 2999),
    disc:           shareInt(t.disc, 1, 99),
  });
  const ext = Object.keys(extra).length ? { [NS_TRACK]: { additional_metadata: extra } } : null;

  return sharePrune({
    title,
    creator:  shareText(t.artist, TEXT_MAX),
    album:    shareText(t.album, TEXT_MAX),
    trackNum: shareInt(t.track_no, 1, 999),
    // JSPF durations are MILLISECONDS. LMS gives us track length in seconds, so
    // the caller scales; filling this is strictly additive (the Roon build has
    // no track length to put here and simply omits it) and it is what a
    // duration-gated match would read.
    duration:   shareInt(t.duration_ms, 1, 24 * 60 * 60 * 1000),
    identifier: shareUriList(t.identifier),
    location:   shareUriList(t.location),
    extension:  ext,
  });
}

function buildShareDoc(meta, entries, version) {
  const list = Array.isArray(entries) ? entries : [];
  const track = [];
  let skipped = 0;
  let truncated = false;
  for (const e of list) {
    if (track.length >= TRACK_MAX) { truncated = true; break; }
    const one = shareTrackEntry(e);
    if (one) track.push(one);
    else skipped++;
  }
  const playlist = sharePrune({
    title:      shareText(meta && meta.name, NAME_MAX) || "Shared playlist",
    annotation: shareText(meta && meta.annotation, TEXT_MAX),
    date:       new Date().toISOString(),
    extension: {
      [NS_PLAYLIST]: {
        additional_metadata: {
          generator: "MusicD LMS Remote",
          generator_version: String(version || ""),
        },
      },
    },
  });
  // Assigned AFTER pruning, because pruning drops empty arrays and the track
  // list is the one key that must always be present: an absent list means
  // "malformed", an empty one means "a playlist with no tracks". A reader has
  // to be able to tell those apart.
  playlist.track = track;
  return { doc: { playlist }, track_count: track.length, skipped, truncated };
}

// The magic is a version stamp, so a reader can reject a blob it does not
// understand instead of guessing at it.
function encodeSharePayload(doc) {
  const gz = zlib.gzipSync(Buffer.from(JSON.stringify(doc), "utf8"), { level: 9 });
  return MAGIC + ":" + gz.toString("base64url");
}

// ---- decode ---------------------------------------------------------------

function decodeSharePayload(blob) {
  // Deliberately forgiving about everything EXCEPT the payload itself.
  //
  // A blob reaches here through clipboards, chat apps, mail clients and
  // hand-selection on a phone. All of those wrap lines, and mail inserts
  // newlines mid-string and quote markers at the start of each one. So:
  // collapse ALL whitespace, find the magic wherever it sits, and keep only
  // base64url characters after it. None of that can turn a bad blob into a good
  // one — the gzip and JSON steps below are still the real check.
  const compact = String(blob || "").replace(/\s+/g, "");
  // Matched case-INSENSITIVELY because iOS autocorrect lowercases the marker on
  // paste. The payload's own case is left untouched — base64url is
  // case-significant, so nothing after the marker may be normalised.
  const at = compact.toUpperCase().indexOf(MAGIC + ":");
  if (at < 0) {
    throw new Error(`That doesn't look like a MusicD playlist — it should contain "${MAGIC}:"`);
  }
  const payload = compact.slice(at + MAGIC.length + 1).replace(/[^A-Za-z0-9_-]/g, "");
  if (!payload) throw new Error("That playlist is empty — nothing followed the marker");

  // Trailing prose cannot be separated by inspection: "Enjoy" is as valid a
  // base64url string as the payload is, so stripping non-base64url characters
  // leaves the sender's own words glued to the end. What CAN separate them is
  // gzip's checksum — only the exact right byte sequence passes it. So on
  // failure, shave characters off the end and retry, bounded. A wrong length
  // fails the CRC rather than yielding plausible garbage, which is what makes
  // this safe rather than a guess.
  let json = null;
  for (let cut = 0; cut <= 40 && cut < payload.length; cut++) {
    try {
      json = zlib.gunzipSync(Buffer.from(payload.slice(0, payload.length - cut), "base64url"))
                 .toString("utf8");
      break;
    } catch (e) { /* not this length — try one shorter */ }
  }
  if (json === null) throw new Error("That playlist is damaged — it may have been cut short in transit");

  let doc;
  try { doc = JSON.parse(json); }
  catch (e) { throw new Error("That playlist is damaged — the contents didn't parse"); }
  if (!doc || !doc.playlist || !Array.isArray(doc.playlist.track)) {
    throw new Error("That playlist has no tracks in it");
  }
  return doc;
}

module.exports = {
  MAGIC, TRACK_MAX, INPUT_MAX, TEXT_MAX, NAME_MAX,
  shareText, shareInt, shareUriList, sharePrune,
  shareTrackEntry, buildShareDoc, encodeSharePayload, decodeSharePayload,
};
