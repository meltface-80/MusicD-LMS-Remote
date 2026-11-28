# The built-in Tailscale engine (musicdnet/, Go on tailscale.com/tsnet; lib/tailscale.js runs it): cross-compiled on
# the build machine for each image platform (amd64, arm64, arm/v7) with no cgo, so it is one static file and nothing
# is downloaded at run time. Userspace networking: no NET_ADMIN, no /dev/net/tun, plain bridge networking works.
FROM --platform=$BUILDPLATFORM golang:1.26-bookworm AS engine
ARG TARGETOS
ARG TARGETARCH
ARG TARGETVARIANT
WORKDIR /src
COPY musicdnet/go.mod musicdnet/go.sum ./
RUN go mod download
COPY musicdnet/ ./
RUN GOARM="${TARGETVARIANT#v}" CGO_ENABLED=0 GOOS="$TARGETOS" GOARCH="$TARGETARCH" \
    go build -trimpath -ldflags="-s -w" -o /out/musicdnet .

FROM node:20-bookworm-slim

WORKDIR /app

# No native builds here — the LMS port depends only on express + compression
# (no better-sqlite3, no music-metadata). The heavy lifting (scanning,
# transcoding for the rooms, playback) is done by the Lyrion Music Server this
# talks to over the network. Two programs are installed for a PHONE AWAY FROM
# HOME (lib/phoneplayers.js): squeezelite, the phone's own LMS player (to
# stdout, resampled to 48 kHz by libsoxr, which Debian's build has), and
# ffmpeg, which makes its sound Opus 256 kbps or FLAC for the phone.
RUN apt-get update \
 && apt-get install -y --no-install-recommends squeezelite ffmpeg \
 && rm -rf /var/lib/apt/lists/*
COPY package*.json ./
ENV NPM_CONFIG_UPDATE_NOTIFIER=false
RUN npm install --omit=dev --no-audit --no-fund --loglevel=error

COPY . .
COPY --from=engine /out/musicdnet /usr/local/bin/musicdnet

RUN mkdir -p /app/data

VOLUME /app/data

EXPOSE 3390

ENV PORT=3390
ENV DOCKER=1
# A dev test build is stamped with its test version and channel
# (.github/workflows/server-dev.yml; lib/build.js): it says which build it is,
# and the in-app updater tells it to pull :dev rather than offering a release.
# A release leaves both empty and reports package.json's version.
ARG APP_VERSION=""
ARG APP_CHANNEL=""
ENV APP_VERSION=$APP_VERSION
ENV APP_CHANNEL=$APP_CHANNEL
# Point at your Lyrion Music Server. If unset, the app tries UDP auto-discovery
# on the local network and you can also set the host in the in-app settings.
# ENV LMS_HOST=192.168.1.50
# ENV LMS_PORT=9000

# Away from home: Tailscale is built in (Settings → Away from home, or set TS_AUTHKEY to join without a browser).
# TS_HOSTNAME names the machine on the tailnet (default musicd-lms); TAILSCALE=off leaves it out.
# ENV TS_AUTHKEY=tskey-auth-…

# Run the launcher directly (not via `npm start`) so it is PID 1: it supervises
# index.js and applies in-app updates (index.js exits 75 → launcher overlays the
# staged build and respawns). As PID 1 it also receives `docker stop`'s SIGTERM
# directly and forwards it to index.js for a clean shutdown, which npm does not.
CMD ["node","launcher.js"]
