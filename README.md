English | [简体中文](README_zh_CN.md)

# AsteriskBOX

An Android sing-box GUI client. ROOT modes execute the [reF1nd sing-box](https://github.com/reF1nd/sing-box-releases) build for Android.

## Telegram Channel

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

## Run Modes

### TPROXY(ROOT)

- Runs the local sing-box executable directly with libsu.
- Uses iptables and policy routing for transparent proxy traffic.

### eBPF(ROOT)

- Runs the local sing-box executable directly with libsu.
- Uses the sing-box eBPF inbound to capture traffic.
- Availability depends on eBPF support in the device kernel.

### asteriskd

- Watches local IPv4/IPv6 addresses and tethering interfaces, then refreshes the relevant iptables rules or BPF maps.
- Cleans up networking rules owned by the active ROOT mode when the service stops.

## Resource Files

- ROOT runtime files are stored in the app-private `files/sing-box` directory.
- Custom resources can be added or replaced locally and updated from configured URLs.

## Broadcast Control

Enable **Broadcast Control** in settings, then send an explicit broadcast to the receiver below. Actions use the `org.asterisk.zcc.abox.action.` prefix.

| Operation | Action suffix |
| --- | --- |
| Start proxy | `PROXY_START` |
| Stop proxy | `PROXY_STOP` |
| Toggle proxy | `PROXY_TOGGLE` |
| Update all URL subscriptions | `SUBSCRIPTION_UPDATE` |
| Cancel broadcast subscription update | `SUBSCRIPTION_UPDATE_CANCEL` |
| Update all resources | `RESOURCE_UPDATE` |
| Cancel resource updates | `RESOURCE_UPDATE_CANCEL` |

```sh
adb shell am broadcast -n org.asterisk.zcc.abox/features.automation.BroadcastControlReceiver -a org.asterisk.zcc.abox.action.SUBSCRIPTION_UPDATE
```

Subscription updates skip local entries; cancellation preserves completed results and scheduled update settings. Resources use the current Resource Management configuration; resource cancellation also clears its shared queue. Repeated update commands of the same kind are merged while running.

Updates run in the background without starting the proxy. Broadcast delivery does not mean the update has finished; check the `BroadcastControl` app logs for results.

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

## License

[GPL-3.0](LICENSE)

## Credits

- [@SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [@reF1nd/sing-box-releases](https://github.com/reF1nd/sing-box-releases)
- [@topjohnwu/libsu](https://github.com/topjohnwu/libsu)
- [@android/material3](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [@mayaxcn/china-ip-list](https://github.com/mayaxcn/china-ip-list)
- [@xchacha20-poly1305/husi](https://github.com/xchacha20-poly1305/husi)
