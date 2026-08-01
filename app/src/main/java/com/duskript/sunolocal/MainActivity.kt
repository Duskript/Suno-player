package com.duskript.sunolocal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.duskript.sunolocal.features.library.state.LibraryViewModel
import com.duskript.sunolocal.features.library.ui.LibraryScreen
import com.duskript.sunolocal.features.settings.ui.SettingsScreen
import com.duskript.sunolocal.shared.ui.SunoLocalTheme

/**
 * MainActivity — single-activity entry point using Jetpack Compose.
 *
 * Configures edge-to-edge rendering, system bar padding, and a NavHost
 * with two destinations: library (default) and settings.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge (draw behind system bars)
        enableEdgeToEdge()

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
        }

        setContent {
            val navController = rememberNavController()

            // Create a single ViewModel scoped to the Activity so player state survives navigation
            val libraryViewModel: LibraryViewModel = viewModel()

            SunoLocalTheme(darkTheme = true) {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "library",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        composable("library") {
                            LibraryScreen(
                                viewModel = libraryViewModel,
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = libraryViewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
