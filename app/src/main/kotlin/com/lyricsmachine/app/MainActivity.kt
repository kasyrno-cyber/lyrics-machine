package com.lyricsmachine.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.lyricsmachine.app.ui.sing.SingScreen
import com.lyricsmachine.app.ui.theme.LyricsMachineTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    val container: AppContainer
        get() = (application as LyricsMachineApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 draws edge to edge anyway; this also gives the system bars matching contrast.
        enableEdgeToEdge()
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
    const val SING = "sing/{songId}?manual={manual}"
    const val SETUP = "setup?kind={kind}&songId={songId}"

    const val KIND_ANALYSE = "analyse"
    const val KIND_SING = "sing"

    fun edit(songId: Long = -1L) = "edit?songId=$songId"
    fun analyse(songId: Long) = "analyse/$songId"

    /** [manual] asks the sing screen for lyrics viewing only, with no speech model. */
    fun sing(songId: Long, manual: Boolean = false) = "sing/$songId?manual=$manual"

    /** Model setup that continues to the [kind] screen ("analyse" or "sing") for [songId] once a model is ready. */
    fun setup(kind: String, songId: Long) = "setup?kind=$kind&songId=$songId"
}

@Composable
private fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var gating by remember { mutableStateOf(false) }

    /**
     * Opens the [kind] screen for [songId], or the model setup screen first when [needsModel] says a model is
     * missing. Ignores taps while a previous check is still running.
     */
    fun go(kind: String, songId: Long, needsModel: suspend () -> Boolean = { true }, options: NavOptionsBuilder.() -> Unit = {}) {
        if (gating) return
        gating = true
        scope.launch {
            try {
                val setup = needsModel() && container.needsModelSetup()
                val route = when {
                    setup -> Routes.setup(kind, songId)
                    kind == Routes.KIND_SING -> Routes.sing(songId)
                    else -> Routes.analyse(songId)
                }
                nav.navigate(route, options)
            } finally {
                gating = false
            }
        }
    }
    fun goSing(id: Long, options: NavOptionsBuilder.() -> Unit = {}) =
        go(Routes.KIND_SING, id, needsModel = { container.singNeedsModel(id) }, options = options)

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
                    go(Routes.KIND_ANALYSE, id) { popUpTo(Routes.LIBRARY) }
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
                    nav.navigate(Routes.setup(Routes.KIND_ANALYSE, id)) { popUpTo(Routes.ANALYSE) { inclusive = true } }
                },
                onLeave = { nav.popBackStack(Routes.LIBRARY, inclusive = false) },
            )
        }
        composable(
            Routes.SETUP,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType; defaultValue = Routes.KIND_ANALYSE },
                navArgument("songId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            val kind = entry.arguments?.getString("kind") ?: Routes.KIND_ANALYSE
            val id = entry.arguments?.getLong("songId") ?: -1L
            ModelSetupScreen(
                container = container,
                showLyricsOnly = kind == Routes.KIND_SING && id >= 0,
                onContinue = {
                    val route = when {
                        id < 0 -> Routes.LIBRARY
                        kind == Routes.KIND_SING -> Routes.sing(id)
                        else -> Routes.analyse(id)
                    }
                    nav.navigate(route) { popUpTo(Routes.SETUP) { inclusive = true } }
                },
                onLyricsOnly = {
                    nav.navigate(Routes.sing(id, manual = true)) { popUpTo(Routes.SETUP) { inclusive = true } }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.SING,
            arguments = listOf(
                navArgument("songId") { type = NavType.LongType },
                navArgument("manual") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            SingScreen(
                container = container,
                songId = entry.arguments?.getLong("songId") ?: -1L,
                manual = entry.arguments?.getBoolean("manual") ?: false,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
