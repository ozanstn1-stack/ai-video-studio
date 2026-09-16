package com.aivideostudio.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.ui.graphics.vector.ImageVector
import com.aivideostudio.R

/**
 * Every destination in the app. Routes are plain strings so deep links and
 * restored state survive process death without extra plumbing.
 */
object Routes {
    const val ONBOARDING = "onboarding"

    const val HOME = "home"
    const val PROJECTS = "projects"
    const val CREATE = "create"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"

    const val ANALYSIS = "analysis/{projectId}"
    const val REVIEW = "review/{projectId}"
    const val CLIPS = "clips/{projectId}"
    const val EDITOR = "editor/{clipId}"
    const val STORAGE = "storage"
    const val AI_SETTINGS = "settings/ai"
    const val DEFAULTS_SETTINGS = "settings/defaults"
    const val PRIVACY_SETTINGS = "settings/privacy"
    const val ABOUT = "about"
    const val DEBUG = "debug"

    fun analysis(projectId: Long) = "analysis/$projectId"
    fun review(projectId: Long) = "review/$projectId"
    fun clips(projectId: Long) = "clips/$projectId"
    fun editor(clipId: Long) = "editor/$clipId"

    const val ARG_PROJECT_ID = "projectId"
    const val ARG_CLIP_ID = "clipId"
}

/** The five tabs that live in the bottom bar. */
enum class BottomDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    PROJECTS(Routes.PROJECTS, R.string.nav_projects, Icons.Outlined.VideoSettings),
    CREATE(Routes.CREATE, R.string.nav_create, Icons.Outlined.AddCircleOutline),
    LIBRARY(Routes.LIBRARY, R.string.nav_library, Icons.Outlined.VideoLibrary),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
    ;

    companion object {
        val routes: Set<String> = entries.map { it.route }.toSet()
        fun fromRoute(route: String?): BottomDestination? = entries.firstOrNull { it.route == route }
    }
}
