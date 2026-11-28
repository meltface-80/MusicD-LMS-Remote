/*
 * usb_jni.cc — the native side of the app's USB audio driver (Stage 9).
 *
 * Android hands an app the open device node of a USB device it has
 * permission for (UsbDeviceConnection.getFileDescriptor()). Everything a
 * driver needs — isochronous transfers above all, which Java has no call
 * for — is done on that descriptor with the kernel's usbdevfs ioctls, the
 * way libusb does it on Linux.
 *
 * 9.1: the probe (capabilities, bus speed). 9.2: the stream. PCM frames are
 * written into a ring from Kotlin; a reaper thread keeps a set of
 * isochronous OUT URBs in flight, each a run of packets sized from the
 * DAC's feedback (an asynchronous DAC says how many frames per packet it
 * wants, in 10.14 or 16.16 fixed point) or from the nominal rate (adaptive
 * and synchronous DACs). What the ring can't supply is sent as silence and
 * counted as an underrun; draining sends what is left and then nothing.
 */
#include <jni.h>
#include <string>
#include <cstdio>
#include <cstring>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cmath>
#include <vector>
#include <mutex>
#include <thread>
#include <atomic>
#include <unistd.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <time.h>
#include <algorithm>
#include <linux/usbdevice_fs.h>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "UsbDriver", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "UsbDriver", __VA_ARGS__)

#ifndef USBDEVFS_CAP_ZERO_PACKET
#define USBDEVFS_CAP_ZERO_PACKET 0x01
#endif
#ifndef USBDEVFS_CAP_BULK_CONTINUATION
#define USBDEVFS_CAP_BULK_CONTINUATION 0x02
#endif
#ifndef USBDEVFS_CAP_NO_PACKET_SIZE_LIM
#define USBDEVFS_CAP_NO_PACKET_SIZE_LIM 0x04
#endif
#ifndef USBDEVFS_CAP_BULK_SCATTER_GATHER
#define USBDEVFS_CAP_BULK_SCATTER_GATHER 0x08
#endif
#ifndef USBDEVFS_CAP_REAP_AFTER_DISCONNECT
#define USBDEVFS_CAP_REAP_AFTER_DISCONNECT 0x10
#endif
#ifndef USBDEVFS_CAP_MMAP
#define USBDEVFS_CAP_MMAP 0x20
#endif
#ifndef USBDEVFS_CAP_DROP_PRIVILEGES
#define USBDEVFS_CAP_DROP_PRIVILEGES 0x40
#endif
#ifndef USBDEVFS_GET_SPEED
#define USBDEVFS_GET_SPEED _IO('U', 31)
#endif

// ------------------------------------------------------------------ probe

extern "C" JNIEXPORT jstring JNICALL
Java_com_musicd_mallard_UsbDac_nativeProbe(JNIEnv* env, jclass, jint fd) {
  char out[512];
  __u32 caps = 0;
  if (ioctl(fd, USBDEVFS_GET_CAPABILITIES, &caps) < 0) {
    snprintf(out, sizeof out, "ioctl GET_CAPABILITIES failed: %s", strerror(errno));
    return env->NewStringUTF(out);
  }
  struct usbdevfs_connectinfo ci;
  memset(&ci, 0, sizeof ci);
  int haveCi = ioctl(fd, USBDEVFS_CONNECTINFO, &ci) == 0;
  snprintf(out, sizeof out,
           "usbdevfs ok: caps=0x%x%s%s%s%s%s%s%s%s",
           caps,
           (caps & USBDEVFS_CAP_ZERO_PACKET) ? " zero-packet" : "",
           (caps & USBDEVFS_CAP_BULK_CONTINUATION) ? " bulk-continuation" : "",
           (caps & USBDEVFS_CAP_NO_PACKET_SIZE_LIM) ? " no-packet-size-limit" : "",
           (caps & USBDEVFS_CAP_BULK_SCATTER_GATHER) ? " scatter-gather" : "",
           (caps & USBDEVFS_CAP_REAP_AFTER_DISCONNECT) ? " reap-after-disconnect" : "",
           (caps & USBDEVFS_CAP_MMAP) ? " mmap" : "",
           (caps & USBDEVFS_CAP_DROP_PRIVILEGES) ? " drop-privileges" : "",
           haveCi ? (ci.slow ? ", low speed" : "") : ", no connectinfo");
  if (haveCi) {
    size_t n = strlen(out);
    snprintf(out + n, sizeof out - n, ", devnum=%u", ci.devnum);
  }
  return env->NewStringUTF(out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_musicd_mallard_UsbDac_nativeSpeed(JNIEnv* env, jclass, jint fd) {
  int s = ioctl(fd, USBDEVFS_GET_SPEED);
  const char* name = "unknown";
  if (s >= 0) {
    switch (s) {
      case 1: name = "low"; break;
      case 2: name = "full"; break;
      case 3: name = "high"; break;
      case 4: name = "wireless"; break;
      case 5: name = "super"; break;
      case 6: name = "super-plus"; break;
      default: name = "other"; break;
    }
  } else {
    struct usbdevfs_connectinfo ci;
    memset(&ci, 0, sizeof ci);
    if (ioctl(fd, USBDEVFS_CONNECTINFO, &ci) == 0) name = ci.slow ? "low" : "full-or-high";
  }
  return env->NewStringUTF(name);
}

// ----------------------------------------------------------------- stream
//
// One stream per open. Once started it runs until the stream is closed:
// isochronous OUT URBs go out back to back, carrying PCM from the ring
// while there is some and silence when there isn't (paused, between
// tracks, an underrun). Pause, flush and drain never stop the packets, so
// the DAC keeps its clock and never sees a gap it might sulk over — some
// stop producing sound after a stopped stream until the interface is
// selected afresh. The position counts only PCM frames taken from the
// ring, not silence, so it stands still while nothing is written.

namespace {

struct Urb {
  usbdevfs_urb* urb = nullptr;   // with its iso_frame_desc tail
  uint8_t* buf = nullptr;
  size_t bufSize = 0;
  int frames = 0;                // PCM frames from the ring in this URB (OUT) — counted when reaped
  bool feedback = false;
  bool inFlight = false;
};

struct Driver {
  int fd = -1;
  int iface = 0, alt = 0;
  int ep = 0, fbEp = 0;
  bool highSpeed = false;
  int interval = 1;        // the data endpoint's bInterval (raw)
  int fbInterval = 1;      // the feedback endpoint's bInterval (raw)
  int maxPacket = 0;       // bytes
  int fbMaxPacket = 4;
  int frameBytes = 0;      // channels * subslot
  int rate = 0;
  int pktsPerUrb = 8, nUrbs = 8;
  double periodUs = 1000;  // between data packets
  double nominal = 0;      // frames per packet, nominal
  double accum = 0;        // fractional frames carried over
  double fbFrames = 0;     // frames per packet the DAC asks for
  bool fbValid = false;
  std::vector<Urb> urbs;
  std::vector<Urb> fburbs;
  // ring of PCM bytes
  std::vector<uint8_t> ring;
  size_t head = 0, tail = 0, fill = 0;
  std::mutex mu;
  std::atomic<bool> streaming{false};   // URBs flowing, the reaper running
  std::atomic<bool> playing{false};     // the ring is consumed (else silence)
  std::atomic<bool> draining{false}, stopping{false}, dead{false};
  std::atomic<int> inFlight{0};
  std::thread reaper;
  // stats
  std::atomic<uint64_t> framesQueued{0}, framesDone{0}, underruns{0}, fbUpdates{0}, errors{0}, urbsDone{0};
  std::atomic<int64_t> lastDoneMs{0};
  std::string lastError;
  std::string events;                   // the last lifecycle calls, newest last
  // What goes out when there is nothing to send: zeros for PCM, DSD silence
  // (0x69) for native DSD, DoP frames with their markers — repeated in phase.
  std::vector<uint8_t> silence;
  size_t silPos = 0;
};

void fillSilence(Driver* d, uint8_t* p, size_t n) {
  if (d->silence.empty()) { memset(p, 0, n); return; }
  for (size_t i = 0; i < n; i++) { p[i] = d->silence[d->silPos]; if (++d->silPos >= d->silence.size()) d->silPos = 0; }
}

int64_t nowMs() {
  struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

void event(Driver* d, const char* what) {
  std::lock_guard<std::mutex> lk(d->mu);
  d->events += what; d->events += ' ';
  if (d->events.size() > 160) d->events.erase(0, d->events.size() - 160);
}

size_t ringFree(Driver* d) { return d->ring.size() - d->fill; }

size_t ringRead(Driver* d, uint8_t* out, size_t n) {
  size_t got = 0;
  while (got < n && d->fill > 0) {
    size_t chunk = std::min(n - got, std::min(d->fill, d->ring.size() - d->head));
    memcpy(out + got, d->ring.data() + d->head, chunk);
    d->head = (d->head + chunk) % d->ring.size();
    d->fill -= chunk; got += chunk;
  }
  return got;
}

size_t ringWrite(Driver* d, const uint8_t* in, size_t n) {
  size_t put = 0;
  while (put < n && d->fill < d->ring.size()) {
    size_t chunk = std::min(n - put, std::min(d->ring.size() - d->fill, d->ring.size() - d->tail));
    memcpy(d->ring.data() + d->tail, in + put, chunk);
    d->tail = (d->tail + chunk) % d->ring.size();
    d->fill += chunk; put += chunk;
  }
  return put;
}

void allocUrb(Urb& u, int packets, size_t packetBytes) {
  size_t sz = sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc) * packets;
  u.urb = (usbdevfs_urb*)calloc(1, sz);
  u.bufSize = packets * packetBytes;
  u.buf = (uint8_t*)calloc(1, u.bufSize ? u.bufSize : 1);
}
void freeUrb(Urb& u) { free(u.urb); free(u.buf); u.urb = nullptr; u.buf = nullptr; }

/* Fill an OUT URB's packets, sized by the feedback: PCM from the ring while playing, silence otherwise. */
void fillOut(Driver* d, Urb& u) {
  std::lock_guard<std::mutex> lk(d->mu);
  int maxFrames = d->maxPacket / d->frameBytes;
  double want = d->fbValid ? d->fbFrames : d->nominal;
  int data = 0;
  size_t off = 0;
  bool play = d->playing;
  for (int p = 0; p < d->pktsPerUrb; p++) {
    d->accum += want;
    int n = (int)floor(d->accum);
    d->accum -= n;
    if (n < 0) n = 0;
    if (n > maxFrames) n = maxFrames;
    size_t bytes = (size_t)n * d->frameBytes;
    size_t got = play ? ringRead(d, u.buf + off, bytes) : 0;
    if (got < bytes) {
      fillSilence(d, u.buf + off + got, bytes - got);
      if (play && !d->draining) d->underruns++;
    }
    u.urb->iso_frame_desc[p].length = (unsigned)bytes;
    u.urb->iso_frame_desc[p].actual_length = 0;
    u.urb->iso_frame_desc[p].status = 0;
    off += bytes;
    data += (int)(got / d->frameBytes);
  }
  u.frames = data;
  u.urb->buffer_length = (int)off;
  d->framesQueued += data;
}

bool submit(Driver* d, Urb& u, bool out) {
  u.urb->type = USBDEVFS_URB_TYPE_ISO;
  u.urb->endpoint = out ? d->ep : d->fbEp;
  u.urb->flags = USBDEVFS_URB_ISO_ASAP;
  u.urb->buffer = u.buf;
  u.urb->number_of_packets = out ? d->pktsPerUrb : 1;
  u.urb->usercontext = &u;
  u.urb->signr = 0;
  if (!out) {
    u.urb->iso_frame_desc[0].length = d->fbMaxPacket;
    u.urb->buffer_length = d->fbMaxPacket;
  }
  if (ioctl(d->fd, USBDEVFS_SUBMITURB, u.urb) < 0) {
    d->errors++;
    { std::lock_guard<std::mutex> lk(d->mu); d->lastError = std::string(out ? "submit out: " : "submit feedback: ") + strerror(errno); }
    if (errno == ENODEV || errno == ESHUTDOWN) d->dead = true;
    u.inFlight = false;
    return false;
  }
  u.inFlight = true;
  d->inFlight++;
  return true;
}

/* The DAC's feedback value → frames per data packet. */
void takeFeedback(Driver* d, Urb& u) {
  unsigned len = u.urb->iso_frame_desc[0].actual_length;
  if (u.urb->iso_frame_desc[0].status != 0 || (len != 3 && len != 4)) return;
  uint32_t v = 0;
  for (unsigned i = 0; i < len; i++) v |= (uint32_t)u.buf[i] << (8 * i);
  double perUnit;   // frames per bus frame (full speed, 10.14 per ms) or per microframe (high speed, 16.16)
  if (!d->highSpeed) perUnit = (double)v / 16384.0;
  else if (len == 4) perUnit = (double)v / 65536.0;
  else perUnit = (double)v / 16384.0 / 8.0;   // a 10.14 per-ms value on a high-speed link
  double unitUs = d->highSpeed ? 125.0 : 1000.0;
  double frames = perUnit * (d->periodUs / unitUs);
  // Sanity: within a fifth of nominal, or it is noise.
  if (frames > d->nominal * 0.8 && frames < d->nominal * 1.2) {
    std::lock_guard<std::mutex> lk(d->mu);
    d->fbFrames = frames; d->fbValid = true; d->fbUpdates++;
  }
}

/* Everything not in flight, submitted (the OUT URBs filled first). */
void submitAll(Driver* d) {
  for (auto& u : d->fburbs) if (!u.inFlight) submit(d, u, false);
  for (auto& u : d->urbs) if (!u.inFlight) { fillOut(d, u); if (!submit(d, u, true)) break; }
}

/* One completed URB back in the air. */
void completed(Driver* d, Urb* u, int status) {
  u->inFlight = false;
  d->inFlight--;
  if (d->stopping) return;
  if (u->feedback) {
    takeFeedback(d, *u);
    submit(d, *u, false);
  } else {
    d->urbsDone++;
    d->framesDone += u->frames;
    d->lastDoneMs = nowMs();
    if (status != 0 && status != -ENOENT) d->errors++;
    fillOut(d, *u);
    submit(d, *u, true);
  }
}

void reaperLoop(Driver* d) {
  d->lastDoneMs = nowMs();
  while (!d->stopping) {
    if (d->dead) break;
    if (d->inFlight == 0) {
      // Nothing in the air (every submit failed): try again shortly rather than wait on nothing.
      submitAll(d);
      if (d->inFlight == 0) { usleep(2000); continue; }
    }
    struct pollfd p; p.fd = d->fd; p.events = POLLOUT; p.revents = 0;
    int r = poll(&p, 1, 100);
    if (r < 0) { if (errno == EINTR) continue; d->errors++; usleep(1000); continue; }
    if (d->stopping) break;
    if (p.revents & (POLLERR | POLLHUP)) { d->dead = true; { std::lock_guard<std::mutex> lk(d->mu); d->lastError = "device gone"; } break; }
    for (;;) {
      usbdevfs_urb* done = nullptr;
      if (ioctl(d->fd, USBDEVFS_REAPURBNDELAY, &done) < 0) {
        if (errno == EAGAIN || errno == EINTR) break;
        if (errno == ENODEV || errno == ESHUTDOWN) { d->dead = true; { std::lock_guard<std::mutex> lk(d->mu); d->lastError = "device gone"; } }
        d->errors++;
        break;
      }
      if (!done) break;
      completed(d, (Urb*)done->usercontext, done->status);
    }
    // Playing, but nothing has come back for a second: the stream has stalled.
    // Marked dead; the sink reopens it (a fresh interface select) from where it is.
    if (d->playing && d->inFlight > 0 && nowMs() - d->lastDoneMs > 1000) {
      d->dead = true;
      { std::lock_guard<std::mutex> lk(d->mu); d->lastError = "stalled: no packets completed for a second"; }
      break;
    }
  }
}

void discardAll(Driver* d) {
  for (auto& u : d->urbs) if (u.inFlight) ioctl(d->fd, USBDEVFS_DISCARDURB, u.urb);
  for (auto& u : d->fburbs) if (u.inFlight) ioctl(d->fd, USBDEVFS_DISCARDURB, u.urb);
}

void startStream(Driver* d) {
  if (d->streaming) return;
  d->stopping = false;
  d->streaming = true;
  submitAll(d);
  if (d->reaper.joinable()) d->reaper.join();
  d->reaper = std::thread(reaperLoop, d);
}

void stopStream(Driver* d) {
  if (!d->streaming) return;
  d->stopping = true;
  discardAll(d);
  if (d->reaper.joinable()) d->reaper.join();
  // Anything still outstanding after the discard: reap it so the kernel frees it.
  int64_t until = nowMs() + 500;
  while (d->inFlight > 0 && nowMs() < until) {
    usbdevfs_urb* done = nullptr;
    if (ioctl(d->fd, USBDEVFS_REAPURBNDELAY, &done) < 0) {
      if (errno == EAGAIN) { usleep(1000); continue; }
      break;
    }
    if (done) { ((Urb*)done->usercontext)->inFlight = false; d->inFlight--; }
  }
  d->streaming = false;
  d->stopping = false;
}

Driver* get(jlong h) { return (Driver*)(intptr_t)h; }

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_musicd_mallard_UsbDriver_nativeOpen(JNIEnv* env, jclass, jint fd, jint iface, jint alt, jint ep, jint fbEp,
                                                      jboolean highSpeed, jint interval, jint fbInterval, jint maxPacket,
                                                      jint fbMaxPacket, jint frameBytes, jint rate, jbyteArray silence) {
  Driver* d = new Driver();
  if (silence) {
    jsize n = env->GetArrayLength(silence);
    if (n > 0) { d->silence.resize((size_t)n); env->GetByteArrayRegion(silence, 0, n, (jbyte*)d->silence.data()); }
  }
  d->fd = fd; d->iface = iface; d->alt = alt; d->ep = ep; d->fbEp = fbEp; d->highSpeed = highSpeed;
  d->interval = interval > 0 ? interval : 1; d->fbInterval = fbInterval > 0 ? fbInterval : 1;
  d->maxPacket = maxPacket; d->fbMaxPacket = fbMaxPacket > 0 ? fbMaxPacket : (highSpeed ? 4 : 3);
  d->frameBytes = frameBytes; d->rate = rate;
  // A packet every bInterval ms at full speed, every 2^(bInterval-1) microframes at high speed.
  d->periodUs = highSpeed ? 125.0 * (1 << (d->interval - 1)) : 1000.0 * d->interval;
  d->nominal = (double)rate * d->periodUs / 1e6;
  // About 64 ms in flight, in URBs of about 4–8 ms.
  int pktsPer8ms = (int)std::max(1.0, floor(8000.0 / d->periodUs));
  d->pktsPerUrb = std::min(pktsPer8ms, 64);
  d->nUrbs = std::max(4, (int)ceil(64000.0 / (d->periodUs * d->pktsPerUrb)));
  if (d->nUrbs > 32) d->nUrbs = 32;
  // Select the streaming alternate setting (bandwidth is claimed here).
  usbdevfs_setinterface si; si.interface = iface; si.altsetting = alt;
  if (ioctl(fd, USBDEVFS_SETINTERFACE, &si) < 0) {
    LOGW("set alt %d/%d: %s", iface, alt, strerror(errno));
    delete d; return 0;
  }
  d->ring.assign((size_t)rate * frameBytes * 2, 0);   // two seconds
  d->urbs.resize(d->nUrbs);
  for (auto& u : d->urbs) allocUrb(u, d->pktsPerUrb, (size_t)maxPacket);
  if (fbEp) {
    d->fburbs.resize(2);
    for (auto& u : d->fburbs) { allocUrb(u, 1, (size_t)d->fbMaxPacket); u.feedback = true; }
  }
  LOGI("open: rate %d, %d B/frame, period %.0f us, nominal %.3f frames/packet, %d x %d packets, max %d B%s",
       rate, frameBytes, d->periodUs, d->nominal, d->nUrbs, d->pktsPerUrb, maxPacket, fbEp ? ", feedback" : "");
  event(d, "open");
  return (jlong)(intptr_t)d;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_musicd_mallard_UsbDriver_nativePlay(JNIEnv*, jclass, jlong h) {
  Driver* d = get(h); if (!d || d->dead) return JNI_FALSE;
  event(d, "play");
  d->draining = false;
  d->playing = true;
  startStream(d);
  return d->dead ? JNI_FALSE : JNI_TRUE;
}

/* Paused: the packets carry on, with silence; the ring keeps what it has. */
extern "C" JNIEXPORT void JNICALL
Java_com_musicd_mallard_UsbDriver_nativePause(JNIEnv*, jclass, jlong h) {
  Driver* d = get(h); if (!d) return;
  event(d, "pause");
  d->playing = false;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_musicd_mallard_UsbDriver_nativeWrite(JNIEnv* env, jclass, jlong h, jobject buf, jint off, jint len) {
  Driver* d = get(h); if (!d || d->dead) return -1;
  uint8_t* p = (uint8_t*)env->GetDirectBufferAddress(buf);
  if (!p) return -1;
  std::lock_guard<std::mutex> lk(d->mu);
  size_t n = ringWrite(d, p + off, (size_t)len);
  n -= n % d->frameBytes;
  return (jint)n;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_musicd_mallard_UsbDriver_nativeFree(JNIEnv*, jclass, jlong h) {
  Driver* d = get(h); if (!d) return 0;
  std::lock_guard<std::mutex> lk(d->mu);
  return (jint)ringFree(d);
}

/* PCM frames not yet heard: in the ring and in flight. */
extern "C" JNIEXPORT jlong JNICALL
Java_com_musicd_mallard_UsbDriver_nativePending(JNIEnv*, jclass, jlong h) {
  Driver* d = get(h); if (!d) return 0;
  std::lock_guard<std::mutex> lk(d->mu);
  uint64_t inRing = d->fill / d->frameBytes;
  uint64_t q = d->framesQueued, done = d->framesDone;
  uint64_t inFlight = q > done ? q - done : 0;
  return (jlong)(inRing + inFlight);
}

/* PCM frames the DAC has taken (silence not counted): the clock the position runs on. */
extern "C" JNIEXPORT jlong JNICALL
Java_com_musicd_mallard_UsbDriver_nativePlayed(JNIEnv*, jclass, jlong h) {
  Driver* d = get(h); if (!d) return 0;
  return (jlong)d->framesDone.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_musicd_mallard_UsbDriver_nativeDrain(JNIEnv*, jclass, jlong h) {
  Driver* d = get(h); if (!d) return;
  event(d, "drain");
  d->draining = true;
}

/* A seek or a new track: the ring is emptied; what is in flight (up to 64 ms) plays out. */
extern "C" JNIEXPORT void JNICALL
Java_com_musicd_mallard_UsbDriver_nativeFlush(JNIEnv*, jclass, jlong h) {
  Driver* d = get(h); if (!d) return;
  event(d, "flush");
  std::lock_guard<std::mutex> lk(d->mu);
  d->head = d->tail = d->fill = 0;
  d->draining = false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_musicd_mallard_UsbDriver_nativeClose(JNIEnv*, jclass, jlong h) {
  Driver* d = get(h); if (!d) return;
  d->playing = false;
  stopStream(d);
  usbdevfs_setinterface si; si.interface = d->iface; si.altsetting = 0;
  ioctl(d->fd, USBDEVFS_SETINTERFACE, &si);
  for (auto& u : d->urbs) freeUrb(u);
  for (auto& u : d->fburbs) freeUrb(u);
  delete d;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_musicd_mallard_UsbDriver_nativeStats(JNIEnv* env, jclass, jlong h) {
  Driver* d = get(h);
  if (!d) return env->NewStringUTF("{}");
  char out[1024];
  double fb; std::string err, ev; size_t fill;
  { std::lock_guard<std::mutex> lk(d->mu); fb = d->fbValid ? d->fbFrames : 0; err = d->lastError; ev = d->events; fill = d->fill; }
  snprintf(out, sizeof out,
           "{\"rate\":%d,\"frame_bytes\":%d,\"period_us\":%.0f,\"nominal\":%.3f,\"feedback\":%.4f,\"feedback_updates\":%llu,"
           "\"urbs\":%d,\"packets_per_urb\":%d,\"max_packet\":%d,\"queued\":%llu,\"done\":%llu,\"urbs_done\":%llu,\"in_flight\":%d,"
           "\"underruns\":%llu,\"errors\":%llu,\"streaming\":%s,\"running\":%s,\"draining\":%s,\"dead\":%s,\"ring_fill\":%zu,"
           "\"last_error\":\"%s\",\"events\":\"%s\"}",
           d->rate, d->frameBytes, d->periodUs, d->nominal, fb, (unsigned long long)d->fbUpdates.load(),
           d->nUrbs, d->pktsPerUrb, d->maxPacket, (unsigned long long)d->framesQueued.load(), (unsigned long long)d->framesDone.load(),
           (unsigned long long)d->urbsDone.load(), d->inFlight.load(), (unsigned long long)d->underruns.load(), (unsigned long long)d->errors.load(),
           d->streaming ? "true" : "false", d->playing ? "true" : "false", d->draining ? "true" : "false", d->dead ? "true" : "false", fill,
           err.c_str(), ev.c_str());
  return env->NewStringUTF(out);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_musicd_mallard_UsbDriver_nativeDead(JNIEnv*, jclass, jlong h) {
  Driver* d = get(h); return (d && d->dead) ? JNI_TRUE : JNI_FALSE;
}
