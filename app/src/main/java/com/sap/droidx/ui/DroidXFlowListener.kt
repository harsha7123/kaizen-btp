package com.sap.droidx.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.sap.cloud.mobile.flows.compose.ext.FlowStateListener

class DroidXFlowListener(
    private val onDone: () -> Unit,
    private val onCancel: () -> Unit
) : FlowStateListener() {

    override suspend fun onFlowFinished(flowName: String?) {
        Handler(Looper.getMainLooper()).post { onDone() }
    }

    override suspend fun onFlowFinishedWithData(flowName: String?, data: Intent?) {
        Handler(Looper.getMainLooper()).post { onDone() }
    }
}

