package main

// A CPU stop says what was busy (item 404): a goroutine spinning is found by
// its function, and every goroutine is counted by state and function.

import (
	"regexp"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

//go:noinline
func spinForTest(stop *atomic.Bool) int {
	x := 0
	for !stop.Load() {
		x++
	}
	return x
}

func waitForTest(ch chan struct{}) { <-ch }

func TestBusyReportFindsTheSpinningGoroutine(t *testing.T) {
	var stop atomic.Bool
	done := make(chan struct{})
	go func() { spinForTest(&stop); close(done) }()
	park := make(chan struct{})
	for i := 0; i < 3; i++ {
		go waitForTest(park)
	}
	defer func() { stop.Store(true); close(park); <-done }()
	time.Sleep(20 * time.Millisecond)

	lines := busyReport(5, 10*time.Millisecond)
	if len(lines) < 3 || len(lines) > 8 {
		t.Fatalf("lines: %q", lines)
	}
	if !strings.HasPrefix(lines[0], "in ") || !strings.Contains(lines[0], ".spinForTest") ||
		!(strings.Contains(lines[0], " running ") || strings.Contains(lines[0], " runnable ")) {
		t.Fatalf("the spinner isn't the busiest: %q", lines)
	}
	joined := strings.Join(lines, "\n")
	if !regexp.MustCompile(`3 chan receive \S*\.waitForTest`).MatchString(joined) {
		t.Fatalf("the parked ones aren't counted by state and function: %q", lines)
	}
	if strings.Contains(joined, "busyReport") {
		t.Fatalf("the sampler counted itself: %q", lines)
	}
	if !strings.Contains(lines[len(lines)-1], "GCs") {
		t.Fatalf("no runtime figures: %q", lines)
	}
}

func TestTallyStacksReadsTheDump(t *testing.T) {
	dump := "goroutine 1 [running]:\nmain.hot(0x1)\n\t/x/a.go:3 +0x1\ncreated by main.main in goroutine 1\n\t/x/a.go:9 +0x2\n\n" +
		"goroutine 7 [select, 3 minutes]:\nruntime.gopark(0x0)\n\t/r/proc.go:1\ntailscale.com/wgengine/magicsock.(*Conn).receive(0x2)\n\t/t/m.go:5\n\n" +
		"goroutine 8 [IO wait]:\ninternal/poll.runtime_pollWait()\n\t/p.go:1\nnet.(*conn).Read()\n\t/n.go:1\nmain.(*Forwarder).handle.func1()\n\t/f.go:1\n"
	busy, all, total := tallyStacks(dump)
	if total != 3 || busy["running main.hot"] != 1 || len(busy) != 1 {
		t.Fatalf("busy %v total %d", busy, total)
	}
	if all["select tailscale.com/wgengine/magicsock.(*Conn).receive"] != 1 || all["IO wait main.(*Forwarder).handle.func1"] != 1 {
		t.Fatalf("all %v", all)
	}
}
