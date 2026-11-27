/*
 * shelf-order.js — the orders the Shelf files its albums in, beside the artist order the server sends: by label (and
 * catalogue number), by catalogue number alone, by year, or shuffled. Pure: moved out of shelf-pick.js to keep each
 * file under this repo's length gate. Labels and numbers in a shop's order: "en", numbers as numbers, case alike —
 * "WARP 61" before "WARP 123". Unlabelled, unnumbered and undated albums go last.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 *
 *   const o = ShelfOrder.create(() => labels, rng)
 *   o.decadeOf(a), o.labelName(a), o.byLabel, o.byCat, o.byYear, o.shuffle(arr, seed)
 */
(() => {
  "use strict";
  window.ShelfOrder = {
    /** @param {() => Array<{name: string}>} labelsNow @param {(seed: number) => () => number} rng */
    create(labelsNow, rng) {
      const natural = new Intl.Collator("en", { numeric: true, sensitivity: "base" });
      /** An album's decade, as its Years tile names it: "1960" for 1960–1969; "none" for one no source dated. */
      const decadeOf = (a) => (a.y ? String(Math.floor(a.y / 10) * 10) : "none");
      const labelName = (a) => { const l = labelsNow(); return a.l === null || a.l === undefined ? "" : l[a.l] ? l[a.l].name : ""; };
      const byLabel = (x, y) => {
        if ((x.l === null) !== (y.l === null)) return x.l === null ? 1 : -1;
        return natural.compare(labelName(x), labelName(y)) || natural.compare(x.c || "￿", y.c || "￿") || natural.compare(x.t, y.t);
      };
      const byCat = (x, y) => (!x.c !== !y.c ? (x.c ? -1 : 1) : natural.compare(x.c || "", y.c || "") || natural.compare(x.t, y.t));
      const byYear = (x, y) => (!x.y !== !y.y ? (x.y ? -1 : 1) : (x.y || 0) - (y.y || 0));
      function shuffle(arr, seed) {
        const r = rng(seed);
        for (let i = arr.length - 1; i > 0; i--) { const j = Math.floor(r() * (i + 1)); const t = arr[i]; arr[i] = arr[j]; arr[j] = t; }
        return arr;
      }
      return { decadeOf, labelName, byLabel, byCat, byYear, shuffle };
    },
  };
})();
