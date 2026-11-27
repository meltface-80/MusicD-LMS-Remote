// The engine stops itself (item 404), on the phone only: not on the app's
// timers, which Android freezes with the app.
//
// On 13 Nov 2026 Android ended the app twice for the engine's CPU (65% of a
// core, then 28%, over five minutes), both times with nothing going through
// it but the app's long polls, or nothing at all: the app's at-home stop
// waited on a timer on its main thread, and the app, cached, never ran it.
// Now the engine looks at itself every Tick and exits, its sign-in kept on
// disk (the app starts it again in seconds when it is wanted):
//
//   - idle: for IdleAfter nothing has gone through its forward but the app's
//     background requests (long polls and state reports, a few hundred bytes
//     each, under BusyBytes in a Tick, where audio, covers and pages are far
//     more), and the app's lease (POST /keep, renewed while the app is on
//     screen or the phone's player plays) has run out; never before the app
//     has made it its way to the server (being signed in, in a browser);
//   - CPU: over the last CPUWindow it used more than CPUShare of a core with
//     nothing passing through (under BusyBytes a Tick, all told: item 416, a
//     cover now and then no longer hides it), whatever the app's lease; or
//     with the app not using it (no lease for the whole window), whatever
//     passes. What it was doing goes to the app's log (stopreport.go),
//     rather than Android ending the whole app for it.
//
// Item 416: on 14 Nov Android ended the app again (30% of a core over five
// minutes) a minute after the phone's paused player gave up its foreground
// service; neither stop had come, though either would have by 10:44 had
// nothing passed (TestThe14thWithNothingPassing). The paused player was
// still fetching ahead through the engine, the app off screen: the CPU of a
// transfer nobody wanted, which Android counts all the same once the app is
// in the background. Without the app's lease the CPU guard now stops it
// whatever passes; and over WarnShare the engine says so, in its log and in
// a note (busy.txt) the app reads at its next start, so that a stop by
// Android says what the engine was doing.
package main

import (
	"fmt"
	"io"
	"sync"
	"sync/atomic"
	"time"
)

type watchConfig struct {
	Tick      time.Duration // how often it looks
	IdleAfter time.Duration // nothing real through the forward and no lease for this long: it stops
	BusyBytes int64         // bytes through the forward in one Tick that are real use, not a long poll
	CPUWindow time.Duration // the CPU guard's window
	CPUShare  float64       // of one core, over the window, with nothing passing or no lease: it stops
	WarnShare float64       // of one core, over the window: it says so, once a window (item 416)
}

// Android ends a background app whose child uses more than 25% of a core over
// five minutes; the guard acts on 20% over one.
var defaultWatch = watchConfig{
	Tick:      15 * time.Second,
	IdleAfter: 150 * time.Second,
	BusyBytes: 16 << 10,
	CPUWindow: time.Minute,
	CPUShare:  0.20,
	WarnShare: 0.10,
}

const (
	stopIdle = "idle"
	stopCPU  = "cpu"
)

type cpuSample struct {
	at    time.Time
	cpu   float64
	moved int64
}

// watcher decides; the clock, the CPU reader, the bytes counter and whether
// it forwards yet are given, so that tests drive it.
type watcher struct {
	cfg   watchConfig
	now   func() time.Time
	cpu   func() float64
	moved func() int64
	// forwarding: the app has made it its way to the server (POST /forward).
	// Before, it is being signed in (the sign-in page in a browser, minutes),
	// and is never idle.
	forwarding func() bool
	conns      func() int // the app's connections through it now, for the words; nil: not said

	mu        sync.Mutex
	keepUntil time.Time
	lastBusy  time.Time // the last look with a transfer
	lastUse   time.Time // the same, or not forwarding yet, for idleness
	lastMoved int64
	lastWarn  time.Time
	samples   []cpuSample
}

func newWatcher(cfg watchConfig, now func() time.Time, cpu func() float64, moved func() int64, forwarding func() bool) *watcher {
	t := now()
	return &watcher{cfg: cfg, now: now, cpu: cpu, moved: moved, forwarding: forwarding, lastBusy: t, lastUse: t,
		lastMoved: moved(), samples: []cpuSample{{t, cpu(), moved()}}}
}

// keep is the app's lease: the engine is wanted for d from now (0 lets it go).
func (w *watcher) keep(d time.Duration) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.keepUntil = w.now().Add(d)
}

// step is one look: "" to go on, or stopIdle / stopCPU and why.
func (w *watcher) step() (kind, why string) {
	kind, why, _, _ = w.look()
	return kind, why
}

// look is one look: a stop and why, or, once a window while it uses more than
// WarnShare of a core, what it is doing (item 416); calm: a whole window
// under WarnShare.
func (w *watcher) look() (kind, why, warn string, calm bool) {
	now, c, m, fw := w.now(), w.cpu(), w.moved(), w.forwarding()
	open := -1
	if w.conns != nil {
		open = w.conns()
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if m-w.lastMoved >= w.cfg.BusyBytes {
		w.lastBusy = now
	}
	if !fw || w.lastBusy == now {
		w.lastUse = now
	}
	w.lastMoved = m
	w.samples = append(w.samples, cpuSample{now, c, m})
	leased := now.Before(w.keepUntil)
	// No lease for a whole window: what the window measured is the app's not using it (a pause of a moment, its
	// lease given back, isn't; nor the minute's transfer before the lease went).
	unwanted := !now.Before(w.keepUntil.Add(w.cfg.CPUWindow))
	// The newest sample at least a window old: what the CPU is measured from.
	from := -1
	for i := len(w.samples) - 1; i >= 0; i-- {
		if now.Sub(w.samples[i].at) >= w.cfg.CPUWindow {
			from = i
			break
		}
	}
	if from >= 0 {
		s := w.samples[from]
		w.samples = w.samples[from:]
		span := now.Sub(s.at).Seconds()
		used := c - s.cpu
		if c >= 0 && s.cpu >= 0 && span > 0 {
			share := used / span
			calm = share <= w.cfg.WarnShare
			what := fmt.Sprintf("%.1f s of CPU in the last %.0f s, %.0f%% of a core", used, span, 100*share)
			passed := through(m-s.moved, open)
			// Nothing passing: under BusyBytes a look, all told, over the window.
			nothing := float64(m-s.moved) < float64(w.cfg.BusyBytes)*span/w.cfg.Tick.Seconds()
			switch {
			case share > w.cfg.CPUShare && nothing:
				return stopCPU, what + ", with nothing passing through (" + passed + ")", "", false
			case share > w.cfg.CPUShare && unwanted:
				return stopCPU, what + ", the app not using it (" + passed + ")", "", false
			case share > w.cfg.WarnShare && now.Sub(w.lastWarn) >= w.cfg.CPUWindow:
				w.lastWarn = now
				lease := "the app not using it"
				if leased {
					lease = fmt.Sprintf("the app's lease %s more", w.keepUntil.Sub(now).Round(time.Second))
				}
				warn = what + "; " + passed + "; " + lease
			}
		}
	}
	if idle := now.Sub(w.lastUse); idle >= w.cfg.IdleAfter && !leased {
		return stopIdle, fmt.Sprintf("nothing but the app's background requests through it for %s, and the app not using it",
			idle.Round(time.Second)), "", calm
	}
	return "", "", warn, calm
}

// through: what went through the forward in a window, in words.
func through(n int64, open int) string {
	s := fmt.Sprintf("%d KB through it", n>>10)
	if n >= 10<<20 {
		s = fmt.Sprintf("%d MB through it", n>>20)
	} else if n >= 1<<20 {
		s = fmt.Sprintf("%.1f MB through it", float64(n)/(1<<20))
	}
	if open >= 0 {
		s += fmt.Sprintf(", %d connection(s) open", open)
	}
	return s
}

// run looks every Tick until a look says stop, then stops with it; what a
// busy look says goes to [busy], and "" once a window is calm.
func (w *watcher) run(stop func(kind, why string), busy func(what string)) {
	t := time.NewTicker(w.cfg.Tick)
	defer t.Stop()
	for range t.C {
		kind, why, warn, calm := w.look()
		switch {
		case kind != "":
			stop(kind, why)
			return
		case warn != "":
			busy(warn)
		case calm:
			busy("")
		}
	}
}

// watchItself starts the watch (the phone's engine; MusicD Server's never stops itself).
func (e *engine) watchItself(cfg watchConfig) {
	e.watch = e.newWatch(cfg, time.Now)
	go e.watch.run(e.stopSelf, e.noteBusy)
}

// newWatch: the engine's watch on its own figures. None of them waits on the
// engine's lock (item 416: a look must come whatever the engine is busy with).
func (e *engine) newWatch(cfg watchConfig, now func() time.Time) *watcher {
	w := newWatcher(cfg, now, cpuSeconds, e.moved.Load, func() bool { return e.fwNow.Load() != nil })
	w.conns = func() int {
		if f := e.fwNow.Load(); f != nil {
			return f.Open()
		}
		return 0
	}
	return w
}

// keep: the app's lease, for /keep; false when the engine doesn't watch itself.
func (e *engine) keep(d time.Duration) bool {
	if e.watch == nil {
		return false
	}
	e.watch.keep(d)
	return true
}

// counted counts what is read through it, for the watch.
type counted struct {
	r io.Reader
	n *atomic.Int64
}

func (c counted) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	if n > 0 && c.n != nil {
		c.n.Add(int64(n))
	}
	return n, err
}
