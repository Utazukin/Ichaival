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

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3Api::class)

package com.utazukin.ichaival.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.utazukin.ichaival.Header
import com.utazukin.ichaival.R
import com.utazukin.ichaival.WebHandler
import com.utazukin.ichaival.getCustomTheme
import com.utazukin.ichaival.ui.theme.IchaivalTheme
import com.utazukin.ichaival.ui.theme.ThemeAlertDialog
import com.utazukin.ichaival.ui.theme.ThemeButton
import com.utazukin.ichaival.ui.theme.ThemeText
import com.utazukin.ichaival.ui.theme.ThemeTextButton
import com.utazukin.ichaival.ui.theme.ThemeTextField
import kotlinx.coroutines.launch

class HeaderSettingsFragment : Fragment() {
    private val headers = mutableStateListOf<Header>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view =  inflater.inflate(R.layout.fragment_header_settings, container, false)
        val composeView: ComposeView = view.findViewById(R.id.settings_compose_view)

        headers.addAll(WebHandler.customHeaders)
        composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val theme = context.getCustomTheme()
                val color = if (theme == getString(R.string.dark_theme)) Color.DarkGray else Color.Black
                IchaivalTheme(theme = theme) {
                    Surface(color = color) {
                        Scaffold(topBar = { AppBar() }) {
                            ConstraintLayout(modifier = Modifier.fillMaxSize()) {
                                val (list, button) = createRefs()
                                HeaderList(headers = headers, modifier = Modifier
                                    .padding(it)
                                    .padding(horizontal = 8.dp)
                                    .constrainAs(list) {
                                        top.linkTo(parent.top)
                                    })
                                HeaderButton(content = { ThemeText(text = getString(R.string.add_header)) }, modifier = Modifier
                                    .padding(it)
                                    .fillMaxWidth()
                                    .constrainAs(button) {
                                        bottom.linkTo(parent.bottom)
                                    })
                            }
                        }
                    }
                }
            }
        }

        return view
    }

    @Composable
    private fun AppBar() {
        val colors = when(requireContext().getCustomTheme()) {
            getString(R.string.black_theme) -> TopAppBarDefaults.topAppBarColors().copy(containerColor = Color.Black)
            getString(R.string.dark_theme) -> TopAppBarDefaults.topAppBarColors().copy(containerColor = Color(0xFF212121))
            else -> TopAppBarDefaults.topAppBarColors()
        }
        TopAppBar(title = { Text("Custom Headers") }, navigationIcon = {
            IconButton(onClick = { requireActivity().supportFragmentManager.popBackStack() }) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        }, colors = colors, modifier = Modifier.shadow(elevation = 5.dp))
    }

    @Composable
    private fun HeaderList(headers: List<Header>, modifier: Modifier) {
        val stateList = remember { headers }
        LazyColumn(modifier) {
            itemsIndexed(stateList) {i, item ->
                HeaderItem(header = item)
                if (i < stateList.lastIndex)
                    HorizontalDivider()
            }
        }
    }

    @Composable
    private fun HeaderItem(header: Header) {
        var openDialog by remember { mutableStateOf(false) }

        if (openDialog)
            HeaderDialog(onDismissRequest = { openDialog = false }, header.name, header.value)

        Column(modifier = Modifier
            .fillMaxWidth()
            .clickable { openDialog = true }
            .padding(vertical = 8.dp)) {
            Text(text = header.name, fontWeight = FontWeight.Bold)
            Text(text = header.value)
        }
    }
    
    @Composable
    private fun HeaderButton(content:@Composable RowScope.() -> Unit, modifier: Modifier = Modifier) {
        var openDialog by remember { mutableStateOf(false) }

        if (openDialog)
            HeaderDialog(onDismissRequest = { openDialog = false })

        ThemeButton(onClick = { openDialog = true }, content = content, modifier = modifier)
    }

    private fun addHeader(name: String, value: String) {
        headers.add(Header(name, value))
        lifecycleScope.launch { WebHandler.updateHeaders(requireContext(), headers.toList()) }
    }

    private fun removeHeader(header: Header) {
        headers.remove(header)
        lifecycleScope.launch { WebHandler.updateHeaders(requireContext(), headers.toList()) }
    }

    private fun replaceHeader(old: Header, new: Header) {
        val index = headers.indexOf(old)
        headers.removeAt(index)
        headers.add(index, new)
        lifecycleScope.launch { WebHandler.updateHeaders(requireContext(), headers.toList()) }
    }

    @Composable
    private fun HeaderDialog(onDismissRequest: () -> Unit, name: String = "", value: String = "") {
        var headerName by remember { mutableStateOf(name) }
        var headerValue by remember { mutableStateOf(value) }
        val dismissButton: (@Composable () -> Unit)? = if (name.isEmpty())
            null
        else {
            { ThemeTextButton(onClick = { removeHeader(Header(name, value)); onDismissRequest() }) {
                Text(text = getString(R.string.delete_header))
            } }
        }
        val title = if (name.isEmpty()) getString(R.string.add_header) else getString(R.string.modify_header)

        ThemeAlertDialog(onDismissRequest = onDismissRequest,
                confirmButton = { ThemeTextButton(onClick = {
                    if (headerName.isNotBlank()) {
                        if (name.isNotEmpty())
                            replaceHeader(Header(name, value), Header(headerName, headerValue))
                        else
                            addHeader(headerName, headerValue)
                        onDismissRequest()
                    }
                }) { Text(text = getString(android.R.string.ok)) } },
                title = { Text(title) },
                dismissButton = dismissButton,
                text = {
                    Column {
                        Text(getString(R.string.header_name))
                        ThemeTextField(value = headerName, onValueChange = { headerName = it }, singleLine = true)
                        Text(getString(R.string.header_value))
                        ThemeTextField(value = headerValue, onValueChange = { headerValue = it }, singleLine = true)
                    }
                })
    }
}