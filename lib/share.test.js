"use strict";

/* Playlist sharing. The format is an INTEROP CONTRACT with the sibling Roon
 * build — blobs are meant to pass in both directions — so the tests here pin
 * the wire shape, not just "it round-trips with itself". A change that keeps
 * encode/decode self-consistent while altering a field name or a coercion
 * would pass a naive round-trip test and silently break every share.
 */

const zlib = require("zlib");
const share = require("./share");
const { buildShareDoc, encodeSharePayload, decodeSharePayload,
        shareTrackEntry, shareText, shareInt, shareUriList } = share;

let pass = 0, fail = 0;
function ok(label, cond, extra) {
  if (cond) { pass++; console.log("  [PASS]", label); }
  else { fail++; console.log("  [FAIL]", label, extra !== undefined ? "— " + JSON.stringify(extra) : ""); }
}

const enc = (meta, tracks) => encodeSharePayload(buildShareDoc(meta, tracks, "1.0.0").doc);

// ---- coercion -------------------------------------------------------------
{
  ok("text collapses whitespace and trims", shareText("  a \n b  ") === "a b");
  ok("text trims again after the clamp", shareText("ab cdef", 3) === "ab");
  ok("non-strings are empty", shareText(42) === "" && shareText(null) === "");
  ok("int honours its range", shareInt("5", 1, 9) === 5 && shareInt("50", 1, 9) === null);
  ok("int rejects nonsense", shareInt("abc", 1, 9) === null);
  // A scheme is what makes it a URI rather than free text.
  ok("uri list needs a scheme",
     JSON.stringify(shareUriList(["https://x/y", "not a uri", "spotify:track:1"]))
       === JSON.stringify(["https://x/y", "spotify:track:1"]));
  ok("uri list dedupes and caps at 4",
     shareUriList(["a:1", "a:1", "b:2", "c:3", "d:4", "e:5"]).length === 4);
}

// ---- the track entry's wire shape ----------------------------------------
{
  const e = shareTrackEntry({ title: "Gigantic", artist: "Pixies", album: "Surfer Rosa",
                              track_no: 2, duration_ms: 191000, year: 1988, disc: 1 });
  // JSPF names, not our own: artist→creator, track_no→trackNum, duration_ms→duration.
  ok("artist is emitted as creator", e.creator === "Pixies" && e.artist === undefined);
  ok("track_no is emitted as trackNum", e.trackNum === 2 && e.track_no === undefined);
  ok("duration_ms is emitted as duration", e.duration === 191000);
  ok("year/disc go in the track extension namespace",
     e.extension["https://musicbrainz.org/doc/jspf#track"].additional_metadata.year === 1988);

  // Empty is OMITTED, never "" — an empty string is a positive claim.
  const bare = shareTrackEntry({ title: "Goo" });
  ok("absent fields are omitted, not blanked",
     !("creator" in bare) && !("album" in bare) && !("extension" in bare), Object.keys(bare));
  ok("a titleless entry is refused", shareTrackEntry({ artist: "X" }) === null);
  ok("a non-object is refused", shareTrackEntry("nope") === null);

  // Nothing may pass through from the caller: a blob must describe the music,
  // never our library.
  const leaky = shareTrackEntry({ title: "T", offset: 41, image_key: "art-9", lms_id: 7, __proto__: {} });
  ok("local identifiers cannot ride along",
     !("offset" in leaky) && !("image_key" in leaky) && !("lms_id" in leaky), Object.keys(leaky));
}

// ---- the document ---------------------------------------------------------
{
  const built = buildShareDoc({ name: "Road Trip" }, [{ title: "A" }, { title: "B" }], "1.0.9");
  const pl = built.doc.playlist;
  ok("document is { playlist: { … } }", !!pl && Array.isArray(pl.track));
  ok("title carries through", pl.title === "Road Trip");
  ok("an unnamed share still gets a title",
     buildShareDoc({}, [{ title: "A" }], "1").doc.playlist.title === "Shared playlist");
  ok("the generator is stamped in the playlist extension",
     pl.extension["https://musicbrainz.org/doc/jspf#playlist"].additional_metadata.generator_version === "1.0.9");
  // An ABSENT track list means malformed; an EMPTY one means a playlist with no
  // tracks. Pruning must not collapse the second into the first.
  ok("an empty playlist still carries a track array",
     Array.isArray(buildShareDoc({ name: "X" }, [], "1").doc.playlist.track));
  ok("untitled entries are counted as skipped, not fatal",
     buildShareDoc({}, [{ title: "A" }, { artist: "no title" }], "1").skipped === 1);
  ok("truncated means the CAP stopped us",
     buildShareDoc({}, Array.from({ length: 3 }, () => ({ title: "t" })), "1").truncated === false);
}

// ---- round trip -----------------------------------------------------------
{
  const blob = enc({ name: "Road Trip" }, [{ title: "A", artist: "B", album: "C", track_no: 1 }]);
  ok("blob carries the MDRP1 marker", blob.startsWith("MDRP1:"));
  const back = decodeSharePayload(blob);
  ok("round-trips", back.playlist.track[0].title === "A" && back.playlist.track[0].creator === "B");
}

// ---- the decoder's tolerance (this is what makes shares survive transit) ---
{
  const blob = enc({ name: "P" }, [{ title: "A", album: "C" }]);
  const titleOf = (b) => decodeSharePayload(b).playlist.track[0].title;

  // Line wrapping — every mail client does this.
  const wrapped = blob.replace(/(.{40})/g, "$1\n");
  ok("survives line wrapping", titleOf(wrapped) === "A");
  // Mail quote markers.
  ok("survives mail quoting", titleOf(wrapped.replace(/^/gm, "> ")) === "A");
  // The sender's own words around it.
  ok("survives surrounding prose", titleOf("Here's that playlist:\n\n" + blob + "\n\nEnjoy!") === "A");
  // iOS autocorrect lowercases the marker on paste.
  ok("survives a lowercased marker", titleOf(blob.replace("MDRP1:", "mdrp1:")) === "A");
  // …but the PAYLOAD is base64url, where case carries meaning.
  let lowered = false;
  try { decodeSharePayload("MDRP1:" + blob.slice(6).toLowerCase()); } catch (e) { lowered = true; }
  ok("a lowercased payload is refused, not guessed at", lowered);

  // The CRC shave is bounded: 41 lengths (0..40).
  ok("shaves up to 40 trailing characters", titleOf(blob + "Enjoy") === "A");
  let tooMuch = false;
  try { decodeSharePayload(blob + "x".repeat(60)); } catch (e) { tooMuch = true; }
  ok("more trailing junk than the shave covers fails rather than guessing", tooMuch);
}

// ---- decoder rejections ---------------------------------------------------
{
  const err = (fn) => { try { fn(); return ""; } catch (e) { return e.message; } };
  ok("no marker → says so", /MDRP1/.test(err(() => decodeSharePayload("just some text"))));
  ok("marker with nothing after → empty", /empty/.test(err(() => decodeSharePayload("MDRP1:"))));
  ok("garbage payload → damaged", /damaged/.test(err(() => decodeSharePayload("MDRP1:zzzz"))));
  // Valid gzip, valid JSON, but not a playlist.
  const notPl = "MDRP1:" + zlib.gzipSync(Buffer.from('{"hello":1}')).toString("base64url");
  ok("valid gzip that isn't a playlist → no tracks", /no tracks/.test(err(() => decodeSharePayload(notPl))));
  // A playlist with zero tracks is VALID — a different fact from malformed.
  const empty = "MDRP1:" + zlib.gzipSync(Buffer.from('{"playlist":{"title":"X","track":[]}}')).toString("base64url");
  ok("an empty playlist decodes rather than erroring", decodeSharePayload(empty).playlist.track.length === 0);
}

console.log((fail ? "\n" + fail + " FAILED, " : "\n") + pass + "/" + (pass + fail) + " share tests passed.");
process.exit(fail ? 1 : 0);
