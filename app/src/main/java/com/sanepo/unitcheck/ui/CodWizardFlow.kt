package com.sanepo.unitcheck.ui

import android.app.Activity
import android.content.Intent

object CodWizardFlow {
    const val EXTRA_WIZARD_STEP = "sanepo_wizard_step"

    private val steps: List<Class<out Activity>> = listOf(
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

    fun startWizard(host: Activity) {
        val intent = Intent(host, steps.first()).apply {
            putExtra(EXTRA_WIZARD_STEP, 0)
        }
        host.startActivity(intent)
    }

    fun isWizard(activity: Activity): Boolean {
        return activity.intent.getIntExtra(EXTRA_WIZARD_STEP, -1) >= 0
    }

    fun stepLabel(activity: Activity): String {
        val idx = activity.intent.getIntExtra(EXTRA_WIZARD_STEP, -1)
        if (idx < 0) return ""
        return "COD ${idx + 1}/${steps.size}"
    }

    fun advanceOrFinish(activity: Activity) {
        val idx = activity.intent.getIntExtra(EXTRA_WIZARD_STEP, -1)
        if (idx >= 0 && idx + 1 < steps.size) {
            val next = Intent(activity, steps[idx + 1]).apply {
                putExtra(EXTRA_WIZARD_STEP, idx + 1)
            }
            activity.startActivity(next)
        }
        activity.finish()
    }
}
