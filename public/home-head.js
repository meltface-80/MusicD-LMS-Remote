/*
 * home-head.js — the head of Home, as Mallard's (HomeScreen.kt Greeting): the date in small brass capitals and the
 * time of day in the serif under it ("Good evening"), painted again each minute so it turns over while open. The strip
 * under it (Random Album and today's album) is filled by app.js's renderHomeUnplayed.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 */
(() => {
  const date = document.getElementById("home-greeting-date");
  const text = document.getElementById("home-greeting-text");
  if (!date || !text) return;
  function paint() {
    const now = new Date(), h = now.getHours();
    // Mallard's order, whatever the browser's locale: "Friday 27 November" (shown in capitals by the theme).
    const part = (o) => now.toLocaleDateString(undefined, o);
    date.textContent = part({ weekday: "long" }) + " " + now.getDate() + " " + part({ month: "long" });
    text.textContent = h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
  }
  paint();
  setInterval(paint, 60000);
})();
