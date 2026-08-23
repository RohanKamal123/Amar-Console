/*
 * Applies the Claude-style stylesheet to an embedded workspace page.
 *
 * The OpenHands frontend is a single-page app: it swaps its DOM without a page load, so
 * a one-shot injection on onPageFinished is not enough. This re-asserts the style tag
 * whenever the document changes, and is idempotent — repeated calls are cheap and do not
 * stack up style tags or observers.
 *
 * The Kotlin side substitutes __CLAUDE_CSS__ with the stylesheet before evaluating this.
 */
(function () {
  var STYLE_ID = "claude-workspace-style";
  var MARKER = "data-claude-style";

  function applyViewport() {
    var viewport = document.querySelector('meta[name="viewport"]');
    if (!viewport) {
      viewport = document.createElement("meta");
      viewport.setAttribute("name", "viewport");
      document.head.appendChild(viewport);
    }
    // viewport-fit=cover lets the composer sit against the gesture bar and use
    // env(safe-area-inset-bottom) for its padding.
    viewport.setAttribute(
      "content",
      "width=device-width, initial-scale=1, viewport-fit=cover"
    );
  }

  function applyStyle() {
    if (!document.head) return false;
    var existing = document.getElementById(STYLE_ID);
    if (!existing) {
      var style = document.createElement("style");
      style.id = STYLE_ID;
      style.textContent = __CLAUDE_CSS__;
      document.head.appendChild(style);
    }
    // The marker scopes every rule, so removing it reverts the page to stock styling.
    document.documentElement.setAttribute(MARKER, "1");
    return true;
  }

  function start() {
    applyViewport();
    applyStyle();

    if (window.__claudeWorkspaceObserver) return;
    var observer = new MutationObserver(function () {
      // Re-assert after client-side navigation replaces <head> or the root element.
      if (!document.getElementById(STYLE_ID) ||
          !document.documentElement.hasAttribute(MARKER)) {
        applyStyle();
      }
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
    window.__claudeWorkspaceObserver = observer;
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start, { once: true });
  } else {
    start();
  }
})();
