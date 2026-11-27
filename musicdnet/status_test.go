package main

import (
	"net/netip"
	"reflect"
	"sort"
	"testing"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

// The owner's own nodes (item 370): this node and the peers signed in as its user; never a node shared in from another
// tailnet, nor a tagged one (another user's), nor an address that is only in Tailscale's range.
func TestOwnIPs(t *testing.T) {
	ip := func(s ...string) []netip.Addr {
		out := []netip.Addr{}
		for _, a := range s {
			out = append(out, netip.MustParseAddr(a))
		}
		return out
	}
	owner, other := tailcfg.UserID(1), tailcfg.UserID(2)
	st := &ipnstate.Status{
		Self: &ipnstate.PeerStatus{UserID: owner, TailscaleIPs: ip("100.90.1.2", "fd7a:115c:a1e0::1")},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			key.NewNode().Public(): {UserID: owner, TailscaleIPs: ip("100.101.102.103")},
			key.NewNode().Public(): {UserID: other, TailscaleIPs: ip("100.64.5.5")},
			key.NewNode().Public(): {UserID: owner, TailscaleIPs: ip("100.70.0.9", "fd7a:115c:a1e0::9")},
		},
	}
	got := ownIPs(st)
	sort.Strings(got)
	want := []string{"100.101.102.103", "100.70.0.9", "100.90.1.2", "fd7a:115c:a1e0::1", "fd7a:115c:a1e0::9"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("own addresses: got %v, want %v", got, want)
	}
	if got := ownIPs(nil); len(got) != 0 {
		t.Fatalf("no status: none, got %v", got)
	}
	if got := ownIPs(&ipnstate.Status{}); len(got) != 0 {
		t.Fatalf("no self yet: none, got %v", got)
	}
}
