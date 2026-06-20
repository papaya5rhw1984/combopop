package com.ryu.combopop.ui
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
object Brand { val Accent = Color(0xFFEC407A) }
private val Dark = darkColorScheme(primary = Brand.Accent)
private val Light = lightColorScheme(primary = Brand.Accent)
@Composable fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) { MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content) }
