#!/usr/bin/env python3
"""Check the merged TesAir manifests before signing/distribution; contains no credentials."""

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


ANDROID = "{http://schemas.android.com/apk/res/android}"
PRODUCT = "com.shilapi.xcertplay.BROWSER_PRODUCT"
PREFIX = "com.shilapi.xcertplay."
PROVISIONING = PREFIX + "browser.BrowserTlsProvisioningReceiver"
REMOVED_PERMISSIONS = {
    "RECORD_AUDIO", "MODIFY_AUDIO_SETTINGS", "FOREGROUND_SERVICE_MICROPHONE",
    "FOREGROUND_SERVICE_LOCATION", "WRITE_SETTINGS", "SYSTEM_ALERT_WINDOW", "PACKAGE_USAGE_STATS",
}
REMOVED_COMPONENTS = {
    "AdbClusterActivity", "CarPlayVideoActivity", "MainActivity", "ImageCropActivity",
    "NavigationWidget", "MapEmbedService", "WheelKeyService", "UsbAutoConfirmService",
    "shared.MyCarAppService",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def entry(application, tag, name):
    return next((child for child in application.findall(tag)
                 if child.get(ANDROID + "name") == name), None)


def validate(path, debug):
    manifest = ET.parse(path).getroot()
    expected_package = "co.tesair.app.debug" if debug else "co.tesair.app"
    require(manifest.get("package") == expected_package, "Application ID changed")
    application = manifest.find("application")
    require(application is not None, "Application is missing")
    product = entry(application, "meta-data", PRODUCT)
    require(product is not None and product.get(ANDROID + "value") == "true",
            "TesAir product metadata is missing")
    require(application.get(ANDROID + "label") == "@string/browser_product_name",
            "Application does not use the TesAir product name")
    require(application.get(ANDROID + "allowBackup") == "false", "Backup must remain disabled")
    if not debug:
        require(application.get(ANDROID + "debuggable") != "true", "Release must not be debuggable")

    permissions = {item.get(ANDROID + "name") for item in manifest.findall("uses-permission")}
    unexpected = {"android.permission." + value for value in REMOVED_PERMISSIONS} & permissions
    require(not unexpected, "Unused permissions remain: " + ", ".join(sorted(unexpected)))
    components = {item.get(ANDROID + "name") for item in application}
    unexpected = {PREFIX + value for value in REMOVED_COMPONENTS} & components
    require(not unexpected, "Native head-unit components remain: " + ", ".join(sorted(unexpected)))

    session = entry(application, "service", PREFIX + "DiPlaySessionService")
    require(session is not None and session.get(ANDROID + "foregroundServiceType") == "connectedDevice",
            "The session must use only the connectedDevice foreground-service type")
    launcher = entry(application, "activity", PREFIX + "DiPlayActivity")
    require(launcher is not None and launcher.get(ANDROID + "theme") == "@style/Theme.Browser.Onboarding",
            "Launcher does not use the browser product theme")
    dashboard = entry(application, "activity", PREFIX + "BrowserDashboardActivity")
    require(dashboard is not None and dashboard.get(ANDROID + "exported") == "false",
            "Private browser dashboard is missing")
    boot = entry(application, "receiver", PREFIX + "BootReceiver")
    require(boot is not None, "Startup receiver is missing")
    actions = {item.get(ANDROID + "name") for item in boot.findall("intent-filter/action")}
    require({"android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"} <= actions,
            "Boot/update recovery actions are missing")
    host = entry(application, "activity", PREFIX + "CarPlayHostActivity")
    require(host is not None and not host.findall("intent-filter"),
            "Native USB entry point remains enabled")

    provisioning = entry(application, "receiver", PROVISIONING)
    if debug:
        require(provisioning is not None and
                provisioning.get(ANDROID + "permission") == "android.permission.DUMP",
                "Debug provisioning must require the shell-only DUMP permission")
    else:
        require(provisioning is None, "Debug TLS provisioning must not ship in release")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--debug", type=Path, required=True, help="Merged debug AndroidManifest.xml")
    parser.add_argument("--release", type=Path, required=True, help="Merged release AndroidManifest.xml")
    args = parser.parse_args()
    for path, debug in ((args.debug, True), (args.release, False)):
        try:
            validate(path, debug)
        except (ET.ParseError, OSError, ValueError) as error:
            parser.exit(1, f"{path}: {error}\n")
    print("TesAir debug and release manifests passed.")


if __name__ == "__main__":
    main()
