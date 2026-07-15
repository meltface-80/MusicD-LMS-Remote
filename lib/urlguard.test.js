"use strict";
const assert = require("assert");
const { assertPublicUrl, ipIsPrivate } = require("./urlguard");

let pass = 0, fail = 0;
const ok = (l) => { pass++; console.log("  [PASS]", l); };
const bad = (l, e) => { fail++; console.log("  [FAIL]", l, "—", e); };

// ---- ipIsPrivate ----
const priv = ["127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
  "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fe80::1", "fd00::1", "::ffff:127.0.0.1"];
const pub = ["8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "192.169.0.1", "100.63.0.1", "2606:4700:4700::1111"];
for (const ip of priv) (ipIsPrivate(ip) ? ok : (l) => bad(l, "should be private"))("private: " + ip);
for (const ip of pub) (!ipIsPrivate(ip) ? ok : (l) => bad(l, "should be public"))("public: " + ip);

(async () => {
  // ---- assertPublicUrl: rejects ----
  const rejects = [
    ["http://127.0.0.1/x", "loopback literal"],
    ["http://169.254.169.254/latest/meta-data/", "cloud metadata"],
    ["http://192.168.0.10/logo.png", "private literal"],
    ["http://[::1]/x", "ipv6 loopback"],
    ["ftp://example.com/x", "non-http scheme"],
    ["file:///etc/passwd", "file scheme"],
    ["not a url", "garbage"],
    ["http://this-host-should-not-exist.invalid/x", "unresolvable"],
  ];
  for (const [u, why] of rejects) {
    try { await assertPublicUrl(u); bad("reject " + why, "was allowed: " + u); }
    catch (e) { ok("rejects " + why + " (" + e.message + ")"); }
  }

  // ---- assertPublicUrl: allows a public literal (no DNS needed) ----
  try { await assertPublicUrl("https://8.8.8.8/logo.png"); ok("allows public IP literal"); }
  catch (e) { bad("allow public IP literal", e.message); }

  console.log(`\n${pass}/${pass + fail} urlguard tests passed.`);
  process.exit(fail ? 1 : 0);
})();
