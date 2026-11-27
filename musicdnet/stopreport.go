// What the engine says when it stops itself (item 404): a note in its state
// directory, read by the app into its log as the engine ends, or at the next
// start if the app was frozen or ended meanwhile. On 13 Nov Android ended the
// app twice for the engine's CPU and nothing of the engine's reached the log;
// now a CPU stop says what was busy: its goroutines in a run state, sampled,
// how many there are by state and function, the runtime's figures, and its
// last log lines. Bounded: a few dozen short lines, once, then it exits.
//
// The note, one item a line:
//
//	kind idle|cpu|busy
//	why <the watch's words>
//	cpu <seconds of CPU so far>
//	busy <a line on what was busy>       (a CPU stop, or busy.txt, only)
//	line <one of its last log lines>
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

const (
	stopNoteName = "stopped.txt"
	busyNoteName = "busy.txt" // item 416: what it was doing when last busy, for after an end by Android
	exitIdle     = 3          // the app tells a stop of its own from a crash by these
	exitCPU      = 4
	noteLineMax  = 300
)

// exit is os.Exit, but not in tests.
var exit = os.Exit

// stopSelf writes the note, closes Tailscale (three seconds at most: what is
// spinning may hold it) and exits.
func (e *engine) stopSelf(kind, why string) {
	os.WriteFile(filepath.Join(e.dir, stopNoteName), []byte(e.stopNote(kind, why)), 0o600)
	os.Remove(filepath.Join(e.dir, busyNoteName)) // the stop's note says it all
	done := make(chan struct{})
	go func() { // the lock too within the three seconds (item 416: nothing the engine holds keeps it)
		e.mu.Lock()
		ts := e.ts
		e.mu.Unlock()
		if ts != nil {
			ts.Close()
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
	}
	if kind == stopCPU {
		exit(exitCPU)
	} else {
		exit(exitIdle)
	}
}

// noteBusy: the watch found it busy (item 416), what it is doing said in
// its log and in a note the app reads at its next start: Android ends the app,
// and the engine with it, five minutes into a load the engine itself lets be
// (a transfer with the app's lease). "" once a window is calm: the note goes.
func (e *engine) noteBusy(what string) {
	p := filepath.Join(e.dir, busyNoteName)
	if what == "" {
		os.Remove(p)
		return
	}
	e.logf("musicdnet: busy: %s", what)
	os.WriteFile(p, []byte(e.stopNote(kindBusy, what+", at "+time.Now().Format("15:04:05"))), 0o600)
}

const kindBusy = "busy"

func (e *engine) stopNote(kind, why string) string {
	var b strings.Builder
	add := func(key, s string) {
		s = strings.ReplaceAll(s, "\n", " ")
		if len(s) > noteLineMax {
			s = s[:noteLineMax] + "…"
		}
		b.WriteString(key + " " + s + "\n")
	}
	add("kind", kind)
	add("why", why)
	add("cpu", fmt.Sprintf("%.1f", cpuSeconds()))
	if kind == stopCPU || kind == kindBusy {
		for _, l := range busyReport(10, 100*time.Millisecond) {
			add("busy", l)
		}
	}
	e.logMu.Lock()
	last := append([]string(nil), e.lines[max(0, len(e.lines)-12):]...)
	e.logMu.Unlock()
	for _, l := range last {
		add("line", l)
	}
	return b.String()
}

// busyReport samples every goroutine [samples] times, [gap] apart: those
// running, runnable or in a system call each time (a spinning one is among
// them), by function; then all of them by state and function, and the
// runtime's own figures (a heap churning is the GC's CPU, not a goroutine's).
func busyReport(samples int, gap time.Duration) []string {
	busy := map[string]int{}
	var all map[string]int
	total := 0
	buf := make([]byte, 4<<20)
	for i := 0; i < samples; i++ {
		n := runtime.Stack(buf, true)
		b, a, t := tallyStacks(string(buf[:n]))
		for k, v := range b {
			busy[k] += v
		}
		all, total = a, t
		if i < samples-1 {
			time.Sleep(gap)
		}
	}
	var out []string
	for _, kv := range top(busy, 6) {
		out = append(out, fmt.Sprintf("in %d of %d looks: %s", kv.n, samples, kv.k))
	}
	var parts []string
	for _, kv := range top(all, 6) {
		parts = append(parts, fmt.Sprintf("%d %s", kv.n, kv.k))
	}
	out = append(out, fmt.Sprintf("%d goroutines; most: %s", total, strings.Join(parts, "; ")))
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	out = append(out, fmt.Sprintf("heap %d MB, %d GCs, GC %.1f%% of its CPU", ms.HeapAlloc>>20, ms.NumGC, 100*ms.GCCPUFraction))
	return out
}

// tallyStacks reads runtime.Stack's dump of all goroutines: those in a run
// state by state and function, all by state and function, and how many. A
// goroutine's function is its first frame outside the runtime and the
// standard library's plumbing; this sampler's own is left out.
func tallyStacks(dump string) (busy, all map[string]int, total int) {
	busy, all = map[string]int{}, map[string]int{}
	for _, g := range strings.Split(dump, "\n\n") {
		lines := strings.Split(strings.TrimSpace(g), "\n")
		if len(lines) < 2 || !strings.HasPrefix(lines[0], "goroutine ") {
			continue
		}
		if strings.Contains(g, ".busyReport(") {
			continue
		}
		state := lines[0]
		if i := strings.IndexByte(state, '['); i >= 0 {
			state = state[i+1:]
		}
		state = strings.TrimSuffix(state, "]:")
		if i := strings.IndexByte(state, ','); i >= 0 {
			state = state[:i]
		}
		fn := ""
		for _, l := range lines[1:] {
			if strings.HasPrefix(l, "\t") || strings.HasPrefix(l, "created by ") {
				continue
			}
			name := l
			if i := strings.LastIndexByte(name, '('); i > 0 {
				name = name[:i]
			}
			if fn == "" {
				fn = name
			}
			if !plumbing(name) {
				fn = name
				break
			}
		}
		key := state + " " + fn
		total++
		all[key]++
		if state == "running" || state == "runnable" || state == "syscall" {
			busy[key]++
		}
	}
	return busy, all, total
}

func plumbing(fn string) bool {
	for _, p := range []string{"runtime.", "internal/", "sync.", "sync/", "syscall.", "time.", "io.", "bufio.", "net.", "os.", "context."} {
		if strings.HasPrefix(fn, p) {
			return true
		}
	}
	return false
}

type kv struct {
	k string
	n int
}

func top(m map[string]int, n int) []kv {
	var out []kv
	for k, v := range m {
		out = append(out, kv{k, v})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].n > out[j].n || (out[i].n == out[j].n && out[i].k < out[j].k) })
	if len(out) > n {
		out = out[:n]
	}
	return out
}
