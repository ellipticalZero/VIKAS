package com.example.vikas.ui.reader

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import org.json.JSONObject

private const val MENU_HIGHLIGHT = 7001

// Our helper script. Positions are character counts from the start of the chapter's text,
// so they do not change with font size or theme.
const val HIGHLIGHT_JS = """
(function () {
  if (window.vikas) return;
  function offsetOf(node, off) {
    var r = document.createRange();
    r.setStart(document.body, 0);
    r.setEnd(node, off);
    return r.toString().length;
  }
  function textNodes() {
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var out = [], n;
    while ((n = w.nextNode())) out.push(n);
    return out;
  }
  function clear() {
    var marks = document.querySelectorAll('mark[data-vk]');
    for (var i = 0; i < marks.length; i++) {
      var m = marks[i], p = m.parentNode;
      while (m.firstChild) p.insertBefore(m.firstChild, m);
      p.removeChild(m);
      p.normalize();
    }
  }
  function apply(h) {
    var nodes = textNodes(), pos = 0, hits = [];
    for (var i = 0; i < nodes.length; i++) {
      var len = nodes[i].length;
      var a = Math.max(h.s, pos), b = Math.min(h.e, pos + len);
      if (a < b) hits.push([nodes[i], a - pos, b - pos]);
      pos += len;
      if (pos >= h.e) break;
    }
    for (var j = 0; j < hits.length; j++) {
      var n = hits[j][0], a2 = hits[j][1], b2 = hits[j][2], mid = n;
      if (a2 > 0) mid = n.splitText(a2);
      if (b2 - a2 < mid.length) mid.splitText(b2 - a2);
      var m = document.createElement('mark');
      m.setAttribute('data-vk', h.id);
      m.style.setProperty('background-color', h.c, 'important');
      mid.parentNode.insertBefore(m, mid);
      m.appendChild(mid);
    }
  }
  window.vikas = {
    sync: function (list) { clear(); for (var i = 0; i < list.length; i++) apply(list[i]); },
    capture: function () {
      var sel = window.getSelection();
      if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
      var r = sel.getRangeAt(0);
      var s = offsetOf(r.startContainer, r.startOffset);
      var e = offsetOf(r.endContainer, r.endOffset);
      if (e < s) { var t = s; s = e; e = t; }
      return { s: s, e: e, t: sel.toString() };
    },
    hit: function (x, y) {
      var el = document.elementFromPoint(x, y);
      var m = (el && el.closest) ? el.closest('mark[data-vk]') : null;
      return m ? parseInt(m.getAttribute('data-vk'), 10) : -1;
    }
  };
})();
"""

class ReaderWebView(context: Context) : WebView(context) {

    var pageReady = false
    var onHighlightSelected: ((start: Int, end: Int, text: String) -> Unit)? = null

    var highlightsJson: String = "[]"
        private set

    fun runJs(script: String, callback: ((String) -> Unit)? = null) {
        evaluateJavascript(script) { callback?.invoke(it ?: "null") }
    }

    // Redraw highlights when the list changes
    fun syncHighlights(json: String) {
        if (json == highlightsJson) return
        highlightsJson = json
        if (pageReady) runJs("if (window.vikas) vikas.sync($json)")
    }

    // The chapter finished loading: add our helper and draw the highlights
    fun onPageLoaded() {
        pageReady = true
        runJs(HIGHLIGHT_JS)
        runJs("if (window.vikas) vikas.sync($highlightsJson)")
    }

    // Adds a "Highlight" button to the text-selection menu
    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        if (callback == null) return super.startActionMode(callback, type)
        val wrapped = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val created = callback.onCreateActionMode(mode, menu)
                menu.add(Menu.NONE, MENU_HIGHLIGHT, 0, "Highlight")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                return created
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
                callback.onPrepareActionMode(mode, menu)

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == MENU_HIGHLIGHT) {
                    // Read the selection first; closing the menu would clear it
                    runJs("window.vikas ? vikas.capture() : null") { result ->
                        try {
                            if (result != "null") {
                                val o = JSONObject(result)
                                onHighlightSelected?.invoke(o.getInt("s"), o.getInt("e"), o.getString("t"))
                            }
                        } catch (e: Exception) {
                            // selection could not be read: ignore
                        }
                        mode.finish()
                    }
                    return true
                }
                return callback.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode) = callback.onDestroyActionMode(mode)

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (callback is ActionMode.Callback2) callback.onGetContentRect(mode, view, outRect)
                else super.onGetContentRect(mode, view, outRect)
            }
        }
        return super.startActionMode(wrapped, type)
    }
}