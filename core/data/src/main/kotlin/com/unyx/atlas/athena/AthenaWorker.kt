package com.unyx.atlas.athena

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class AthenaWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted parameters: WorkerParameters, private val coordinator: AthenaCoordinator,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        coordinator.refresh()
        // Each source already has bounded retries. The next periodic pass handles recovery.
        return Result.success()
    }
}
