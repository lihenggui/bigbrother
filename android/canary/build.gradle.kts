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

// A test app, not part of the product: it does on purpose, once each, the
// things Big Brother is meant to notice, so that what gets recorded can be
// compared with what was done (scripts/check-canary.sh).
plugins {
    alias(libs.plugins.bigbrother.android.application)
}

android {
    namespace = "li.mercury.bigbrother.canary"

    defaultConfig {
        applicationId = "li.mercury.bigbrother.canary"
        versionCode = 1
        versionName = "1"
    }
}
