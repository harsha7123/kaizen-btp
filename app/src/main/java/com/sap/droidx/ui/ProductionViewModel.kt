package com.sap.droidx.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sap.droidx.data.ProductionOrder
import com.sap.droidx.data.ProductionRepository
import kotlinx.coroutines.launch

class ProductionViewModel : ViewModel() {

    private val repo = ProductionRepository()

    private val _order            = MutableLiveData<ProductionOrder?>()
    private val _sessionGrCount   = MutableLiveData(0)
    private val _sessionReceived  = MutableLiveData(0)
    private val _lastHuId         = MutableLiveData<String?>()
    private val _loading          = MutableLiveData(false)
    private val _error            = MutableLiveData<String?>()
    private val _message          = MutableLiveData<String?>()

    val order:           LiveData<ProductionOrder?> = _order
    val sessionGrCount:  LiveData<Int>              = _sessionGrCount
    val sessionReceived: LiveData<Int>              = _sessionReceived
    val lastHuId:        LiveData<String?>          = _lastHuId
    val loading:         LiveData<Boolean>          = _loading
    val error:           LiveData<String?>          = _error
    val message:         LiveData<String?>          = _message

    fun fetchOrder(orderId: String) {
        _loading.value = true
        _error.value   = null
        viewModelScope.launch {
            try {
                val o = repo.fetchOrder(orderId)
                _order.value           = o
                _sessionGrCount.value  = 0
                _sessionReceived.value = o.receivedQty
                _error.value           = null
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun doGR(operatorName: String) {
        val order = _order.value ?: return
        _loading.value = true
        viewModelScope.launch {
            try {
                val result = repo.createGR(order.orderId, order.palletQty, operatorName)
                _sessionGrCount.value  = (_sessionGrCount.value  ?: 0) + 1
                _sessionReceived.value = (_sessionReceived.value ?: 0) + order.palletQty
                _lastHuId.value        = result.huId
                _message.value         = "GR posted — HU: ${result.huId}"
            } catch (e: Exception) {
                _error.value = "GR failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun doGRAndShip(operatorName: String) {
        val order   = _order.value ?: return
        val grCount = _sessionGrCount.value ?: 0
        if (grCount == 0) { _error.value = "Post at least one GR before shipping"; return }
        _loading.value = true
        viewModelScope.launch {
            try {
                repo.createDelivery(order.orderId, order.door, order.shipTo, grCount, operatorName)
                _message.value = "Delivery created  ·  Door: ${order.door}  ·  Ship To: ${order.shipTo}"
            } catch (e: Exception) {
                _error.value = "Ship failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun reset() {
        _order.value           = null
        _sessionGrCount.value  = 0
        _sessionReceived.value = 0
        _lastHuId.value        = null
        _error.value           = null
        _message.value         = null
    }

    fun clearMessage() { _message.value = null }
    fun clearError()   { _error.value   = null }
}
