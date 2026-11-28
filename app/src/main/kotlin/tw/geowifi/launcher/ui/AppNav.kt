package tw.geowifi.launcher.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tw.geowifi.launcher.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(startDestination: String) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val showBar = !route.startsWith("editor")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    if (!showBar) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route.startsWith("rules"),
                        onClick = { nav.navigate("rules") { launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_rules)) },
                    )
                    NavigationBarItem(
                        selected = route.startsWith("log"),
                        onClick = { nav.navigate("log") { launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.List, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_log)) },
                    )
                    NavigationBarItem(
                        selected = route.startsWith("permissions"),
                        onClick = { nav.navigate("permissions") { launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_permissions)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable("rules") { RulesScreen(onAdd = { nav.navigate("editor/-1") }, onEdit = { nav.navigate("editor/$it") }, onPermissions = { nav.navigate("permissions") }) }
            composable("log") { EventLogScreen() }
            composable("permissions") { PermissionsScreen() }
            composable(
                "editor/{ruleId}",
                arguments = listOf(navArgument("ruleId") { type = NavType.LongType }),
            ) { entry ->
                RuleEditorScreen(
                    ruleId = entry.arguments?.getLong("ruleId") ?: -1L,
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }
}
