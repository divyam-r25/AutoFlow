package com.autoflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.autoflow.navigation.NavGraph
import com.autoflow.ui.theme.AutoFlowTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContent {

            AutoFlowTheme {

                NavGraph()

            }

        }

    }

}