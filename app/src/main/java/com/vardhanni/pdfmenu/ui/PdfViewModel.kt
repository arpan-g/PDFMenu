package com.vardhanni.pdfmenu.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vardhanni.pdfmenu.domain.repository.PdfRepository
import com.vardhanni.pdfmenu.domain.usecase.CompressPdfUseCase
import com.vardhanni.pdfmenu.domain.usecase.UnlockPdfUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class PdfUiEvent {
    data class ShowPdf(val file: File) : PdfUiEvent()
    data class ShowError(val message: String) : PdfUiEvent()
    data class Toast(val message: String) : PdfUiEvent()
    data class ActionSuccess(val action: String) : PdfUiEvent()
}

class PdfViewModel(
    private val repository: PdfRepository,
    private val unlockPdfUseCase: UnlockPdfUseCase,
    private val compressPdfUseCase: CompressPdfUseCase
) : ViewModel() {

    var currentPdfUri by mutableStateOf<Uri?>(null)
    var currentFileName by mutableStateOf<String?>(null)
    var freeActionsRemaining by mutableStateOf(1)
    
    var tempPdfFile by mutableStateOf<File?>(null)
    private var originalUnlockedFile: File? = null
    var compressedPdfFile by mutableStateOf<File?>(null)
    
    var isSaveVisible by mutableStateOf(false)
    var isSaveEnabled by mutableStateOf(false)
    var showPasswordDialog by mutableStateOf(false)
    var isRetryPassword by mutableStateOf(false)
    var showCompressDialog by mutableStateOf(false)
    var showLockDialog by mutableStateOf(false)
    var wasEncrypted by mutableStateOf(false)
    
    var compressionQuality by mutableFloatStateOf(0.5f)
    var compressedFileSize by mutableLongStateOf(0L)
    var isCompressing by mutableStateOf(false)
    var isLocking by mutableStateOf(false)

    private var compressionJob: Job? = null
    private var latestCompressionRequestId: Long = 0

    private val _uiEvents = MutableSharedFlow<PdfUiEvent>()
    val uiEvents = _uiEvents.asSharedFlow()

    fun onFilePicked(uri: Uri, fileName: String?) {
        currentPdfUri = uri
        currentFileName = fileName
        isSaveVisible = false
        isSaveEnabled = false
        wasEncrypted = false
        cleanupAllTempFiles()
        processPdf(uri)
    }

    fun processPdf(uri: Uri, password: String? = null) {
        viewModelScope.launch {
            unlockPdfUseCase(uri, password).onSuccess { result ->
                originalUnlockedFile = result.file
                tempPdfFile = result.file
                isSaveVisible = true
                showPasswordDialog = false
                
                // Only enable save if we actually required a password to unlock it
                // This prevents the save button from being enabled for standard unencrypted PDFs
                wasEncrypted = result.wasEncrypted
                isSaveEnabled = password != null || (result.wasEncrypted && !isInitialLoad(result.file))
                
                _uiEvents.emit(PdfUiEvent.ShowPdf(result.file))
            }.onFailure { e ->
                if (e.message?.contains("Password", ignoreCase = true) == true || e is SecurityException) {
                    isRetryPassword = password != null
                    showPasswordDialog = true
                    wasEncrypted = true
                } else {
                    _uiEvents.emit(PdfUiEvent.ShowError(e.message ?: "Unknown error"))
                }
            }
        }
    }

    private fun isInitialLoad(file: File): Boolean {
        // Simple heuristic: if we just picked it and wasEncrypted is true but no password was used,
        // it might just have an owner password. We'll enable save for that too as it's a 'change'.
        return false 
    }

    fun onCompressQualitySliderChange(quality: Float) {
        compressionQuality = quality
    }

    fun startCompressionCalculation() {
        val quality = compressionQuality
        val requestId = ++latestCompressionRequestId
        compressionJob?.cancel()
        
        val sourceFile = originalUnlockedFile ?: return
        
        isCompressing = true
        compressionJob = viewModelScope.launch {
            compressPdfUseCase(sourceFile, quality).onSuccess { file ->
                if (requestId == latestCompressionRequestId) {
                    compressedPdfFile?.let { if (it.exists()) it.delete() }
                    compressedPdfFile = file
                    compressedFileSize = file.length()
                    isCompressing = false
                } else {
                    if (file.exists()) file.delete()
                }
            }.onFailure { e ->
                if (requestId == latestCompressionRequestId) {
                    _uiEvents.emit(PdfUiEvent.ShowError("Compression calculation failed: ${e.message}"))
                    isCompressing = false
                }
            }
        }
    }

    fun onCompressDialogOpened() {
        if (compressedPdfFile == null && !isCompressing) {
            startCompressionCalculation()
        }
    }

    fun applyCompression() {
        compressedPdfFile?.let { newFile ->
            tempPdfFile?.let { current ->
                if (current.exists() && current != originalUnlockedFile && current != newFile) {
                    current.delete()
                }
            }
            tempPdfFile = newFile
            compressedFileSize = newFile.length()
            compressedPdfFile = null 
            isSaveEnabled = true // Enable save because a change was applied
            viewModelScope.launch {
                _uiEvents.emit(PdfUiEvent.ShowPdf(newFile))
                _uiEvents.emit(PdfUiEvent.ActionSuccess("COMPRESS"))
            }
        }
        showCompressDialog = false
    }

    fun lockPdf(password: String) {
        val sourceFile = tempPdfFile ?: return
        isLocking = true
        viewModelScope.launch {
            repository.lockPdf(sourceFile, password).onSuccess { file ->
                tempPdfFile?.let { current ->
                    if (current.exists() && current != originalUnlockedFile && current != file) {
                        current.delete()
                    }
                }
                tempPdfFile = file
                isSaveEnabled = true
                isLocking = false
                showLockDialog = false
                // CRITICAL FIX: Do NOT emit ShowPdf for a locked file. 
                // The PdfViewerFragment in alpha15 crashes when trying to show its own password dialog.
                // We keep the current (unlocked) preview visible for the user.
                _uiEvents.emit(PdfUiEvent.Toast("PDF password protection applied. Save to export."))
                _uiEvents.emit(PdfUiEvent.ActionSuccess("LOCK"))
            }.onFailure { e ->
                isLocking = false
                _uiEvents.emit(PdfUiEvent.ShowError("Locking failed: ${e.message}"))
            }
        }
    }

    fun saveUnprotected(targetUri: Uri) {
        val sourceFile = tempPdfFile ?: return
        viewModelScope.launch {
            repository.savePdf(sourceFile, targetUri).onSuccess {
                _uiEvents.emit(PdfUiEvent.Toast("PDF saved successfully!"))
                _uiEvents.emit(PdfUiEvent.ActionSuccess("SAVE"))
            }.onFailure { e ->
                _uiEvents.emit(PdfUiEvent.ShowError("Save failed: ${e.message}"))
            }
        }
    }

    private fun cleanupAllTempFiles() {
        originalUnlockedFile?.let { if (it.exists()) it.delete() }
        tempPdfFile?.let { if (it.exists() && it != originalUnlockedFile) it.delete() }
        compressedPdfFile?.let { if (it.exists()) it.delete() }
        
        originalUnlockedFile = null
        tempPdfFile = null
        compressedPdfFile = null
    }

    override fun onCleared() {
        super.onCleared()
        cleanupAllTempFiles()
    }

    class Factory(private val repository: PdfRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val unlockPdfUseCase = UnlockPdfUseCase(repository)
            val compressPdfUseCase = CompressPdfUseCase(repository)
            return PdfViewModel(repository, unlockPdfUseCase, compressPdfUseCase) as T
        }
    }
}
