FROM node:20-bookworm-slim

WORKDIR /app

# No native builds here — the LMS port depends only on express + compression
# (no better-sqlite3, no music-metadata, no ffmpeg/squeezelite). The heavy
# lifting (scanning, transcoding, playback) is done by the Lyrion Music Server
# this talks to over the network.
COPY package*.json ./
ENV NPM_CONFIG_UPDATE_NOTIFIER=false
RUN npm install --omit=dev --no-audit --no-fund --loglevel=error

COPY . .

RUN mkdir -p /app/data

VOLUME /app/data

EXPOSE 3390

ENV PORT=3390
ENV DOCKER=1
# Point at your Lyrion Music Server. If unset, the app tries UDP auto-discovery
# on the local network and you can also set the host in the in-app settings.
# ENV LMS_HOST=192.168.1.50
# ENV LMS_PORT=9000

# Run the launcher directly (not via `npm start`) so it is PID 1: it supervises
# index.js and applies in-app updates (index.js exits 75 → launcher overlays the
# staged build and respawns). As PID 1 it also receives `docker stop`'s SIGTERM
# directly and forwards it to index.js for a clean shutdown, which npm does not.
CMD ["node","launcher.js"]
