package suwayomi.tachidesk.graphql.queries

import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.fork.ForkFeatureAvailability
import suwayomi.tachidesk.graphql.types.ForkFeaturesAvailableStatus
import suwayomi.tachidesk.graphql.types.SettingsType

class SettingsQuery {
    @RequireAuth
    fun settings(): SettingsType = SettingsType()

    @RequireAuth
    fun forkFeaturesAvailable(): ForkFeaturesAvailableStatus = ForkFeatureAvailability.graphqlStatus()
}
