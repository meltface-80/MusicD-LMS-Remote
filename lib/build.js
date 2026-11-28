// @ts-check
"use strict";
/*
 * Which build this is. A release build reports package.json's version and offers the in-app update to the next
 * release. A TEST build (the ":dev" image, made from the dev branch by .github/workflows/server-dev.yml) is stamped
 * with APP_VERSION (tools/test-version.js: "1.0.87-t3", after the release before it, before the next) and
 * APP_CHANNEL=dev. A test build never offers the in-app update: that reads releases/latest, which would "update" a
 * test build back to the last release. It is updated the way it was installed, by pulling the ":dev" image again.
 */

/**
 * @param {NodeJS.ProcessEnv} env @param {{ version: string }} pkg
 * @returns {{ version: string, channel: "dev"|"release" }}
 */
function buildInfo(env, pkg) {
  const channel = env.APP_CHANNEL === "dev" ? "dev" : "release";
  const stamped = String(env.APP_VERSION || "").trim();
  return { version: /^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$/.test(stamped) ? stamped : pkg.version, channel };
}

const DEV_NOTE = "This is a test build from the dev branch. Update it by pulling the :dev image again " +
  "(docker pull ghcr.io/meltface-80/musicd-lms-remote:dev, then recreate the container).";

/**
 * What /api/update/status and /check answer: the updater's state for a release build; for a test build, never an
 * update on offer, and why.
 * @param {{ version: string, channel: string }} build @param {Record<string, any>} status the updater's getStatus()
 * @returns {Record<string, any>}
 */
function updateStatus(build, status) {
  if (build.channel === "dev") {
    return { ...status, available: false, latest: build.version, current: build.version, channel: "dev", notes: DEV_NOTE, is_docker: true };
  }
  return { ...status, current: build.version, channel: "release", is_docker: true };
}

module.exports = { buildInfo, updateStatus, DEV_NOTE };
