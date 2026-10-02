# GHTxRAT Child APK template

- minSdk 34 (Android 14)
- targetSdk 37 (Android 17)
- compileSdk 37
- AGP 9.4.0
- Gradle 9.6.0
- JDK 17

The server build worker replaces the package name, version, WebView URL, backend URL, parent UID, and selected Android runtime permissions for every build. It also signs release APKs with the automatically generated persistent GHTxRAT keystore.

The app shows a visible pairing screen before opening the configured WebView. Selected runtime permissions are requested through Android's normal permission UI. The app does not silently grant permissions or bypass system consent.

## Device Owner protection

GHTxRAT supports transparent device-management protection when the APK is provisioned as Android Device Owner. In that mode the dashboard can:

- block uninstall of the managed package with `DevicePolicyManager.setUninstallBlocked()`;
- hide/show the launcher icon using the app component state;
- perform full factory reset with `DevicePolicyManager.wipeData()`.

These controls do not silently elevate privileges. A normal installed APK cannot make itself Device Owner. Provisioning must be performed through an Android-supported enterprise/managed-device enrollment flow. The dashboard should show the device's `deviceOwner` state before relying on these controls.
