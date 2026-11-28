// site.js — shared by every page: the feature cards' ⓘ, and the Copy buttons beside commands.
// The feature cards' ⓘ buttons: each opens its card's how-to, and closes it again.
document.addEventListener("click", function (e) {
  var btn = e.target.closest && e.target.closest(".card h3 .info");
  if (!btn) return;
  var how = document.getElementById(btn.getAttribute("aria-controls"));
  if (!how) return;
  var open = btn.getAttribute("aria-expanded") !== "true";
  btn.setAttribute("aria-expanded", open ? "true" : "false");
  how.hidden = !open;
});
document.querySelectorAll("[data-copy]").forEach(function (btn) {
  btn.addEventListener("click", function () {
    if (btn.classList.contains("done")) return;
    var text = btn.parentNode.querySelector("pre").textContent;
    // "Copied", and back to "Copy", in the page's language (i18n.js sets data-copied and the button's own words).
    var was = btn.innerHTML;
    var done = function () { btn.textContent = btn.getAttribute("data-copied") || "Copied"; btn.classList.add("done");
      setTimeout(function () { btn.innerHTML = was; btn.classList.remove("done"); }, 1600); };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, function () {});
  });
});
