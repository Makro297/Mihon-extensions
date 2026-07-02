package eu.kanade.tachiyomi.extension.vi.sangchanhteam

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

// Activity để xử lý deep link từ browser:
// https://sangchanhteam.com/truyen/{slug}/
class SangChanhTeamUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size >= 2 && pathSegments[0] == "truyen") {
            val slug = pathSegments[1]
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.SEARCH"
                putExtra("query", "${SangChanhTeam.PREFIX_ID_SEARCH}$slug")
                putExtra("filter", packageName)
            }
            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e("SangChanhTeamUrlActivity", e.toString())
            }
        } else {
            Log.e("SangChanhTeamUrlActivity", "Could not parse URI from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
