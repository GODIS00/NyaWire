package com.gdhapp.client.background

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import com.gdhapp.client.GdhappApplication
import com.gdhapp.client.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SubscriptionUpdater(private val repository: com.gdhapp.client.domain.repository.SubscriptionRepository,
    private val preferences: com.gdhapp.client.data.SettingsRepository, private val session: com.gdhapp.client.vpn.VpnSessionStore,
    private val networkAvailable: () -> Boolean = { true }) {
    private val mutex = Mutex()
    suspend fun refreshOnOpen(progress: (Set<String>) -> Unit): Int = mutex.withLock {
        if (!preferences.settings.value.refreshOnOpen || !networkAvailable()) return@withLock 0
        val pending = repository.groups.value.filter { it.sourceUrl != null }.map { it.id }.toMutableSet()
        var failures = 0
        try {
            progress(pending.toSet())
            pending.toList().forEach { id ->
                currentCoroutineContext().ensureActive()
                try { repository.refresh(id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failures++ }
                pending.remove(id); progress(pending.toSet())
            }
        } finally { progress(emptySet()) }
        failures
    }
    suspend fun refreshDue() = mutex.withLock {
        val settings = preferences.settings.value
        if (!settings.autoUpdateSubscriptions) return@withLock
        val groups = repository.groups.value
        val now = System.currentTimeMillis()
        groups.filter { due(it, settings, now) }.forEach { group ->
            try { repository.refresh(group.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep last working subscription; retry on the next scheduled run. */ }
        }
    }
    companion object {
        const val JOB_ID = 21401
        fun due(group: SubscriptionGroup, settings: ClientSettings, now: Long): Boolean {
            val hours = settings.subscriptionUpdateHours.takeIf { it > 0 } ?: group.autoUpdateHours.coerceAtLeast(1)
            return settings.autoUpdateSubscriptions && group.sourceUrl != null && now - group.updatedAtEpochMs >= hours * 3_600_000L
        }
        fun schedule(context: Context, enabled: Boolean) {
            val jobs = context.getSystemService(JobScheduler::class.java)
            if (!enabled) { jobs.cancel(JOB_ID); return }
            if (jobs.getPendingJob(JOB_ID) != null) return
            jobs.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, SubscriptionUpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setPeriodic(15 * 60_000L).build())
        }
    }
}

class SubscriptionUpdateJob : JobService() {
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        task = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { (application as GdhappApplication).subscriptionUpdater.refreshDue(); jobFinished(params, false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { jobFinished(params, true) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(); task = null; return true }
    override fun onDestroy() { task?.cancel(); super.onDestroy() }
}
