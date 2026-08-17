English | [简体中文](README_zh_CN.md)

# AsteriskBOX

An Android sing-box GUI client. ROOT modes execute the [reF1nd sing-box](https://github.com/reF1nd/sing-box-releases) build for Android.

## Telegram Channel

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

## Features

- TPROXY(ROOT) and eBPF(ROOT) run modes
- Import and manage strict sing-box JSON configurations from QR codes, local files, or URL subscriptions
- Outbound, DNS, routing, rule-set, and resource management
- Live status, traffic, connections, proxy selection, and delay tests through the official sing-box command API
- Material 3 Compose UI

## Run Modes

### TPROXY(ROOT)

- Runs the bundled sing-box binary with a TPROXY inbound.
- Uses iptables and policy routing for transparent proxy traffic.

### eBPF(ROOT)

- Uses the reF1nd sing-box eBPF inbound without a TUN device or local SOCKS5 intermediary.
- Uses the TC data plane for local traffic and optional `socket_assign` for exact downstream interfaces.
- IP CIDRs are passed to `bypass_rule_set`; domain rules do not apply.
- Availability depends on device kernel, cgroup v2, and eBPF support.

### asteriskd

- Watches local IPv4/IPv6 addresses and tethering interfaces, then refreshes the relevant iptables rules or BPF maps.
- Cleans up networking rules owned by the active ROOT mode when the service stops.

## Resource Files

- ROOT runtime files are stored in the app-private `files/sing-box` directory.
- The bundled reF1nd sing-box ROOT core can be replaced from Resource Management.
- Direct CIDR and custom resource files can be replaced locally or updated from configured URLs; rule sets remain part of the sing-box JSON configuration.

## Development

Initialize submodules before building:

```bash
git submodule update --init --recursive
```

Build with Android Studio or the Gradle wrapper:

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux:

```bash
./gradlew assembleDebug
```

The build resolves the configured reF1nd sing-box versions, builds the native helper submodules, and produces ABI split APKs plus a universal APK.

If Gradle cannot find the Android NDK, configure it through Android Studio, `ndk.dir` in `local.properties`, or `ANDROID_NDK_HOME`.

## WSA

```bash
appops set org.asterisk.zcc.abox ACTIVATE_VPN allow
```

## License

[GPL-3.0](LICENSE)

## Credits

- [@SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [@reF1nd/sing-box-releases](https://github.com/reF1nd/sing-box-releases)
- [@topjohnwu/libsu](https://github.com/topjohnwu/libsu)
- [@android/material3](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [@mayaxcn/china-ip-list](https://github.com/mayaxcn/china-ip-list)
