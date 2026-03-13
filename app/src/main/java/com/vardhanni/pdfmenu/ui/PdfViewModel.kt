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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class PdfUiEvent {
    data class ShowPdf(val file: File) : PdfUiEvent()
    data class ShowError(val message: String) : PdfUiEvent()
    data class Toast(val message: String) : PdfUiEvent()
}

class PdfViewModel(
    private val repository: PdfRepository,
    private val unlockPdfUseCase: UnlockPdfUseCase,
    private val compressPdfUseCase: CompressPdfUseCase
) : ViewModel() {

    var currentPdfUri by mutableStateOf<Uri?>(null)
    var tempPdfFile by mutableStateOf<File?>(null)
    var compressedPdfFile by mutableStateOf<File?>(null)
    var isSaveVisible by mutableStateOf(false)
    var showPasswordDialog by mutableStateOf(false)
    var isRetryPassword by mutableStateOf(false)
    var showCompressDialog by mutableStateOf(false)
    var compressionQuality by mutableFloatStateOf(0.5f)
    var compressedFileSize by mutableLongStateOf(0L)

    private var compressionJob: Job? = null

    private val _uiEvents = MutableSharedFlow<PdfUiEvent>()
    val uiEvents = _uiEvents.asSharedFlow()

    fun onFilePicked(uri: Uri) {
        currentPdfUri = uri
        isSaveVisible = false
        processPdf(uri)
    }

    fun processPdf(uri: Uri, password: String? = null) {
        viewModelScope.launch {
            unlockPdfUseCase(uri, password).onSuccess { file ->
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

    fun onCompressQualityChanged(quality: Float) {
        compressionQuality = quality
        compressionJob?.cancel()
        val sourceFile = tempPdfFile ?: return
        
        compressionJob = viewModelScope.launch {
            // Debounce the slider input to avoid rapid OOM-prone tasks
            delay(500) 
            compressPdfUseCase(sourceFile, quality).onSuccess { file ->
                compressedPdfFile = file
                compressedFileSize = file.length()
            }.onFailure { e ->
                // Don't toast for cancelled jobs
                if (e !is kotlinx.coroutines.CancellationException) {
                    _uiEvents.emit(PdfUiEvent.ShowError("Compression preview failed: ${e.message}"))
                }
            }
        }
    }

    fun applyCompression() {
        compressedPdfFile?.let {
            tempPdfFile = it
            viewModelScope.launch {
                _uiEvents.emit(PdfUiEvent.ShowPdf(it))
            }
        }
        showCompressDialog = false
    }

    fun saveUnprotected(targetUri: Uri) {
        val sourceFile = tempPdfFile ?: return
        viewModelScope.launch {
            repository.savePdf(sourceFile, targetUri).onSuccess {
                _uiEvents.emit(PdfUiEvent.Toast("PDF saved successfully!"))
            }.onFailure { e ->
                _uiEvents.emit(PdfUiEvent.ShowError("Save failed: ${e.message}"))
            }
        }
    }

    class Factory(private val repository: PdfRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val unlockPdfUseCase = UnlockPdfUseCase(repository)
            val compressPdfUseCase = CompressPdfUseCase(repository)
            return PdfViewModel(repository, unlockPdfUseCase, compressPdfUseCase) as T
        }
    }
}
