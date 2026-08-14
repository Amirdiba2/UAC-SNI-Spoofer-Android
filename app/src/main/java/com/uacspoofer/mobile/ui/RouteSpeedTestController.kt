package com.uacspoofer.mobile.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.uacspoofer.mobile.profiles.ProfileLatencyTester
import com.uacspoofer.mobile.profiles.RouteSpeedProbeResult
import com.uacspoofer.mobile.profiles.RouteSpeedProbeStage
import com.uacspoofer.mobile.profiles.RouteSpeedTestPlan
import com.uacspoofer.mobile.vpn.AdaptiveCandidate
import com.uacspoofer.mobile.vpn.AdaptiveDnsResolvers
import com.uacspoofer.mobile.vpn.AdaptiveSavedRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.random.Random

internal enum class RouteSpeedStatus { QUEUED, STARTING, TESTING, PASSED, FAILED, STOPPED }

internal enum class RouteTournamentStage(
    val title: String,
    val subtitle: String,
    val shortlistSize: Int,
    val samplesPerCandidate: Int,
    val workers: Int,
) {
    QUALIFIER("Qualifying", "Every route gets one cold connectivity test", Int.MAX_VALUE, 1, 3),
    VERIFICATION("Verification", "The best 96 routes are checked again", 96, 1, 3),
    STABILITY("Stability", "24 diverse routes get repeated stability samples", 24, 2, 2),
    STRESS("Stress test", "6 finalists face repeated cold-start tests", 6, 3, 1),
    CHAMPIONSHIP("ABBA final", "Champion and backup are compared A-B-B-A", 2, 2, 1),
    COMPLETE("Complete", "Champion and backup are ready", 0, 0, 0),
}

internal data class RouteObservation(
    val stage: RouteTournamentStage,
    val accepted: Boolean,
    val score: Int,
    val latencyMs: Long?,
    val dnsLatencyMs: Long?,
    val payloadBytes: Int,
    val throughputKbps: Long,
    val httpSucceeded: Int,
    val httpAttempted: Int,
    val dnsSucceeded: Boolean,
    val detail: String,
    val failureFingerprint: String,
)

internal data class RouteSpeedRow(
    val candidateId: String,
    val label: String,
    val route: String,
    val edgeKey: String,
    val resolverKey: String,
    val fragmentKey: String,
    val mtu: Int,
    val status: RouteSpeedStatus = RouteSpeedStatus.QUEUED,
    val stageReached: RouteTournamentStage = RouteTournamentStage.QUALIFIER,
    val score: Int = 0,
    val tournamentScore: Int = 0,
    val confidence: Int = 0,
    val latencyMs: Long? = null,
    val p95LatencyMs: Long? = null,
    val jitterMs: Long? = null,
    val dnsLatencyMs: Long? = null,
    val payloadBytes: Int = 0,
    val throughputKbps: Long = 0L,
    val httpSucceeded: Int = 0,
    val httpAttempted: Int = 0,
    val dnsSucceeded: Boolean = false,
    val dnsSuccessCount: Int = 0,
    val successfulSamples: Int = 0,
    val observations: List<RouteObservation> = emptyList(),
    val failureFingerprint: String = "Not tested",
    val detail: String = "Waiting to test",
) {
    val sampleCount: Int get() = observations.size
    val usable: Boolean get() = successfulSamples > 0
}

internal data class SavedRouteDetails(
    val id: String,
    val label: String,
    val edge: String,
    val role: String,
    val resolver: String,
    val fragment: String,
    val mtu: Int,
    val directCompat: Boolean,
)

internal data class SavedRouteProfileDetails(
    val profileName: String,
    val profileId: String,
    val profileType: String,
    val protocol: String,
    val server: String,
    val transport: String,
    val security: String,
    val sni: String,
    val host: String,
    val path: String,
    val alpn: String,
    val fingerprint: String,
    val networkTransport: String,
    val carrier: String,
    val carrierClass: String,
    val provider: String,
    val asn: String,
    val networkFingerprint: String,
    val networkMtu: Int,
    val metered: Boolean,
    val validated: Boolean,
    val ipSupport: String,
    val champion: SavedRouteDetails?,
    val backup: SavedRouteDetails?,
)

internal class RouteSpeedTestController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val tester = ProfileLatencyTester(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var plan: RouteSpeedTestPlan? = null
    private var loadJob: Job? = null
    private var testJob: Job? = null
    private var stageJob: Job? = null
    private var pauseRequested = false
    private var manualAdvanceRequested = false
    private var manualAdvanceCandidateIds: List<String>? = null

    val rows = mutableStateListOf<RouteSpeedRow>()
    private val finalStageRows = mutableStateListOf<RouteSpeedRow>()
    var loading by mutableStateOf(false)
        private set
    var testing by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    var profileName by mutableStateOf("-")
        private set
    var networkLabel by mutableStateOf("Detecting network…")
        private set
    var notice by mutableStateOf("Preparing the Route Tournament…")
        private set
    var activeCandidateId by mutableStateOf<String?>(null)
        private set
    var activeCount by mutableStateOf(0)
        private set
    var currentStage by mutableStateOf(RouteTournamentStage.QUALIFIER)
        private set
    var phaseCompletedCount by mutableStateOf(0)
        private set
    var phaseTotalCount by mutableStateOf(0)
        private set
    var recommendedCandidateId by mutableStateOf<String?>(null)
        private set
    var backupCandidateId by mutableStateOf<String?>(null)
        private set
    var championConfidence by mutableStateOf(0)
        private set
    var selectedCandidateId by mutableStateOf<String?>(null)
        private set
    var savedRouteProfileName by mutableStateOf<String?>(null)
        private set
    var savedChampionLabel by mutableStateOf<String?>(null)
        private set
    var savedBackupLabel by mutableStateOf<String?>(null)
        private set
    var savedRouteDetails by mutableStateOf<SavedRouteProfileDetails?>(null)
        private set
    var finalStageHistoryAvailable by mutableStateOf(false)
        private set
    var viewingFinalStageHistory by mutableStateOf(false)
        private set
    var finalStageHistoryTitle by mutableStateOf("Last Championship")
        private set

    val completedCount: Int
        get() = rows.count { it.sampleCount > 0 }

    val healthyCount: Int
        get() = rows.count(RouteSpeedRow::usable)

    val advanceReadyCount: Int
        get() = rows.count { row ->
            row.observations.any { observation ->
                observation.stage == currentStage && observation.accepted
            }
        }

    val canAdvanceNow: Boolean
        get() = !loading &&
            currentStage != RouteTournamentStage.COMPLETE &&
            advanceReadyCount > 0 &&
            (testing || paused)

    fun load(force: Boolean = false) {
        if (testing || loading || (!force && plan != null)) return
        preparePlan(resumeIfRequested = false)
    }

    fun refresh() {
        if (testing) {
            notice = "Pause the Tournament before reloading the profile and network"
            return
        }
        viewingFinalStageHistory = false
        finalStageRows.clear()
        notice = "Reloading the current configuration and network…"
        preparePlan(resumeIfRequested = false)
    }

    fun startTest() {
        val prepared = plan ?: return preparePlan(resumeIfRequested = false)
        if (testing || loading || prepared.candidates.isEmpty()) return
        beginRun(prepared, reset = true)
    }

    fun resumeTest() {
        val prepared = plan ?: return preparePlan(resumeIfRequested = true)
        if (testing || loading || prepared.candidates.isEmpty()) return
        beginRun(prepared, reset = false)
    }

    fun pauseTest() {
        pauseRequested = true
        preferences.edit().putBoolean(KEY_RUN_REQUESTED, false).apply()
        if (testing) {
            testJob?.cancel()
        } else {
            paused = currentStage != RouteTournamentStage.COMPLETE
            if (paused) notice = "Route Tournament paused • use the current best route or resume"
            stopKeepAliveService()
        }
    }

    fun advanceStageNow() {
        val prepared = plan ?: return
        if (!canAdvanceNow || manualAdvanceRequested) return
        val promoted = manualAdvanceShortlist(currentStage)
        if (promoted.isEmpty()) {
            notice = "No fully healthy result is ready in ${currentStage.title} yet"
            return
        }
        manualAdvanceRequested = true
        manualAdvanceCandidateIds = promoted
        notice = "Advancing ${promoted.size} healthy routes from ${currentStage.title} now…"
        if (testing) {
            stageJob?.cancel(CancellationException("Manual stage advance"))
        } else {
            applyPausedManualAdvance(prepared, promoted)
        }
    }

    fun resumePersistedIfRequested() {
        if (testing || loading || !preferences.getBoolean(KEY_RUN_REQUESTED, false)) return
        preparePlan(resumeIfRequested = true)
    }

    fun selectRoute(candidateId: String) {
        val prepared = plan ?: return
        val row = rows.firstOrNull { it.candidateId == candidateId } ?: return
        if (!row.usable) return
        val backup = rankedRows().firstOrNull { it.candidateId != candidateId && it.usable }
        if (
            tester.selectRouteWinner(
                plan = prepared,
                candidateId = candidateId,
                score = row.score,
                backupCandidateId = backup?.candidateId,
                backupScore = backup?.score ?: 0,
            )
        ) {
            selectedCandidateId = candidateId
            backupCandidateId = backup?.candidateId
            savedRouteProfileName = prepared.profile.name
            savedChampionLabel = row.label
            savedBackupLabel = backup?.label
            savedRouteDetails = buildSavedRouteDetails(
                prepared,
                prepared.candidates.firstOrNull { it.id == candidateId }?.toSavedRouteDetails(),
                backup?.candidateId?.let { id ->
                    prepared.candidates.firstOrNull { it.id == id }?.toSavedRouteDetails()
                },
            )
            notice = if (backup == null) {
                "${row.label} saved for this network and configuration"
            } else {
                "Champion and backup saved for automatic recovery on this network"
            }
        }
    }

    fun loadLastFinalStageList(): Boolean {
        if (testing) {
            notice = "Pause the Tournament before opening the previous Championship"
            return false
        }
        val prepared = plan ?: run {
            notice = "Current profile and network are not ready yet"
            return false
        }
        val snapshot = restoreFinalStageSnapshot(prepared)
        val fallback = if (snapshot.isEmpty() && hasMatchingSession(prepared)) {
            val ids = restoreStageIds(RouteTournamentStage.CHAMPIONSHIP).orEmpty()
            rows.filter { it.candidateId in ids }
        } else {
            snapshot
        }
        if (fallback.isEmpty()) {
            notice = "No previous Championship list is available yet"
            return false
        }
        finalStageRows.clear()
        finalStageRows.addAll(fallback)
        viewingFinalStageHistory = true
        notice = "Loaded ${fallback.size} routes from the previous Championship"
        return true
    }

    fun showLiveRanking() {
        viewingFinalStageHistory = false
        notice = if (testing) {
            "Live ${currentStage.title} ranking restored"
        } else {
            "Live Tournament ranking restored"
        }
    }

    fun visibleRows(): List<RouteSpeedRow> =
        (if (viewingFinalStageHistory) finalStageRows else rows).sortedWith(
        compareBy<RouteSpeedRow> {
            when {
                it.candidateId == recommendedCandidateId -> 0
                it.candidateId == backupCandidateId -> 1
                it.usable -> 2
                it.status == RouteSpeedStatus.TESTING || it.status == RouteSpeedStatus.STARTING -> 3
                it.status == RouteSpeedStatus.QUEUED -> 4
                else -> 5
            }
        }.thenByDescending { it.tournamentScore }
            .thenByDescending { it.confidence }
            .thenByDescending { it.throughputKbps }
            .thenBy { it.p95LatencyMs ?: Long.MAX_VALUE },
    )

    private fun preparePlan(resumeIfRequested: Boolean) {
        loadJob?.cancel()
        viewingFinalStageHistory = false
        finalStageRows.clear()
        finalStageHistoryAvailable = false
        loadJob = scope.launch {
            loading = true
            notice = "Detecting network and restoring the Route Tournament…"
            try {
                val prepared = tester.prepareRouteSpeedTest()
                plan = prepared
                profileName = prepared.profile.name
                networkLabel = buildNetworkLabel(prepared)
                viewingFinalStageHistory = false
                finalStageRows.clear()
                selectedCandidateId = prepared.savedChampionId
                savedRouteProfileName = prepared.savedChampionLabel?.let { prepared.profile.name }
                savedChampionLabel = prepared.savedChampionLabel
                savedBackupLabel = prepared.savedBackupLabel
                savedRouteDetails = buildSavedRouteDetails(
                    prepared,
                    prepared.savedChampion?.toSavedRouteDetails(),
                    prepared.savedBackup?.toSavedRouteDetails(),
                )
                finalStageHistoryAvailable = hasFinalStageSnapshot(prepared) ||
                    (hasMatchingSession(prepared) &&
                        !restoreStageIds(RouteTournamentStage.CHAMPIONSHIP).isNullOrEmpty())
                restoreRows(prepared)
                currentStage = restoreCurrentStage()
                updatePhaseProgress(currentStage)
                updateRecommendedCandidates()
                paused = hasMatchingSession(prepared) && currentStage != RouteTournamentStage.COMPLETE
                notice = when {
                    currentStage == RouteTournamentStage.COMPLETE ->
                        "Tournament complete • Champion and backup are ready"
                    paused ->
                        "${currentStage.title} restored • tap RESUME to continue"
                    rows.isNotEmpty() ->
                        "${rows.size} route genomes ready for ${prepared.profile.name}"
                    else -> "No routes available"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                plan = null
                rows.clear()
                paused = false
                selectedCandidateId = null
                savedRouteProfileName = null
                savedChampionLabel = null
                savedBackupLabel = null
                savedRouteDetails = null
                finalStageHistoryAvailable = false
                viewingFinalStageHistory = false
                finalStageRows.clear()
                notice = "Could not prepare routes: ${error.shortMessage()}"
                networkLabel = "Network unavailable"
            } finally {
                loading = false
            }
            if (resumeIfRequested && preferences.getBoolean(KEY_RUN_REQUESTED, false)) {
                plan?.let { beginRun(it, reset = false) }
            }
        }
    }

    private fun beginRun(prepared: RouteSpeedTestPlan, reset: Boolean) {
        if (testJob?.isActive == true) return
        viewingFinalStageHistory = false
        finalStageRows.clear()
        if (reset) {
            clearPersistedRows()
            rows.clear()
            rows.addAll(prepared.candidates.map(::initialRow))
            createSession(prepared)
            currentStage = RouteTournamentStage.QUALIFIER
            recommendedCandidateId = null
            backupCandidateId = null
            championConfidence = 0
        } else {
            rows.indices.forEach { index ->
                if (rows[index].status in setOf(
                        RouteSpeedStatus.STARTING,
                        RouteSpeedStatus.TESTING,
                        RouteSpeedStatus.STOPPED,
                    )
                ) {
                    rows[index] = rows[index].copy(
                        status = if (rows[index].usable) RouteSpeedStatus.PASSED else RouteSpeedStatus.QUEUED,
                        detail = if (rows[index].usable) rows[index].detail else "Waiting to resume",
                    )
                }
            }
            currentStage = restoreCurrentStage()
        }
        if (currentStage == RouteTournamentStage.COMPLETE) {
            paused = false
            preferences.edit().putBoolean(KEY_RUN_REQUESTED, false).apply()
            notice = "Route Tournament is already complete"
            stopKeepAliveService()
            return
        }
        pauseRequested = false
        manualAdvanceRequested = false
        manualAdvanceCandidateIds = null
        paused = false
        testing = true
        preferences.edit().putBoolean(KEY_RUN_REQUESTED, true).apply()
        startKeepAliveService()
        testJob = scope.launch {
            try {
                runTournament(prepared)
                preferences.edit().putBoolean(KEY_RUN_REQUESTED, false).apply()
                paused = false
                finishTournament()
            } catch (cancelled: CancellationException) {
                rows.indices.forEach { index ->
                    if (rows[index].status == RouteSpeedStatus.STARTING || rows[index].status == RouteSpeedStatus.TESTING) {
                        rows[index] = rows[index].copy(
                            status = RouteSpeedStatus.STOPPED,
                            detail = "Paused before this sample completed",
                        )
                    }
                }
                persistAllRows()
                updateRecommendedCandidates()
                paused = currentStage != RouteTournamentStage.COMPLETE
                notice = if (recommendedCandidateId == null) {
                    "Route Tournament paused • tap RESUME to continue"
                } else {
                    "Tournament paused • current Champion can be used now"
                }
                if (!pauseRequested) preferences.edit().putBoolean(KEY_RUN_REQUESTED, true).apply()
            } finally {
                activeCandidateId = null
                activeCount = 0
                testing = false
                testJob = null
                if (!preferences.getBoolean(KEY_RUN_REQUESTED, false)) stopKeepAliveService()
            }
        }
    }

    private suspend fun runTournament(prepared: RouteSpeedTestPlan) {
        var stage = currentStage
        while (stage != RouteTournamentStage.COMPLETE) {
            currentCoroutineContext().ensureActive()
            currentStage = stage
            val candidateIds = stageCandidateIds(stage)
            if (candidateIds.isEmpty()) break
            persistStage(stage, candidateIds)
            runStage(prepared, stage, candidateIds)
            val nextStage = stage.next()
            val manuallyPromoted = manualAdvanceCandidateIds
            if (manualAdvanceRequested && manuallyPromoted != null) {
                if (nextStage != RouteTournamentStage.COMPLETE) {
                    persistStage(nextStage, manuallyPromoted)
                }
                manualAdvanceRequested = false
                manualAdvanceCandidateIds = null
            }
            stage = nextStage
            currentStage = stage
            preferences.edit().putString(KEY_STAGE, stage.name).apply()
        }
    }

    private suspend fun runStage(
        prepared: RouteSpeedTestPlan,
        stage: RouteTournamentStage,
        candidateIds: List<String>,
    ) {
        val schedule = buildSchedule(prepared, stage, candidateIds)
        val remaining = remainingSchedule(stage, schedule)
        phaseTotalCount = schedule.size
        phaseCompletedCount = schedule.size - remaining.size
        notice = "${stage.title} • ${remaining.size} samples left • ${stage.workers} workers"
        if (remaining.isEmpty()) return

        try {
            coroutineScope {
                stageJob = currentCoroutineContext()[Job]
                val queue = Channel<String>(Channel.UNLIMITED)
                remaining.forEach(queue::trySend)
                queue.close()
                List(stage.workers) {
                    launch {
                        for (candidateId in queue) {
                        currentCoroutineContext().ensureActive()
                        val candidate = prepared.candidates.firstOrNull { it.id == candidateId } ?: continue
                        activeCandidateId = candidate.id
                        activeCount++
                        updateRow(candidate.id) {
                            it.copy(
                                status = RouteSpeedStatus.STARTING,
                                stageReached = stage,
                                detail = "${stage.title}: starting a fresh Xray route",
                            )
                        }
                        try {
                            val result = tester.measureRouteSpeedCandidate(prepared, candidate) { probeStage ->
                                withContext(Dispatchers.Main.immediate) {
                                    updateRow(candidate.id) { current ->
                                        current.copy(
                                            status = when (probeStage) {
                                                RouteSpeedProbeStage.STARTING -> RouteSpeedStatus.STARTING
                                                RouteSpeedProbeStage.PROBING -> RouteSpeedStatus.TESTING
                                            },
                                            detail = when (probeStage) {
                                                RouteSpeedProbeStage.STARTING -> "${stage.title}: cold-starting Xray"
                                                RouteSpeedProbeStage.PROBING -> "${stage.title}: testing HTTP, DNS and payload"
                                            },
                                        )
                                    }
                                }
                            }
                            updateRow(candidate.id) { aggregateResult(it, result, stage) }
                            persistRow(rows.first { it.candidateId == candidate.id })
                            phaseCompletedCount++
                            updateRecommendedCandidates()
                            notice = "${stage.title} • $phaseCompletedCount/$phaseTotalCount • $healthyCount healthy"
                        } finally {
                            activeCount--
                        }
                        }
                    }
                }.joinAll()
            }
        } catch (cancelled: CancellationException) {
            if (!manualAdvanceRequested) throw cancelled
            rows.indices.forEach { index ->
                if (rows[index].status == RouteSpeedStatus.STARTING || rows[index].status == RouteSpeedStatus.TESTING) {
                    rows[index] = rows[index].copy(
                        status = if (rows[index].usable) RouteSpeedStatus.PASSED else RouteSpeedStatus.STOPPED,
                        detail = "${stage.title}: skipped after manual advance",
                    )
                }
            }
            persistAllRows()
            notice = "${manualAdvanceCandidateIds.orEmpty().size} healthy routes promoted from ${stage.title}"
        } finally {
            stageJob = null
        }
    }

    private fun manualAdvanceShortlist(stage: RouteTournamentStage): List<String> {
        val next = stage.next()
        val passedThisStage = rows.filter { row ->
            row.observations.any { observation ->
                observation.stage == stage && observation.accepted
            }
        }
        if (passedThisStage.isEmpty()) return emptyList()
        if (next == RouteTournamentStage.COMPLETE) {
            return rankedRows(passedThisStage).map(RouteSpeedRow::candidateId)
        }
        val limit = minOf(next.shortlistSize, passedThisStage.size)
        return when (next) {
            RouteTournamentStage.VERIFICATION,
            RouteTournamentStage.STABILITY -> diverseShortlist(passedThisStage, limit)
            RouteTournamentStage.STRESS,
            RouteTournamentStage.CHAMPIONSHIP -> rankedRows(passedThisStage).take(limit).map(RouteSpeedRow::candidateId)
            else -> emptyList()
        }
    }

    private fun applyPausedManualAdvance(prepared: RouteSpeedTestPlan, promoted: List<String>) {
        val next = currentStage.next()
        manualAdvanceRequested = false
        manualAdvanceCandidateIds = null
        if (next == RouteTournamentStage.COMPLETE) {
            preferences.edit().putBoolean(KEY_RUN_REQUESTED, false).apply()
            paused = false
            finishTournament()
            stopKeepAliveService()
            return
        }
        persistStage(next, promoted)
        currentStage = next
        preferences.edit().putString(KEY_STAGE, next.name).apply()
        updatePhaseProgress(next)
        notice = "${promoted.size} healthy routes promoted • starting ${next.title}"
        beginRun(prepared, reset = false)
    }

    private fun stageCandidateIds(stage: RouteTournamentStage): List<String> {
        restoreStageIds(stage)?.let { restored ->
            if (restored.isNotEmpty()) return restored
        }
        if (stage == RouteTournamentStage.QUALIFIER) return rows.map(RouteSpeedRow::candidateId)
        val previous = stage.previous() ?: return emptyList()
        val sourceIds = restoreStageIds(previous).orEmpty().ifEmpty { rows.map(RouteSpeedRow::candidateId) }
        val sourceRows = rows.filter { it.candidateId in sourceIds && it.sampleCount > 0 }
        val limit = minOf(stage.shortlistSize, sourceRows.size)
        if (limit <= 0) return emptyList()
        return when (stage) {
            RouteTournamentStage.VERIFICATION,
            RouteTournamentStage.STABILITY -> diverseShortlist(sourceRows, limit)
            RouteTournamentStage.STRESS,
            RouteTournamentStage.CHAMPIONSHIP -> rankedRows(sourceRows).take(limit).map(RouteSpeedRow::candidateId)
            else -> emptyList()
        }
    }

    private fun diverseShortlist(source: List<RouteSpeedRow>, limit: Int): List<String> {
        val ranked = rankedRows(source)
        val selected = LinkedHashSet<String>()
        ranked.take(maxOf(1, limit / 4)).forEach { selected += it.candidateId }
        fun addCategoryBest(selector: (RouteSpeedRow) -> String) {
            ranked.groupBy(selector).values
                .mapNotNull { group -> group.firstOrNull() }
                .sortedWith(routeRankingComparator())
                .forEach { if (selected.size < limit) selected += it.candidateId }
        }
        addCategoryBest(RouteSpeedRow::edgeKey)
        addCategoryBest(RouteSpeedRow::resolverKey)
        addCategoryBest(RouteSpeedRow::fragmentKey)
        addCategoryBest { it.mtu.toString() }
        ranked.forEach { if (selected.size < limit) selected += it.candidateId }
        return selected.take(limit)
    }

    private fun buildSchedule(
        prepared: RouteSpeedTestPlan,
        stage: RouteTournamentStage,
        candidateIds: List<String>,
    ): List<String> {
        if (stage == RouteTournamentStage.CHAMPIONSHIP && candidateIds.size >= 2) {
            val a = candidateIds[0]
            val b = candidateIds[1]
            return listOf(a, b, b, a)
        }
        val seedBase = "${prepared.signature}|${prepared.session.network.learningKey()}|${stage.name}".hashCode()
        return buildList {
            repeat(stage.samplesPerCandidate) { round ->
                addAll(candidateIds.shuffled(Random(seedBase + round * 7_919)))
            }
        }
    }

    private fun remainingSchedule(stage: RouteTournamentStage, schedule: List<String>): List<String> {
        val alreadyCompleted = rows.associate { row ->
            row.candidateId to row.observations.count { it.stage == stage }
        }
        val desiredOccurrence = HashMap<String, Int>()
        return schedule.filter { candidateId ->
            val occurrence = (desiredOccurrence[candidateId] ?: 0) + 1
            desiredOccurrence[candidateId] = occurrence
            (alreadyCompleted[candidateId] ?: 0) < occurrence
        }
    }

    private fun aggregateResult(
        previous: RouteSpeedRow,
        result: RouteSpeedProbeResult,
        stage: RouteTournamentStage,
    ): RouteSpeedRow {
        val failure = classifyFailure(result)
        val observations = previous.observations + result.toObservation(stage, failure)
        val successful = observations.count(RouteObservation::accepted)
        val latencies = observations.mapNotNull(RouteObservation::latencyMs).sorted()
        val dnsLatencies = observations.filter(RouteObservation::dnsSucceeded)
            .mapNotNull(RouteObservation::dnsLatencyMs)
            .sorted()
        val throughputs = observations.map(RouteObservation::throughputKbps).filter { it > 0L }.sorted()
        val dnsSuccesses = observations.count(RouteObservation::dnsSucceeded)
        val base = previous.copy(
            status = if (successful > 0) RouteSpeedStatus.PASSED else RouteSpeedStatus.FAILED,
            stageReached = stage,
            score = observations.map(RouteObservation::score).average().roundToInt(),
            latencyMs = median(latencies),
            p95LatencyMs = percentile95(latencies),
            jitterMs = if (latencies.size >= 2) (percentile95(latencies) ?: 0L) - latencies.first() else null,
            dnsLatencyMs = median(dnsLatencies),
            payloadBytes = observations.sumOf(RouteObservation::payloadBytes),
            throughputKbps = median(throughputs) ?: 0L,
            httpSucceeded = observations.sumOf(RouteObservation::httpSucceeded),
            httpAttempted = observations.sumOf(RouteObservation::httpAttempted),
            dnsSucceeded = dnsSuccesses > 0,
            dnsSuccessCount = dnsSuccesses,
            successfulSamples = successful,
            observations = observations,
            failureFingerprint = when {
                successful == observations.size -> "Healthy across every sample"
                successful > 0 -> "Intermittent • $failure"
                else -> failure
            },
            detail = result.error ?: result.detail,
        )
        return base.copy(
            tournamentScore = calculateTournamentScore(base),
            confidence = calculateRouteConfidence(base),
        )
    }

    private fun calculateTournamentScore(row: RouteSpeedRow): Int {
        if (row.sampleCount == 0) return 0
        val passRate = row.successfulSamples.toDouble() / row.sampleCount
        val httpRate = if (row.httpAttempted > 0) row.httpSucceeded.toDouble() / row.httpAttempted else 0.0
        val dnsRate = row.dnsSuccessCount.toDouble() / row.sampleCount
        val speedReward = if (row.throughputKbps > 0) log2(row.throughputKbps.toDouble() + 1.0) * 18.0 else 0.0
        val latencyPenalty = ((row.p95LatencyMs ?: 8_000L) / 24.0).coerceAtMost(170.0)
        val jitterPenalty = ((row.jitterMs ?: 0L) / 15.0).coerceAtMost(90.0)
        return (
            passRate * 330.0 +
                row.score * 3.8 +
                httpRate * 110.0 +
                dnsRate * 75.0 +
                speedReward -
                latencyPenalty -
                jitterPenalty
            ).roundToInt().coerceIn(0, 1_000)
    }

    private fun calculateRouteConfidence(row: RouteSpeedRow): Int {
        if (row.sampleCount == 0) return 0
        val sampleFactor = (row.sampleCount / 9.0).coerceIn(0.0, 1.0)
        val passRate = row.successfulSamples.toDouble() / row.sampleCount
        val dnsRate = row.dnsSuccessCount.toDouble() / row.sampleCount
        val httpRate = if (row.httpAttempted > 0) row.httpSucceeded.toDouble() / row.httpAttempted else 0.0
        val jitterQuality = 1.0 - ((row.jitterMs ?: 0L) / 2_500.0).coerceIn(0.0, 1.0)
        return (
            sampleFactor * 25.0 +
                passRate * 35.0 +
                dnsRate * 15.0 +
                httpRate * 15.0 +
                jitterQuality * 10.0
            ).roundToInt().coerceIn(0, 99)
    }

    private fun classifyFailure(result: RouteSpeedProbeResult): String {
        if (result.accepted) return "Healthy"
        val text = listOf(result.error, result.detail).joinToString(" ").lowercase()
        return when {
            "ssl" in text || "tls" in text || "handshake" in text || "certificate" in text ->
                "TLS/SNI handshake blocked"
            "reset" in text || "broken pipe" in text || "econnreset" in text ->
                "TCP path reset"
            result.httpSucceeded > 0 && !result.dnsSucceeded ->
                "DNS path failed after HTTP succeeded"
            result.httpAttempted > 0 && result.httpSucceeded in 1 until result.httpAttempted ->
                "Partial egress • only some targets worked"
            result.dnsSucceeded && result.httpSucceeded == 0 ->
                "HTTP egress blocked while DNS worked"
            result.throughputKbps in 1 until MIN_STABLE_THROUGHPUT_KBPS ->
                "Unstable or stalled throughput"
            "timeout" in text || "timed out" in text ->
                "Route timeout before complete connectivity"
            "network is unreachable" in text || "no route" in text ->
                "Edge unreachable on this network"
            else -> "Connectivity probe rejected the route"
        }
    }

    private fun finishTournament() {
        restoreStageIds(RouteTournamentStage.CHAMPIONSHIP)
            ?.takeIf(List<String>::isNotEmpty)
            ?.let(::persistFinalStageSnapshot)
        currentStage = RouteTournamentStage.COMPLETE
        preferences.edit().putString(KEY_STAGE, RouteTournamentStage.COMPLETE.name).apply()
        phaseCompletedCount = phaseTotalCount
        updateRecommendedCandidates()
        notice = if (recommendedCandidateId == null) {
            "Tournament complete • no route passed the complete connectivity check"
        } else {
            "Tournament complete • Champion confidence $championConfidence% • backup ready"
        }
    }

    private fun updateRecommendedCandidates() {
        val ranked = rankedRows().filter(RouteSpeedRow::usable)
        recommendedCandidateId = ranked.getOrNull(0)?.candidateId
        backupCandidateId = ranked.getOrNull(1)?.candidateId
        val champion = ranked.getOrNull(0)
        val backup = ranked.getOrNull(1)
        championConfidence = if (champion == null) {
            0
        } else {
            val margin = if (backup == null) {
                8
            } else {
                ((champion.tournamentScore - backup.tournamentScore).coerceAtLeast(0) / 8).coerceAtMost(12)
            }
            (champion.confidence + margin).coerceAtMost(99)
        }
    }

    private fun rankedRows(source: List<RouteSpeedRow> = rows): List<RouteSpeedRow> =
        source.sortedWith(routeRankingComparator())

    private fun routeRankingComparator(): Comparator<RouteSpeedRow> =
        compareByDescending<RouteSpeedRow> { it.tournamentScore }
            .thenByDescending { it.successfulSamples }
            .thenByDescending { it.confidence }
            .thenByDescending { it.throughputKbps }
            .thenBy { it.p95LatencyMs ?: Long.MAX_VALUE }

    private fun initialRow(candidate: AdaptiveCandidate): RouteSpeedRow {
        val resolver = AdaptiveDnsResolvers.idFor(candidate.settings.dnsResolverUrl)
        val fragment = if (candidate.runtimeOptions.finalmaskEnabled) {
            "${candidate.settings.finalmaskPacket}/${candidate.edge.finalmaskMaxSplit}/${candidate.settings.finalmaskDelayMs}ms"
        } else {
            "Fragment off"
        }
        return RouteSpeedRow(
            candidateId = candidate.id,
            label = candidate.label,
            route = "${candidate.edge.address}:${candidate.edge.port}  •  $resolver  •  $fragment  •  MTU ${candidate.settings.tunMtu}",
            edgeKey = "${candidate.edge.address}:${candidate.edge.port}",
            resolverKey = resolver,
            fragmentKey = fragment,
            mtu = candidate.settings.tunMtu,
        )
    }

    private fun restoreRows(prepared: RouteSpeedTestPlan) {
        val matching = hasMatchingSession(prepared)
        rows.clear()
        rows.addAll(
            prepared.candidates.map { candidate ->
                if (matching) restoreRow(candidate) ?: initialRow(candidate) else initialRow(candidate)
            },
        )
        if (!matching) {
            clearPersistedRows()
            preferences.edit().putBoolean(KEY_RUN_REQUESTED, false).apply()
        }
    }

    private fun createSession(prepared: RouteSpeedTestPlan) {
        preferences.edit()
            .putString(KEY_PROFILE_ID, prepared.profile.id)
            .putString(KEY_SIGNATURE, prepared.signature)
            .putString(KEY_NETWORK_KEY, prepared.session.network.learningKey())
            .putString(KEY_CANDIDATE_IDS, prepared.candidates.joinToString("\n") { it.id })
            .putString(KEY_STAGE, RouteTournamentStage.QUALIFIER.name)
            .putString(stageIdsKey(RouteTournamentStage.QUALIFIER), prepared.candidates.joinToString("\n") { it.id })
            .putBoolean(KEY_SESSION_EXISTS, true)
            .apply()
    }

    private fun hasMatchingSession(prepared: RouteSpeedTestPlan): Boolean =
        preferences.getBoolean(KEY_SESSION_EXISTS, false) &&
            preferences.getString(KEY_PROFILE_ID, null) == prepared.profile.id &&
            preferences.getString(KEY_SIGNATURE, null) == prepared.signature &&
            preferences.getString(KEY_NETWORK_KEY, null) == prepared.session.network.learningKey()

    private fun persistStage(stage: RouteTournamentStage, candidateIds: List<String>) {
        preferences.edit()
            .putString(KEY_STAGE, stage.name)
            .putString(stageIdsKey(stage), candidateIds.joinToString("\n"))
            .apply()
        if (stage == RouteTournamentStage.CHAMPIONSHIP) {
            persistFinalStageSnapshot(candidateIds)
        }
    }

    private fun restoreCurrentStage(): RouteTournamentStage = runCatching {
        RouteTournamentStage.valueOf(
            preferences.getString(KEY_STAGE, RouteTournamentStage.QUALIFIER.name)
                ?: RouteTournamentStage.QUALIFIER.name,
        )
    }.getOrDefault(RouteTournamentStage.QUALIFIER)

    private fun restoreStageIds(stage: RouteTournamentStage): List<String>? =
        preferences.getString(stageIdsKey(stage), null)
            ?.lineSequence()
            ?.filter(String::isNotBlank)
            ?.toList()

    private fun updatePhaseProgress(stage: RouteTournamentStage) {
        if (stage == RouteTournamentStage.COMPLETE) {
            val prepared = plan
            val finalists = restoreStageIds(RouteTournamentStage.CHAMPIONSHIP).orEmpty()
            phaseTotalCount = if (prepared != null && finalists.isNotEmpty()) {
                buildSchedule(prepared, RouteTournamentStage.CHAMPIONSHIP, finalists).size
            } else {
                4
            }
            phaseCompletedCount = phaseTotalCount
            return
        }
        val prepared = plan ?: return
        val ids = stageCandidateIds(stage)
        val schedule = buildSchedule(prepared, stage, ids)
        phaseTotalCount = schedule.size
        phaseCompletedCount = schedule.size - remainingSchedule(stage, schedule).size
    }

    private fun persistRow(row: RouteSpeedRow) {
        preferences.edit().putString(rowKey(row.candidateId), row.toJson().toString()).apply()
    }

    private fun persistAllRows() {
        val editor = preferences.edit()
        rows.forEach { editor.putString(rowKey(it.candidateId), it.toJson().toString()) }
        editor.apply()
    }

    private fun restoreRow(candidate: AdaptiveCandidate): RouteSpeedRow? {
        val raw = preferences.getString(rowKey(candidate.id), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val observations = json.optJSONArray("observations")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.toObservation()?.let(::add)
                    }
                }
            }.orEmpty()
            val restoredStatus = runCatching {
                RouteSpeedStatus.valueOf(json.optString("status", RouteSpeedStatus.QUEUED.name))
            }.getOrDefault(RouteSpeedStatus.QUEUED)
            val base = initialRow(candidate).copy(
                status = if (restoredStatus in setOf(RouteSpeedStatus.STARTING, RouteSpeedStatus.TESTING)) {
                    RouteSpeedStatus.STOPPED
                } else {
                    restoredStatus
                },
                stageReached = runCatching {
                    RouteTournamentStage.valueOf(json.optString("stageReached", RouteTournamentStage.QUALIFIER.name))
                }.getOrDefault(RouteTournamentStage.QUALIFIER),
                score = json.optInt("score", 0),
                tournamentScore = json.optInt("tournamentScore", 0),
                confidence = json.optInt("confidence", 0),
                latencyMs = json.optLongOrNull("latency"),
                p95LatencyMs = json.optLongOrNull("p95Latency"),
                jitterMs = json.optLongOrNull("jitter"),
                dnsLatencyMs = json.optLongOrNull("dnsLatency"),
                payloadBytes = json.optInt("payload", 0),
                throughputKbps = json.optLong("throughput", 0L),
                httpSucceeded = json.optInt("httpSucceeded", 0),
                httpAttempted = json.optInt("httpAttempted", 0),
                dnsSucceeded = json.optBoolean("dnsSucceeded", false),
                dnsSuccessCount = json.optInt("dnsSuccessCount", 0),
                successfulSamples = json.optInt("successfulSamples", 0),
                observations = observations,
                failureFingerprint = json.optString("failureFingerprint", "Not tested"),
                detail = json.optString("detail", "Waiting to resume").take(MAX_PERSISTED_DETAIL),
            )
            if (observations.isEmpty()) base else base.copy(
                successfulSamples = observations.count(RouteObservation::accepted),
            )
        }.getOrNull()
    }

    private fun RouteSpeedRow.toJson() = JSONObject()
        .put("candidateId", candidateId)
        .put("label", label)
        .put("route", route)
        .put("edgeKey", edgeKey)
        .put("resolverKey", resolverKey)
        .put("fragmentKey", fragmentKey)
        .put("mtu", mtu)
        .put("status", status.name)
        .put("stageReached", stageReached.name)
        .put("score", score)
        .put("tournamentScore", tournamentScore)
        .put("confidence", confidence)
        .put("latency", latencyMs ?: JSONObject.NULL)
        .put("p95Latency", p95LatencyMs ?: JSONObject.NULL)
        .put("jitter", jitterMs ?: JSONObject.NULL)
        .put("dnsLatency", dnsLatencyMs ?: JSONObject.NULL)
        .put("payload", payloadBytes)
        .put("throughput", throughputKbps)
        .put("httpSucceeded", httpSucceeded)
        .put("httpAttempted", httpAttempted)
        .put("dnsSucceeded", dnsSucceeded)
        .put("dnsSuccessCount", dnsSuccessCount)
        .put("successfulSamples", successfulSamples)
        .put("failureFingerprint", failureFingerprint)
        .put("detail", detail.take(MAX_PERSISTED_DETAIL))
        .put("observations", JSONArray().apply {
            observations.forEach { put(it.toJson()) }
        })

    private fun persistFinalStageSnapshot(candidateIds: List<String>) {
        val prepared = plan ?: return
        val finalists = rows.filter { it.candidateId in candidateIds }
        if (finalists.isEmpty()) return
        val snapshot = JSONObject()
            .put("profileId", prepared.profile.id)
            .put("profileName", prepared.profile.name)
            .put("signature", prepared.signature)
            .put("networkKey", prepared.session.network.learningKey())
            .put("carrier", prepared.session.network.carrier)
            .put("carrierClass", prepared.session.network.carrierClass)
            .put("networkLabel", buildNetworkLabel(prepared))
            .put("savedAt", System.currentTimeMillis())
            .put("rows", JSONArray().apply { finalists.forEach { put(it.toJson()) } })
        preferences.edit().putString(finalStageSnapshotKey(prepared), snapshot.toString()).apply()
        finalStageHistoryAvailable = true
        finalStageHistoryTitle = "${prepared.profile.name} • ${buildNetworkLabel(prepared)}"
    }

    private fun hasFinalStageSnapshot(prepared: RouteSpeedTestPlan): Boolean =
        preferences.getString(finalStageSnapshotKey(prepared), null)?.isNotBlank() == true

    private fun restoreFinalStageSnapshot(prepared: RouteSpeedTestPlan): List<RouteSpeedRow> {
        val raw = preferences.getString(finalStageSnapshotKey(prepared), null) ?: return emptyList()
        return runCatching {
            val snapshot = JSONObject(raw)
            val network = prepared.session.network
            if (
                snapshot.optString("profileId") != prepared.profile.id ||
                snapshot.optString("signature") != prepared.signature ||
                snapshot.optString("networkKey") != network.learningKey() ||
                snapshot.optString("carrier") != network.carrier ||
                snapshot.optString("carrierClass") != network.carrierClass
            ) {
                return@runCatching emptyList()
            }
            finalStageHistoryTitle = buildString {
                append(snapshot.optString("profileName", "Previous profile"))
                snapshot.optString("networkLabel", "").takeIf(String::isNotBlank)?.let {
                    append(" • ").append(it)
                }
            }
            val array = snapshot.optJSONArray("rows") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toSnapshotRow()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.toSnapshotRow(): RouteSpeedRow? = runCatching {
        val observations = optJSONArray("observations")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toObservation()?.let(::add)
                }
            }
        }.orEmpty()
        RouteSpeedRow(
            candidateId = optString("candidateId"),
            label = optString("label", "Saved finalist"),
            route = optString("route"),
            edgeKey = optString("edgeKey"),
            resolverKey = optString("resolverKey"),
            fragmentKey = optString("fragmentKey"),
            mtu = optInt("mtu", 1_280),
            status = runCatching { RouteSpeedStatus.valueOf(optString("status")) }
                .getOrDefault(RouteSpeedStatus.PASSED),
            stageReached = runCatching { RouteTournamentStage.valueOf(optString("stageReached")) }
                .getOrDefault(RouteTournamentStage.CHAMPIONSHIP),
            score = optInt("score"),
            tournamentScore = optInt("tournamentScore"),
            confidence = optInt("confidence"),
            latencyMs = optLongOrNull("latency"),
            p95LatencyMs = optLongOrNull("p95Latency"),
            jitterMs = optLongOrNull("jitter"),
            dnsLatencyMs = optLongOrNull("dnsLatency"),
            payloadBytes = optInt("payload"),
            throughputKbps = optLong("throughput"),
            httpSucceeded = optInt("httpSucceeded"),
            httpAttempted = optInt("httpAttempted"),
            dnsSucceeded = optBoolean("dnsSucceeded"),
            dnsSuccessCount = optInt("dnsSuccessCount"),
            successfulSamples = optInt("successfulSamples"),
            observations = observations,
            failureFingerprint = optString("failureFingerprint", "Saved Championship finalist"),
            detail = optString("detail", "Loaded from previous Championship"),
        )
    }.getOrNull()

    private fun RouteObservation.toJson() = JSONObject()
        .put("stage", stage.name)
        .put("accepted", accepted)
        .put("score", score)
        .put("latency", latencyMs ?: JSONObject.NULL)
        .put("dnsLatency", dnsLatencyMs ?: JSONObject.NULL)
        .put("payload", payloadBytes)
        .put("throughput", throughputKbps)
        .put("httpSucceeded", httpSucceeded)
        .put("httpAttempted", httpAttempted)
        .put("dnsSucceeded", dnsSucceeded)
        .put("detail", detail.take(MAX_PERSISTED_DETAIL))
        .put("failure", failureFingerprint)

    private fun JSONObject.toObservation(): RouteObservation? = runCatching {
        RouteObservation(
            stage = RouteTournamentStage.valueOf(optString("stage", RouteTournamentStage.QUALIFIER.name)),
            accepted = optBoolean("accepted", false),
            score = optInt("score", 0),
            latencyMs = optLongOrNull("latency"),
            dnsLatencyMs = optLongOrNull("dnsLatency"),
            payloadBytes = optInt("payload", 0),
            throughputKbps = optLong("throughput", 0L),
            httpSucceeded = optInt("httpSucceeded", 0),
            httpAttempted = optInt("httpAttempted", 0),
            dnsSucceeded = optBoolean("dnsSucceeded", false),
            detail = optString("detail", "").take(MAX_PERSISTED_DETAIL),
            failureFingerprint = optString("failure", "Connectivity probe rejected the route"),
        )
    }.getOrNull()

    private fun RouteSpeedProbeResult.toObservation(
        stage: RouteTournamentStage,
        failure: String,
    ) = RouteObservation(
        stage = stage,
        accepted = accepted,
        score = score,
        latencyMs = latencyMs,
        dnsLatencyMs = dnsLatencyMs,
        payloadBytes = payloadBytes,
        throughputKbps = throughputKbps,
        httpSucceeded = httpSucceeded,
        httpAttempted = httpAttempted,
        dnsSucceeded = dnsSucceeded,
        detail = error ?: detail,
        failureFingerprint = failure,
    )

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    private fun clearPersistedRows() {
        val ids = preferences.getString(KEY_CANDIDATE_IDS, null).orEmpty().lineSequence().filter(String::isNotBlank)
        val editor = preferences.edit()
        ids.forEach { editor.remove(rowKey(it)) }
        RouteTournamentStage.entries.forEach { editor.remove(stageIdsKey(it)) }
        editor.remove(KEY_PROFILE_ID)
            .remove(KEY_SIGNATURE)
            .remove(KEY_NETWORK_KEY)
            .remove(KEY_CANDIDATE_IDS)
            .remove(KEY_STAGE)
            .remove(KEY_SESSION_EXISTS)
            .apply()
    }

    private fun updateRow(candidateId: String, transform: (RouteSpeedRow) -> RouteSpeedRow) {
        val index = rows.indexOfFirst { it.candidateId == candidateId }
        if (index >= 0) rows[index] = transform(rows[index])
    }

    private fun buildNetworkLabel(plan: RouteSpeedTestPlan): String {
        val network = plan.session.network
        val provider = network.networkProvider
            .takeUnless { it.isBlank() || it == "unknown" || it == network.carrierClass }
            ?: network.carrier.takeUnless { it.isBlank() || it == "unknown" }
        return buildString {
            append(network.transport.replaceFirstChar(Char::uppercase))
            provider?.let { append(" • ").append(it) }
            if (network.networkAsn.isNotBlank() && network.networkAsn != "unknown") {
                append(" • AS").append(network.networkAsn)
            }
        }
    }

    private fun buildSavedRouteDetails(
        prepared: RouteSpeedTestPlan,
        champion: SavedRouteDetails?,
        backup: SavedRouteDetails?,
    ): SavedRouteProfileDetails? {
        if (champion == null) return null
        val profile = prepared.profile
        val runtime = profile.runtimeIdentity(prepared.session.settings)
        val network = prepared.session.network
        return SavedRouteProfileDetails(
            profileName = profile.name,
            profileId = profile.id,
            profileType = if (profile.isBuiltIn) "Built-in" else "Custom",
            protocol = runtime.protocol.wireName.uppercase(),
            server = "${profile.serverHost}:${profile.serverPort}",
            transport = runtime.network.ifBlank { "tcp" },
            security = runtime.security.ifBlank { "none" },
            sni = runtime.sni.ifBlank { "Not set" },
            host = runtime.host.ifBlank { "Not set" },
            path = runtime.path.ifBlank { "Not set" },
            alpn = runtime.alpn.ifBlank { "Not set" },
            fingerprint = runtime.fingerprint.ifBlank { "Not set" },
            networkTransport = network.transport,
            carrier = network.carrier.ifBlank { "Unknown" },
            carrierClass = network.carrierClass.ifBlank { "unknown" },
            provider = network.networkProvider.ifBlank { "Unknown" },
            asn = network.networkAsn.takeUnless { it.isBlank() || it == "unknown" }?.let { "AS$it" } ?: "Unknown",
            networkFingerprint = network.learningKey(),
            networkMtu = network.mtu,
            metered = network.metered,
            validated = network.validated,
            ipSupport = when {
                network.hasIpv4 && network.hasIpv6 -> "IPv4 + IPv6"
                network.hasIpv4 -> "IPv4"
                network.hasIpv6 -> "IPv6"
                else -> "Unknown"
            },
            champion = champion,
            backup = backup,
        )
    }

    private fun AdaptiveSavedRoute.toSavedRouteDetails() = SavedRouteDetails(
        id = id,
        label = label,
        edge = "$address:$port",
        role = role,
        resolver = AdaptiveDnsResolvers.idFor(resolverUrl),
        fragment = if (finalmaskEnabled) {
            "$finalmaskPacket/$maxSplit/${finalmaskDelayMs}ms • length $finalmaskLength"
        } else {
            "Fragment off"
        },
        mtu = tunMtu,
        directCompat = directCompat,
    )

    private fun AdaptiveCandidate.toSavedRouteDetails() = SavedRouteDetails(
        id = id,
        label = label,
        edge = "${edge.address}:${edge.port}",
        role = edge.role,
        resolver = AdaptiveDnsResolvers.idFor(settings.dnsResolverUrl),
        fragment = if (runtimeOptions.finalmaskEnabled) {
            "${settings.finalmaskPacket}/${edge.finalmaskMaxSplit}/${settings.finalmaskDelayMs}ms • length ${settings.finalmaskLength}"
        } else {
            "Fragment off"
        },
        mtu = settings.tunMtu,
        directCompat = runtimeOptions.preserveTransportFields || id.contains("direct-compat", ignoreCase = true),
    )

    private fun startKeepAliveService() {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, RouteSpeedTestService::class.java).setAction(RouteSpeedTestService.ACTION_START),
        )
    }

    private fun stopKeepAliveService() {
        appContext.stopService(Intent(appContext, RouteSpeedTestService::class.java))
    }

    private fun rowKey(candidateId: String) = "$KEY_ROW_PREFIX$candidateId"
    private fun stageIdsKey(stage: RouteTournamentStage) = "$KEY_STAGE_IDS_PREFIX${stage.name}"

    private fun finalStageSnapshotKey(prepared: RouteSpeedTestPlan): String {
        val network = prepared.session.network
        val identity = listOf(
            prepared.profile.id,
            prepared.signature,
            network.learningKey(),
            network.carrier,
            network.carrierClass,
        ).joinToString("\u001F")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return "$KEY_FINAL_STAGE_SNAPSHOT_PREFIX$digest"
    }

    private fun Throwable.shortMessage(): String =
        message?.substringBefore('\n')?.take(120)?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    private fun RouteTournamentStage.next(): RouteTournamentStage = when (this) {
        RouteTournamentStage.QUALIFIER -> RouteTournamentStage.VERIFICATION
        RouteTournamentStage.VERIFICATION -> RouteTournamentStage.STABILITY
        RouteTournamentStage.STABILITY -> RouteTournamentStage.STRESS
        RouteTournamentStage.STRESS -> RouteTournamentStage.CHAMPIONSHIP
        RouteTournamentStage.CHAMPIONSHIP,
        RouteTournamentStage.COMPLETE -> RouteTournamentStage.COMPLETE
    }

    private fun RouteTournamentStage.previous(): RouteTournamentStage? = when (this) {
        RouteTournamentStage.QUALIFIER -> null
        RouteTournamentStage.VERIFICATION -> RouteTournamentStage.QUALIFIER
        RouteTournamentStage.STABILITY -> RouteTournamentStage.VERIFICATION
        RouteTournamentStage.STRESS -> RouteTournamentStage.STABILITY
        RouteTournamentStage.CHAMPIONSHIP -> RouteTournamentStage.STRESS
        RouteTournamentStage.COMPLETE -> RouteTournamentStage.CHAMPIONSHIP
    }

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle] else values[middle - 1] + (values[middle] - values[middle - 1]) / 2
    }

    private fun percentile95(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val index = (ceil(values.size * 0.95).toInt() - 1).coerceIn(0, values.lastIndex)
        return values[index]
    }

    companion object {
        private const val PREFERENCES_NAME = "route_speed_test_session_v2"
        private const val KEY_SESSION_EXISTS = "session_exists"
        private const val KEY_RUN_REQUESTED = "run_requested"
        private const val KEY_PROFILE_ID = "profile_id"
        private const val KEY_SIGNATURE = "signature"
        private const val KEY_NETWORK_KEY = "network_key"
        private const val KEY_CANDIDATE_IDS = "candidate_ids"
        private const val KEY_STAGE = "tournament_stage"
        private const val KEY_STAGE_IDS_PREFIX = "stage_ids:"
        private const val KEY_ROW_PREFIX = "row:"
        private const val KEY_FINAL_STAGE_SNAPSHOT_PREFIX = "final_stage_snapshot:"
        private const val MAX_PERSISTED_DETAIL = 800
        private const val MIN_STABLE_THROUGHPUT_KBPS = 96L
        @Volatile private var instance: RouteSpeedTestController? = null

        fun get(context: Context): RouteSpeedTestController = instance ?: synchronized(this) {
            instance ?: RouteSpeedTestController(context.applicationContext).also { instance = it }
        }
    }
}
