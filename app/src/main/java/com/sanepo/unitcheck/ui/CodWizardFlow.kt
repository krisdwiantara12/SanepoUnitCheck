package com.sanepo.unitcheck.ui

import android.app.Activity
import android.content.Intent

object CodWizardFlow {
    const val EXTRA_WIZARD_STEP = "sanepo_wizard_step"
    const val EXTRA_EXPRESS_MODE = "sanepo_cod_express"

    private val fullSteps: List<Class<out Activity>> = listOf(
        DisplayTestActivity::class.java,
        TouchTestActivity::class.java,
        BatteryDetailActivity::class.java,
        AudioTestActivity::class.java,
        CameraTestActivity::class.java,
        SensorTestActivity::class.java,
        ConnectivityTestActivity::class.java,
        ButtonPortTestActivity::class.java,
        CodPreBuyActivity::class.java,
        ReportActivity::class.java
    )

    // 90-second Rapid COD Express flow targeting highest-risk second-hand smartphone defects.
    private val expressSteps: List<Class<out Activity>> = listOf(
        DisplayTestActivity::class.java,
        TouchTestActivity::class.java,
        AudioTestActivity::class.java,
        CameraTestActivity::class.java,
        ConnectivityTestActivity::class.java,
        CodPreBuyActivity::class.java,
        ReportActivity::class.java
    )

    fun startWizard(host: Activity, express: Boolean = false) {
        val list = if (express) expressSteps else fullSteps
        val intent = Intent(host, list.first()).apply {
            putExtra(EXTRA_WIZARD_STEP, 0)
            putExtra(EXTRA_EXPRESS_MODE, express)
        }
        host.startActivity(intent)
    }

    fun isWizard(activity: Activity): Boolean {
        return activity.intent.getIntExtra(EXTRA_WIZARD_STEP, -1) >= 0
    }

    fun isExpress(activity: Activity): Boolean {
        return activity.intent.getBooleanExtra(EXTRA_EXPRESS_MODE, false)
    }

    fun stepLabel(activity: Activity): String {
        val idx = activity.intent.getIntExtra(EXTRA_WIZARD_STEP, -1)
        if (idx < 0) return ""
        val express = isExpress(activity)
        val total = if (express) expressSteps.size else fullSteps.size
        val tag = if (express) "KILAT 90s" else "COD FULL"
        return "$tag ${idx + 1}/$total"
    }

    fun advanceOrFinish(activity: Activity) {
        val idx = activity.intent.getIntExtra(EXTRA_WIZARD_STEP, -1)
        val express = isExpress(activity)
        val list = if (express) expressSteps else fullSteps
        if (idx >= 0 && idx + 1 < list.size) {
            val next = Intent(activity, list[idx + 1]).apply {
                putExtra(EXTRA_WIZARD_STEP, idx + 1)
                putExtra(EXTRA_EXPRESS_MODE, express)
            }
            activity.startActivity(next)
        }
        activity.finish()
    }
}
