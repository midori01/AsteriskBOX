//go:build with_ebpf && (linux || android)

package core

import (
	"errors"
	"fmt"
	"slices"

	CiliumEBPF "github.com/cilium/ebpf"
)

// RuntimeResources identifies resources held by one owner, not resources with
// matching names elsewhere on the host. IDs are snapshots: inspection may race
// with shutdown, in which case it reports an error instead of a false zero.
type RuntimeResources struct {
	Programs []RuntimeProgram
	Maps     []RuntimeMapResource
	Error    error
}

// RuntimeMapResource binds an owned map ID to the semantic metadata selected
// by its loader. Metadata stays private so consumers cannot fabricate health
// classifications; RuntimeResources remains the only diagnostics hand-off.
type RuntimeMapResource struct {
	ID       CiliumEBPF.MapID
	metadata mapMetadata
	info     *CiliumEBPF.MapInfo
}

func (r *RuntimeResources) add(programs []*CiliumEBPF.Program, maps map[string]*CiliumEBPF.Map, metadata map[string]mapMetadata) {
	for _, program := range programs {
		if program == nil {
			continue
		}
		info, err := program.Info()
		if err != nil {
			r.Error = errors.Join(r.Error, err)
			continue
		}
		if id, ok := info.ID(); ok {
			mapIDs, _ := info.MapIDs()
			r.Programs = append(r.Programs, RuntimeProgram{ID: id, Name: info.Name, Type: info.Type, MapIDs: mapIDs, MapCount: len(mapIDs)})
		}
	}
	for name, m := range maps {
		if m == nil {
			continue
		}
		info, err := m.Info()
		if err != nil {
			r.Error = errors.Join(r.Error, err)
			continue
		}
		if id, ok := info.ID(); ok {
			r.Maps = append(r.Maps, RuntimeMapResource{ID: id, metadata: metadata[name].normalized(), info: info})
		}
	}
}

func (r *RuntimeResources) merge(other RuntimeResources) {
	r.Programs = append(r.Programs, other.Programs...)
	r.Maps = append(r.Maps, other.Maps...)
	r.Error = errors.Join(r.Error, other.Error)
}

func (b *TCBackend) RuntimeResources() (r RuntimeResources) {
	if b == nil {
		return
	}
	b.access.RLock()
	defer b.access.RUnlock()
	if b.runtime != nil {
		r.add(b.runtime.programs, b.runtime.maps, b.runtime.mapMetadata)
	}
	r.merge(b.icmpEchoReply.RuntimeResources())
	return
}
func (b *CgroupBackend) RuntimeResources() (r RuntimeResources) {
	if b == nil {
		return
	}
	b.access.RLock()
	defer b.access.RUnlock()
	if b.runtime != nil {
		r.add(b.runtime.programs, b.runtime.maps, b.runtime.mapMetadata)
	}
	return
}
func (b *SharedPacketRewriteBackend) RuntimeResources() (r RuntimeResources) {
	if b == nil {
		return
	}
	b.access.RLock()
	defer b.access.RUnlock()
	if b.runtime != nil {
		r.add(b.runtime.programs, b.runtime.maps, b.runtime.mapMetadata)
	}
	r.merge(b.icmpEchoReply.RuntimeResources())
	return
}
func (b *ICMPEchoReplyBackend) RuntimeResources() (r RuntimeResources) {
	if b == nil {
		return
	}
	b.access.RLock()
	defer b.access.RUnlock()
	if b.runtime != nil {
		r.add(b.runtime.programs, b.runtime.maps, b.runtime.mapMetadata)
	}
	return
}
func (b *SelfBypass) RuntimeResources() (r RuntimeResources) {
	if b == nil {
		return
	}
	b.access.RLock()
	defer b.access.RUnlock()
	r.add(b.programs, map[string]*CiliumEBPF.Map{"sockets": b.sockets, "storage": b.storage}, map[string]mapMetadata{
		"sockets": {category: MapCategoryRuntime, admissionCritical: true},
		"storage": {category: MapCategoryRuntime},
	})
	return
}
func (b *ProcessTracker) RuntimeResources() (r RuntimeResources) {
	if b == nil {
		return
	}
	b.access.RLock()
	defer b.access.RUnlock()
	r.add(b.programs, map[string]*CiliumEBPF.Map{"owners": b.owners, "policy": b.policyUID, "metadata": b.metadata}, map[string]mapMetadata{
		"owners":   {category: MapCategoryRuntime, evictionMode: mapEvictionLRUSafeCache},
		"policy":   {category: MapCategoryConfiguredPolicy},
		"metadata": {category: MapCategoryRuntime, evictionMode: mapEvictionLRUSafeCache},
	})
	return
}

func InspectRuntimeResources(owners ...RuntimeResources) RuntimeState {
	var resources RuntimeResources
	for _, owner := range owners {
		resources.merge(owner)
	}
	slices.SortFunc(resources.Programs, func(a, b RuntimeProgram) int { return int(a.ID) - int(b.ID) })
	resources.Programs = slices.CompactFunc(resources.Programs, func(a, b RuntimeProgram) bool { return a.ID == b.ID })
	slices.SortFunc(resources.Maps, func(a, b RuntimeMapResource) int { return int(a.ID) - int(b.ID) })
	resources.Maps = slices.CompactFunc(resources.Maps, func(a, b RuntimeMapResource) bool {
		if a.ID != b.ID {
			return false
		}
		if a.metadata != b.metadata {
			resources.Error = errors.Join(resources.Error, fmt.Errorf("conflicting semantic metadata for eBPF map id %d", a.ID))
		}
		return true
	})
	state := RuntimeState{ProgramsError: resources.Error, MapOccupancy: MapOccupancyReport{Status: "pass"}}
	if resources.Error != nil {
		state.MapOccupancy.Error = resources.Error.Error()
	}
	for _, program := range resources.Programs {
		state.Programs = append(state.Programs, program)
	}
	for _, resource := range resources.Maps {
		m, err := CiliumEBPF.NewMapFromID(resource.ID)
		if err != nil {
			state.MapOccupancy.Maps = append(state.MapOccupancy.Maps, MapOccupancy{ID: resource.ID, Error: fmt.Sprint(err), Pressure: "unavailable"})
			continue
		}
		if resource.info == nil {
			_ = m.Close()
			state.MapOccupancy.Maps = append(state.MapOccupancy.Maps, MapOccupancy{ID: resource.ID, Error: "map metadata snapshot unavailable", Pressure: "unavailable"})
			continue
		}
		state.MapOccupancy.Maps = append(state.MapOccupancy.Maps, inspectMapOccupancy(m, resource.ID, resource.info, resource.metadata))
		_ = m.Close()
	}
	state.MapOccupancy.Status = mapOccupancyStatus(state.MapOccupancy.Maps)
	if state.MapOccupancy.Error != "" {
		state.MapOccupancy.Status = "unknown"
	}
	return state
}
