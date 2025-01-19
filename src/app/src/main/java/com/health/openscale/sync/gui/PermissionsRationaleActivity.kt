package com.health.openscale.sync.gui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.openscale.sync.gui.theme.OpenScaleSyncTheme

class PermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenScaleSyncTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Column (
                        horizontalAlignment = Alignment.CenterHorizontally
                    ){
                        Text("You can find detailed information of the privacy policy at https://github.com/oliexdev/openScale/wiki/openScale-sync")
                    }
                }
            }
        }
    }
}