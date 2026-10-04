package in_.weenja.hawidgets

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** `homebase://unlock?c=…`: a code link from the developer. No screen of its own; a short note, then gone. */
class UnlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent?.data?.getQueryParameter("c").orEmpty()
        if (Pro.redeem(this, code)) Toast.makeText(this, "Homebase Pro unlocked", Toast.LENGTH_LONG).show()
        finish()
    }
}
