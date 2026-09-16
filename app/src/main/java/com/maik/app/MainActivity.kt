package com.maik.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Root() }
    }
}

@Composable
internal fun Root(vm: ChatViewModel = viewModel()) {
    MaikTheme(vm.themeMode) {
      CompositionLocalProvider(LocalHaptics provides vm.hapticsEnabled) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Coming back from the notification should land on the download, not
            // on whatever screen happened to be open when you left.
            val downloading by DownloadBus.running.collectAsState()
            LaunchedEffect(downloading) {
                if (downloading && vm.screen == Screen.List) vm.showDownload()
            }

            // Back always means "up one level", never "leave the app mid-chat".
            BackHandler(enabled = vm.screen != Screen.List) { vm.back() }

            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
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
