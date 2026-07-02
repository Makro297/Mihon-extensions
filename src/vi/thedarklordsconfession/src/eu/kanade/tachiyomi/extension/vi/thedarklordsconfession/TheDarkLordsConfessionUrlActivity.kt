package eu.kanade.tachiyomi.extension.vi.thedarklordsconfession

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

// Xử lý deep link: https://thedarklordsconfession.io.vn/chapters/{id}
class TheDarkLordsConfessionUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size >= 2 && pathSegments[0] == "chapters") {
            val chapterId = pathSegments[1]
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.SEARCH"
                putExtra("query", "${TheDarkLordsConfession.PREFIX_ID_SEARCH}$chapterId")
                putExtra("filter", packageName)
            }
            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e("TDLCUrlActivity", e.toString())
            }
        } else {
            Log.e("TDLCUrlActivity", "Could not parse URI from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
