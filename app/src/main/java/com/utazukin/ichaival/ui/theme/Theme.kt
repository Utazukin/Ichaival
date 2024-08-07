/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2024 Utazukin
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.utazukin.ichaival.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.utazukin.ichaival.R
import com.utazukin.ichaival.getCustomTheme

private val DarkColorScheme = darkColorScheme(
        primary = Purple80,
        secondary = PurpleGrey80,
        tertiary = Pink80,
        background = Color.Black
)

private val BlackColorScheme = darkColorScheme(
        primary = Color.DarkGray,
        onPrimary = Color.White,
        background = Color.Black
)

private val GrayColorScheme = darkColorScheme(
        primary = Color.DarkGray,
        onPrimary = Color.White,
        background = Color(0xFF303030)
)

private val LightColorScheme = lightColorScheme(
        primary = Purple40,
        secondary = PurpleGrey40,
        tertiary = Pink40

        /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun IchaivalTheme(
    darkTheme: Boolean = true,
        // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    theme: String? = null,
    content: @Composable () -> Unit
) {
    val grayTheme = LocalContext.current.getString(R.string.dark_theme)
    val colorScheme = when {
        theme == LocalContext.current.getString(R.string.black_theme) -> BlackColorScheme
        theme == grayTheme -> GrayColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = if (theme != grayTheme) colorScheme.background.toArgb() else Color.Black.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
    )
}

@Composable
fun ThemeButton(onClick: () -> Unit, content:@Composable RowScope.() -> Unit, modifier: Modifier = Modifier) {
    val shape = if (LocalContext.current.getCustomTheme() != LocalContext.current.getString(R.string.material_theme)) RoundedCornerShape(8) else ButtonDefaults.shape
    Button(onClick = onClick, content = content, shape = shape, modifier = modifier, elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp))
}

@Composable
fun ThemeText(text: String) {
    if (LocalContext.current.getCustomTheme() != LocalContext.current.getString(R.string.material_theme))
        Text(text = text.uppercase())
    else
        Text(text = text)
}

@Composable
fun ThemeTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, content:@Composable RowScope.() -> Unit) {
    val context = LocalContext.current
    val buttonColors = when(context.getCustomTheme()) {
        context.getString(R.string.black_theme) -> ButtonDefaults.textButtonColors().copy(contentColor = Color.White)
        context.getString(R.string.dark_theme) -> ButtonDefaults.textButtonColors().copy(contentColor = Color.White)
        else -> ButtonDefaults.textButtonColors()
    }
    TextButton(onClick = onClick, content = content, modifier = modifier, colors = buttonColors)
}

@Composable
fun ThemeTextField(value: String, onValueChange: (String) -> Unit, singleLine: Boolean = false) {
    val context = LocalContext.current
    val theme = context.getCustomTheme()
    val colors = if (theme == context.getString(R.string.material_theme))
        TextFieldDefaults.colors()
    else {
        val containerColor = if (theme == context.getString(R.string.black_theme)) Color.Black else MaterialTheme.colorScheme.primary
        TextFieldDefaults.colors().copy(
                focusedContainerColor = containerColor,
                focusedIndicatorColor = Color(0xFF008577),
                unfocusedContainerColor = containerColor,
                cursorColor = Color(0xFF008577))
    }
    TextField(value = value, onValueChange = onValueChange, singleLine = singleLine, colors = colors)
}

@Composable
fun ThemeAlertDialog(onDismissRequest: () -> Unit,
                     confirmButton: @Composable () -> Unit,
                     dismissButton: (@Composable () -> Unit)?,
                     title: (@Composable () -> Unit)?,
                     text: (@Composable () -> Unit)?) {
    val context = LocalContext.current
    val dialogShape = if (context.getCustomTheme() != context.getString(R.string.material_theme)) RectangleShape else AlertDialogDefaults.shape
    val containerColor = when(context.getCustomTheme()) {
        context.getString(R.string.material_theme) -> AlertDialogDefaults.containerColor
        context.getString(R.string.black_theme) -> Color.Black
        else -> MaterialTheme.colorScheme.primary
    }
    AlertDialog(onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            title = title,
            text = text,
            containerColor = containerColor,
            shape = dialogShape)
}