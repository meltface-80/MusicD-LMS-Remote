package main

import (
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// Bug list item 158: the app's port taken by something else, the forwarder took another without a word, and the app
// went on sending to the port it named — its sign-in with every request — to whatever held it.
func TestForwarderRefusesATakenPort(t *testing.T) {
	squat, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer squat.Close()
	port := squat.Addr().(*net.TCPAddr).Port
	f := &Forwarder{}
	if addr, err := f.StartOn(port); err == nil {
		f.Close()
		t.Fatalf("port %d taken, yet the forwarder listens on %s", port, addr)
	}
	g := &Forwarder{}
	addr, err := g.StartOn(0)
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	if addr == "" {
		t.Fatal("asked for any free port: no address")
	}
}

// Bug list item 228: the probe asked /api/health, which waits on a busy server; over Tailscale's relay that took longer
// than the probe's time, and the app, taking the tunnel for broken, dropped the connections getting through. It asks
// /api/ping first (answered at once), and /api/health only of a server from before it.
func TestProbeAsksThePingFirst(t *testing.T) {
	serve := func(ping int, healthDelay time.Duration) string {
		mux := http.NewServeMux()
		if ping != 0 {
			mux.HandleFunc("/api/ping", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(ping) })
		}
		mux.HandleFunc("/api/health", func(w http.ResponseWriter, r *http.Request) {
			select {
			case <-time.After(healthDelay):
			case <-r.Context().Done():
			}
			io.WriteString(w, `{"ok":true}`)
		})
		srv := httptest.NewServer(mux)
		t.Cleanup(srv.Close)
		return strings.TrimPrefix(srv.URL, "http://")
	}
	hc := &http.Client{Timeout: 500 * time.Millisecond}
	if !probe(hc, serve(200, 3*time.Second)) {
		t.Fatal("a busy server whose ping answers: not found answering")
	}
	if !probe(hc, serve(0, 0)) {
		t.Fatal("a server from before /api/ping, its health answering: not found answering")
	}
	if probe(hc, serve(500, 0)) {
		t.Fatal("a ping answering 500: taken for answering")
	}
	if probe(hc, serve(0, 3*time.Second)) {
		t.Fatal("no ping and a health that doesn't answer in time: taken for answering")
	}
}
