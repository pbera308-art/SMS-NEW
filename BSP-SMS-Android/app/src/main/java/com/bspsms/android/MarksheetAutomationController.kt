package com.bspsms.android

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.roundToInt

enum class BulkDownloaderMode { AUTO, MANUAL }

enum class AutomationJobStatus { IDLE, RUNNING, PAUSED, COMPLETED, STOPPED, ERROR }

data class HrpcStudentEntry(
    val rowIndex: Int,
    val rollNo: String,
    val name: String,
    val hasView: Boolean,
    val hasPrint: Boolean,
    val viewHref: String = "",
    val printHref: String = ""
) {
    val uniqueKey: String get() = "${rollNo.trim().lowercase()}|${name.trim().lowercase()}"
}

data class BulkDownloadHistoryItem(
    val rollNo: String,
    val studentName: String,
    val pageNumber: Int,
    val fileName: String,
    val pagesSaved: List<Int>,
    val timestamp: String
)

data class BulkFailedItem(
    val rollNo: String,
    val studentName: String,
    val pageNumber: Int,
    val rowIndex: Int,
    val reason: String
)

data class BulkMarksheetState(
    val mode: BulkDownloaderMode = BulkDownloaderMode.AUTO,
    val jobStatus: AutomationJobStatus = AutomationJobStatus.IDLE,
    val selectedPages: Set<Int> = emptySet(), // empty = all 5 pages
    val safFolderLabel: String = "",
    val hasSafFolder: Boolean = false,
    val scannedStudents: List<HrpcStudentEntry> = emptyList(),
    val totalStudents: Int = 0,
    val completedDownloads: Int = 0,
    val pendingDownloads: Int = 0,
    val failedDownloadsCount: Int = 0,
    val currentStudentName: String = "",
    val currentRollNo: String = "",
    val currentPageNumber: Int = 1,
    val totalPages: Int = 1,
    val statusMessage: String = "Open Student Information table in Portal after Search, then tap Scan Table.",
    val isError: Boolean = false,
    val downloadHistory: List<BulkDownloadHistoryItem> = emptyList(),
    val failedList: List<BulkFailedItem> = emptyList(),
    val completedKeys: Set<String> = emptySet(),
    val singleBusyKey: String? = null
)

data class ResultFailedItem(
    val rollNo: String,
    val studentName: String,
    val pageNumber: Int,
    val rowIndex: Int,
    val reason: String
)

data class ResultReadyState(
    val jobStatus: AutomationJobStatus = AutomationJobStatus.IDLE,
    val scannedStudents: List<HrpcStudentEntry> = emptyList(),
    val extractedRows: List<StudentSummativeResultRow> = emptyList(),
    val extractedKeys: Set<String> = emptySet(),
    val totalStudents: Int = 0,
    val completedCount: Int = 0,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val currentStudentName: String = "",
    val currentRollNo: String = "",
    val currentPageNumber: Int = 1,
    val totalPages: Int = 1,
    val safFolderLabel: String = "",
    val hasSafFolder: Boolean = false,
    val lastExportedFileName: String = "",
    val statusMessage: String = "Open Student Information table in Portal after Search, then tap Start Extraction.",
    val isError: Boolean = false,
    val failedList: List<ResultFailedItem> = emptyList()
)

class MarksheetAutomationController(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var portalWebViewProvider: () -> WebView? = { null }

    private val _bulkState = MutableStateFlow(BulkMarksheetState())
    val bulkState: StateFlow<BulkMarksheetState> = _bulkState.asStateFlow()

    private val _resultState = MutableStateFlow(ResultReadyState())
    val resultState: StateFlow<ResultReadyState> = _resultState.asStateFlow()

    private var bulkJob: Job? = null
    private var resultJob: Job? = null

    @Volatile
    private var pauseBulkRequested = false
    @Volatile
    private var stopBulkRequested = false
    @Volatile
    private var pauseResultRequested = false
    @Volatile
    private var stopResultRequested = false

    @Volatile
    var cookieResolver: (String) -> String? = { url ->
        runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
    }

    // Latest saved HRPC filter snapshot so Back Navigation can restore Academic Year, Medium, Class, Section, Page
    private val lastHrpcSnapshotRef = AtomicReference<String?>(null)

    @Volatile
    var pendingHrpcRestoreFromBack: Boolean = false

    // Captures direct PDF download triggered when Portal Print endpoint serves application/pdf via DownloadListener
    private val capturedPdfDownloadUrl = AtomicReference<String?>(null)
    private val capturedPdfUserAgent = AtomicReference<String?>(null)

    @Volatile
    var lastWebViewCacheMissAtMs: Long = 0L

    @Volatile
    var historyGoBackProducesCacheMiss: Boolean = false

    @Volatile
    var lastMainFrameErrorDesc: String? = null

    @Volatile
    var lastMainFrameErrorAtMs: Long = 0L

    init {
        refreshSafFolderState()
    }

    fun attachPortalWebViewProvider(provider: () -> WebView?) {
        portalWebViewProvider = provider
    }

    fun refreshSafFolderState() {
        val label = SafStorageHelper.getSavedFolderLabel(appContext)
        val hasUri = SafStorageHelper.getSavedFolderUri(appContext) != null
        _bulkState.update { it.copy(safFolderLabel = label, hasSafFolder = hasUri) }
        _resultState.update { it.copy(safFolderLabel = label, hasSafFolder = hasUri) }
    }

    fun onSafFolderSelected(treeUri: Uri) {
        val label = SafStorageHelper.persistFolderUri(appContext, treeUri)
        _bulkState.update {
            it.copy(
                safFolderLabel = label,
                hasSafFolder = true,
                statusMessage = "Download folder selected: $label",
                isError = false
            )
        }
        _resultState.update {
            it.copy(
                safFolderLabel = label,
                hasSafFolder = true
            )
        }
    }

    fun updateLastHrpcSnapshot(snapshotJson: String) {
        if (snapshotJson.isNotBlank() && snapshotJson != "{}") {
            lastHrpcSnapshotRef.set(snapshotJson)
        }
    }

    fun getLastHrpcSnapshot(): String? = lastHrpcSnapshotRef.get()

    fun isAnyAutomationActive(): Boolean =
        _bulkState.value.jobStatus == AutomationJobStatus.RUNNING ||
            _bulkState.value.singleBusyKey != null ||
            _resultState.value.jobStatus == AutomationJobStatus.RUNNING

    fun notifyWebViewCacheMiss() {
        val now = System.currentTimeMillis()
        lastWebViewCacheMissAtMs = now
        historyGoBackProducesCacheMiss = true
    }

    fun notifyWebViewMainFrameError(desc: String?) {
        lastMainFrameErrorDesc = desc?.takeIf { it.isNotBlank() } ?: "Portal page failed to load"
        lastMainFrameErrorAtMs = System.currentTimeMillis()
    }

    fun clearWebViewTransientError() {
        lastMainFrameErrorDesc = null
    }

    fun isAutomatedPrintSuppressionActive(): Boolean {
        val bStatus = _bulkState.value.jobStatus
        return bStatus == AutomationJobStatus.RUNNING || _bulkState.value.singleBusyKey != null
    }

    fun onPortalDownloadTriggered(url: String, mimeType: String?, userAgent: String?) {
        val looksPdf = mimeType?.contains("pdf", ignoreCase = true) == true ||
            url.contains(".pdf", ignoreCase = true) ||
            url.contains("print", ignoreCase = true)
        if (looksPdf && isAutomatedPrintSuppressionActive()) {
            capturedPdfDownloadUrl.set(url)
            capturedPdfUserAgent.set(userAgent)
        }
    }

    fun setBulkMode(mode: BulkDownloaderMode) {
        _bulkState.update { it.copy(mode = mode) }
    }

    fun toggleSelectedPage(page: Int) {
        if (page !in 1..5) return
        _bulkState.update { st ->
            val next = if (st.selectedPages.contains(page)) {
                st.selectedPages - page
            } else {
                st.selectedPages + page
            }
            st.copy(selectedPages = next)
        }
    }

    fun clearSelectedPages() {
        _bulkState.update { it.copy(selectedPages = emptySet()) }
    }

    fun selectAllPages() {
        _bulkState.update { it.copy(selectedPages = setOf(1, 2, 3, 4, 5)) }
    }

    fun pauseBulk() {
        pauseBulkRequested = true
        _bulkState.update {
            it.copy(
                jobStatus = AutomationJobStatus.PAUSED,
                statusMessage = "Paused cleanly after current step. Tap Resume to continue."
            )
        }
    }

    fun resumeBulk() {
        pauseBulkRequested = false
        if (_bulkState.value.jobStatus == AutomationJobStatus.PAUSED) {
            _bulkState.update {
                it.copy(
                    jobStatus = AutomationJobStatus.RUNNING,
                    statusMessage = "Resuming bulk marksheet download..."
                )
            }
        }
    }

    fun stopBulk() {
        stopBulkRequested = true
        pauseBulkRequested = false
        _bulkState.update {
            it.copy(
                jobStatus = AutomationJobStatus.STOPPED,
                statusMessage = "Bulk marksheet download stopped by user."
            )
        }
    }

    fun pauseResult() {
        pauseResultRequested = true
        _resultState.update {
            it.copy(
                jobStatus = AutomationJobStatus.PAUSED,
                statusMessage = "Paused extraction. Tap Resume to continue."
            )
        }
    }

    fun resumeResult() {
        pauseResultRequested = false
        if (_resultState.value.jobStatus == AutomationJobStatus.PAUSED) {
            _resultState.update {
                it.copy(
                    jobStatus = AutomationJobStatus.RUNNING,
                    statusMessage = "Resuming summative marks extraction..."
                )
            }
        }
    }

    fun stopResult() {
        stopResultRequested = true
        pauseResultRequested = false
        _resultState.update {
            it.copy(
                jobStatus = AutomationJobStatus.STOPPED,
                statusMessage = "Extraction stopped by user."
            )
        }
    }

    fun scanCurrentTableForBulk() {
        scope.launch {
            val webView = portalWebViewProvider()
            if (webView == null) {
                _bulkState.update { it.copy(statusMessage = "Portal WebView is not ready.", isError = true) }
                return@launch
            }
            val scan = scanTableInternal(webView)
            if (!scan.ok) {
                _bulkState.update {
                    it.copy(
                        scannedStudents = emptyList(),
                        statusMessage = scan.error,
                        isError = true
                    )
                }
                return@launch
            }
            saveHrpcSnapshotNow(webView)
            val completed = _bulkState.value.completedKeys
            val totalEst = maxOf(scan.totalEntries, scan.students.size)
            _bulkState.update {
                it.copy(
                    scannedStudents = scan.students,
                    totalStudents = totalEst,
                    currentPageNumber = scan.currentPage,
                    totalPages = scan.totalPages,
                    pendingDownloads = (totalEst - completed.size).coerceAtLeast(0),
                    statusMessage = "Found ${scan.students.size} students on Page ${scan.currentPage}/${scan.totalPages} (Total Entries: $totalEst).",
                    isError = false
                )
            }
        }
    }

    fun scanCurrentTableForResult() {
        scope.launch {
            val webView = portalWebViewProvider()
            if (webView == null) {
                _resultState.update { it.copy(statusMessage = "Portal WebView is not ready.", isError = true) }
                return@launch
            }
            val scan = scanTableInternal(webView)
            if (!scan.ok) {
                _resultState.update {
                    it.copy(
                        scannedStudents = emptyList(),
                        statusMessage = scan.error,
                        isError = true
                    )
                }
                return@launch
            }
            saveHrpcSnapshotNow(webView)
            val totalEst = maxOf(scan.totalEntries, scan.students.size)
            val done = _resultState.value.extractedKeys.size
            _resultState.update {
                it.copy(
                    scannedStudents = scan.students,
                    totalStudents = totalEst,
                    currentPageNumber = scan.currentPage,
                    totalPages = scan.totalPages,
                    pendingCount = (totalEst - done).coerceAtLeast(0),
                    statusMessage = "Found ${scan.students.size} students on Page ${scan.currentPage}/${scan.totalPages} (Total Entries: $totalEst).",
                    isError = false
                )
            }
        }
    }

    fun startBulkAutoDownload(clearPreviousHistory: Boolean = true) {
        val treeUri = SafStorageHelper.getSavedFolderUri(appContext)
        if (treeUri == null) {
            _bulkState.update {
                it.copy(
                    statusMessage = "Please select a destination folder first (Tap 'Choose Download Folder').",
                    isError = true
                )
            }
            return
        }
        val webView = portalWebViewProvider()
        if (webView == null) {
            _bulkState.update { it.copy(statusMessage = "Portal WebView is not ready.", isError = true) }
            return
        }

        bulkJob?.cancel()
        pauseBulkRequested = false
        stopBulkRequested = false

        if (clearPreviousHistory) {
            _bulkState.update {
                it.copy(
                    completedDownloads = 0,
                    failedDownloadsCount = 0,
                    downloadHistory = emptyList(),
                    failedList = emptyList(),
                    completedKeys = emptySet(),
                    currentStudentName = "",
                    currentRollNo = ""
                )
            }
        }

        bulkJob = scope.launch {
            runBulkAutoLoop(webView, treeUri)
        }
    }

    fun retryFailedBulkDownloads() {
        val treeUri = SafStorageHelper.getSavedFolderUri(appContext)
        if (treeUri == null) {
            _bulkState.update { it.copy(statusMessage = "Select a destination folder first.", isError = true) }
            return
        }
        val webView = portalWebViewProvider() ?: return
        val failed = _bulkState.value.failedList.toList()
        if (failed.isEmpty()) return

        bulkJob?.cancel()
        pauseBulkRequested = false
        stopBulkRequested = false

        bulkJob = scope.launch {
            _bulkState.update {
                it.copy(
                    jobStatus = AutomationJobStatus.RUNNING,
                    isError = false,
                    statusMessage = "Retrying ${failed.size} failed download(s)..."
                )
            }
            val selectedPagesList = effectiveSelectedPages()
            for (item in failed) {
                if (!waitWhilePausedBulk()) break
                if (stopBulkRequested) break

                // Navigate to the failed student's page if needed
                val moved = ensureOnTablePage(webView, item.pageNumber)
                if (!moved) {
                    updateFailedReason(item, "Could not return to Page ${item.pageNumber}")
                    continue
                }

                val student = HrpcStudentEntry(
                    rowIndex = item.rowIndex,
                    rollNo = item.rollNo,
                    name = item.studentName,
                    hasView = true,
                    hasPrint = true
                )
                val res = processSingleStudentPdf(webView, treeUri, student, item.pageNumber, selectedPagesList)
                if (res.isSuccess) {
                    val hist = res.getOrThrow()
                    _bulkState.update { st ->
                        val nextFailed = st.failedList.filterNot {
                            it.rollNo == item.rollNo && it.studentName == item.studentName && it.pageNumber == item.pageNumber
                        }
                        val nextCompletedKeys = st.completedKeys + student.uniqueKey
                        st.copy(
                            completedDownloads = nextCompletedKeys.size,
                            failedDownloadsCount = nextFailed.size,
                            pendingDownloads = (st.totalStudents - nextCompletedKeys.size).coerceAtLeast(0),
                            failedList = nextFailed,
                            completedKeys = nextCompletedKeys,
                            downloadHistory = listOf(hist) + st.downloadHistory,
                            statusMessage = "Retry succeeded: ${hist.fileName}"
                        )
                    }
                } else {
                    updateFailedReason(item, res.exceptionOrNull()?.message ?: "Retry failed")
                }
            }

            _bulkState.update { st ->
                val finalStatus = when {
                    stopBulkRequested -> AutomationJobStatus.STOPPED
                    st.failedList.isNotEmpty() -> AutomationJobStatus.COMPLETED
                    else -> AutomationJobStatus.COMPLETED
                }
                st.copy(
                    jobStatus = finalStatus,
                    statusMessage = if (st.failedList.isEmpty()) {
                        "All failed downloads retried successfully!"
                    } else {
                        "Retry finished with ${st.failedList.size} remaining failure(s)."
                    }
                )
            }
        }
    }

    fun downloadSingleStudentManual(student: HrpcStudentEntry) {
        val treeUri = SafStorageHelper.getSavedFolderUri(appContext)
        if (treeUri == null) {
            _bulkState.update { it.copy(statusMessage = "Please choose a download folder first.", isError = true) }
            return
        }
        val webView = portalWebViewProvider() ?: return
        if (_bulkState.value.jobStatus == AutomationJobStatus.RUNNING || _bulkState.value.singleBusyKey != null) return

        scope.launch {
            val currentPage = _bulkState.value.currentPageNumber
            val selectedPagesList = effectiveSelectedPages()
            _bulkState.update {
                it.copy(
                    singleBusyKey = student.uniqueKey,
                    currentStudentName = student.name,
                    currentRollNo = student.rollNo,
                    statusMessage = "Downloading marksheet PDF for Roll ${student.rollNo} (${student.name})...",
                    isError = false
                )
            }
            val res = processSingleStudentPdf(webView, treeUri, student, currentPage, selectedPagesList)
            if (res.isSuccess) {
                val hist = res.getOrThrow()
                _bulkState.update { st ->
                    val nextCompletedKeys = st.completedKeys + student.uniqueKey
                    st.copy(
                        singleBusyKey = null,
                        completedDownloads = nextCompletedKeys.size,
                        pendingDownloads = (st.totalStudents - nextCompletedKeys.size).coerceAtLeast(0),
                        completedKeys = nextCompletedKeys,
                        downloadHistory = listOf(hist) + st.downloadHistory,
                        statusMessage = "Saved ${hist.fileName} cleanly to SAF folder.",
                        isError = false
                    )
                }
            } else {
                val reason = res.exceptionOrNull()?.message ?: "Failed to save PDF"
                _bulkState.update { st ->
                    val fail = BulkFailedItem(student.rollNo, student.name, currentPage, student.rowIndex, reason)
                    val nextFailed = (st.failedList + fail).distinctBy { "${it.rollNo}|${it.studentName}|${it.pageNumber}" }
                    st.copy(
                        singleBusyKey = null,
                        failedDownloadsCount = nextFailed.size,
                        failedList = nextFailed,
                        statusMessage = "Failed for ${student.name}: $reason",
                        isError = true
                    )
                }
            }
        }
    }

    fun captureOpenMarksheetManually(rollNoInput: String, nameInput: String) {
        val treeUri = SafStorageHelper.getSavedFolderUri(appContext)
        if (treeUri == null) {
            _bulkState.update { it.copy(statusMessage = "Please choose a download folder first.", isError = true) }
            return
        }
        val webView = portalWebViewProvider() ?: return
        scope.launch {
            val roll = rollNoInput.trim().ifBlank { "000" }
            val name = nameInput.trim().ifBlank { "MARKSHEET" }
            val selectedPages = effectiveSelectedPages()
            val fileName = SafStorageHelper.sanitizeMarksheetFileName(roll, name, "pdf")
            _bulkState.update {
                it.copy(
                    singleBusyKey = "manual_open",
                    statusMessage = "Capturing currently open marksheet as $fileName...",
                    isError = false
                )
            }
            val saveRes = captureActiveMarksheetWebViewToSaf(webView, treeUri, fileName, selectedPages)
            if (saveRes.isSuccess) {
                val hist = BulkDownloadHistoryItem(
                    rollNo = roll,
                    studentName = name,
                    pageNumber = _bulkState.value.currentPageNumber,
                    fileName = fileName,
                    pagesSaved = selectedPages,
                    timestamp = nowTimeStr()
                )
                _bulkState.update { st ->
                    st.copy(
                        singleBusyKey = null,
                        completedDownloads = st.completedDownloads + 1,
                        downloadHistory = listOf(hist) + st.downloadHistory,
                        statusMessage = "Saved $fileName successfully!",
                        isError = false
                    )
                }
            } else {
                _bulkState.update {
                    it.copy(
                        singleBusyKey = null,
                        statusMessage = saveRes.exceptionOrNull()?.message ?: "Failed capturing open marksheet",
                        isError = true
                    )
                }
            }
        }
    }

    fun startResultExtraction(clearPrevious: Boolean = true) {
        val webView = portalWebViewProvider()
        if (webView == null) {
            _resultState.update { it.copy(statusMessage = "Portal WebView is not ready.", isError = true) }
            return
        }
        resultJob?.cancel()
        pauseResultRequested = false
        stopResultRequested = false

        if (clearPrevious) {
            _resultState.update {
                it.copy(
                    extractedRows = emptyList(),
                    extractedKeys = emptySet(),
                    completedCount = 0,
                    failedCount = 0,
                    failedList = emptyList(),
                    lastExportedFileName = ""
                )
            }
        }

        resultJob = scope.launch {
            runResultExtractionLoop(webView)
        }
    }

    fun retryFailedResultExtractions() {
        val webView = portalWebViewProvider() ?: return
        val failed = _resultState.value.failedList.toList()
        if (failed.isEmpty()) return

        resultJob?.cancel()
        pauseResultRequested = false
        stopResultRequested = false

        resultJob = scope.launch {
            _resultState.update {
                it.copy(
                    jobStatus = AutomationJobStatus.RUNNING,
                    isError = false,
                    statusMessage = "Retrying ${failed.size} failed marksheet extraction(s)..."
                )
            }
            for (item in failed) {
                if (!waitWhilePausedResult()) break
                if (stopResultRequested) break

                val moved = ensureOnTablePage(webView, item.pageNumber)
                if (!moved) continue

                val entry = HrpcStudentEntry(item.rowIndex, item.rollNo, item.studentName, hasView = true, hasPrint = false)
                val res = extractSingleStudentSummative(webView, entry, item.pageNumber)
                if (res.isSuccess) {
                    val row = res.getOrThrow()
                    _resultState.update { st ->
                        val updatedRows = ResultXlsxExporter.recalculatePositions(st.extractedRows + row)
                        val nextKeys = st.extractedKeys + entry.uniqueKey
                        val nextFailed = st.failedList.filterNot {
                            it.rollNo == item.rollNo && it.studentName == item.studentName && it.pageNumber == item.pageNumber
                        }
                        st.copy(
                            extractedRows = updatedRows,
                            extractedKeys = nextKeys,
                            completedCount = nextKeys.size,
                            pendingCount = (st.totalStudents - nextKeys.size).coerceAtLeast(0),
                            failedList = nextFailed,
                            failedCount = nextFailed.size,
                            statusMessage = "Extracted ${row.studentName} cleanly."
                        )
                    }
                }
            }
            _resultState.update { st ->
                st.copy(
                    jobStatus = if (stopResultRequested) AutomationJobStatus.STOPPED else AutomationJobStatus.COMPLETED,
                    statusMessage = if (st.failedList.isEmpty()) {
                        "Retry complete! ${st.extractedRows.size} student row(s) ready for Excel export."
                    } else {
                        "Completed with ${st.failedList.size} remaining failure(s)."
                    }
                )
            }
        }
    }

    fun exportResultWorkbookToSafFolder(fileNameInput: String = "") {
        val treeUri = SafStorageHelper.getSavedFolderUri(appContext)
        if (treeUri == null) {
            _resultState.update {
                it.copy(
                    statusMessage = "Select a SAF folder or use 'Save As .xlsx' to download.",
                    isError = true
                )
            }
            return
        }
        val rows = _resultState.value.extractedRows
        if (rows.isEmpty()) {
            _resultState.update { it.copy(statusMessage = "No student marks extracted yet.", isError = true) }
            return
        }
        scope.launch {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val finalName = fileNameInput.trim()
                .ifBlank { "Result_Ready_$timestamp.xlsx" }
                .let { if (it.endsWith(".xlsx", ignoreCase = true)) it else "$it.xlsx" }

            val writeRes = withContext(Dispatchers.IO) {
                SafStorageHelper.writeFileToFolder(
                    context = appContext,
                    treeUri = treeUri,
                    mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    displayName = finalName,
                    overwriteExisting = true
                ) { out ->
                    ResultXlsxExporter.writeWorkbook(rows, "Result Ready", out)
                }
            }
            if (writeRes.isSuccess) {
                _resultState.update {
                    it.copy(
                        lastExportedFileName = finalName,
                        statusMessage = "Excel saved: $finalName (${rows.size} students)",
                        isError = false
                    )
                }
            } else {
                _resultState.update {
                    it.copy(
                        statusMessage = writeRes.exceptionOrNull()?.message ?: "Failed saving Excel file",
                        isError = true
                    )
                }
            }
        }
    }

    fun exportResultWorkbookToExplicitUri(targetUri: Uri, fileNameLabel: String) {
        val rows = _resultState.value.extractedRows
        if (rows.isEmpty()) return
        scope.launch {
            val writeRes = withContext(Dispatchers.IO) {
                SafStorageHelper.writeToExplicitUri(appContext, targetUri) { out ->
                    ResultXlsxExporter.writeWorkbook(rows, "Result Ready", out)
                }
            }
            if (writeRes.isSuccess) {
                _resultState.update {
                    it.copy(
                        lastExportedFileName = fileNameLabel,
                        statusMessage = "Excel exported cleanly (${rows.size} students).",
                        isError = false
                    )
                }
            } else {
                _resultState.update {
                    it.copy(
                        statusMessage = writeRes.exceptionOrNull()?.message ?: "Export failed",
                        isError = true
                    )
                }
            }
        }
    }

    private suspend fun runBulkAutoLoop(webView: WebView, treeUri: Uri) {
        _bulkState.update {
            it.copy(
                jobStatus = AutomationJobStatus.RUNNING,
                isError = false,
                statusMessage = "Scanning Student Information table..."
            )
        }

        val selectedPagesList = effectiveSelectedPages()
        var currentPageSafetyCount = 0
        historyGoBackProducesCacheMiss = false
        clearWebViewTransientError()

        while (currentPageSafetyCount < 200) {
            currentPageSafetyCount++
            if (!waitWhilePausedBulk() || stopBulkRequested) break

            var scan = scanTableInternal(webView)
            if (!scan.ok && !scan.sessionExpired) {
                val targetPage = _bulkState.value.currentPageNumber.coerceAtLeast(1)
                if (recoverStudentTableFromSnapshot(webView, targetPage)) {
                    scan = scanTableInternal(webView)
                }
            }
            if (!scan.ok) {
                _bulkState.update {
                    it.copy(
                        jobStatus = if (scan.sessionExpired) AutomationJobStatus.PAUSED else AutomationJobStatus.ERROR,
                        statusMessage = scan.error,
                        isError = true
                    )
                }
                return
            }

            saveHrpcSnapshotNow(webView)
            val totalEst = maxOf(_bulkState.value.totalStudents, scan.totalEntries, scan.students.size)

            _bulkState.update { st ->
                st.copy(
                    scannedStudents = scan.students,
                    totalStudents = totalEst,
                    currentPageNumber = scan.currentPage,
                    totalPages = scan.totalPages,
                    pendingDownloads = (totalEst - st.completedKeys.size).coerceAtLeast(0)
                )
            }

            for (student in scan.students) {
                if (!waitWhilePausedBulk() || stopBulkRequested) break

                // Prevent duplicate downloads cleanly
                if (_bulkState.value.completedKeys.contains(student.uniqueKey)) {
                    continue
                }

                _bulkState.update { st ->
                    st.copy(
                        currentStudentName = student.name,
                        currentRollNo = student.rollNo,
                        currentPageNumber = scan.currentPage,
                        statusMessage = "Page ${scan.currentPage}/${scan.totalPages}: Downloading ${student.rollNo} - ${student.name}..."
                    )
                }

                val res = processSingleStudentPdf(webView, treeUri, student, scan.currentPage, selectedPagesList)
                if (res.isSuccess) {
                    val hist = res.getOrThrow()
                    _bulkState.update { st ->
                        val nextKeys = st.completedKeys + student.uniqueKey
                        st.copy(
                            completedDownloads = nextKeys.size,
                            pendingDownloads = (st.totalStudents - nextKeys.size).coerceAtLeast(0),
                            completedKeys = nextKeys,
                            downloadHistory = listOf(hist) + st.downloadHistory,
                            statusMessage = "Saved ${hist.fileName}"
                        )
                    }
                } else {
                    val reason = res.exceptionOrNull()?.message ?: "Download failed"
                    if (reason.contains("session expired", ignoreCase = true)) {
                        pauseBulkRequested = true
                        _bulkState.update {
                            it.copy(
                                jobStatus = AutomationJobStatus.PAUSED,
                                statusMessage = "Portal session expired. Sign in on Portal and tap Resume.",
                                isError = true
                            )
                        }
                        if (!waitWhilePausedBulk() || stopBulkRequested) break
                    } else {
                        val fail = BulkFailedItem(student.rollNo, student.name, scan.currentPage, student.rowIndex, reason)
                        _bulkState.update { st ->
                            val nextFailed = (st.failedList + fail).distinctBy { "${it.rollNo}|${it.studentName}|${it.pageNumber}" }
                            st.copy(
                                failedList = nextFailed,
                                failedDownloadsCount = nextFailed.size,
                                statusMessage = "Failed for ${student.rollNo} (${student.name}): $reason"
                            )
                        }
                    }
                }

                // Wait briefly so WebView settles before next student
                delay(250L)
            }

            if (stopBulkRequested) break
            if (!waitWhilePausedBulk() || stopBulkRequested) break

            if (!scan.hasNextPage && scan.currentPage >= scan.totalPages) {
                break
            }

            _bulkState.update {
                it.copy(statusMessage = "Moving to Page ${scan.currentPage + 1}...")
            }
            val advanced = advanceToNextTablePage(webView, scan.currentPage)
            if (!advanced) {
                break
            }
        }

        _bulkState.update { st ->
            val finishedStatus = if (stopBulkRequested) AutomationJobStatus.STOPPED else AutomationJobStatus.COMPLETED
            val msg = if (stopBulkRequested) {
                "Stopped. Saved ${st.completedDownloads} PDF(s), Failed ${st.failedDownloadsCount}."
            } else {
                "Completed! Downloaded ${st.completedDownloads} marksheet PDF(s). Failed: ${st.failedDownloadsCount}."
            }
            st.copy(
                jobStatus = finishedStatus,
                currentStudentName = "",
                currentRollNo = "",
                pendingDownloads = (st.totalStudents - st.completedKeys.size - st.failedDownloadsCount).coerceAtLeast(0),
                statusMessage = msg,
                isError = false
            )
        }
    }

    private suspend fun runResultExtractionLoop(webView: WebView) {
        _resultState.update {
            it.copy(
                jobStatus = AutomationJobStatus.RUNNING,
                isError = false,
                statusMessage = "Scanning Student Information table..."
            )
        }

        var pageSafetyCount = 0
        historyGoBackProducesCacheMiss = false
        clearWebViewTransientError()

        while (pageSafetyCount < 200) {
            pageSafetyCount++
            if (!waitWhilePausedResult() || stopResultRequested) break

            var scan = scanTableInternal(webView)
            if (!scan.ok && !scan.sessionExpired) {
                val targetPage = _resultState.value.currentPageNumber.coerceAtLeast(1)
                if (recoverStudentTableFromSnapshot(webView, targetPage)) {
                    scan = scanTableInternal(webView)
                }
            }
            if (!scan.ok) {
                _resultState.update {
                    it.copy(
                        jobStatus = if (scan.sessionExpired) AutomationJobStatus.PAUSED else AutomationJobStatus.ERROR,
                        statusMessage = scan.error,
                        isError = true
                    )
                }
                return
            }

            saveHrpcSnapshotNow(webView)
            val totalEst = maxOf(_resultState.value.totalStudents, scan.totalEntries, scan.students.size)

            _resultState.update { st ->
                st.copy(
                    scannedStudents = scan.students,
                    totalStudents = totalEst,
                    currentPageNumber = scan.currentPage,
                    totalPages = scan.totalPages,
                    pendingCount = (totalEst - st.extractedKeys.size).coerceAtLeast(0)
                )
            }

            for (student in scan.students) {
                if (!waitWhilePausedResult() || stopResultRequested) break
                if (_resultState.value.extractedKeys.contains(student.uniqueKey)) continue

                _resultState.update {
                    it.copy(
                        currentStudentName = student.name,
                        currentRollNo = student.rollNo,
                        currentPageNumber = scan.currentPage,
                        statusMessage = "Page ${scan.currentPage}/${scan.totalPages}: Extracting marks for ${student.rollNo} - ${student.name}..."
                    )
                }

                val res = extractSingleStudentSummative(webView, student, scan.currentPage)
                if (res.isSuccess) {
                    val row = res.getOrThrow()
                    _resultState.update { st ->
                        val updatedRows = ResultXlsxExporter.recalculatePositions(st.extractedRows + row)
                        val nextKeys = st.extractedKeys + student.uniqueKey
                        st.copy(
                            extractedRows = updatedRows,
                            extractedKeys = nextKeys,
                            completedCount = nextKeys.size,
                            pendingCount = (st.totalStudents - nextKeys.size).coerceAtLeast(0),
                            statusMessage = "Extracted ${row.rollNo} - ${row.studentName} (Grand Total: ${row.grandTotal})"
                        )
                    }
                } else {
                    val reason = res.exceptionOrNull()?.message ?: "Extraction failed"
                    if (reason.contains("session expired", ignoreCase = true)) {
                        pauseResultRequested = true
                        _resultState.update {
                            it.copy(
                                jobStatus = AutomationJobStatus.PAUSED,
                                statusMessage = "Portal session expired. Re-login in Portal and tap Resume.",
                                isError = true
                            )
                        }
                        if (!waitWhilePausedResult() || stopResultRequested) break
                    } else {
                        val fail = ResultFailedItem(student.rollNo, student.name, scan.currentPage, student.rowIndex, reason)
                        _resultState.update { st ->
                            val nextFailed = (st.failedList + fail).distinctBy { "${it.rollNo}|${it.studentName}|${it.pageNumber}" }
                            st.copy(
                                failedList = nextFailed,
                                failedCount = nextFailed.size
                            )
                        }
                    }
                }
                delay(200L)
            }

            if (stopResultRequested) break
            if (!waitWhilePausedResult() || stopResultRequested) break

            if (!scan.hasNextPage && scan.currentPage >= scan.totalPages) break
            val advanced = advanceToNextTablePage(webView, scan.currentPage)
            if (!advanced) break
        }

        _resultState.update { st ->
            val finalRows = ResultXlsxExporter.recalculatePositions(st.extractedRows)
            st.copy(
                jobStatus = if (stopResultRequested) AutomationJobStatus.STOPPED else AutomationJobStatus.COMPLETED,
                extractedRows = finalRows,
                currentStudentName = "",
                currentRollNo = "",
                pendingCount = (st.totalStudents - st.extractedKeys.size - st.failedCount).coerceAtLeast(0),
                statusMessage = if (stopResultRequested) {
                    "Stopped. Extracted ${finalRows.size} student row(s)."
                } else {
                    "Extraction complete! ${finalRows.size} student(s) ready. Tap 'Download Excel (.xlsx)' to save."
                }
            )
        }
    }

    private suspend fun processSingleStudentPdf(
        webView: WebView,
        treeUri: Uri,
        student: HrpcStudentEntry,
        tablePageNum: Int,
        selectedPages: List<Int>
    ): Result<BulkDownloadHistoryItem> {
        capturedPdfDownloadUrl.set(null)
        capturedPdfUserAgent.set(null)

        // Ensure table is on expected page
        val tableReady = ensureOnTablePage(webView, tablePageNum)
        if (!tableReady) {
            return Result.failure(IllegalStateException("Could not reach table Page $tablePageNum"))
        }

        val triggerJson = evalJs(
            webView,
            PortalHrpcDomScripts.triggerStudentActionJs(student.rowIndex, student.rollNo, student.name, "print")
        )
        val triggerObj = runCatching { JSONObject(triggerJson) }.getOrNull()
        if (triggerObj?.optBoolean("ok", false) != true) {
            return Result.failure(IllegalStateException(triggerObj?.optString("error") ?: "Print button failed"))
        }

        // Wait up to 20s for either direct PDF download listener OR rendered HTML marksheet page
        val waitOutcome = waitForMarksheetOrPdfDownload(webView, timeoutMs = 20_000L)
        val directPdfUrl = capturedPdfDownloadUrl.getAndSet(null)

        val fileName = SafStorageHelper.sanitizeMarksheetFileName(student.rollNo, student.name, "pdf")

        return try {
            if (!directPdfUrl.isNullOrBlank()) {
                val cookieHeader = runCatching {
                    cookieResolver(directPdfUrl)
                }.getOrNull()
                val rawPdfBytes = withContext(Dispatchers.IO) {
                    downloadBytesWithCookies(directPdfUrl, cookieHeader, capturedPdfUserAgent.get())
                }
                val writeRes = withContext(Dispatchers.IO) {
                    SafStorageHelper.writeFileToFolder(
                        context = appContext,
                        treeUri = treeUri,
                        mimeType = "application/pdf",
                        displayName = fileName,
                        overwriteExisting = true
                    ) { out ->
                        val filterRes = MarksheetPdfGenerator.filterExistingPdfBytes(
                            rawPdfBytes = rawPdfBytes,
                            selectedPagesInput = selectedPages,
                            cacheDir = appContext.cacheDir,
                            outStream = out
                        )
                        if (filterRes.isFailure) {
                            throw filterRes.exceptionOrNull() ?: IllegalStateException("PDF write failed")
                        }
                    }
                }
                if (writeRes.isFailure) {
                    throw writeRes.exceptionOrNull() ?: IllegalStateException("SAF write failed")
                }
            } else {
                if (!waitOutcome.ok) {
                    return Result.failure(IllegalStateException(waitOutcome.error))
                }
                // Extra brief settle for tables/images/Bangla font layout
                delay(450L)
                val saveRes = captureActiveMarksheetWebViewToSaf(webView, treeUri, fileName, selectedPages)
                if (saveRes.isFailure) {
                    return Result.failure(saveRes.exceptionOrNull() ?: IllegalStateException("PDF generation failed"))
                }
            }

            // Return cleanly to the Student Information table and restore pagination page
            returnToStudentTableFromMarksheet(webView, tablePageNum)

            Result.success(
                BulkDownloadHistoryItem(
                    rollNo = student.rollNo,
                    studentName = student.name,
                    pageNumber = tablePageNum,
                    fileName = fileName,
                    pagesSaved = selectedPages,
                    timestamp = nowTimeStr()
                )
            )
        } catch (t: Throwable) {
            returnToStudentTableFromMarksheet(webView, tablePageNum)
            Result.failure(t)
        }
    }

    private suspend fun extractSingleStudentSummative(
        webView: WebView,
        student: HrpcStudentEntry,
        tablePageNum: Int
    ): Result<StudentSummativeResultRow> {
        val tableReady = ensureOnTablePage(webView, tablePageNum)
        if (!tableReady) {
            return Result.failure(IllegalStateException("Could not reach table Page $tablePageNum"))
        }

        val triggerJson = evalJs(
            webView,
            PortalHrpcDomScripts.triggerStudentActionJs(student.rowIndex, student.rollNo, student.name, "view")
        )
        val triggerObj = runCatching { JSONObject(triggerJson) }.getOrNull()
        if (triggerObj?.optBoolean("ok", false) != true) {
            return Result.failure(IllegalStateException(triggerObj?.optString("error") ?: "View button failed"))
        }

        val waitOutcome = waitForMarksheetOrPdfDownload(webView, timeoutMs = 20_000L)
        if (!waitOutcome.ok) {
            returnToStudentTableFromMarksheet(webView, tablePageNum)
            return Result.failure(IllegalStateException(waitOutcome.error))
        }

        delay(300L)
        val extractedJson = evalJs(webView, PortalHrpcDomScripts.extractSummativeMarksJs(student.rollNo, student.name))
        returnToStudentTableFromMarksheet(webView, tablePageNum)

        val obj = runCatching { JSONObject(extractedJson) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Failed parsing marksheet DOM"))

        if (!obj.optBoolean("ok", false)) {
            val err = obj.optString("error", "Summative Evaluation table not found")
            if (obj.optBoolean("sessionExpired", false)) {
                return Result.failure(IllegalStateException("Portal session expired"))
            }
            return Result.failure(IllegalStateException(err))
        }

        val subjectsObj = obj.optJSONObject("subjects") ?: JSONObject()
        val printedObj = obj.optJSONObject("printed") ?: JSONObject()

        fun termMark(subKey: String, termField: String): String {
            return subjectsObj.optJSONObject(subKey)?.optString(termField, "")?.trim().orEmpty()
        }

        fun sumStrings(values: List<String>): String {
            var sum = 0.0
            var anyNum = false
            for (v in values) {
                val n = v.toDoubleOrNull()
                if (n != null && n.isFinite()) {
                    sum += n
                    anyNum = true
                }
            }
            return if (!anyNum) "" else formatCleanNumber(sum)
        }

        val ben1 = termMark("ben", "t1"); val eng1 = termMark("eng", "t1"); val evs1 = termMark("evs", "t1")
        val math1 = termMark("math", "t1"); val health1 = termMark("health", "t1"); val art1 = termMark("art", "t1")
        val tot1 = sumStrings(listOf(ben1, eng1, evs1, math1, health1, art1)).ifBlank {
            printedObj.optString("t1", "").trim()
        }

        val ben2 = termMark("ben", "t2"); val eng2 = termMark("eng", "t2"); val evs2 = termMark("evs", "t2")
        val math2 = termMark("math", "t2"); val health2 = termMark("health", "t2"); val art2 = termMark("art", "t2")
        val tot2 = sumStrings(listOf(ben2, eng2, evs2, math2, health2, art2)).ifBlank {
            printedObj.optString("t2", "").trim()
        }

        val ben3 = termMark("ben", "t3"); val eng3 = termMark("eng", "t3"); val evs3 = termMark("evs", "t3")
        val math3 = termMark("math", "t3"); val health3 = termMark("health", "t3"); val art3 = termMark("art", "t3")
        val tot3 = sumStrings(listOf(ben3, eng3, evs3, math3, health3, art3)).ifBlank {
            printedObj.optString("t3", "").trim()
        }

        fun subjectTotal(key: String, m1: String, m2: String, m3: String): String {
            val computed = sumStrings(listOf(m1, m2, m3))
            return computed.ifBlank { termMark(key, "total") }
        }

        val benTotal = subjectTotal("ben", ben1, ben2, ben3)
        val engTotal = subjectTotal("eng", eng1, eng2, eng3)
        val evsTotal = subjectTotal("evs", evs1, evs2, evs3)
        val mathTotal = subjectTotal("math", math1, math2, math3)
        val healthTotal = subjectTotal("health", health1, health2, health3)
        val artTotal = subjectTotal("art", art1, art2, art3)

        val grandTotalStr = sumStrings(listOf(benTotal, engTotal, evsTotal, mathTotal, healthTotal, artTotal)).ifBlank {
            printedObj.optString("grandTotal", "").trim()
        }

        // Compute max full marks dynamically based on which subjects have numeric marks (standard = 6 subjects x 100 = 600)
        val activeSubjectCount = listOf(benTotal, engTotal, evsTotal, mathTotal, healthTotal, artTotal)
            .count { it.toDoubleOrNull() != null }
            .coerceAtLeast(1)
        val fullMarksTotal = activeSubjectCount * 100.0
        val grandNum = grandTotalStr.toDoubleOrNull()
        val pctStr = printedObj.optString("percentage", "").trim().ifBlank {
            if (grandNum != null && fullMarksTotal > 0.0) {
                String.format(Locale.US, "%.2f", (grandNum / fullMarksTotal) * 100.0)
            } else {
                ""
            }
        }

        val posStr = printedObj.optString("position", "").trim()

        val displayRoll = obj.optString("rollNo", student.rollNo).trim().ifBlank { student.rollNo }
        val displayName = obj.optString("studentName", student.name).trim().ifBlank { student.name }

        return Result.success(
            StudentSummativeResultRow(
                rollNo = displayRoll,
                studentName = displayName,
                term1 = SummativeTermMarks(ben1, eng1, evs1, math1, health1, art1, tot1),
                term2 = SummativeTermMarks(ben2, eng2, evs2, math2, health2, art2, tot2),
                term3 = SummativeTermMarks(ben3, eng3, evs3, math3, health3, art3, tot3),
                benTotal = benTotal,
                engTotal = engTotal,
                evsTotal = evsTotal,
                mathTotal = mathTotal,
                healthTotal = healthTotal,
                artTotal = artTotal,
                grandTotal = grandTotalStr,
                percentage = pctStr,
                position = posStr
            )
        )
    }

    private suspend fun captureActiveMarksheetWebViewToSaf(
        webView: WebView,
        treeUri: Uri,
        fileName: String,
        selectedPages: List<Int>
    ): Result<Uri> {
        val pdfBytesRes = MarksheetPdfGenerator.captureSelectedPagesToPdfBytes(webView, selectedPages, 5)
        if (pdfBytesRes.isFailure) {
            return Result.failure(pdfBytesRes.exceptionOrNull() ?: IllegalStateException("Render failed"))
        }
        val bytes = pdfBytesRes.getOrThrow()
        return withContext(Dispatchers.IO) {
            SafStorageHelper.writeFileToFolder(
                context = appContext,
                treeUri = treeUri,
                mimeType = "application/pdf",
                displayName = fileName,
                overwriteExisting = true
            ) { out ->
                out.write(bytes)
            }
        }
    }

    private suspend fun waitForMarksheetOrPdfDownload(webView: WebView, timeoutMs: Long): SimpleOutcome {
        val startMs = System.currentTimeMillis()
        clearWebViewTransientError()
        val deadline = startMs + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (capturedPdfDownloadUrl.get() != null) {
                return SimpleOutcome(true, "")
            }
            if (lastWebViewCacheMissAtMs >= startMs) {
                return SimpleOutcome(false, "Portal form cache miss (ERR_CACHE_MISS)")
            }
            if (lastMainFrameErrorAtMs >= startMs && !lastMainFrameErrorDesc.isNullOrBlank()) {
                return SimpleOutcome(false, lastMainFrameErrorDesc ?: "Portal page error")
            }
            val raw = evalJs(webView, PortalHrpcDomScripts.checkMarksheetRenderJs())
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null) {
                if (obj.optBoolean("hasAuthForm", false)) {
                    return SimpleOutcome(false, "Portal session expired. Please log in again.")
                }
                val readyState = obj.optString("readyState", "")
                val looksLikeMarksheet = obj.optBoolean("looksLikeMarksheet", false)
                val spinner = obj.optBoolean("spinnerVisible", false)
                val pendingImgs = obj.optInt("pendingImgs", 0)
                if (readyState == "complete" && looksLikeMarksheet && !spinner && pendingImgs == 0) {
                    return SimpleOutcome(true, "")
                }
            }
            delay(260L)
        }
        return SimpleOutcome(false, "Timed out waiting for marksheet to render")
    }

    private enum class TableWaitResult { READY, CACHE_MISS, TIMEOUT }

    private suspend fun waitForStudentTableVisibleWithAbort(
        webView: WebView,
        timeoutMs: Long,
        stepStartMs: Long
    ): TableWaitResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (lastWebViewCacheMissAtMs >= stepStartMs) {
                return TableWaitResult.CACHE_MISS
            }
            val currentUrl = withContext(Dispatchers.Main) { webView.url.orEmpty() }
            if (currentUrl.contains("chromewebdata", ignoreCase = true) || currentUrl == "about:blank") {
                val errCheck = evalJs(
                    webView,
                    "(function(){var t=(document.body&&document.body.innerText)||'';return /ERR_CACHE_MISS|Webpage not available|Confirm Form Resubmission/i.test(t)?'CACHE_MISS':'OTHER';})();"
                )
                if (errCheck.contains("CACHE_MISS")) {
                    historyGoBackProducesCacheMiss = true
                    return TableWaitResult.CACHE_MISS
                }
            }
            val scan = scanTableInternal(webView)
            if (scan.ok && scan.students.isNotEmpty()) {
                return TableWaitResult.READY
            }
            delay(240L)
        }
        return TableWaitResult.TIMEOUT
    }

    private suspend fun recoverStudentTableFromSnapshot(webView: WebView, targetPageNumber: Int): Boolean {
        val snapStr = lastHrpcSnapshotRef.get()
        val snapUrl = runCatching { JSONObject(snapStr ?: "{}").optString("url", "") }.getOrDefault("")
            .takeIf { it.startsWith("http", ignoreCase = true) }
            ?: MainActivity.PORTAL_URL

        pendingHrpcRestoreFromBack = false
        clearWebViewTransientError()

        // Inspect current DOM: if we are not already on a clean filter/table page, load snapUrl cleanly via GET
        val pageProbe = evalJs(
            webView,
            """
            (function() {
                var bodyTxt = (document.body && document.body.innerText) ? document.body.innerText : '';
                var hasErr = /ERR_CACHE_MISS|Webpage not available|Confirm Form Resubmission/i.test(bodyTxt) ||
                    location.href.indexOf('chromewebdata') !== -1 || location.href === 'about:blank';
                var hasStudentTable = !!document.querySelector('table[data-bsp-student-table="true"]') ||
                    (/Showing\s+\d+\s+to\s+\d+\s+of\s+\d+\s+entries/i.test(bodyTxt) && !/Summative\s+Evaluation/i.test(bodyTxt));
                var onMarksheet = !hasStudentTable && /Summative\s+Evaluation|Progress\s+Report|Holistic\s+Report/i.test(bodyTxt);
                var hasSelects = document.querySelectorAll('select').length >= 2;
                return JSON.stringify({ hasErr: hasErr, hasStudentTable: hasStudentTable, onMarksheet: onMarksheet, hasSelects: hasSelects });
            })();
            """.trimIndent()
        )
        val probeObj = runCatching { JSONObject(pageProbe) }.getOrNull()
        val hasErr = probeObj?.optBoolean("hasErr", true) ?: true
        val hasTable = probeObj?.optBoolean("hasStudentTable", false) ?: false
        val onMarksheet = probeObj?.optBoolean("onMarksheet", false) ?: false
        val hasSelects = probeObj?.optBoolean("hasSelects", false) ?: false

        if (hasTable) {
            return navigateToExactTablePageIfNeeded(webView, targetPageNumber)
        }

        if (hasErr || onMarksheet || !hasSelects) {
            val loadStartMs = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                webView.stopLoading()
                webView.loadUrl(snapUrl)
            }
            val loadDeadline = loadStartMs + 14_000L
            while (System.currentTimeMillis() < loadDeadline) {
                delay(280L)
                val readyJson = evalJs(
                    webView,
                    "(function(){var t=(document.body&&document.body.innerText)||'';return JSON.stringify({ready:document.readyState==='complete',auth:!!document.querySelector('input[type=\"password\"],img[src*=\"captcha\" i]'),selects:document.querySelectorAll('select').length,table:/Showing\\s+\\d+\\s+to\\s+\\d+/i.test(t)});})();"
                )
                val rObj = runCatching { JSONObject(readyJson) }.getOrNull()
                if (rObj?.optBoolean("auth", false) == true) return false
                if (rObj?.optBoolean("table", false) == true) {
                    return navigateToExactTablePageIfNeeded(webView, targetPageNumber)
                }
                if (rObj?.optBoolean("ready", false) == true && (rObj.optInt("selects", 0) >= 1)) {
                    break
                }
            }
        }

        if (!snapStr.isNullOrBlank()) {
            evalJs(webView, PortalHrpcDomScripts.restoreHrpcFiltersJs(snapStr))
        }
        val appeared = waitForStudentTableVisible(webView, timeoutMs = 15_000L)
        if (!appeared) return false
        return navigateToExactTablePageIfNeeded(webView, targetPageNumber)
    }

    private suspend fun navigateToExactTablePageIfNeeded(webView: WebView, targetPage: Int): Boolean {
        val currentScan = scanTableInternal(webView)
        if (!currentScan.ok) return false
        if (currentScan.currentPage == targetPage || targetPage <= 1) return true

        val clickJson = evalJs(webView, PortalHrpcDomScripts.clickPaginationPageJs(targetPage, "exact"))
        val clickObj = runCatching { JSONObject(clickJson) }.getOrNull()
        if (clickObj?.optBoolean("alreadyOnPage", false) == true) return true
        if (clickObj?.optBoolean("clicked", false) == true) {
            delay(450L)
            val deadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < deadline) {
                val afterScan = scanTableInternal(webView)
                if (afterScan.ok && afterScan.currentPage == targetPage) return true
                delay(250L)
            }
        }
        return true
    }

    private suspend fun returnToStudentTableFromMarksheet(webView: WebView, targetPageNumber: Int) {
        val inPageRaw = evalJs(webView, PortalHrpcDomScripts.tryCloseInPageMarksheetOrModalJs())
        val inPageObj = runCatching { JSONObject(inPageRaw) }.getOrNull()
        if (inPageObj?.optBoolean("handled", false) == true) {
            delay(320L)
            ensureOnTablePage(webView, targetPageNumber)
            return
        }

        if (!historyGoBackProducesCacheMiss && webView.canGoBack()) {
            val goBackStartMs = System.currentTimeMillis()
            pendingHrpcRestoreFromBack = true
            clearWebViewTransientError()
            withContext(Dispatchers.Main) { webView.goBack() }
            when (waitForStudentTableVisibleWithAbort(webView, timeoutMs = 12_000L, stepStartMs = goBackStartMs)) {
                TableWaitResult.READY -> {
                    pendingHrpcRestoreFromBack = false
                    navigateToExactTablePageIfNeeded(webView, targetPageNumber)
                    return
                }
                TableWaitResult.CACHE_MISS -> {
                    historyGoBackProducesCacheMiss = true
                    recoverStudentTableFromSnapshot(webView, targetPageNumber)
                    return
                }
                TableWaitResult.TIMEOUT -> {
                    // Fall through to clean GET + filter restore recovery
                }
            }
        }

        recoverStudentTableFromSnapshot(webView, targetPageNumber)
    }

    private suspend fun ensureOnTablePage(webView: WebView, targetPage: Int): Boolean {
        val scanNow = scanTableInternal(webView)
        if (scanNow.ok && scanNow.students.isNotEmpty()) {
            return navigateToExactTablePageIfNeeded(webView, targetPage)
        }
        return recoverStudentTableFromSnapshot(webView, targetPage)
    }

    private suspend fun advanceToNextTablePage(webView: WebView, prevPage: Int): Boolean {
        val clickJson = evalJs(webView, PortalHrpcDomScripts.clickPaginationPageJs(prevPage + 1, "next"))
        val obj = runCatching { JSONObject(clickJson) }.getOrNull() ?: return false
        if (!obj.optBoolean("ok", false)) return false

        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            delay(300L)
            val after = scanTableInternal(webView)
            if (after.ok && after.currentPage > prevPage) {
                return true
            }
        }
        return false
    }

    private suspend fun waitForStudentTableVisible(webView: WebView, timeoutMs: Long): Boolean {
        val startMs = System.currentTimeMillis()
        val deadline = startMs + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (lastWebViewCacheMissAtMs >= startMs) return false
            val scan = scanTableInternal(webView)
            if (scan.ok && scan.students.isNotEmpty()) return true
            delay(280L)
        }
        return false
    }

    private suspend fun scanTableInternal(webView: WebView): TableScanOutcome {
        val raw = evalJs(webView, PortalHrpcDomScripts.scanHrpcTableJs())
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return TableScanOutcome(ok = false, error = "Unable to inspect portal page.")
        if (!obj.optBoolean("ok", false)) {
            return TableScanOutcome(
                ok = false,
                sessionExpired = obj.optBoolean("sessionExpired", false),
                error = obj.optString("reason", "Student table not detected.")
            )
        }
        val arr = obj.optJSONArray("students") ?: JSONArray()
        val list = mutableListOf<HrpcStudentEntry>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            list.add(
                HrpcStudentEntry(
                    rowIndex = item.optInt("rowIndex", i),
                    rollNo = item.optString("rollNo", ""),
                    name = item.optString("name", ""),
                    hasView = item.optBoolean("hasView", false),
                    hasPrint = item.optBoolean("hasPrint", false),
                    viewHref = item.optString("viewHref", ""),
                    printHref = item.optString("printHref", "")
                )
            )
        }
        return TableScanOutcome(
            ok = true,
            currentPage = obj.optInt("currentPage", 1).coerceAtLeast(1),
            totalPages = obj.optInt("totalPages", 1).coerceAtLeast(1),
            totalEntries = obj.optInt("totalEntries", list.size).coerceAtLeast(list.size),
            hasNextPage = obj.optBoolean("hasNextPage", false),
            students = list
        )
    }

    private suspend fun saveHrpcSnapshotNow(webView: WebView) {
        val snap = evalJs(webView, PortalHrpcDomScripts.snapshotHrpcStateJs())
        updateLastHrpcSnapshot(snap)
    }

    private fun effectiveSelectedPages(): List<Int> {
        val sel = _bulkState.value.selectedPages
        return if (sel.isEmpty()) listOf(1, 2, 3, 4, 5) else sel.sorted()
    }

    private fun updateFailedReason(item: BulkFailedItem, newReason: String) {
        _bulkState.update { st ->
            val updated = st.failedList.map {
                if (it.rollNo == item.rollNo && it.studentName == item.studentName && it.pageNumber == item.pageNumber) {
                    it.copy(reason = newReason)
                } else {
                    it
                }
            }
            st.copy(failedList = updated, failedDownloadsCount = updated.size, statusMessage = newReason)
        }
    }

    private suspend fun waitWhilePausedBulk(): Boolean {
        while (pauseBulkRequested && !stopBulkRequested) {
            delay(300L)
        }
        return !stopBulkRequested
    }

    private suspend fun waitWhilePausedResult(): Boolean {
        while (pauseResultRequested && !stopResultRequested) {
            delay(300L)
        }
        return !stopResultRequested
    }

    private fun formatCleanNumber(v: Double): String {
        return if (v == v.roundToInt().toDouble()) {
            v.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        }
    }

    private fun nowTimeStr(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun downloadBytesWithCookies(urlStr: String, cookies: String?, userAgent: String?): ByteArray {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            if (!cookies.isNullOrBlank()) setRequestProperty("Cookie", cookies)
            if (!userAgent.isNullOrBlank()) setRequestProperty("User-Agent", userAgent)
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("Download HTTP $code")
            }
            val baos = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                input.copyTo(baos)
            }
            val bytes = baos.toByteArray()
            if (bytes.isEmpty()) error("Empty PDF response")
            bytes
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun evalJs(webView: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { raw ->
                val decoded = if (raw == null || raw == "null") {
                    "{}"
                } else {
                    try {
                        if (raw.startsWith("\"") && raw.endsWith("\"")) {
                            JSONArray("[$raw]").getString(0)
                        } else {
                            raw
                        }
                    } catch (_: Exception) {
                        raw
                    }
                }
                if (cont.isActive) cont.resume(decoded)
            }
        }

    private data class SimpleOutcome(val ok: Boolean, val error: String)
    private data class TableScanOutcome(
        val ok: Boolean,
        val sessionExpired: Boolean = false,
        val currentPage: Int = 1,
        val totalPages: Int = 1,
        val totalEntries: Int = 0,
        val hasNextPage: Boolean = false,
        val students: List<HrpcStudentEntry> = emptyList(),
        val error: String = ""
    )
}
