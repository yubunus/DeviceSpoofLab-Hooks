# DeviceSpoofLab-Hooks

LSPosed module that spoofs device identifiers, build properties, telephony,
network, locale and WebView fingerprinting surfaces inside hooked target
processes.

## Requirements

- Rooted Android with LSPosed, or preferably [Vector](https://github.com/JingMatrix/Vector) at its latest version.
- Android 8.0+ / SDK 26+ on the target device.

## Install

1. Install LSPosed via Magisk, KernelSU, or another supported root manager.
2. Install the `app-debug.apk` from releases.
3. Enable the module in the LSPosed manager, and add your target apps to the scope.
4. Open the DeviceSpoofLab-Hooks app, choose a device preset or edit the device, randomize identifiers to liking, then force-stop and relaunch the target apps.

"Spoof screen size" is off by default because a spoofed screen size and density can break app layouts.

## Troubleshooting

If the target app isn't seeing the spoofed values:

- Confirm the target app is in the module's scope and the module is enabled.
  For updates, uninstall the com.devicespooflab.hooks app fully then reinstall the app-debug.apk from releases.
- If the DeviceSpoofLab-Hooks app was already open when you enabled the module,
  force-stop it and open it again. It can only pass your settings on to target
  apps once the module is enabled.
- Force-stop the target app afterwards so it picks up the new values
  cleanly instead of caching whatever it read on startup.
- If you updated without uninstalling, open the DeviceSpoofLab-Hooks app once.
  It moves a profile saved by an older version to the corrected defaults.

The target app crashes or misbehaves:

- Don't set an Android version higher than the phone's real one. Apps then
  call APIs that don't exist on the phone and crash.
- If you turned on "Spoof screen size" and layouts look wrong, turn it off and
  restart the target app.

Inadequate hooks or Xposed detected:

- Update LSPosed to its latest version, or try nightly builds/forked version.
- Make an Issue on Github and explain the issue your experiencing.

## License

This project is licensed under the [MIT License](https://opensource.org/licenses/MIT).
