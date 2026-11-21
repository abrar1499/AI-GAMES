package com.lumo.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
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
import androidx.compose.ui.graphics.toArgb
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
        enableEdgeToEdge(SystemBarStyle.dark(C.Bg.toArgb()), SystemBarStyle.dark(C.Bg.toArgb()))
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
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val start = if (FirebaseAuth.getInstance().currentUser == null) "phone" else "boot"

    fun afterSignIn() = nav.navigate("boot") { popUpTo(0) }

    NavHost(
        navController = nav, startDestination = start,
        modifier = Modifier.fillMaxSize().background(C.Bg),
        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
    ) {
        composable("phone") { PhoneScreen(flow, onCodeSent = { nav.navigate("code") }, onSignedIn = ::afterSignIn) }
        composable("code") { CodeScreen(flow, onBack = { nav.popBackStack() }, onSignedIn = ::afterSignIn) }
        composable("boot") {
            LaunchedEffect(Unit) {
                val profile = runCatching { Repo.myProfile() }.getOrNull()
                nav.navigate(if (profile == null) "name" else "home") { popUpTo(0) }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent) }
        }
        composable("name") {
            NameScreen(busy = saving) { name ->
                saving = true
                scope.launch {
                    runCatching { Repo.saveProfile(name) }
                    saving = false
                    nav.navigate("home") { popUpTo(0) }
                }
            }
        }
        composable("home") {
            HomeScreen(
                onOpenChat = { nav.navigate("chat/$it") },
                onNewChat = { nav.navigate("new") },
                onSignOut = { Repo.signOut(); nav.navigate("phone") { popUpTo(0) } },
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
