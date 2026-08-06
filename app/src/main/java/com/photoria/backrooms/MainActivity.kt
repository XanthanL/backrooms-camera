package com.photoria.backrooms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.photoria.backrooms.ui.screen.CameraScreen
import com.photoria.backrooms.ui.theme.PhotoriaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoriaTheme {
                CameraScreen()
            }
        }
    }
}
