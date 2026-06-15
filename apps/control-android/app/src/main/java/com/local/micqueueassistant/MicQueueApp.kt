package com.local.micqueueassistant

import android.app.Application
import com.local.micqueueassistant.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MicQueueApp : Application() {
    lateinit var repository: MicQueueRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = MicQueueRepository(this, AppDatabase.create(this))
        applicationScope.launch { repository.initialize() }
    }

    companion object {
        lateinit var instance: MicQueueApp
            private set

        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
