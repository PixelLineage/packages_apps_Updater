/*
 * Copyright (C) 2017-2023 The LineageOS Project
 * Copyright (C) 2025 PixelOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.pixelos.ota.misc

object Constants {
    const val AB_PAYLOAD_BIN_PATH: String = "payload.bin"
    const val AB_PAYLOAD_PROPERTIES_PATH: String = "payload_properties.txt"
    const val PREF_LAST_UPDATE_CHECK: String = "last_update_check"
    const val PREF_AUTO_UPDATES_CHECK: String = "auto_updates_check"
    const val PREF_AB_PERF_MODE: String = "ab_perf_mode"
    const val PREF_CERTIFIED_PROP_STATUS: String = "certified_prop_status"
    const val PREF_CHECK_FOR_CERTIFIED_PROPS: String = "check_certified_prop"
    const val PREF_NEEDS_REBOOT_ID: String = "needs_reboot_id"
    const val UNCRYPT_FILE_EXT: String = ".uncrypt"
    const val PROP_AB_DEVICE: String = "ro.build.ab_update"
    const val PROP_BUILD_DATE: String = "ro.build.date.utc"
    const val PROP_BUILD_VERSION: String = "ro.pixel.build.version"
    const val PROP_DEVICE: String = "ro.pixel.device"
    const val PREF_INSTALL_OLD_TIMESTAMP: String = "install_old_timestamp"
    const val PREF_INSTALL_NEW_TIMESTAMP: String = "install_new_timestamp"
    const val PREF_INSTALL_PACKAGE_PATH: String = "install_package_path"
    const val PREF_INSTALL_AGAIN: String = "install_again"
    const val PREF_INSTALL_NOTIFIED: String = "install_notified"
    const val PREF_UPDATE_RECOVERY: String = "update_recovery"
    const val UPDATE_RECOVERY_EXEC: String = "/vendor/bin/install-recovery.sh"
    const val UPDATE_RECOVERY_PROPERTY: String = "persist.vendor.recovery_update"
}
