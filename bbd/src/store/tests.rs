// Copyright (C) 2026 Mercury Li
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License version 3 as
// published by the Free Software Foundation.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

use super::SandboxEntry;

pub(super) fn sandbox_entry_for(uid: u32) -> SandboxEntry {
    SandboxEntry {
        uid,
        package: "com.a".into(),
        enabled: true,
        android_id: "0123456789abcdef".into(),
        saved_ops: "android:camera=default".into(),
        install_id: "test".into(),
        saved_permissions: "android.permission.ACCESS_FINE_LOCATION=granted+user_set".into(),
        created: 1000,
        settings: String::new(),
    }
}
