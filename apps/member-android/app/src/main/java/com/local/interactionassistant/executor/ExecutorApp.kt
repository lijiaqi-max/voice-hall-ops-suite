package com.local.interactionassistant.executor

import android.app.Application
import com.local.interactionassistant.executor.cloud.CloudRepository
import com.local.interactionassistant.executor.cloud.TokenCipher
import com.local.interactionassistant.executor.data.ExecutorDatabase

class ExecutorApp : Application() {
    lateinit var cloudRepository: CloudRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val database = ExecutorDatabase.create(this)
        cloudRepository = CloudRepository(database, TokenCipher())
    }

    companion object {
        lateinit var instance: ExecutorApp
            private set
    }
}
