package com.jarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.ai.ui.JarvisApp
import com.jarvis.ai.ui.theme.JarvisTheme
import com.jarvis.ai.viewmodel.JarvisViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JarvisTheme {
                val viewModel: JarvisViewModel = viewModel()
                JarvisApp(viewModel = viewModel)
            }
        }
    }
}
