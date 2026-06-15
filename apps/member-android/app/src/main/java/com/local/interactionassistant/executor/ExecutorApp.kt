package com.local.interactionassistant.executor

import android.app.Application
import com.local.interactionassistant.executor.cloud.CloudRepository
import com.local.interactionassistant.executor.data.ExecutorDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ExecutorApp : Application() {
    lateinit var repository: LocalRepository
        private set
    lateinit var cloudRepository: CloudRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val database = ExecutorDatabase.create(this)
        repository = LocalRepository(
            context = this,
            database = database,
        )
        cloudRepository = CloudRepository(database)
        applicationScope.launch { repository.initialize() }
    }

    companion object {
        lateinit var instance: ExecutorApp
            private set

        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
