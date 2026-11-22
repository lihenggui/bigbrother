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

package li.mercury.bigbrother.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API

class BigBrotherIssueRegistry : IssueRegistry() {
    override val api = CURRENT_API
    override val minApi = CURRENT_API
    override val issues = listOf(
        TestMethodNameDetector.ISSUE,
        LongFunctionDetector.ISSUE,
        LongSourceFileDetector.COMPOSE_ISSUE,
        LongSourceFileDetector.SOURCE_ISSUE,
    )
    override val vendor = Vendor(vendorName = "Big Brother")
}
