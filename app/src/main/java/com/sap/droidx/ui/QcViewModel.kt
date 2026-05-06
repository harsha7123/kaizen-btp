package com.sap.droidx.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sap.droidx.data.QcItem
import com.sap.droidx.data.QcRepository
import kotlinx.coroutines.launch

class QcViewModel : ViewModel() {

    private val repo = QcRepository()

    private val _item    = MutableLiveData<QcItem?>()
    private val _loading = MutableLiveData(false)
    private val _error   = MutableLiveData<String?>()
    private val _message = MutableLiveData<String?>()

    val item:    LiveData<QcItem?>  = _item
    val loading: LiveData<Boolean>  = _loading
    val error:   LiveData<String?>  = _error
    val message: LiveData<String?>  = _message

    fun fetchItem(sourceId: String, sourceType: String) {
        _loading.value = true
        _error.value   = null
        viewModelScope.launch {
            try {
                _item.value  = repo.fetchItem(sourceId, sourceType)
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun decide(status: String, operatorName: String) {
        val item = _item.value ?: return
        _loading.value = true
        viewModelScope.launch {
            try {
                repo.updateStatus(item.itemId, status, operatorName)
                _item.value    = item.copy(status = status)
                _message.value = if (status == "APPROVED") "Item approved" else "Item rejected"
            } catch (e: Exception) {
                _error.value = "Failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun reset() {
        _item.value    = null
        _error.value   = null
        _message.value = null
    }

    fun clearMessage() { _message.value = null }
    fun clearError()   { _error.value   = null }
}
