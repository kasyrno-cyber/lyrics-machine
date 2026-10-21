package com.lyricsmachine.app

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lyricsmachine.app.ui.edit.EditSongScreen
import com.lyricsmachine.app.ui.library.LibraryScreen
import com.lyricsmachine.app.ui.theme.LyricsMachineTheme

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

    fun edit(songId: Long = -1L) = "edit?songId=$songId"
    fun analyse(songId: Long) = "analyse/$songId"
    fun sing(songId: Long) = "sing/$songId"
}

@Composable
private fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                container = container,
                onOpenSong = { nav.navigate(Routes.sing(it)) },
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
                    nav.navigate(Routes.analyse(id)) { popUpTo(Routes.LIBRARY) }
                },
            )
        }
        composable(Routes.ANALYSE) { PlaceholderScreen("analyse") }
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
