/*
 * Copyright (C) 2026 Mercury Li
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package li.mercury.bigbrother.canary

import android.accessibilityservice.AccessibilityService
import android.service.notification.NotificationListenerService
import android.view.accessibility.AccessibilityEvent

/*
 * Services that do nothing, for `scripts/check-sandbox.sh` to give the
 * access to the notifications and to the screen, and to check that the
 * sandbox takes it while the app is isolated and gives it back to these
 * and no others afterwards.
 */

/** Given the notifications by the check. */
class NotificationProbe : NotificationListenerService()

/** Given the screen by the check. */
class ScreenProbe : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}

/** Never given the screen: it must not get it from the sandbox either. */
class IdleScreenProbe : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
