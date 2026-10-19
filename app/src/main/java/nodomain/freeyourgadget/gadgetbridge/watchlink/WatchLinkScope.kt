/*  Copyright (C) 2026 WatchLink contributors

    This file is part of WatchLink, a modified version of Gadgetbridge.

    WatchLink is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    WatchLink is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.watchlink

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiFreebudsCoordinator
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType

/**
 * WatchLink product scope.
 *
 * The MVP is: pairing, battery, device info, time sync, notification relay,
 * health/activity sync, music control and watch faces. Everything else the
 * Huawei protocol supports is switched off here rather than deleted, so that
 * upstream Gadgetbridge fixes keep merging cleanly. Flip a flag to bring a
 * feature back.
 */
object WatchLinkScope {
    const val WEATHER = false
    const val CALENDAR = false
    const val CONTACTS = false
    const val ALARMS = false
    const val NAVIGATION = false
    const val WATCH_APPS = false
    const val FIND_DEVICE = false

    /** Only Huawei/Honor wearables are recognised during discovery; headphones are excluded. */
    @JvmStatic
    fun isSupported(type: DeviceType): Boolean {
        val coordinator = type.deviceCoordinator
        return coordinator is HuaweiCoordinator && coordinator !is HuaweiFreebudsCoordinator
    }
}
