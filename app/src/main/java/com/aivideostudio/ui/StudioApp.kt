package com.aivideostudio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aivideostudio.ui.navigation.BottomDestination
import com.aivideostudio.ui.navigation.Routes
import com.aivideostudio.ui.screens.about.AboutScreen
import com.aivideostudio.ui.screens.analysis.AnalysisScreen
import com.aivideostudio.ui.screens.clips.ClipsScreen
import com.aivideostudio.ui.screens.create.CreateScreen
import com.aivideostudio.ui.screens.debug.DebugScreen
import com.aivideostudio.ui.screens.editor.EditorScreen
import com.aivideostudio.ui.screens.home.HomeScreen
import com.aivideostudio.ui.screens.library.LibraryScreen
import com.aivideostudio.ui.screens.onboarding.OnboardingScreen
import com.aivideostudio.ui.screens.projects.ProjectsScreen
import com.aivideostudio.ui.screens.review.ReviewScreen
import com.aivideostudio.ui.screens.settings.AiSettingsScreen
import com.aivideostudio.ui.screens.settings.DefaultsSettingsScreen
import com.aivideostudio.ui.screens.settings.PrivacySettingsScreen
import com.aivideostudio.ui.screens.settings.SettingsScreen
import com.aivideostudio.ui.screens.storage.StorageScreen
import com.aivideostudio.domain.repository.SettingsRepository
import com.aivideostudio.ui.theme.StudioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Root navigation. The bottom bar lives here rather than inside each screen so
 * the selected tab, the insets and the transitions stay consistent.
 */
@Composable
fun StudioApp(
    startTab: String? = null,
    navController: NavHostController = rememberNavController(),
    viewModel: StartupViewModel = hiltViewModel(),
) {
    val onboardingDone by viewModel.onboardingCompleted.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in BottomDestination.routes

    // The onboarding flag is read from storage, so hold a plain background
    // until it arrives rather than flashing the wrong start screen.
    val done = onboardingDone
    if (done == null) {
        Box(Modifier.fillMaxSize())
        return
    }

    val startDestination = when {
        !done -> Routes.ONBOARDING
        startTab == "create" -> Routes.CREATE
        startTab == "library" -> Routes.LIBRARY
        else -> Routes.HOME
    }

    Scaffold(
        containerColor = StudioColors.Background,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
            ) {
                StudioBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(bottom = padding.calculateBottomPadding())) {
            StudioNavHost(navController = navController, startDestination = startDestination)
        }
    }
}

@Composable
private fun StudioBottomBar(
    currentRoute: String?,
    onSelect: (BottomDestination) -> Unit,
) {
    NavigationBar(
        containerColor = StudioColors.Surface,
        contentColor = StudioColors.TextPrimary,
        tonalElevation = 0.dp,
    ) {
        BottomDestination.entries.forEach { destination ->
            val selected = currentRoute == destination.route
            val label = stringResource(destination.labelRes)
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onSelect(destination) },
                icon = {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Icon(destination.icon, contentDescription = label)
                    }
                },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = StudioColors.PrimaryBright,
                    selectedTextColor = StudioColors.PrimaryBright,
                    unselectedIconColor = StudioColors.TextTertiary,
                    unselectedTextColor = StudioColors.TextTertiary,
                    indicatorColor = StudioColors.Primary.copy(alpha = 0.16f),
                ),
            )
        }
    }
}

@Composable
private fun StudioNavHost(
    navController: NavHostController,
    startDestination: String,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { fadeIn(tween(180)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition = { fadeOut(tween(180)) },
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onCreate = { navController.navigateToTab(Routes.CREATE) },
                onOpenProject = { id -> navController.navigate(Routes.review(id)) },
                onOpenClips = { id -> navController.navigate(Routes.clips(id)) },
                onOpenLibrary = { navController.navigateToTab(Routes.LIBRARY) },
                onOpenStorage = { navController.navigate(Routes.STORAGE) },
            )
        }

        composable(Routes.PROJECTS) {
            ProjectsScreen(
                onCreate = { navController.navigateToTab(Routes.CREATE) },
                onOpenProject = { id -> navController.navigate(Routes.review(id)) },
                onOpenAnalysis = { id -> navController.navigate(Routes.analysis(id)) },
            )
        }

        composable(Routes.CREATE) {
            CreateScreen(
                onAnalysisStarted = { id ->
                    navController.navigate(Routes.analysis(id)) {
                        popUpTo(Routes.CREATE) { inclusive = false }
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onEditClip = { clipId -> navController.navigate(Routes.editor(clipId)) },
                onCreate = { navController.navigateToTab(Routes.CREATE) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenAi = { navController.navigate(Routes.AI_SETTINGS) },
                onOpenDefaults = { navController.navigate(Routes.DEFAULTS_SETTINGS) },
                onOpenPrivacy = { navController.navigate(Routes.PRIVACY_SETTINGS) },
                onOpenStorage = { navController.navigate(Routes.STORAGE) },
                onOpenDebug = { navController.navigate(Routes.DEBUG) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
            )
        }

        composable(
            route = Routes.ANALYSIS,
            arguments = listOf(navArgument(Routes.ARG_PROJECT_ID) { type = NavType.LongType }),
        ) { entry ->
            AnalysisScreen(
                projectId = entry.arguments?.getLong(Routes.ARG_PROJECT_ID) ?: 0L,
                onFinished = { id ->
                    navController.navigate(Routes.review(id)) {
                        popUpTo(Routes.ANALYSIS) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.REVIEW,
            arguments = listOf(navArgument(Routes.ARG_PROJECT_ID) { type = NavType.LongType }),
        ) { entry ->
            ReviewScreen(
                projectId = entry.arguments?.getLong(Routes.ARG_PROJECT_ID) ?: 0L,
                onBack = { navController.popBackStack() },
                onOpenClips = { id -> navController.navigate(Routes.clips(id)) },
                onRegenerate = { id -> navController.navigate(Routes.analysis(id)) },
            )
        }

        composable(
            route = Routes.CLIPS,
            arguments = listOf(navArgument(Routes.ARG_PROJECT_ID) { type = NavType.LongType }),
        ) { entry ->
            ClipsScreen(
                projectId = entry.arguments?.getLong(Routes.ARG_PROJECT_ID) ?: 0L,
                onBack = { navController.popBackStack() },
                onEditClip = { clipId -> navController.navigate(Routes.editor(clipId)) },
                onOpenLibrary = { navController.navigateToTab(Routes.LIBRARY) },
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument(Routes.ARG_CLIP_ID) { type = NavType.LongType }),
        ) { entry ->
            EditorScreen(
                clipId = entry.arguments?.getLong(Routes.ARG_CLIP_ID) ?: 0L,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.STORAGE) {
            StorageScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.AI_SETTINGS) {
            AiSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DEFAULTS_SETTINGS) {
            DefaultsSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.PRIVACY_SETTINGS) {
            PrivacySettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DEBUG) {
            DebugScreen(onBack = { navController.popBackStack() })
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Decides where the app opens. Reading this from DataStore is fast, but it is
 * still asynchronous, so the value is exposed as state and the start destination
 * is chosen once it arrives.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val onboardingCompleted: StateFlow<Boolean?> = settingsRepository.preferences
        .map { it.onboardingCompleted }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )
}

@Composable
fun StudioTopBarTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = StudioColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextTertiary,
            )
        }
    }
}
