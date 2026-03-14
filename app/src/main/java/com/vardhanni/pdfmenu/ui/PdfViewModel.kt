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
    var tempPdfFile by mutableStateOf<File?>(null)
    private var originalUnlockedFile: File? = null
    var compressedPdfFile by mutableStateOf<File?>(null)
    
    var isSaveVisible by mutableStateOf(false)
    var showPasswordDialog by mutableStateOf(false)
    var isRetryPassword by mutableStateOf(false)
    var showCompressDialog by mutableStateOf(false)
    var compressionQuality by mutableFloatStateOf(0.5f)
    var compressedFileSize by mutableLongStateOf(0L)
    var isCompressing by mutableStateOf(false)

    private var compressionJob: Job? = null

    private val _uiEvents = MutableSharedFlow<PdfUiEvent>()
    val uiEvents = _uiEvents.asSharedFlow()

    fun onFilePicked(uri: Uri) {
        currentPdfUri = uri
        isSaveVisible = false
        cleanupAllTempFiles()
        processPdf(uri)
    }

    fun processPdf(uri: Uri, password: String? = null) {
        viewModelScope.launch {
            unlockPdfUseCase(uri, password).onSuccess { file ->
                originalUnlockedFile = file
                tempPdfFile = file
                isSaveVisible = true
                showPasswordDialog = false
                _uiEvents.emit(PdfUiEvent.ShowPdf(file))
            }.onFailure { e ->
                if (e.message?.contains("Password", ignoreCase = true) == true || e is SecurityException) {
                    isRetryPassword = password != null
                    showPasswordDialog = true
                } else {
                    _uiEvents.emit(PdfUiEvent.ShowError(e.message ?: "Unknown error"))
                }
            }
        }
    }

    fun onCompressQualitySliderChange(quality: Float) {
        compressionQuality = quality
    }

    fun startCompressionCalculation() {
        val quality = compressionQuality
        compressionJob?.cancel()
        val sourceFile = tempPdfFile ?: return
        
        isCompressing = true
        compressionJob = viewModelScope.launch {
            compressPdfUseCase(sourceFile, quality).onSuccess { file ->
                compressedPdfFile?.let { if (it.exists()) it.delete() }
                compressedPdfFile = file
                compressedFileSize = file.length()
                isCompressing = false
            }.onFailure { e ->
                if (e !is kotlinx.coroutines.CancellationException) {
                    _uiEvents.emit(PdfUiEvent.ShowError("Compression preview failed: ${e.message}"))
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
            viewModelScope.launch {
                _uiEvents.emit(PdfUiEvent.ShowPdf(newFile))
                _uiEvents.emit(PdfUiEvent.ActionSuccess("COMPRESS"))
            }
        }
        showCompressDialog = false
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
