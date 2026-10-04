package in_.weenja.hawidgets

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import in_.weenja.hawidgets.ha.Api
import in_.weenja.hawidgets.widgets.Refresh
import kotlinx.coroutines.launch

/**
 * Standard HA OAuth login in a WebView. HA sends the browser to <base>/hawidgets/callback?code=…,
 * which we intercept and swap for a refresh token. Nothing but the HA login page is shown.
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var api: Api
    private var base = ""
    private var redirect = ""
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        web = findViewById(R.id.web); progress = findViewById(R.id.progress); status = findViewById(R.id.status)
        api = Api(this)
        base = intent.getStringExtra("base") ?: api.prefs.baseUrl
        redirect = api.redirectUriFor(base)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = intercept(request.url)
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { progress.visibility = View.VISIBLE }
            override fun onPageFinished(view: WebView, url: String) { progress.visibility = View.GONE }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) status.text = "Cannot reach $base — ${error.description}. Check the URL and that this phone can reach Home Assistant."
            }
        }
        status.text = "Log in to Home Assistant at $base"
        web.loadUrl(api.authorizeUrl(base))
    }

    private fun intercept(url: Uri): Boolean {
        if (!url.toString().startsWith(redirect)) return false
        if (done) return true
        done = true
        val code = url.getQueryParameter("code")
        if (code == null) { status.text = "Login was cancelled"; done = false; return true }
        web.visibility = View.INVISIBLE
        status.text = "Connecting…"
        lifecycleScope.launch {
            try {
                api.exchangeCode(base, code)
                Refresh.fetch(this@LoginActivity)
                Refresh.all(this@LoginActivity, fetch = false)
                Toast.makeText(this@LoginActivity, "Connected to Home Assistant", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK); finish()
            } catch (e: Exception) {
                status.text = "Login failed: ${e.message}"
                web.visibility = View.VISIBLE; done = false
            }
        }
        return true
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
