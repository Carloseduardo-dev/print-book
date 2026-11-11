package com.printbook.app.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.printbook.app.ui.theme.PrintBook
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.printbook.app.R
import androidx.navigation.NavHostController
import com.printbook.app.ui.bookdetail.BookDetailScreen
import com.printbook.app.ui.capture.CaptureScreen
import com.printbook.app.ui.editor.EditorScreen
import com.printbook.app.ui.reader.ReaderScreen
import com.printbook.app.ui.home.HomeScreen
import com.printbook.app.ui.library.LibraryScreen
import com.printbook.app.ui.settings.SettingsScreen
import kotlin.reflect.KClass

private data class TopLevelTab(
    val route: Any,
    val routeClass: KClass<*>,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
)

private val tabs = listOf(
    TopLevelTab(HomeRoute, HomeRoute::class, R.string.tab_home, R.drawable.ic_tab_home),
    TopLevelTab(LibraryRoute, LibraryRoute::class, R.string.tab_library, R.drawable.ic_tab_library),
    TopLevelTab(CaptureRoute, CaptureRoute::class, R.string.tab_capture, R.drawable.ic_pb_capture),
    TopLevelTab(SettingsRoute, SettingsRoute::class, R.string.tab_settings, R.drawable.ic_tab_settings),
)

@Composable
fun PrintBookNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = tabs.any { tab -> destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = PrintBook.tokens.subtle,
                                unselectedTextColor = PrintBook.tokens.subtle,
                            ),
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    onStartCapture = { navController.navigateToTab(CaptureRoute) },
                    onOpenBook = { navController.navigate(BookDetailRoute(it)) },
                    onContinueReading = { navController.navigate(ReaderRoute(it)) },
                )
            }
            composable<LibraryRoute> {
                LibraryScreen(
                    onOpenBook = { navController.navigate(BookDetailRoute(it)) },
                    onOpenPage = { bookId, index -> navController.navigate(ReaderRoute(bookId, index)) },
                )
            }
            composable<CaptureRoute> { CaptureScreen(onOpenBook = { navController.navigate(BookDetailRoute(it)) }) }
            composable<SettingsRoute> { SettingsScreen() }
            composable<BookDetailRoute> {
                BookDetailScreen(
                    onBack = { navController.popBackStack() },
                    onRead = { navController.navigate(ReaderRoute(it)) },
                    onEdit = { navController.navigate(EditorRoute(it)) },
                )
            }
            composable<ReaderRoute> { ReaderScreen(onBack = { navController.popBackStack() }) }
            composable<EditorRoute> {
                EditorScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPage = { bookId, index -> navController.navigate(ReaderRoute(bookId, index)) },
                )
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
