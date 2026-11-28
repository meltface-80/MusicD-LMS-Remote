package main

// The phone's engine stops itself (item 404): idle, with nothing but the app's
// long polls through it and no lease from the app; or busy on the CPU with
// nothing passing through. The clock, the CPU and the bytes are the test's.

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type fakeWatch struct {
	t     time.Time
	cpu   float64
	moved int64
	fw    bool
	w     *watcher
}

var testWatch = watchConfig{Tick: 15 * time.Second, IdleAfter: 150 * time.Second, BusyBytes: 16 << 10, CPUWindow: time.Minute,
	CPUShare: 0.20, WarnShare: 0.10}

func newFake() *fakeWatch {
	f := &fakeWatch{t: time.Date(2026, 11, 13, 9, 11, 48, 0, time.UTC), cpu: 100, fw: true}
	f.w = newWatcher(testWatch, func() time.Time { return f.t }, func() float64 { return f.cpu }, func() int64 { return f.moved },
		func() bool { return f.fw })
	return f
}

// tick: one Tick on, with [bytes] through the forward and [share] of a core used meanwhile.
func (f *fakeWatch) tick(bytes int64, share float64) (string, string) {
	f.t = f.t.Add(testWatch.Tick)
	f.moved += bytes
	f.cpu += share * testWatch.Tick.Seconds()
	return f.w.step()
}

// A long poll's request and answer, and a state report, in a Tick: under a kilobyte and a half.
const pollBytes = 1500

func TestIdleWithOnlyLongPollsStops(t *testing.T) {
	f := newFake()
	for i := 1; i < 10; i++ { // 135 s
		if kind, _ := f.tick(pollBytes, 0.01); kind != "" {
			t.Fatalf("stopped after %d s: %s", i*15, kind)
		}
	}
	kind, why := f.tick(pollBytes, 0.01) // 150 s
	if kind != stopIdle || !strings.Contains(why, "2m30s") {
		t.Fatalf("after 150 s of long polls: %q %q", kind, why)
	}
}

func TestRealUseKeepsItAndTheIdleTimeCountsFromIt(t *testing.T) {
	f := newFake()
	for i := 0; i < 40; i++ { // ten minutes of audio through it
		if kind, _ := f.tick(500<<10, 0.05); kind != "" {
			t.Fatalf("stopped while in use: %s", kind)
		}
	}
	for i := 1; i < 10; i++ {
		if kind, _ := f.tick(0, 0); kind != "" {
			t.Fatalf("stopped %d s after the last transfer", i*15)
		}
	}
	if kind, _ := f.tick(0, 0); kind != stopIdle {
		t.Fatalf("150 s after the last transfer: %q", kind)
	}
}

func TestTheAppsLeaseKeepsItThenLetsItGo(t *testing.T) {
	f := newFake()
	f.w.keep(5 * time.Minute) // on screen, or the phone playing a track it holds whole
	for i := 0; i < 19; i++ {
		if kind, _ := f.tick(pollBytes, 0.01); kind != "" {
			t.Fatalf("stopped within the lease, at %d s: %s", (i+1)*15, kind)
		}
	}
	if kind, _ := f.tick(pollBytes, 0.01); kind != stopIdle { // 300 s: the lease is up, idle all along
		t.Fatalf("lease up: %q", kind)
	}
	g := newFake()
	g.w.keep(time.Hour)
	g.w.keep(0) // let go
	for i := 0; i < 9; i++ {
		g.tick(pollBytes, 0.01)
	}
	if kind, _ := g.tick(pollBytes, 0.01); kind != stopIdle {
		t.Fatalf("a lease let go still kept it: %q", kind)
	}
}

func TestNeverIdleWhileBeingSignedIn(t *testing.T) {
	f := newFake()
	f.fw = false // no forward yet: the sign-in page open in a browser
	for i := 0; i < 40; i++ {
		if kind, _ := f.tick(0, 0.01); kind != "" {
			t.Fatalf("stopped while being signed in, at %d s: %s", (i+1)*15, kind)
		}
	}
	f.fw = true // signed in and forwarding: idle counts from now
	for i := 1; i < 10; i++ {
		if kind, _ := f.tick(0, 0.01); kind != "" {
			t.Fatalf("stopped %d s after the forward came", i*15)
		}
	}
	if kind, _ := f.tick(0, 0.01); kind != stopIdle {
		t.Fatalf("150 s after the forward came, idle: %q", kind)
	}
}

func TestCPUGuardStopsABusyEngineWithNothingPassing(t *testing.T) {
	f := newFake()
	f.w.keep(time.Hour) // the guard acts whatever the app wants: Android would end the app
	for i := 1; i < 4; i++ {
		if kind, _ := f.tick(pollBytes, 0.65); kind != "" {
			t.Fatalf("stopped before a whole window, at %d s: %s", i*15, kind)
		}
	}
	kind, why := f.tick(pollBytes, 0.65)
	if kind != stopCPU || !strings.Contains(why, "39.0 s of CPU in the last 60 s, 65% of a core") {
		t.Fatalf("65%% of a core for a minute with long polls only: %q %q", kind, why)
	}
}

func TestCPUGuardLeavesTransfersAndModestUseAlone(t *testing.T) {
	f := newFake()
	f.w.keep(time.Hour)
	for i := 0; i < 20; i++ { // audio through it costs CPU: not a stop
		if kind, _ := f.tick(500<<10, 0.40); kind != "" {
			t.Fatalf("stopped while passing audio: %s", kind)
		}
	}
	for i := 0; i < 20; i++ { // 15% with nothing passing: under the guard
		if kind, _ := f.tick(pollBytes, 0.15); kind != "" {
			t.Fatalf("stopped at 15%% of a core: %s", kind)
		}
	}
	// The last transfer inside the window: not yet; a whole window after it: stopped.
	f.tick(500<<10, 0.40)
	for i := 1; i < 4; i++ {
		if kind, _ := f.tick(pollBytes, 0.50); kind != "" {
			t.Fatalf("stopped %d s after a transfer: %s", i*15, kind)
		}
	}
	if kind, _ := f.tick(pollBytes, 0.50); kind != stopCPU {
		t.Fatalf("a window of 50%% after the last transfer: %q", kind)
	}
	if n := len(f.w.samples); n > 6 {
		t.Fatalf("samples kept: %d (only a window's)", n)
	}
}

func TestCPUUnknownNeverStops(t *testing.T) {
	f := newFake()
	f.w.keep(time.Hour)
	f.cpu = -1
	for i := 0; i < 20; i++ {
		f.cpu = -1
		if kind, _ := f.w.step(); kind != "" {
			t.Fatalf("stopped on an unknown CPU: %s", kind)
		}
		f.t = f.t.Add(testWatch.Tick)
	}
}

func TestKeepRouteAndStopNote(t *testing.T) {
	dir := t.TempDir()
	e := &engine{dir: dir}
	srv := httptest.NewServer(e.handler("k"))
	defer srv.Close()
	keep := func() string {
		req, _ := http.NewRequest("POST", srv.URL+"/keep?s=180", nil)
		req.Header.Set("X-Secret", "k")
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var b strings.Builder
		buf := make([]byte, 256)
		n, _ := res.Body.Read(buf)
		b.Write(buf[:n])
		return b.String()
	}
	if got := keep(); !strings.Contains(got, `"kept":false`) {
		t.Fatalf("the server's engine, no watch: %s", got)
	}
	e.watch = newWatcher(testWatch, time.Now, func() float64 { return 1 }, e.moved.Load, func() bool { return true })
	if got := keep(); !strings.Contains(got, `"kept":true`) || !e.watch.keepUntil.After(time.Now().Add(170*time.Second)) {
		t.Fatalf("the phone's: %s, kept until %v", got, e.watch.keepUntil)
	}

	for i := 0; i < 20; i++ {
		e.logf("magicsock: line %d", i)
	}
	code := -1
	exit = func(c int) { code = c }
	defer func() { exit = os.Exit }()
	e.stopSelf(stopIdle, "nothing but the app's background requests")
	note, err := os.ReadFile(filepath.Join(dir, stopNoteName))
	if err != nil || code != exitIdle {
		t.Fatalf("idle stop: code %d, note %v", code, err)
	}
	s := string(note)
	if !strings.HasPrefix(s, "kind idle\nwhy nothing but the app's background requests\ncpu ") ||
		!strings.Contains(s, "line ") || !strings.Contains(s, "magicsock: line 19\n") || strings.Contains(s, "magicsock: line 7\n") ||
		strings.Contains(s, "busy ") {
		t.Fatalf("idle note:\n%s", s)
	}
	e.stopSelf(stopCPU, "39.0 s of CPU in the last 60 s")
	note, _ = os.ReadFile(filepath.Join(dir, stopNoteName))
	if code != exitCPU || !strings.Contains(string(note), "kind cpu\n") || !strings.Contains(string(note), "\nbusy ") ||
		strings.Count(string(note), "\n") > 30 {
		t.Fatalf("cpu stop: code %d, note:\n%s", code, note)
	}
}
