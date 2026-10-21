package com.lyricsmachine.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lyricsmachine.app.ui.analyse.AnalyseScreen
import com.lyricsmachine.app.ui.edit.EditSongScreen
import com.lyricsmachine.app.ui.library.LibraryScreen
import com.lyricsmachine.app.ui.setup.ModelSetupScreen
import com.lyricsmachine.app.ui.theme.LyricsMachineTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    val container: AppContainer
        get() = (application as LyricsMachineApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LyricsMachineTheme {
                AppNav(container)
            }
        }
    }
}

object Routes {
    const val LIBRARY = "library"
    const val EDIT = "edit?songId={songId}"
    const val ANALYSE = "analyse/{songId}"
    const val SING = "sing/{songId}"
    const val SETUP = "setup?next={next}"

    fun edit(songId: Long = -1L) = "edit?songId=$songId"
    fun analyse(songId: Long) = "analyse/$songId"
    fun sing(songId: Long) = "sing/$songId"

    /** Model setup that continues to [next] (a route) once a speech model is ready. */
    fun setup(next: String) = "setup?next=${Uri.encode(next)}"
}

@Composable
private fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()

    /** Navigates to [target], or to the model setup screen first when [needsModel] says a model is missing. */
    fun go(target: String, needsModel: suspend () -> Boolean = { true }, options: NavOptionsBuilder.() -> Unit = {}) {
        scope.launch {
            val setup = needsModel() && container.needsModelSetup()
            nav.navigate(if (setup) Routes.setup(target) else target, options)
        }
    }
    fun goSing(id: Long, options: NavOptionsBuilder.() -> Unit = {}) =
        go(Routes.sing(id), needsModel = { container.singNeedsModel(id) }, options = options)

    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                container = container,
                onOpenSong = { goSing(it) },
                onAddSong = { nav.navigate(Routes.edit()) },
                onEditSong = { nav.navigate(Routes.edit(it)) },
            )
        }
        composable(
            Routes.EDIT,
            arguments = listOf(navArgument("songId") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            EditSongScreen(
                container = container,
                songId = entry.arguments?.getLong("songId") ?: -1L,
                onBack = { nav.popBackStack() },
                onSavedLyricsOnly = { nav.popBackStack() },
                onNeedsAnalysis = { id ->
                    go(Routes.analyse(id)) { popUpTo(Routes.LIBRARY) }
                },
            )
        }
        composable(
            Routes.ANALYSE,
            arguments = listOf(navArgument("songId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("songId") ?: -1L
            AnalyseScreen(
                container = container,
                songId = id,
                onSing = { goSing(id) { popUpTo(Routes.ANALYSE) { inclusive = true } } },
                onEditLyrics = { nav.navigate(Routes.edit(id)) { popUpTo(Routes.ANALYSE) { inclusive = true } } },
                // The setup screen continues back to this analysis, which then starts again.
                onNeedsModel = {
                    nav.navigate(Routes.setup(Routes.analyse(id))) { popUpTo(Routes.ANALYSE) { inclusive = true } }
                },
                onLeave = { nav.popBackStack(Routes.LIBRARY, inclusive = false) },
            )
        }
        composable(
            Routes.SETUP,
            arguments = listOf(navArgument("next") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val next = entry.arguments?.getString("next")
            ModelSetupScreen(
                container = container,
                onContinue = {
                    nav.navigate(next?.takeIf { it.isNotBlank() } ?: Routes.LIBRARY) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SING) { PlaceholderScreen("sing") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderScreen(title: String) {
    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text(title) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {}
    }
}
