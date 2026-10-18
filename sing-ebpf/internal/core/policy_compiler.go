//go:build with_ebpf && (linux || android)

package core

import (
	"net/netip"
	"slices"

	E "github.com/sagernet/sing/common/exceptions"
)

// CompiledPolicy is an immutable, independently scoped match/action snapshot.
// Destination interception precedes port actions, selectors, and bypass CIDRs.
// The library does not infer any application's DNS or routing semantics.
type CompiledPolicy struct {
	uidEntries              []uidLPMKey
	uidDefaultBypass        bool
	sharedDefaultBypass     bool
	localForce              dualStackCIDRPrefixes
	sharedForce             dualStackCIDRPrefixes
	localEchoReply          dualStackCIDRPrefixes
	sharedEchoReply         dualStackCIDRPrefixes
	includeSource           dualStackCIDRPrefixes
	excludeSource           dualStackCIDRPrefixes
	includeSourceMAC        []MACAddress
	excludeSourceMAC        []MACAddress
	localBypassPortEntries  []compiledPortDecision
	sharedBypassPortEntries []compiledPortDecision
	localInitialBypass      dualStackCIDRPrefixes
	sharedInitialBypass     dualStackCIDRPrefixes
	endpointEnabled         bool
	endpoint                dualStackCIDRPrefixes
	endpointPortEntries     []tcPortKey
}

type compiledPortDecision struct {
	key    tcPortKey
	action uint8
}

const (
	portActionPass           uint8 = 1
	portActionIntercept      uint8 = 2
	portActionAfterSelectors uint8 = 4
)

func CompileActionPolicy(config ActionPolicy) (CompiledPolicy, error) {
	if len(config.Local.SourceCIDR)+len(config.Local.SourceMAC) != 0 || len(config.Shared.UID) != 0 {
		return CompiledPolicy{}, E.New("unsupported eBPF policy selector scope")
	}
	local, err := compileActionScope(config.Local, "local")
	if err != nil {
		return CompiledPolicy{}, err
	}
	shared, err := compileActionScope(config.Shared, "shared")
	if err != nil {
		return CompiledPolicy{}, err
	}
	endpoint, endpointPortEntries, err := compileEndpointPolicy(
		config.EndpointEnabled,
		config.EndpointEnableTCP,
		config.EndpointEnableUDP,
		config.EndpointCIDR,
		config.EndpointPort,
		config.EnableTCP,
		config.EnableUDP,
	)
	if err != nil {
		return CompiledPolicy{}, err
	}
	return CompiledPolicy{
		uidEntries: local.uidEntries, uidDefaultBypass: local.uidDefaultBypass,
		sharedDefaultBypass: config.Shared.Default == DecisionPass,
		localForce:          local.force, sharedForce: shared.force,
		localEchoReply: local.echoReply, sharedEchoReply: shared.echoReply,
		includeSource: shared.includeSource, excludeSource: shared.excludeSource,
		includeSourceMAC: shared.includeSourceMAC, excludeSourceMAC: shared.excludeSourceMAC,
		localBypassPortEntries: local.ports, sharedBypassPortEntries: shared.ports,
		localInitialBypass: local.bypass, sharedInitialBypass: shared.bypass,
		endpointEnabled:     config.EndpointEnabled,
		endpoint:            endpoint,
		endpointPortEntries: endpointPortEntries,
	}, nil
}

type compiledActionScope struct {
	uidEntries                         []uidLPMKey
	uidDefaultBypass                   bool
	includeSource, excludeSource       dualStackCIDRPrefixes
	includeSourceMAC, excludeSourceMAC []MACAddress
	force, bypass, echoReply           dualStackCIDRPrefixes
	ports                              []compiledPortDecision
}

func compileActionScope(scope ActionScope, name string) (compiledActionScope, error) {
	result := compiledActionScope{}
	if !scope.Default.Valid() {
		return result, E.New("invalid ", name, " default decision")
	}
	var err error
	result.uidEntries, result.uidDefaultBypass, err = compileUIDActionPolicy(scope.UID, scope.Default)
	if err != nil {
		return result, E.Cause(err, name, " UID policy")
	}
	compilePrefixes := func(rules []CIDRDecision, action Decision, echoReplyOnly bool) (dualStackCIDRPrefixes, error) {
		var prefixes []netip.Prefix
		for _, rule := range rules {
			if !rule.Prefix.IsValid() || !rule.Action.Valid() {
				return dualStackCIDRPrefixes{}, E.New("invalid ", name, " CIDR decision")
			}
			if rule.Action == action && (!echoReplyOnly || rule.EchoReply) {
				prefixes = append(prefixes, rule.Prefix)
			}
		}
		ipv4, ipv6, err := compileCIDRPrefixes(prefixes)
		if len(ipv4) > maxDestinationCIDRPolicyEntries || len(ipv6) > maxDestinationCIDRPolicyEntries {
			return dualStackCIDRPrefixes{}, E.New(name, " CIDR policy exceeds map capacity")
		}
		return dualStackCIDRPrefixes{ipv4, ipv6}, err
	}
	if result.includeSource, err = compilePrefixes(scope.SourceCIDR, DecisionIntercept, false); err != nil {
		return result, err
	}
	if result.excludeSource, err = compilePrefixes(scope.SourceCIDR, DecisionPass, false); err != nil {
		return result, err
	}
	if result.force, err = compilePrefixes(scope.DestinationCIDR, DecisionIntercept, false); err != nil {
		return result, err
	}
	if result.bypass, err = compilePrefixes(scope.DestinationCIDR, DecisionPass, false); err != nil {
		return result, err
	}
	for _, rule := range scope.SourceCIDR {
		if rule.EchoReply {
			return result, E.New("invalid ", name, " source CIDR decision: echo reply applies to destinations")
		}
	}
	for _, rule := range scope.DestinationCIDR {
		if rule.EchoReply && rule.Action != DecisionIntercept {
			return result, E.New("invalid ", name, " destination CIDR decision: echo reply requires intercept")
		}
	}
	if result.echoReply, err = compilePrefixes(scope.DestinationCIDR, DecisionIntercept, true); err != nil {
		return result, err
	}
	for _, rule := range scope.SourceMAC {
		if !rule.Action.Valid() {
			return result, E.New("invalid ", name, " source MAC decision")
		}
		if rule.Action == DecisionPass {
			result.excludeSourceMAC = append(result.excludeSourceMAC, rule.Address)
		} else {
			result.includeSourceMAC = append(result.includeSourceMAC, rule.Address)
		}
	}
	ports := make(map[tcPortKey]uint8)
	for _, rule := range scope.DestinationPort {
		if rule.Port == 0 || !rule.Action.Valid() || (rule.Protocol != ProtocolTCP && rule.Protocol != ProtocolUDP) {
			return result, E.New("invalid ", name, " destination port decision")
		}
		key := tcPortKey{Protocol: rule.Protocol, Port: rule.Port}
		action := uint8(rule.Action) + 1
		if rule.AfterSelectors {
			action |= portActionAfterSelectors
		}
		if previous, exists := ports[key]; exists && previous != action {
			return result, E.New("conflicting ", name, " port decisions")
		}
		ports[key] = action
	}
	for key, action := range ports {
		result.ports = append(result.ports, compiledPortDecision{key, action})
	}
	slices.SortFunc(result.ports, func(a, b compiledPortDecision) int {
		if a.key.Protocol != b.key.Protocol {
			return int(a.key.Protocol) - int(b.key.Protocol)
		}
		return int(a.key.Port) - int(b.key.Port)
	})
	return result, nil
}

func compileEndpointPolicy(
	enabled, enableTCP, enableUDP bool,
	cidr []netip.Prefix,
	portRanges []PortRange,
	globalTCP, globalUDP bool,
) (dualStackCIDRPrefixes, []tcPortKey, error) {
	if !enabled {
		return dualStackCIDRPrefixes{}, nil, nil
	}
	if len(cidr) == 0 || len(portRanges) == 0 {
		return dualStackCIDRPrefixes{}, nil, E.New("TC eBPF endpoint policy requires CIDR and port entries")
	}
	endpointIPv4, endpointIPv6, err := compileCIDRPrefixes(cidr)
	if err != nil {
		return dualStackCIDRPrefixes{}, nil, E.Cause(err, "compile TC eBPF endpoint CIDR policy")
	}
	if len(endpointIPv4) > maxDestinationCIDRPolicyEntries || len(endpointIPv6) > maxDestinationCIDRPolicyEntries {
		return dualStackCIDRPrefixes{}, nil, E.New("TC eBPF endpoint CIDR policy exceeds map capacity")
	}
	endpointEnableTCP := enableTCP
	endpointEnableUDP := enableUDP
	if !endpointEnableTCP && !endpointEnableUDP {
		endpointEnableTCP = true
		endpointEnableUDP = true
	}
	endpointEnableTCP = endpointEnableTCP && globalTCP
	endpointEnableUDP = endpointEnableUDP && globalUDP
	if !endpointEnableTCP && !endpointEnableUDP {
		return dualStackCIDRPrefixes{}, nil, E.New("TC eBPF endpoint network does not overlap enabled inbound network")
	}
	endpointPortEntries, err := compilePortPolicy(portRanges, endpointEnableTCP, endpointEnableUDP)
	if err != nil {
		return dualStackCIDRPrefixes{}, nil, E.Cause(err, "compile TC eBPF endpoint port policy")
	}
	return dualStackCIDRPrefixes{ipv4: endpointIPv4, ipv6: endpointIPv6}, endpointPortEntries, nil
}

func compilePortPolicy(ranges []PortRange, enableTCP, enableUDP bool) ([]tcPortKey, error) {
	var entries []tcPortKey
	for _, portRange := range ranges {
		if portRange.Start == 0 || portRange.Start > portRange.End {
			return nil, E.New("invalid eBPF port bypass range")
		}
		for port := uint32(portRange.Start); port <= uint32(portRange.End); port++ {
			if enableTCP {
				entries = append(entries, tcPortKey{Protocol: ProtocolTCP, Port: uint16(port)})
			}
			if enableUDP {
				entries = append(entries, tcPortKey{Protocol: ProtocolUDP, Port: uint16(port)})
			}
			if len(entries) > tcPortPolicyCapacity {
				return nil, E.New("eBPF port bypass policy exceeds map capacity")
			}
		}
	}
	return entries, nil
}
