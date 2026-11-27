package main

// Item 416: on 14 Nov 2026 Android ended the app for the engine's CPU (89.8 s
// in five minutes, 30% of a core) a minute after the phone's paused player
// gave up its foreground service (10:46:05), the app off screen since
// 10:41:34; the engine had not stopped itself. Its watch replayed here, on the
// app's own log of that morning: the player playing (a megabyte a look, its
// lease renewed every minute) until 10:41:04, paused, then off screen.

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// the14th: the watch at 10:37:00, the player playing, its lease renewed each minute until 10:41:00 (the pause).
func the14th() *fakeWatch {
	f := newFake()
	f.t = time.Date(2026, 11, 14, 10, 37, 0, 0, time.UTC)
	f.w = newWatcher(testWatch, func() time.Time { return f.t }, func() float64 { return f.cpu }, func() int64 { return f.moved },
		func() bool { return true })
	for f.t.Before(time.Date(2026, 11, 14, 10, 41, 0, 0, time.UTC)) {
		if f.t.Second() == 0 {
			f.w.keep(180 * time.Second) // EngineUse's renewal, while the player plays
		}
		if kind, why := f.tick(1<<20, 0.14); kind != "" {
			f.w.keep(0)
			panic("stopped while playing: " + why)
		}
	}
	f.w.keep(180 * time.Second) // the last renewal, at 10:41:00
	return f
}

func clock(h, m, s int) time.Time { return time.Date(2026, 11, 14, h, m, s, 0, time.UTC) }

// Had nothing but the long polls passed after the pause, item 404's guard would have stopped it by 10:42:15, and its
// idle stop by 10:44; neither came. So something passed: the paused player fetching ahead (20 tracks on Wi-Fi).
func TestThe14thWithNothingPassing(t *testing.T) {
	f := the14th()
	for f.t.Before(clock(10, 47, 4)) {
		kind, why := f.tick(pollBytes, 0.30)
		if kind == "" {
			continue
		}
		if kind != stopCPU || f.t.After(clock(10, 42, 15)) || !strings.Contains(why, "with nothing passing through") {
			t.Fatalf("at %s: %q %q", f.t.Format("15:04:05"), kind, why)
		}
		return
	}
	t.Fatal("never stopped, long polls only at 30% of a core")
}

// The paused player fetching ahead, two megabytes a look, at 30% of a core: a transfer, but nobody's. Item 404's guard
// let it be till Android ended the app; a whole window after the app's lease ran out (10:44:00) it stops whatever
// passes: an app frozen with its lease given, say. Android's next five minutes (10:42:04 to 10:47:04) would then have
// held 53 s of its CPU, under the 75 s it allows.
func TestThe14thFetchingAheadOnceTheLeaseIsOut(t *testing.T) {
	f := the14th()
	for f.t.Before(clock(10, 47, 4)) {
		kind, why := f.tick(2<<20, 0.30)
		if kind == "" {
			continue
		}
		if kind != stopCPU || f.t.After(clock(10, 45, 0)) || !strings.Contains(why, "% of a core, the app not using it (") ||
			!strings.Contains(why, " MB through it") {
			t.Fatalf("at %s: %q %q", f.t.Format("15:04:05"), kind, why)
		}
		return
	}
	t.Fatal("never stopped: Android ended the app at 10:47 for this")
}

// The app now lets the lease go as it goes off screen with the player paused (EngineUse.lease): a whole window on,
// 10:42:30, it stops; a pause of a moment, the lease given back within the window, doesn't.
func TestThe14thFetchingAheadTheLeaseLetGo(t *testing.T) {
	f := the14th()
	f.tick(2<<20, 0.30) // 10:41:15
	f.tick(2<<20, 0.30) // 10:41:30
	f.w.keep(0)         // 10:41:30, off screen (10:41:34 in the log)
	for f.t.Before(clock(10, 42, 15)) {
		if kind, why := f.tick(2<<20, 0.30); kind != "" {
			t.Fatalf("%s, the lease let go under a window ago: %q %q", f.t.Format("15:04:05"), kind, why)
		}
	}
	if kind, why := f.tick(2<<20, 0.30); kind != stopCPU || !strings.Contains(why, "the app not using it") {
		t.Fatalf("10:42:30, the lease let go a window ago: %q %q", kind, why)
	}

	g := the14th()
	g.w.keep(0) // 10:41:00, paused from the notification…
	g.tick(2<<20, 0.30)
	g.tick(2<<20, 0.30)
	g.w.keep(180 * time.Second) // …and playing again
	for i := 0; i < 20; i++ {
		if kind, why := g.tick(2<<20, 0.30); kind != "" {
			t.Fatalf("a pause of half a minute, then playing: stopped at %s: %q", g.t.Format("15:04:05"), why)
		}
		if g.t.Second() == 0 {
			g.w.keep(180 * time.Second)
		}
	}
}

// With the lease, a cover or a page now and then (16 KB once a minute) no longer hides a spinning engine: what passed
// is counted over the whole window.
func TestCPUGuardCountsWhatPassedOverTheWindow(t *testing.T) {
	f := newFake()
	f.w.keep(time.Hour)
	for i := 1; i <= 12; i++ {
		b := int64(pollBytes)
		if i%4 == 0 {
			b = 20 << 10
		}
		kind, why := f.tick(b, 0.30)
		if kind == "" {
			continue
		}
		if kind != stopCPU || i > 8 || !strings.Contains(why, "with nothing passing through") {
			t.Fatalf("look %d: %q %q", i, kind, why)
		}
		return
	}
	t.Fatal("never stopped: 30% of a core, long polls and a cover a minute")
}

// Over WarnShare it says what it is doing, once a window, and says when it is calm again; with the lease and a
// transfer, even over CPUShare, it goes on (the app on screen, or the phone playing: Android leaves the app be).
func TestBusyIsSaidOnceAWindow(t *testing.T) {
	f := newFake()
	f.w.conns = func() int { return 3 }
	f.w.keep(time.Hour)
	var warns []string
	calms := 0
	for i := 1; i <= 12; i++ {
		share := 0.15
		if i > 6 {
			share = 0.35
		}
		kind, _, warn, calm := f.w.lookAfter(f, 2<<20, share)
		if kind != "" {
			t.Fatalf("look %d stopped with the lease and a transfer: %s", i, kind)
		}
		if warn != "" {
			warns = append(warns, warn)
		}
		if calm {
			calms++
		}
	}
	if len(warns) != 3 || calms != 0 || !strings.HasPrefix(warns[0], "9.0 s of CPU in the last 60 s, 15% of a core; 8.0 MB through it, 3 connection(s) open; the app's lease ") {
		t.Fatalf("busy said %d times, calm %d: %q", len(warns), calms, warns)
	}
	for i := 0; i < 5; i++ {
		f.w.lookAfter(f, 2<<20, 0.05)
	}
	if _, _, warn, calm := f.w.lookAfter(f, 2<<20, 0.05); warn != "" || !calm {
		t.Fatalf("a window at 5%%: warn %q calm %v", warn, calm)
	}
}

// lookAfter: one Tick on, as [tick], through look.
func (w *watcher) lookAfter(f *fakeWatch, bytes int64, share float64) (string, string, string, bool) {
	f.t = f.t.Add(testWatch.Tick)
	f.moved += bytes
	f.cpu += share * testWatch.Tick.Seconds()
	return w.look()
}

// What it said busy is in its log and in busy.txt, for the app's next start (Android ends the app and the engine with
// it); gone once calm, and once it stops itself (its stop note says it all).
func TestBusyNote(t *testing.T) {
	dir := t.TempDir()
	e := &engine{dir: dir}
	e.logf("magicsock: a line")
	e.noteBusy("18.0 s of CPU in the last 60 s, 30% of a core; 9 MB through it")
	b, err := os.ReadFile(filepath.Join(dir, busyNoteName))
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	if !strings.HasPrefix(s, "kind busy\nwhy 18.0 s of CPU in the last 60 s, 30% of a core; 9 MB through it, at ") ||
		!strings.Contains(s, "\nbusy ") || !strings.Contains(s, "line ") || !strings.Contains(s, "musicdnet: busy: 18.0 s of CPU") {
		t.Fatalf("busy note:\n%s", s)
	}
	e.noteBusy("")
	if _, err := os.Stat(filepath.Join(dir, busyNoteName)); !os.IsNotExist(err) {
		t.Fatalf("calm, the note still there: %v", err)
	}
	e.noteBusy("again")
	exit = func(int) {}
	defer func() { exit = os.Exit }()
	e.stopSelf(stopCPU, "the app not using it")
	if _, err := os.Stat(filepath.Join(dir, busyNoteName)); !os.IsNotExist(err) {
		t.Fatalf("stopped itself, the busy note still there: %v", err)
	}
}

// A look, and a stop, never wait on the engine's lock, whatever holds it (a join under way, say).
func TestTheWatchDoesNotWaitOnTheEnginesLock(t *testing.T) {
	e := &engine{dir: t.TempDir()}
	w := e.newWatch(testWatch, time.Now)
	e.mu.Lock()
	defer e.mu.Unlock()
	looked := make(chan struct{})
	go func() { w.look(); close(looked) }()
	select {
	case <-looked:
	case <-time.After(2 * time.Second):
		t.Fatal("the look waits on the engine's lock")
	}
	code := make(chan int, 1)
	exit = func(c int) { code <- c }
	defer func() { exit = os.Exit }()
	go e.stopSelf(stopCPU, "test")
	select {
	case c := <-code:
		if c != exitCPU {
			t.Fatalf("exit %d", c)
		}
	case <-time.After(6 * time.Second):
		t.Fatal("the stop waits on the engine's lock")
	}
}
