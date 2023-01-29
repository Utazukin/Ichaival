/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2023 Utazukin
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
import android.net.ConnectivityManager
import com.google.android.material.color.DynamicColors
import com.utazukin.ichaival.database.DatabaseReader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.createCrashLogger(this)
        DatabaseReader.init(this)
        WebHandler.connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}