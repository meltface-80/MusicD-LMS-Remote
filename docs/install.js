(function () {
  "use strict";

  // Published to the GitHub Container Registry by .github/workflows/image.yml
  // on every release — installs PULL it; nothing is cloned or built locally.
  var IMAGE = "ghcr.io/meltface-80/musicd-lms-remote:latest";
  // Container and volume names stay exactly as they always were, so an
  // existing install moves onto the published image keeping all its data.
  var NAME  = "musicd-lms-remote";

  var els = {
    modes:      document.getElementById("modes"),
    lmshost:    document.getElementById("lmshost"),
    lmshostReq: document.getElementById("lmshost-req"),
    lmshostHint:document.getElementById("lmshost-hint"),
    lmsport:    document.getElementById("lmsport"),
    appport:    document.getElementById("appport"),
    fAppport:   document.getElementById("f-appport"),
    mount:      document.getElementById("mount"),
    mountFields:document.getElementById("mount-fields"),
    musicpath:  document.getElementById("musicpath"),
    auth:       document.getElementById("auth"),
    authFields: document.getElementById("auth-fields"),
    lmsuser:    document.getElementById("lmsuser"),
    lmspass:    document.getElementById("lmspass"),
    cmd:        document.getElementById("cmd"),
    copy:       document.getElementById("copy"),
    tabRun:     document.getElementById("tab-run"),
    tabCompose: document.getElementById("tab-compose"),
    modeNote:   document.getElementById("mode-note"),
    afterNote:  document.getElementById("after-note")
  };

  var state = { mode: "same", tab: "run" };
  var plainCmd = "";

  function esc(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }
  function val(el, fallback) {
    var v = el.value.trim();
    return v === "" ? fallback : v;
  }

  var NOTES = {
    same:     "Host networking lets the app <b>auto-discover LMS</b> on the local network, so the LMS address is optional — set it anyway if discovery doesn't find your server.",
    separate: "UDP auto-discovery only works on the same LAN segment, so <b>set the LMS address explicitly</b> here.",
    macos:    "Docker Desktop on macOS can't use host networking. If LMS runs <b>on this same Mac</b>, use <code>host.docker.internal</code> as the LMS address; otherwise enter the LMS machine's IP."
  };

  // ---- command builders --------------------------------------------------
  function buildRun() {
    var mode = state.mode;
    var lines = [];   // [text, cls] pairs per token line
    var appport = val(els.appport, "3390");
    var lmsport = val(els.lmsport, "9000");
    var lmshost = els.lmshost.value.trim();

    var out = [];
    out.push({ t: "# Run it — --pull always fetches the current release every time this runs", c: "c" });

    var cmd = ["docker run -d \\"];
    cmd.push("  --name " + NAME + " \\");
    cmd.push("  --pull always \\");
    cmd.push("  --restart unless-stopped \\");
    cmd.push("  -e DEBUG=1 \\");

    if (mode === "same") {
      cmd.push("  --network host \\");
      if (lmshost && lmshost !== "192.168.1.50") {
        cmd.push("  -e LMS_HOST=" + lmshost + " \\");
        if (lmsport !== "9000") cmd.push("  -e LMS_PORT=" + lmsport + " \\");
      }
    } else {
      cmd.push("  -e LMS_HOST=" + (lmshost || "192.168.1.50") + " \\");
      cmd.push("  -e LMS_PORT=" + lmsport + " \\");
      cmd.push("  -p " + appport + ":3390 \\");
    }
    if (els.auth.checked) {
      if (els.lmsuser.value.trim()) cmd.push("  -e LMS_USER=" + els.lmsuser.value.trim() + " \\");
      if (els.lmspass.value.trim()) cmd.push("  -e LMS_PASS=" + els.lmspass.value.trim() + " \\");
    }
    cmd.push("  -v musicd-lms-remote-data:/app/data \\");
    if (els.mount.checked) {
      cmd.push("  -v " + val(els.musicpath, "/path/to/your/music") + ":/music:ro \\");
    }
    cmd.push("  " + IMAGE);

    out.push({ t: cmd.join("\n"), c: "" });
    out.push({ t: "", c: "" });
    out.push({ t: "# Follow the logs (DEBUG=1 makes them verbose — handy for troubleshooting)", c: "c" });
    out.push({ t: "docker logs -f " + NAME, c: "" });
    return out;
  }

  function buildCompose() {
    var mode = state.mode;
    var appport = val(els.appport, "3390");
    var lmsport = val(els.lmsport, "9000");
    var lmshost = els.lmshost.value.trim() || "192.168.1.50";

    var y = [];
    // The whole block is a runnable shell command: the heredoc writes
    // docker-compose.yml, then `docker compose up -d` pulls the published
    // image and starts it. (Emitting only the YAML contents used to strand
    // users: nothing created the file.)
    y.push("# Paste this whole block into a terminal — it writes docker-compose.yml,");
    y.push("# then pulls the published image and starts the container.");
    y.push("cat > docker-compose.yml <<'EOF'");
    y.push("services:");
    y.push("  musicd-lms-remote:");
    y.push("    image: " + IMAGE);
    y.push("    pull_policy: always   # every up fetches the current release");
    y.push("    container_name: " + NAME);
    y.push("    restart: unless-stopped");
    if (mode === "same") {
      y.push("    network_mode: host   # LMS auto-discovery on the local network");
    } else {
      y.push("    ports:");
      y.push("      - \"" + appport + ":3390\"");
    }
    var envs = [];
    // Verbose logging on by default — `docker compose logs -f` (below) then
    // shows why LMS connects/covers resolve/etc. Set DEBUG: "0" to quieten it.
    envs.push("      DEBUG: \"1\"");
    if (mode !== "same" || (els.lmshost.value.trim() && els.lmshost.value.trim() !== "192.168.1.50")) {
      envs.push("      LMS_HOST: \"" + lmshost + "\"");
      envs.push("      LMS_PORT: \"" + lmsport + "\"");
    }
    if (els.auth.checked) {
      if (els.lmsuser.value.trim()) envs.push("      LMS_USER: \"" + els.lmsuser.value.trim() + "\"");
      if (els.lmspass.value.trim()) envs.push("      LMS_PASS: \"" + els.lmspass.value.trim() + "\"");
    }
    if (envs.length) {
      y.push("    environment:");
      y = y.concat(envs);
    }
    y.push("    volumes:");
    y.push("      - musicd-lms-remote-data:/app/data");
    if (els.mount.checked) {
      y.push("      - " + val(els.musicpath, "/path/to/your/music") + ":/music:ro   # read-only music mount");
    }
    y.push("");
    y.push("volumes:");
    y.push("  musicd-lms-remote-data:");
    y.push("EOF");
    y.push("");
    y.push("docker compose up -d");
    y.push("");
    y.push("# Follow the logs (DEBUG is on above — handy for troubleshooting)");
    y.push("docker compose logs -f");
    return [{ t: y.join("\n"), c: "" }];
  }

  // very light highlighter for the pre block
  function highlight(text) {
    return esc(text)
      .replace(/^(#.*)$/gm, '<span class="c">$1</span>')
      .replace(/(--?[a-zA-Z-]+)/g, '<span class="k">$1</span>')
      .replace(/((?:LMS_HOST|LMS_PORT|LMS_USER|LMS_PASS|MUSIC_DIR|PORT|DEBUG)[=:])/g, '<span class="k">$1</span>');
  }

  function render() {
    var parts = state.tab === "run" ? buildRun() : buildCompose();
    plainCmd = parts.map(function (p) { return p.t; }).join("\n");
    els.cmd.innerHTML = parts.map(function (p) {
      return p.c === "c" ? '<span class="c">' + esc(p.t) + "</span>" : highlight(p.t);
    }).join("\n");

    els.modeNote.innerHTML = NOTES[state.mode];

    var appport = val(els.appport, "3390");
    var openPort = state.mode === "same" ? "3390" : appport;
    els.afterNote.innerHTML = "Then open <code>http://&lt;this-machine-IP&gt;:" + esc(openPort) +
      "</code>" + (state.mode === "macos" ? " (or <code>http://localhost:" + esc(appport) + "</code> on the Mac itself)" : "") + ".";

    syncUrl();
  }

  // keep the URL shareable: re-encode form state into the query string
  function syncUrl() {
    var p = new URLSearchParams();
    if (state.mode !== "same") p.set("mode", state.mode);
    var host = els.lmshost.value.trim();
    if (host && host !== "192.168.1.50" && host !== "host.docker.internal") p.set("lmshost", host);
    if (val(els.lmsport, "9000") !== "9000") p.set("lmsport", val(els.lmsport, "9000"));
    if (val(els.appport, "3390") !== "3390") p.set("port", val(els.appport, "3390"));
    if (els.mount.checked && val(els.musicpath, "") !== "/path/to/your/music") p.set("music", val(els.musicpath, ""));
    var qs = p.toString();
    history.replaceState(null, "", location.pathname + (qs ? "?" + qs : "") + location.hash);
  }

  function setMode(mode) {
    state.mode = mode;
    var labels = els.modes.querySelectorAll(".mode");
    for (var i = 0; i < labels.length; i++) {
      var sel = labels[i].getAttribute("data-mode") === mode;
      labels[i].classList.toggle("sel", sel);
      labels[i].querySelector("input").checked = sel;
    }
    // field visibility / hints per mode
    if (mode === "macos") {
      els.lmshost.value = els.lmshost.value.trim() === "192.168.1.50" || els.lmshost.value.trim() === ""
        ? "host.docker.internal" : els.lmshost.value;
      els.lmshostHint.textContent = "Use host.docker.internal if LMS runs on this same Mac, otherwise the LMS machine's IP.";
    } else {
      if (els.lmshost.value.trim() === "host.docker.internal") els.lmshost.value = "192.168.1.50";
      els.lmshostHint.textContent = mode === "same"
        ? "Optional with host networking — auto-discovery usually finds LMS. Set it if discovery fails."
        : "The address of the machine running Lyrion Music Server.";
    }
    els.lmshostReq.style.display = mode === "same" ? "none" : "inline";
    els.fAppport.classList.toggle("hidden", mode === "same");
    render();
  }

  function setTab(tab) {
    state.tab = tab;
    els.tabRun.classList.toggle("sel", tab === "run");
    els.tabCompose.classList.toggle("sel", tab === "compose");
    render();
  }

  // ---- wiring --------------------------------------------------------------
  els.modes.addEventListener("change", function (e) {
    if (e.target.name === "mode") setMode(e.target.value);
  });
  els.tabRun.addEventListener("click", function () { setTab("run"); });
  els.tabCompose.addEventListener("click", function () { setTab("compose"); });

  els.mount.addEventListener("change", function () {
    els.mountFields.classList.toggle("hidden", !els.mount.checked);
    render();
  });
  els.auth.addEventListener("change", function () {
    els.authFields.classList.toggle("hidden", !els.auth.checked);
    render();
  });

  ["lmshost", "lmsport", "appport", "musicpath", "lmsuser", "lmspass"].forEach(function (id) {
    els[id === "lmshost" ? "lmshost" : id].addEventListener("input", render);
  });

  els.copy.addEventListener("click", function () {
    function done() {
      els.copy.textContent = "Copied ✓";
      els.copy.classList.add("done");
      setTimeout(function () {
        els.copy.textContent = "Copy";
        els.copy.classList.remove("done");
      }, 1600);
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(plainCmd).then(done, fallbackCopy);
    } else fallbackCopy();
    function fallbackCopy() {
      var ta = document.createElement("textarea");
      ta.value = plainCmd;
      ta.style.position = "fixed"; ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand("copy"); done(); } catch (e) {}
      document.body.removeChild(ta);
    }
  });

  // ---- URL query prefill (e.g. ?mode=separate&lmshost=192.168.1.20&port=8080&music=/srv/music)
  var q = new URLSearchParams(location.search);
  if (q.get("lmshost")) els.lmshost.value = q.get("lmshost");
  if (q.get("lmsport")) els.lmsport.value = q.get("lmsport");
  if (q.get("port"))    els.appport.value = q.get("port");
  if (q.get("music"))   { els.mount.checked = true; els.mountFields.classList.remove("hidden"); els.musicpath.value = q.get("music"); }
  var qm = q.get("mode");
  setMode(qm === "separate" || qm === "macos" ? qm : "same");
})();
