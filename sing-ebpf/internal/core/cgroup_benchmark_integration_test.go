//go:build with_ebpf && linux && ebpf_integration

package core

import (
	"encoding/binary"
	"errors"
	"net/netip"
	"runtime"
	"testing"
	"unsafe"

	CiliumEBPF "github.com/cilium/ebpf"
	"github.com/cilium/ebpf/asm"
	"golang.org/x/sys/unix"
)

// cgroupSockAddrContext is the userspace-visible prefix of struct
// bpf_sock_addr accepted by BPF_PROG_TEST_RUN. The final socket field remains
// zero: this benchmark compares the selected programs' policy hot path, not a
// live socket's cookie value.
type cgroupSockAddrContext struct {
	UserFamily uint32
	UserIPv4   uint32
	UserIPv6   [4]uint32
	UserPort   uint32
	Family     uint32
	Type       uint32
	Protocol   uint32
	MsgSrcIPv4 uint32
	MsgSrcIPv6 [4]uint32
	Socket     uint64
}

type bpfProgTestRunAttr struct {
	ProgramFD   uint32
	ReturnValue uint32
	DataSizeIn  uint32
	DataSizeOut uint32
	DataIn      uint64
	DataOut     uint64
	Repeat      uint32
	Duration    uint32
	ContextSize uint32
	ContextOut  uint32
	ContextIn   uint64
	ContextData uint64
	Flags       uint32
	CPU         uint32
	BatchSize   uint32
	Reserved    uint32
}

const kernelENOTSUPP = unix.Errno(524)

func BenchmarkCgroupConnect4SelfBypass(b *testing.B) {
	requireEBPFIntegration(b, "benchmark cgroup connect4 self-bypass")
	for _, direct := range []bool{false, true} {
		name := "cookie_hash"
		if direct {
			name = "direct_tgid"
		}
		b.Run(name, func(b *testing.B) {
			benchmarkCgroupConnect4SelfBypass(b, direct)
		})
	}
}

// BenchmarkSelfBypassDecisionMicro isolates the two operations removed or
// added by the direct-TGID object. CGroupSockAddr programs do not have a
// BPF_PROG_TEST_RUN runner on many kernels, so the full-object benchmark above
// skips there; these socket-filter programs keep the same helpers and map type
// while providing a broadly supported kernel-ns/op comparison.
func BenchmarkSelfBypassDecisionMicro(b *testing.B) {
	requireEBPFIntegration(b, "benchmark self-bypass decision operations")
	metadataMap, err := CiliumEBPF.NewMap(&CiliumEBPF.MapSpec{
		Name:       "sb_bench_cookie",
		Type:       CiliumEBPF.Hash,
		KeySize:    8,
		ValueSize:  4,
		MaxEntries: 8192,
		Flags:      unix.BPF_F_NO_PREALLOC,
	})
	if err != nil {
		b.Fatal(err)
	}
	b.Cleanup(func() { _ = metadataMap.Close() })
	key := uint64(1)
	value := uint32(SocketMetadataSelfBypass)
	if err = metadataMap.Update(&key, &value, CiliumEBPF.UpdateAny); err != nil {
		b.Fatal(err)
	}
	programs := []struct {
		name         string
		instructions asm.Instructions
	}{
		{
			name: "cookie_hash",
			instructions: asm.Instructions{
				asm.StoreImm(asm.RFP, -8, int64(key), asm.DWord),
				asm.LoadMapPtr(asm.R1, metadataMap.FD()),
				asm.Mov.Reg(asm.R2, asm.RFP),
				asm.Add.Imm(asm.R2, -8),
				asm.FnMapLookupElem.Call(),
				asm.JEq.Imm(asm.R0, 0, "allow"),
				asm.LoadMem(asm.R0, asm.R0, 0, asm.Word),
				asm.And.Imm(asm.R0, SocketMetadataSelfBypass),
				asm.JEq.Imm(asm.R0, 0, "allow"),
				asm.Mov.Imm(asm.R0, 0),
				asm.Return(),
				asm.Mov.Imm(asm.R0, 1).WithSymbol("allow"),
				asm.Return(),
			},
		},
		{
			name: "direct_tgid",
			instructions: asm.Instructions{
				asm.FnGetCurrentPidTgid.Call(),
				asm.RSh.Imm(asm.R0, 32),
				asm.JNE.Imm(asm.R0, 1, "allow"),
				asm.Mov.Imm(asm.R0, 0),
				asm.Return(),
				asm.Mov.Imm(asm.R0, 1).WithSymbol("allow"),
				asm.Return(),
			},
		},
	}
	packet := make([]byte, 64)
	for _, candidate := range programs {
		b.Run(candidate.name, func(b *testing.B) {
			program, err := CiliumEBPF.NewProgram(&CiliumEBPF.ProgramSpec{
				Name:         "sb_bench_self",
				Type:         CiliumEBPF.SocketFilter,
				License:      "GPL",
				Instructions: candidate.instructions,
			})
			if err != nil {
				b.Fatal(err)
			}
			defer program.Close()
			b.ReportMetric(float64(len(candidate.instructions)), "instructions")
			var result uint32
			var kernelDurationNS int64
			b.ResetTimer()
			for range b.N {
				currentResult, duration, durationErr := program.Benchmark(packet, 1, nil)
				if durationErr != nil {
					b.Fatal(durationErr)
				}
				result = currentResult
				kernelDurationNS += duration.Nanoseconds()
			}
			b.StopTimer()
			if result != 0 && result != 1 {
				b.Fatalf("unexpected result: %d", result)
			}
			b.ReportMetric(float64(kernelDurationNS)/float64(b.N), "kernel-ns/op")
		})
	}
}

func benchmarkCgroupConnect4SelfBypass(b *testing.B, direct bool) {
	bypass, err := NewSelfBypassWithCapacity(64)
	if err != nil {
		b.Fatal(err)
	}
	selfMap, err := bypass.PreparedMap()
	if err != nil {
		_ = bypass.Close()
		b.Fatal(err)
	}
	runtimeState := &cgroupRuntime{
		maps:        make(map[string]*CiliumEBPF.Map),
		programs:    make([]*CiliumEBPF.Program, cgroupProgramCount),
		enable_tcp:  true,
		direct_tgid: direct,
	}
	capacity := CgroupMapCapacity{TCPRedirect: 64, UDPRedirect: 1, UDPPeer: 1, UDPFlow: 1, SocketBypass: 64}
	backend := &CgroupBackend{
		runtime:        runtimeState,
		mapCapacity:    capacity,
		redirectIPv4:   netip.MustParsePrefix("127.0.0.0/8"),
		selfBypass:     bypass,
		selfBypassTGID: 1, // Deliberately does not match this benchmark process.
	}
	b.Cleanup(func() {
		_ = backend.Close()
		_ = bypass.Close()
	})
	if err = prepareCgroupMaps(runtimeState, capacity, 0, 0, selfMap, dualStackCIDRPrefixes{}); err != nil {
		b.Fatal(err)
	}
	backend.listenerPort = 65530
	if err = backend.updateCgroupControl(backend.listenerPort); err != nil {
		b.Fatal(err)
	}
	runtimeState.programs, err = backend.loadCgroupObjectPrograms()
	if err != nil {
		b.Fatal(err)
	}
	if runtimeState.direct_tgid != direct {
		b.Fatalf("selected direct-TGID object = %v, want %v", runtimeState.direct_tgid, direct)
	}
	program := runtimeState.programs[cgroupProgramConnect4]
	if program == nil {
		b.Fatal("cgroup connect4 program was not loaded")
	}
	loadSpec := cgroupLoadSpec(runtimeState)
	spec, err := loadSpec()
	if err != nil {
		b.Fatal(err)
	}
	for _, candidate := range spec.Programs {
		if candidate.SectionName == "cgroup/connect4_cookie" {
			b.ReportMetric(float64(len(candidate.Instructions)), "instructions")
			break
		}
	}

	address := netip.MustParseAddr("203.0.113.1").As4()
	context := cgroupSockAddrContext{
		UserFamily: unix.AF_INET,
		UserIPv4:   binary.NativeEndian.Uint32(address[:]),
		UserPort:   uint32(binary.NativeEndian.Uint16([]byte{0x01, 0xbb})),
		Family:     unix.AF_INET,
		Type:       unix.SOCK_STREAM,
		Protocol:   unix.IPPROTO_TCP,
	}
	attribute := bpfProgTestRunAttr{
		ProgramFD:   uint32(program.FD()),
		Repeat:      1,
		ContextSize: uint32(unsafe.Sizeof(context)),
		ContextIn:   uint64(uintptr(unsafe.Pointer(&context))),
	}
	var kernelDurationNS uint64
	b.ResetTimer()
	for range b.N {
		attribute.Duration = 0
		for {
			_, _, errno := unix.Syscall(unix.SYS_BPF, unix.BPF_PROG_TEST_RUN,
				uintptr(unsafe.Pointer(&attribute)), unsafe.Sizeof(attribute))
			if errno == 0 || errors.Is(errno, unix.EINTR) {
				break
			}
			if errors.Is(errno, kernelENOTSUPP) || errors.Is(errno, unix.EOPNOTSUPP) {
				b.Skip("kernel does not support BPF_PROG_TEST_RUN for cgroup socket-address programs")
			}
			b.Fatal(errno)
		}
		kernelDurationNS += uint64(attribute.Duration)
	}
	b.StopTimer()
	runtime.KeepAlive(&context)
	b.ReportMetric(float64(kernelDurationNS)/float64(b.N), "kernel-ns/op")
}
