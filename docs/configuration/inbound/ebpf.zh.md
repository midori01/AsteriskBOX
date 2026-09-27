---
icon: material/memory
---

# eBPF

!!! quote "sing-box 1.15.0 中的更改"

    eBPF 入站仍为实验功能，仅在带有 `with_ebpf` 编译标签的 Linux 和 Android
    构建中可用。

eBPF 入站将选中的本机或下游 TCP/UDP 流量透明送入 sing-box 常规路由流程，并自动
创建和清理所需的内核网络状态。它不使用[监听字段](/zh/configuration/shared/listen/)。

## 示例

使用默认 cgroup 数据面接管本机流量：

```json
{
  "type": "ebpf",
  "tag": "ebpf-in",
  "network": ["tcp", "udp"],
  "local": {
    "enabled": true,
    "data_plane": "cgroup",
    "dns_mode": "respect_policy",
    "bypass_private_address": true
  }
}
```

还需要接管下游客户端时，加入 shared 路径并替换接口名：

```json
{
  "shared": {
    "enabled": true,
    "data_plane": "packet_rewrite",
    "interface": ["wlan1"],
    "dns_mode": "respect_policy",
    "bypass_private_address": true
  }
}
```

## 数据面

| 路径 | 数据面 | 用途 |
| --- | --- | --- |
| local | `cgroup`（默认） | 在 cgroup v2 层级接管本机 socket，不跟随网络接口。 |
| local | `tc` | 在当前默认接口接管本机报文。 |
| shared | `packet_rewrite`（默认） | 在以太网帧下游接口改写报文并恢复回复。 |
| shared | `socket_assign` | 将报文分配给透明监听器，也支持 raw-IP、PPP 和隧道链路。 |

除非目标内核或链路类型需要其他路径，建议使用默认值。各路径的内核能力与接口差异
见 [eBPF 内核要求](/zh/manual/misc/ebpf-kernel-requirements/)。

## 字段

### network

启用的传输协议：`tcp`、`udp` 或两者，默认同时启用。

### udp_timeout

UDP 会话超时，默认 `5m`。

### tc_priority

TC filter 优先级，范围 1 至 65535，默认 `1`。仅在需要与其他 filter 协调顺序时
修改。默认值允许在内核支持时使用 TCX；自定义优先级会使用 `clsact`，以保留数值
排序语义。

### fakeip_icmp

| 值 | 行为 |
| --- | --- |
| `off` | 不响应发往 FakeIP 的 ICMP Echo Request，默认值。 |
| `reply` | 对发往已配置 FakeIP 前缀且安全、未分片的请求合成本地 Echo Reply。 |

该回复只表示本机作出了响应，不反映映射目标的可达性或延迟。local `cgroup` 没有报文
hook，无法响应本机 ICMP；local `tc` 和两种 shared 数据面可响应各自路径上的请求。

## local

### local.enabled

启用本机流量接管。如果 local/shared 均未显式配置 `enabled`，默认启用 local、禁用
shared；一旦任一字段显式出现，未显式启用的路径即为禁用。

### local.data_plane

可选 `cgroup`（默认）或 `tc`。TC 路径跟随当前默认接口，cgroup 路径跟随选中的
cgroup v2 子树。

当 `local.endpoint_connected_bypass.enabled` 为 `true` 时，省略
`local.data_plane` 会自动选择 `tc`。该 TC-only 策略不能与显式 `cgroup` 或
`local.cgroup_path` 同时使用。

### local.cgroup_path

`cgroup` 数据面使用的绝对 cgroup v2 子树。省略时接管当前可见的 cgroup v2 根层级
及其子层级。

Android 厂商的 netd hook 可能造成挂载冲突。sing-box 优先尝试多程序挂载，只在兼容
错误下回退旧式独占挂载；设备无法安全共享根 cgroup hook 时应使用 local `tc`。

### local.dns_mode

| 值 | 对目标端口 53 的行为 |
| --- | --- |
| `hijack` | 在 UID/包名筛选前接管。 |
| `respect_policy` | 先应用 UID/包名筛选，再接管。默认值。 |
| `off` | 绕过。 |

此选项只处理已启用的 TCP/UDP 流量，不识别 DoH 或 DoT。

### local.ipv6

启用本机 IPv6 接管，默认 `true`。

### local.bypass_private_address

绕过私有和特殊用途目标地址，默认 `true`。

### local.bypass_rule_set

目标 IP CIDR 命中这些规则集时绕过 local 数据面，非 IP 规则会被忽略。该策略与
`shared.bypass_rule_set` 独立，并以事务方式更新所有启用的 local 后端。

### local.include_uid

需要接管的 UID。配置任一 include UID、范围或包名后，未匹配的 UID 默认绕过。

### local.include_uid_range

需要接管的 UID 范围，格式为包含两端的 `start:end`。

### local.exclude_uid

需要绕过的 UID。exclude 优先于 include。

### local.exclude_uid_range

需要绕过的 UID 范围，格式为包含两端的 `start:end`。

### local.include_android_user

需要接管的 Android 用户 ID，仅 Android。

### local.include_package

解析出的 UID 需要接管的 Android 包名，仅 Android。

### local.exclude_package

解析出的 UID 需要绕过的 Android 包名，仅 Android。无法区分共用 UID 的包，也无法
把其他系统 UID 代发的流量归属于原始包名。

### local.bypass_port

需要绕过的目标端口。FakeIP 强制接管和 DNS 模式优先于此字段，因此配置端口 53 时
会产生告警。

### local.bypass_port_range

需要绕过的目标端口范围，格式为包含两端的 `start:end`。

### local.endpoint_connected_bypass

仅支持一个 `endpoint_connected_bypass` 配置组。

该策略只由 local TC 数据面实现。启用后，如果省略 `local.data_plane`，会自动选择
`tc`；不能与显式 local `cgroup` 数据面或 `local.cgroup_path` 同时使用。

例如：

```json
{
  "local": {
    "endpoint_connected_bypass": {
      "enabled": true,
      "network": ["tcp", "udp"],
      "ip_cidr": [
        "162.120.128.0/17",
        "162.159.193.0/24",
        "2606:4700:100::/48"
      ],
      "port": [
        500,
        2408,
        4500
      ]
    }
  }
}
```

启用后必须同时配置非空的 `ip_cidr` 和 `port`。`network` 可选择 `tcp` 和/或 `udp`，
省略时默认使用该入站已启用的两种协议。只有网络协议、目标 IP `ip_cidr` 与目标端口
`port` 同时匹配的本机流量才会匹配 endpoint。配置缺失或 `enabled` 为 `false` 时，
保持原有的 local 策略；endpoint 未匹配时也保持原有的 local 策略。此功能只作用于
local 路径，shared 路径完全不受影响。

匹配 endpoint 且 VPN 尚未 READY 时，流量会被明确强制接管（FORCE INTERCEPT），继续
进入 unified TC/eBPF-in。强制接管只负责让流量进入 eBPF-in，不指定任何 outbound；进入
sing-box 后由正常的 Router、`route.rules`、`clash_mode` 和默认 outbound 决定路由。

匹配 endpoint 且 VPN 已 READY 时，流量执行 native bypass，不再进入 eBPF-in 或 Router。
VPN 断开并回到 READY=false 后，匹配流量会自动恢复 FORCE INTERCEPT，再次进入正常的
sing-box 路由流程。FakeIP 和 DNS 的强制接管优先级保持不变。

候选 `tun*` 和 `ipsec*` 接口必须处于 UP 状态并拥有 global-unicast 地址，并排除
`MyInterfaces()` 中已登记的 sing-box 自有接口。该机制仅排除已登记的自有接口，无法
识别所有无关的第三方 TUN。

普通 TUN 第一次成功的 RX/TX 采样只建立计数基线；读取或解析失败不更新该基线。
后续采样观察到 RX 或 TX 严格增长后，
仅对同一个符合候选条件的 `(ifindex, name)` 锁存 READY。流量静默或计数回退不清除该锁存；
接口消失、身份变化或成为自有接口时清除基线和锁存，重建接口必须重新建立基线。
IPsec 不锁存 READY：当前采样存在符合条件的非 local table、`RTN_UNICAST` 默认路由时
才 READY，路由消失即不再 READY。全局期望 READY 是当前各合格接口 READY 的逻辑或，
不会仅因另一个未就绪接口仍 active 就继承旧全局值。

每轮采样所有候选接口。READY 布尔值变化仅在 TC control 写入成功后提交；失败时保留此前
已提交的状态并在后续采样重试。只有 READY 来源变化时不重复写入 TC control。

启用此功能时，现有接口 worker 每秒进行一次 readiness sampling，接口或网络事件也可
立即触发采样。READY 状态变化只更新 dynamic TC control flag，不会重建 backend、TC
attachment 或静态 endpoint maps。

## shared

### shared.enabled

启用从所配置下游接口进入的流量接管。

### shared.data_plane

可选 `packet_rewrite`（默认）或 `socket_assign`。`packet_rewrite` 要求以太网帧；
raw-IP、PPP/PPPoE 和受支持的隧道链路应使用 `socket_assign`。local 与 shared 可
独立选择数据面。

### shared.dns_mode

取值与 `local.dns_mode` 相同。`respect_policy` 会先应用来源 CIDR/MAC 筛选，再
接管端口 53。

### shared.interface

==启用 shared 接管时必填==

客户端流量进入本机的下游接口，可配置多个。暂不存在的接口会重试；接口成为当前默认
上游时暂时排除，恢复下游角色后重新接管。不接受 loopback。

### shared.ipv6

启用 shared IPv6 接管，默认 `true`。此字段不会为客户端配置地址、路由器通告、转发
或上游 IPv6 路由。

### shared.bypass_private_address

绕过私有和特殊用途目标地址，默认 `true`。

### shared.bypass_rule_set

目标 IP CIDR 命中这些规则集时绕过 shared 数据面，非 IP 规则会被忽略。该策略与
`local.bypass_rule_set` 独立，并以事务方式更新所有启用的 shared 后端。

### shared.include_source_cidr

需要接管的客户端来源 CIDR。当 CIDR 或 MAC include 列表任一配置时，命中任一列表的
来源即接管，均未命中时绕过。

### shared.exclude_source_cidr

需要绕过的客户端来源 CIDR。exclude 优先。

### shared.include_mac_address

需要接管的 48 位来源 MAC，仅适用于以太网帧接口。MAC 与 CIDR include 是或（OR）关系，
不是同时满足。

### shared.exclude_mac_address

需要绕过的 48 位来源 MAC，仅适用于以太网帧接口。CIDR 或 MAC 任一 exclude 命中都会
优先绕过，覆盖所有 include。

### shared.bypass_port

需要绕过的目标端口。FakeIP 与 DNS 优先级同 local。

### shared.bypass_port_range

需要绕过的目标端口范围，格式为包含两端的 `start:end`。

!!! note

    shared 模式不提供转发、NAT、DHCP、IPv6 路由器通告或热点管理，这些功能应由
    操作系统配置。

## 策略顺序

安全与服务流量绕过最先执行；随后 FakeIP 前缀强制接管；DNS 模式及 local UID/shared
来源筛选早于端口、私网地址和各自数据面的规则集绕过。顶层兼容规则集会应用到所有
已启用路径，路径级规则集策略仍彼此独立。shared 的 CIDR 与 MAC include 为或关系，
任一 exclude 命中都优先绕过。

## 诊断

- `sing-box tools ebpf status` 对所选数据面执行不挂载的内核能力和对象加载预检。
- `sing-box api ebpf` 从运行实例读取 attachment、恢复状态、活动程序、map 占用、资源、
  UDP/会话统计、分片/放行计数和失败信息。local cgroup 还会报告回退后实际使用的挂载、
  UDP 清理、socket storage 和时间源模式；需要启用
  [sing-box API 服务](/zh/configuration/service/api/)。

local TC 和 shared `socket_assign` 还会报告实际的 TCX/clsact 挂载机制、SOCKMAP/direct
listener 查找、delivery 接口、策略路由值、活动/待回收资源数量、health/reconcile 时间，
以及在受管网络切换边界递增的网络代数。

诊断响应在顶层携带 `schemaVersion`，即使当前没有运行中的 eBPF 入站也会返回。客户端
应以该字段作为整份响应的版本。

具体命令和计数解释见 [eBPF 问题排查](/zh/manual/misc/ebpf-troubleshooting/)。

## 限制

- 一个 sing-box 实例只能有一个 eBPF 入站启用 local 接管；其他 eBPF 入站必须仅启用
  shared。
- IPv4 分片和非 atomic IPv6 分片会绕过接管，因为无法取得完整传输层 tuple；IPv6
  atomic fragment 正常处理。
- 网络变化会触发 attachment 与受管状态协调，但上游连通性和热点能力仍由操作系统负责。
