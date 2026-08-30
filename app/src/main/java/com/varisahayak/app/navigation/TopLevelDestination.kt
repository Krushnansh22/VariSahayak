package com.varisahayak.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.varisahayak.R
import com.varisahayak.domain.model.Capabilities
import com.varisahayak.domain.model.UserRole

/**
 * Bottom-bar entries.
 *
 * The set is role-dependent: a volunteer's most-used surface is the QR scanner, which a
 * command user has no reason to see. Keeping this in one place stops each screen from
 * growing its own idea of what the navigation looks like.
 */
enum class TopLevelDestination(
    val icon: ImageVector,
) {
    HOME(Icons.Filled.Home),
    INCIDENTS(Icons.Filled.ListAlt),
    MAP(Icons.Filled.Map),
    COMMS(Icons.Filled.Message),
    PROFILE(Icons.Filled.Person),
    ;

    /**
     * Labels change based on role. The Administrator/Organiser "Home" is the Command
     * dashboard, which they think of as "Operations".
     */
    @StringRes
    fun labelResFor(role: UserRole): Int = when (this) {
        HOME -> when (role) {
            UserRole.ORGANISER, UserRole.ADMINISTRATOR -> R.string.command_title
            else -> R.string.nav_dashboard
        }
        INCIDENTS -> R.string.nav_incidents
        MAP -> R.string.nav_map
        COMMS -> R.string.comms_title
        PROFILE -> R.string.nav_profile
    }

    /**
     * Routes are also role-dependent: "Home" lands on different dashboards.
     */
    fun routeFor(role: UserRole): Destination = when (this) {
        HOME -> homeRoute(role)
        INCIDENTS -> Destination.IncidentList
        MAP -> Destination.IncidentMap
        COMMS -> Destination.Communication
        PROFILE -> Destination.Profile
    }

    companion object {
        /**
         * Derived from [Capabilities] rather than from the role directly, so the bottom
         * bar and the screen contents can never disagree about what a role may do.
         *
         * Note this is about *prominence*, not permission.
         */
        fun forRole(role: UserRole): List<TopLevelDestination> {
            return buildList {
                add(HOME)
                add(INCIDENTS)
                add(MAP)
                add(COMMS)
                add(PROFILE)
            }
        }

        /** The start destination for a role, used once the profile resolves. */
        fun homeRoute(role: UserRole): Destination = when (role) {
            UserRole.VOLUNTEER -> Destination.VolunteerDashboard
            UserRole.MEDICAL_RESPONDER,
            UserRole.POLICE_RESPONDER,
            UserRole.NGO_RESPONDER,
            -> Destination.ResponderDashboard

            UserRole.ORGANISER -> Destination.CommandDashboard
            UserRole.ADMINISTRATOR -> Destination.AdminDashboard
        }
    }
}
