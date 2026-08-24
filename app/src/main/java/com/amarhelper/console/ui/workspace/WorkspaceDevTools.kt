package com.amarhelper.console.ui.workspace

import android.content.Context
import android.webkit.WebResourceResponse
import com.amarhelper.console.core.log.AppLogger

/**
 * On-device developer tools for the embedded workspace.
 *
 * Eruda gives a console, network panel and DOM inspector inside the page itself, which
 * is the only way to inspect a WebView without a USB cable and a desktop browser.
 *
 * It is served from the app's assets rather than a CDN — a debugging tool that needs
 * the internet to load is useless for diagnosing a page that is failing to load — and
 * rather than being pushed through `evaluateJavascript`, which would mean moving half a
 * megabyte across a Binder transaction. A sentinel URL is intercepted instead and the
 * asset returned as the response, so the page fetches it like any other script.
 */
object WorkspaceDevTools {

    /** The reserved host Android documents for app-served content. */
    const val ERUDA_URL = "https://appassets.androidplatform.net/eruda.js"

    private const val TAG = "WorkspaceDevTools"
    private const val ASSET = "eruda.js"

    /** Serves the bundled script when the page requests the sentinel URL. */
    fun interceptOrNull(context: Context, url: String?): WebResourceResponse? {
        if (url != ERUDA_URL) return null
        return try {
            WebResourceResponse(
                "application/javascript",
                "utf-8",
                context.assets.open(ASSET),
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Developer tools asset unavailable", e)
            null
        }
    }

    /**
     * Loads the script and starts it, then reports back.
     *
     * The result is surfaced rather than assumed: an injection that silently fails looks
     * exactly like a page that has nothing to report.
     */
    val LOADER_SCRIPT: String = """
        (function () {
          if (window.eruda) { eruda.show(); return "already running"; }
          var script = document.createElement("script");
          script.src = "$ERUDA_URL";
          script.onload = function () {
            try {
              eruda.init();
              eruda.show();
            } catch (error) {
              console.error("eruda failed to start: " + error);
            }
          };
          script.onerror = function () {
            console.error("eruda script could not be loaded");
          };
          (document.head || document.documentElement).appendChild(script);
          return "loading";
        })();
    """
}
