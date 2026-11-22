package com.lumo.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.firebase.auth.FirebaseAuth
import com.lumo.app.data.Repo
import com.lumo.app.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // status bar icons follow the light/dark theme
        super.onCreate(savedInstanceState)
        useHighestRefreshRate()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        setContent {
            LumoTheme {
                if (!BuildConfig.HAS_FIREBASE) SetupScreen() else App()
            }
        }
    }

    /** Runs at the screen's top refresh rate (90/120/144Hz) for buttery scrolling. */
    private fun useHighestRefreshRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val d = display ?: return
        val current = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }
}

@Composable
private fun App() {
    val nav = rememberNavController()
    val flow = remember { AuthFlow() }
    val auth = FirebaseAuth.getInstance()
    val scope = rememberCoroutineScope()
    // Accounts from the old phone-number login have no username: send them to the login screen.
    if (auth.currentUser != null && auth.currentUser?.email.isNullOrEmpty()) auth.signOut()
    // Already signed in: open straight on the chats (from the phone's copy), no waiting screen.
    val start = if (auth.currentUser == null) "login" else "home"
    if (auth.currentUser != null) LaunchedEffect(Unit) { runCatching { Repo.ensureProfile() } }

    fun afterSignIn() = nav.navigate("boot") { popUpTo(0) }

    NavHost(
        navController = nav, startDestination = start,
        modifier = Modifier.fillMaxSize().background(C.Bg),
        // Quick, crisp screen changes (~0.2 s) so moving around feels instant.
        enterTransition = { slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 6 } + fadeIn(tween(160)) },
        exitTransition = { fadeOut(tween(90)) },
        popEnterTransition = { fadeIn(tween(160)) },
        popExitTransition = { slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { it / 6 } + fadeOut(tween(140)) },
    ) {
        composable("login") { LoginScreen(flow, onSignedIn = ::afterSignIn) }
        composable("boot") {
            LaunchedEffect(Unit) {
                // First login: the username is also the display name.
                runCatching { Repo.ensureProfile() }
                nav.navigate("home") { popUpTo(0) }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent) }
        }
        composable("home") {
            HomeScreen(
                onOpenChat = { nav.navigate("chat/$it") },
                onNewChat = { nav.navigate("new") },
                onSignOut = { scope.launch { Repo.signOut(); nav.navigate("login") { popUpTo(0) } } },
            )
        }
        composable("new") {
            NewChatScreen(onBack = { nav.popBackStack() }, onOpen = { uid -> nav.navigate("chat/$uid") { popUpTo("home") } })
        }
        composable("chat/{uid}") { entry ->
            ChatScreen(entry.arguments?.getString("uid").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
