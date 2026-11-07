/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package suwayomi.tachidesk.graphql.types

enum class MergeDefaultMode {
    MIRROR,
    SEQUENCE,
}

enum class MergeUnnumberedChapterMatching {
    TITLE_SCANLATOR,
    KEEP_ALL,
}

enum class MergeChapterDisplay {
    ORIGINAL,
    NORMALIZED,
}

enum class MergeOfflineMode {
    USE_EXISTING,
    MATERIALIZE,
    ONLINE_ONLY,
}
