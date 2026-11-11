package io.github.battcap.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.battcap.R
import kotlinx.coroutines.launch

/** Les écrans, dans l'ordre du menu latéral. */
enum class Screen(@StringRes val title: Int) {
    HOME(R.string.nav_home),
    HISTORY(R.string.nav_history),
    STATS(R.string.nav_stats),
    SETTINGS(R.string.nav_settings)
}

@Composable
private fun Screen.icon(): Painter = when (this) {
    Screen.HOME -> rememberVectorPainter(Icons.Filled.Home)
    Screen.HISTORY -> rememberVectorPainter(Icons.AutoMirrored.Filled.List)
    Screen.STATS -> painterResource(R.drawable.ic_chart)
    Screen.SETTINGS -> rememberVectorPainter(Icons.Filled.Settings)
}

/**
 * Navigation par tiroir latéral Material 3 (le composant standard d'Android pour ce besoin).
 * Trois écrans seulement et aucun paramètre à transmettre : un simple état suffit,
 * pas besoin de la bibliothèque Navigation. Le bouton Retour ferme le tiroir,
 * puis ramène à l'Accueil avant de quitter l'app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    // Enum = Serializable : l'écran courant survit à la rotation et à la recréation de l'activité
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp)
                )
                Screen.entries.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(item.title)) },
                        icon = { Icon(item.icon(), contentDescription = null) },
                        selected = item == screen,
                        onClick = {
                            screen = item
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(screen.title)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.nav_open_menu))
                        }
                    }
                )
            }
        ) { padding ->
            when (screen) {
                Screen.HOME -> HomeScreen(vm, padding)
                Screen.HISTORY -> HistoryScreen(vm, padding)
                Screen.STATS -> StatsScreen(vm, padding)
                Screen.SETTINGS -> SettingsScreen(vm, padding)
            }
        }
    }
}
