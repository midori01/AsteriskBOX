//go:build with_ebpf && linux && ebpf_integration

package core

import (
	"errors"
	"net/netip"
	"testing"

	CiliumEBPF "github.com/cilium/ebpf"
	"github.com/cilium/ebpf/asm"
	"github.com/cilium/ebpf/features"
)

func TestSelectedObjectLoadProbeIntegration(t *testing.T) {
	requireEBPFIntegration(t, "load the selected eBPF objects without attaching them")
	tests := []struct {
		name     string
		tcSocket bool
		load     func() (string, error)
	}{
		{
			name:     "tc",
			tcSocket: true,
			load: func() (string, error) {
				return loadTCProbeObject(kernelProbePlan{
					localTC: true, sharedSocketAssign: true,
					enableTCP: true, enableUDP: true, enableIPv6: true,
				})
			},
		},
		{
			name: "cgroup",
			load: func() (string, error) {
				return loadCgroupProbeObject(kernelProbePlan{
					localCgroup: true, enableTCP: true, enableUDP: true, enableIPv6: true,
				})
			},
		},
		{
			name: "packet_rewrite",
			load: func() (string, error) {
				return loadSharedNetworkProbeObject(kernelProbePlan{
					sharedPacketRewrite: true, enableTCP: true, enableUDP: true, enableIPv6: true,
				})
			},
		},
		{
			name: "icmp_echo_reply",
			load: func() (string, error) {
				return loadICMPEchoReplyProbeObject(kernelProbePlan{
					localTC: true, enableIPv6: true,
				})
			},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if test.tcSocket {
				requireTCSocketAssignment(t)
			}
			if _, err := test.load(); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestTCObjectSelectionMatrixIntegration(t *testing.T) {
	requireEBPFIntegration(t, "load every selected TC object path")
	requireTCSocketAssignment(t)
	tests := []struct {
		name      string
		local     bool
		shared    bool
		storage   bool
		legacyTCP bool
	}{
		{"local", true, false, false, false},
		{"local_legacy_tcp", true, false, false, true},
		{"local_storage", true, false, true, false},
		{"local_storage_legacy_tcp", true, false, true, true},
		{"shared", false, true, false, false},
		{"shared_legacy_tcp", false, true, false, true},
		{"combined", true, true, false, false},
		{"combined_legacy_tcp", true, true, false, true},
		{"combined_storage", true, true, true, false},
		{"combined_storage_legacy_tcp", true, true, true, true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			var bypass *SelfBypass
			var selfMap, storage *CiliumEBPF.Map
			if test.local {
				var err error
				bypass, err = NewSelfBypassWithCapacity(64)
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { _ = bypass.Close() })
				selfMap, err = bypass.PreparedMap()
				if err != nil {
					t.Fatal(err)
				}
				if test.storage {
					if features.HaveMapType(CiliumEBPF.SkStorage) != nil || features.HaveProgramHelper(CiliumEBPF.SchedCLS, asm.FnSkStorageGet) != nil {
						t.Skip("TC socket-local storage is unavailable")
					}
					bypass.access.Lock()
					storage, err = bypass.prepareStorageLocked()
					bypass.access.Unlock()
					if err != nil {
						t.Skipf("TC socket-local storage map is unavailable: %v", err)
					}
				}
			}
			backend, err := prepareTC(TCConfig{
				ListenerPort: 1, DeliveryInterface: 1, RoutingMark: DefaultTCRoutingMark,
				EnableLocal: test.local, EnableShared: test.shared, EnableIPv4: true,
				EnableLocalIPv6: test.local, EnableSharedIPv6: test.shared,
				EnableTCP: true, EnableUDP: true, AssignmentCapacity: 64,
				SelfBypassMap: selfMap, SelfBypassStorage: storage,
			}, test.legacyTCP)
			if err != nil {
				t.Fatal(err)
			}
			defer backend.Close()
			if backend.tcpListenerMap == test.legacyTCP {
				t.Fatalf("selected TCP path drifted: sockmap=%v legacy=%v", backend.tcpListenerMap, test.legacyTCP)
			}
			if backend.selfStorageActive != test.storage {
				t.Fatalf("selected storage path drifted: active=%v want=%v", backend.selfStorageActive, test.storage)
			}
		})
	}
}

func TestCgroupObjectSelectionMatrixIntegration(t *testing.T) {
	requireEBPFIntegration(t, "load every selected cgroup object path")
	tests := []struct {
		name                    string
		direct, coarse, storage bool
	}{
		{"base", false, false, false},
		{"coarse", false, true, false},
		{"direct", true, false, false},
		{"direct_coarse", true, true, false},
		{"storage", false, false, true},
		{"storage_coarse", false, true, true},
		{"direct_storage", true, false, true},
		{"direct_storage_coarse", true, true, true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if test.direct && features.HaveProgramHelper(CiliumEBPF.CGroupSockAddr, asm.FnGetCurrentPidTgid) != nil {
				t.Skip("cgroup current-TGID helper is unavailable")
			}
			if test.coarse && features.HaveProgramHelper(CiliumEBPF.CGroupSockAddr, asm.FnKtimeGetCoarseNs) != nil {
				t.Skip("cgroup coarse-time helper is unavailable")
			}
			if test.storage && (features.HaveMapType(CiliumEBPF.SkStorage) != nil || features.HaveProgramHelper(CiliumEBPF.CGroupSockAddr, asm.FnSkStorageGet) != nil) {
				t.Skip("cgroup socket-local storage is unavailable")
			}
			bypass, err := NewSelfBypassWithCapacity(64)
			if err != nil {
				t.Fatal(err)
			}
			defer bypass.Close()
			selfMap, err := bypass.PreparedMap()
			if err != nil {
				t.Fatal(err)
			}
			runtimeState := &cgroupRuntime{
				maps: make(map[string]*CiliumEBPF.Map), programs: make([]*CiliumEBPF.Program, cgroupProgramCount),
				enable_tcp: true, enable_udp: true, direct_tgid: test.direct,
				coarse_time_supported: test.coarse, sk_storage: test.storage,
				socket_release_supported: test.storage,
			}
			backend := &CgroupBackend{
				runtime: runtimeState, redirectIPv4: netip.MustParsePrefix("127.0.0.0/8"),
				redirectIPv6: netip.MustParsePrefix("fd00::/64"), enableIPv6: true, selfBypassTGID: 1,
			}
			if err = prepareCgroupMaps(runtimeState, CgroupMapCapacity{
				TCPRedirect: 64, UDPRedirect: 64, UDPPeer: 64, UDPFlow: 64, SocketBypass: 64,
			}, 0, 0, selfMap, dualStackCIDRPrefixes{}); err == nil {
				runtimeState.programs, err = backend.loadCgroupObjectPrograms()
			}
			if err != nil {
				if test.storage && errors.Is(err, CiliumEBPF.ErrNotSupported) {
					t.Skipf("cgroup storage object is unavailable: %v", err)
				}
				t.Fatal(err)
			}
			defer backend.Close()
			if runtimeState.direct_tgid != test.direct || runtimeState.coarse_time_supported != test.coarse || runtimeState.sk_storage != test.storage {
				t.Fatalf("selected cgroup object drifted: direct=%v coarse=%v storage=%v", runtimeState.direct_tgid, runtimeState.coarse_time_supported, runtimeState.sk_storage)
			}
		})
	}
}
