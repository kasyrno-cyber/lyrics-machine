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
import com.lyricsmachine.app.ui.theme.LyricsMachineTheme

class MainActivity : ComponentActivity() {
    val container: AppContainer
        get() = (application as LyricsMachineApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LyricsMachineTheme {
                AppNav()
            }
        }
    }
}

object Routes {
    const val LIBRARY = "library"
    const val EDIT = "edit/{songId}"
    const val ANALYSE = "analyse/{songId}"
    const val SING = "sing/{songId}"
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) { PlaceholderScreen("Library") }
        composable(
            Routes.EDIT,
            arguments = listOf(
                navArgument("songId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { PlaceholderScreen("edit") }
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
