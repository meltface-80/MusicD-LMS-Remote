/*
 * shelf-touch.js — the Shelf's finger, wheel and keys: a swipe moves it, a swipe held out keeps it turning, a hard
 * flick spins it, a tap brings a case to the front or turns the front one over; the wheel and a trackpad step it; a
 * TV remote's or a keyboard's arrows step it, Shift+arrow ten, Enter turns over, S spins.
 *
 * Moved out of shelf.js (the Roon build's, where it is one file) so each file here stays under this repo's length
 * gate: made by shelf.js with the hub, as the page's other parts are, and reading the shelf's state through it.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 *
 *   const touch = ShelfTouch.create(hub)
 *   touch.stepDrag(now, dt)   shelf.js's frame, while a finger holds the shelf
 *   touch.clearWheel()        shelf.js's stopSpin
 *   touch.shuttleOn(side)     the held swipe's arrows, lit or not
 *   touch.SWIPE               how far a finger goes for one album, against a cover's step
 */
(() => {
  "use strict";
  window.ShelfTouch = {
    create(h) {
      const { $, clamp, live, stage } = h;
      // How far a finger goes for one album, against a cover's own step (from Mandarin: 1.4, the shelf asked to be
      // less quick to move). A flick that spins it is firmer for the same reason. The wheel and a trackpad are as before.
      const SWIPE = 1.4;
      let wheelTimer = 0;
      const rubber = (x) => { if (x < 0) return x * 0.3; if (x > h.N - 1) return h.N - 1 + (x - (h.N - 1)) * 0.3; return x; };
      function shuttleOn(side) { $("#sh-l").classList.toggle("on", side === "l"); $("#sh-r").classList.toggle("on", side === "r"); }

      // The tracks are chosen on the album view (shelf-back.js), which has its own presses: the stage's pointer is the
      // shelf's alone.
      stage.addEventListener("pointerdown", (e) => {
        if ((e.pointerType === "mouse" && e.button !== 0) || !h.N) return;
        try { stage.setPointerCapture(e.pointerId); } catch (err) { /* a pointer the browser no longer tracks: the drag still works while it stays over the stage */ }
        const caught = h.mode === "spin" || (h.mode === "spring" && Math.abs(h.vp) > 0.003);
        h.stopSpin();
        h.player.closeZones();
        h.player.volClose();
        const now = performance.now();
        h.drag = { id: e.pointerId, x0: e.clientX, x: e.clientX, p0: h.p, t0: now, still: now, moved: false, caught, samples: [[now, e.clientX]], shuttle: false, acc: 0, rate: 0 };
        h.mode = "drag"; h.vp = 0;
        stage.classList.add("grabbing");
        h.kick();
      });
      stage.addEventListener("pointermove", (e) => {
        const drag = h.drag;
        if (!drag || e.pointerId !== drag.id) return;
        const now = performance.now();
        if (Math.abs(e.clientX - drag.x) > 1.5) drag.still = now;
        drag.x = e.clientX;
        drag.samples.push([now, e.clientX]);
        while (drag.samples.length > 2 && now - drag.samples[0][0] > 120) drag.samples.shift();
        if (!drag.moved && Math.abs(drag.x - drag.x0) > 8) { drag.moved = true; h.flippedV = null; }
        applyDrag();
        h.kick();
      });
      function applyDrag() {
        const d = h.drag;
        const raw = d.p0 - (d.x - d.x0) / (h.stepPx * SWIPE) + d.acc;
        h.p = h.wrap() ? raw : rubber(raw);
      }
      // Swipe and HOLD: once the finger is well out and has stopped, the shelf keeps turning that way, faster the
      // further out it is held, until the finger lifts.
      function stepDrag(now, dt) {
        const drag = h.drag;
        if (!drag) { h.mode = "idle"; return; }   // the finger is gone however it went: nothing to follow
        const dx = drag.x - drag.x0;
        const far = Math.abs(dx) > Math.max(80, h.W * 0.2);
        if (!drag.shuttle && drag.moved && far && h.wrap() && now - drag.still > 200) drag.shuttle = true;
        if (drag.shuttle) {
          const lim = Math.max(40, h.W * 0.08);
          let rate = 0;
          if (Math.abs(dx) > lim) rate = -Math.sign(dx) * (3 + 15 * Math.pow(clamp((Math.abs(dx) - lim) / (h.W * 0.35), 0, 1), 1.3));
          drag.rate = rate;
          drag.acc += (rate * dt) / 1000;
          shuttleOn(rate === 0 ? null : dx < 0 ? "l" : "r");
          applyDrag();
        }
      }
      function endDrag(e) {
        const d = h.drag;
        if (!d || e.pointerId !== d.id) return;
        h.drag = null;
        stage.classList.remove("grabbing"); shuttleOn(null);
        if (e.type === "pointercancel") { h.settleTo(Math.round(h.p)); return; }
        const now = performance.now();
        const dx = d.x - d.x0;
        let v = 0;   // px per ms over the last ~100 ms; a finger that stopped before lifting has none
        const s = d.samples.filter((q) => now - q[0] < 100);
        if (s.length >= 2 && now - s[s.length - 1][0] < 80) v = (s[s.length - 1][1] - s[0][1]) / Math.max(8, s[s.length - 1][0] - s[0][0]);
        if (!d.moved) {
          if (d.caught) h.settleTo(Math.round(h.p)); else tapAt(e.clientX, e.clientY);
          return;
        }
        if (d.shuttle) { h.vp = d.rate / 1000; h.settleTo(Math.round(h.p + Math.sign(d.rate) * 0.4)); return; }
        if (Math.abs(v) >= 1.7 && Math.abs(dx) >= Math.max(140, h.W * 0.16) && now - d.t0 < 700) { h.spin(v < 0 ? 1 : -1, Math.abs(v)); return; }
        h.vp = -v / (h.stepPx * SWIPE);
        if (Math.abs(dx) < clamp(h.W * 0.16, 80, 240)) { h.settleTo(Math.round(d.p0) + (dx < 0 ? 1 : -1)); return; }
        h.settleTo(Math.round(h.p + h.vp * 240));
      }
      stage.addEventListener("pointerup", endDrag);
      stage.addEventListener("pointercancel", endDrag);
      // Where capture failed, the finger can lift off the stage: the window still hears it, so a held swipe never
      // keeps turning on its own.
      window.addEventListener("pointerup", endDrag);
      window.addEventListener("pointercancel", endDrag);
      stage.addEventListener("lostpointercapture", (e) => { const d = h.drag; if (d && e.pointerId === d.id) endDrag({ type: "pointerup", pointerId: e.pointerId, clientX: d.x, clientY: 0 }); });
      // Which cover is under the finger. Measured from each item's own projected box rather than elementFromPoint,
      // which does not hit-test reliably inside a preserve-3d scene; where boxes overlap, the one nearest the front wins.
      function itemAt(x, y) {
        let best = null, bestD = Infinity;
        for (const [v, el] of live) {
          if (el.style.visibility === "hidden") continue;
          const d = v - h.p, ad = Math.abs(d);
          let face = el._front;
          if (ad >= 0.5) face = d < 0 ? el._l : el._r;
          const r = face.getBoundingClientRect();
          if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom && ad < bestD) { best = v; bestD = ad; }
        }
        return best;
      }
      function turnOver(v) {
        if (h.mode === "spin") h.stopSpin();
        h.flippedV = h.flippedV === v ? null : v;
        const el = h.flippedV !== null ? live.get(v) : null;
        if (el && el._album) {
          const a = el._album, img = el._front.querySelector("img");
          if (a.k && !h.colourOf.has(a.k) && img && img.complete && img.naturalWidth) {
            const c = h.averageColour(img);
            if (c) { h.colourOf.set(a.k, c); h.paintColour(el, a); }
          }
          el._backOn = true;
          el._back.innerHTML = h.back.backHTML(a);
        }
        // still drifting in from a swipe: finish on the album, squarely
        if (Math.abs(v - h.p) > 1e-3) { h.vp = 0; h.settleTo(v); } else h.kick();
      }
      function tapAt(x, y) {
        const v = itemAt(x, y);
        if (v === null) { h.settleTo(Math.round(h.p)); return; }
        if (Math.abs(v - h.p) < 0.5) turnOver(v);
        else h.settleTo(v);
      }
      stage.addEventListener("wheel", (e) => {
        if (!h.N) return;
        e.preventDefault();
        if (h.drag) return;   // a finger is on the shelf: it decides
        if (h.mode === "spin") h.stopSpin();
        const delta = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
        const notch = e.deltaMode === 1 || (e.deltaX === 0 && Math.abs(e.deltaY) >= 50);
        if (notch) { const base = h.mode === "spring" ? h.target : Math.round(h.p); h.vp = 0; h.settleTo(base + Math.sign(delta)); return; }
        h.mode = "wheel";
        h.p += delta / (h.stepPx * 0.9);
        if (!h.wrap()) h.p = rubber(h.p);
        clearTimeout(wheelTimer);
        wheelTimer = setTimeout(() => { h.vp = 0; h.settleTo(Math.round(h.p)); }, 120);
        h.kick();
      }, { passive: false });
      stage.addEventListener("keydown", (e) => {
        if (!h.N) return;
        // with the album view open, a step shows the next album in it rather than closing it
        const step = (n) => { if (h.mode === "spin") h.stopSpin(); const base = h.mode === "spring" ? h.target : Math.round(h.p); h.vp = 0; h.settleTo(base + n); if (h.flippedV !== null) h.flippedV = h.target; };
        if (e.key === "ArrowRight") { step(e.shiftKey ? 10 : 1); e.preventDefault(); }
        else if (e.key === "ArrowLeft") { step(e.shiftKey ? -10 : -1); e.preventDefault(); }
        else if (e.key === "Enter" || e.key === " ") { turnOver(Math.round(h.p)); e.preventDefault(); }
        else if (e.key === "s" || e.key === "S") h.spin(1, 2.4);
      });
      $("#spin-btn").addEventListener("click", () => h.spin(1, 2.4));
      return { stepDrag, shuttleOn, clearWheel: () => clearTimeout(wheelTimer), SWIPE };
    },
  };
})();
