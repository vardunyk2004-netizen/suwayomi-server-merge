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

enum class MergeDedupeStrategy {
    PREFERRED_SOURCE,
    MOST_PAGES,
    NEWEST_UPLOAD,
    MANUAL_PER_CHAPTER,
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

enum class ProgressConflictPolicy {
    NEWEST_WINS,
    VIRTUAL_WINS,
    SOURCE_WINS,
}

enum class ProgressBookmarkPolicy {
    UNION,
    FOLLOW_LATEST,
}

enum class ProgressDirection {
    BOTH,
    TO_SOURCE_ONLY,
}

enum class MergeTrackerPolicy {
    COPY,
    MERGE,
    IGNORE,
}

enum class MergeOpdsVisibility {
    MERGED_ONLY,
    BOTH,
}

enum class ReindexAdoptMode {
    COPY,
    MOVE,
}

enum class DoctorSchedule {
    OFF,
    DAILY,
    WEEKLY,
}

enum class ForkFeaturesAvailableStatus {
    AVAILABLE,
    DISABLED_BY_SETTING,
    DISABLED_BY_STOCK_MODE,
    UNAVAILABLE,
}
