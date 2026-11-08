package com.listentogether.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.listenTogetherDataStore: DataStore<Preferences> by preferencesDataStore(name = "listen_together")
