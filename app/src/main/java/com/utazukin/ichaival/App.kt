/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2025 Utazukin
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

package com.utazukin.ichaival

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.google.android.material.color.DynamicColors
import com.utazukin.ichaival.database.DatabaseReader
import kotlinx.coroutines.launch

class App : Application(), SingletonImageLoader.Factory {

    init {
        instance = this
    }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.createCrashLogger(this)
        DatabaseReader.init(this)
        with(ProcessLifecycleOwner.get()) {
            lifecycleScope.launch { WebHandler.init(this@App) }
        }
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory =
                WebHandler.httpClient.newBuilder()
                    .addNetworkInterceptor { chain ->
                        val request = if (WebHandler.apiKey.isEmpty())
                            chain.request()
                        else
                            chain.request().newBuilder().addHeader("Authorization", WebHandler.apiKey).build()
                        chain.proceed(request)
                    }.build()))
            }
            .build()
    }

    companion object {
        private lateinit var instance: App
        val context: Context
            get() = instance.applicationContext
    }
}