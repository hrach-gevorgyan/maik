package com.maik.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maik.app.data.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import com.maik.app.ui.components.*
import com.maik.app.ui.list.*
import com.maik.app.ui.settings.*
import com.maik.app.ui.setup.*
import com.maik.app.ui.theme.*

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, as the splash screen library requires. It stays up the
        // few milliseconds until chat history is read, so the list never flashes empty.
        installSplashScreen().setKeepOnScreenCondition { !vm.chatsLoaded }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A long decode is exactly the workload this mode exists for: a lower clock the
        // phone can hold, instead of a boost followed by throttling.
        val power = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (power?.isSustainedPerformanceModeSupported == true) {
            window.setSustainedPerformanceMode(true)
        }
        if (savedInstanceState == null) handle(intent)
        setContent { Root(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** Only the download notification lands on the download; nothing else jumps there. */
    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_NEW_CHAT) {
            intent.action = null
            vm.newChat()
            return
        }
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_DOWNLOAD) {
            intent.removeExtra(EXTRA_OPEN)
            if (DownloadBus.running.value) vm.showDownload()
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_DOWNLOAD = "download"
        const val ACTION_NEW_CHAT = "com.maik.app.NEW_CHAT"
    }
}

@Composable
internal fun Root(vm: ChatViewModel = viewModel()) {
    MaikTheme(vm.themeMode) {
      CompositionLocalProvider(LocalHaptics provides vm.hapticsEnabled) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Back always means "up one level", never "leave the app mid-chat".
            BackHandler(enabled = vm.screen != Screen.List) { vm.back() }

            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
                contentAlignment = androidx.compose.ui.Alignment.TopCenter
            ) {
              Box(Modifier.fillMaxHeight().widthIn(max = 680.dp)) {
                // On a wide screen — landscape, a tablet, split view — a chat that runs
                // the full width is unreadable. Text keeps a comfortable measure and
                // sits in the middle instead.
                AnimatedContent(
                    targetState = vm.screen,
                    transitionSpec = {
                        if (targetState == Screen.List) backward() else forward()
                    },
                    label = "screen"
                ) { screen ->
                    when (screen) {
                        Screen.List -> ConversationListScreen(vm)
                        Screen.Settings -> SettingsScreen(vm)
                        Screen.Setup -> SetupScreen(vm)
                        // The chat stays on screen whatever the model is doing; its
                        // state shows as a strip above the messages instead.
                        Screen.Chat -> ChatScreen(vm)
                    }
                }
              }
            }
        }
      }
    }
}
