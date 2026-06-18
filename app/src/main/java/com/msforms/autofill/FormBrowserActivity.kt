package com.msforms.autofill

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.widget.Toast
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.msforms.autofill.databinding.ActivityBrowserBinding
import org.json.JSONObject

@Suppress("SpellCheckingInspection")
class FormBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private var autoSubmitEnabled = false
    private var userName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        var formUrl = intent.getStringExtra("FORM_URL") ?: ""

        // Ensure we force English locale via URL parameter
        if (formUrl.contains("forms.office.com")) {
            val separator = if (formUrl.contains("?")) "&" else "?"
            formUrl = if (formUrl.contains("lang=")) {
                formUrl.replace(Regex("lang=[^&]*"), "lang=en-US")
            } else {
                "$formUrl${separator}lang=en-US"
            }
        }

        val sharedPref = getSharedPreferences("StaffPrefs", MODE_PRIVATE)
        val name    = sharedPref.getString("full_name",    "") ?: ""
        val staffId = sharedPref.getString("staff_number", "") ?: ""
        val company = sharedPref.getString("company",      "") ?: ""
        val dept    = sharedPref.getString("department",   "") ?: ""
        
        userName = name
        autoSubmitEnabled = sharedPref.getBoolean("auto_submit", false)

        setupWebView(name, staffId, company, dept)

        binding.fabAutofill.setOnClickListener {
            Toast.makeText(this, "Retrying Autofill...", Toast.LENGTH_SHORT).show()
            injectAutofillScript(binding.webView, name, staffId, company, dept)
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.webView.loadUrl(formUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(name: String, staffId: String, company: String, dept: String) {
        with(binding.webView.settings) {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            databaseEnabled      = true
            useWideViewPort      = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls  = true
            displayZoomControls  = false
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("WebViewConsole", "${consoleMessage?.message()}")
                return true
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress == 100) {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAutofillScript(view, name, staffId, company, dept)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
        }
        
        binding.webView.addJavascriptInterface(WebAppInterface(), "Android")
    }

    inner class WebAppInterface {
        @android.webkit.JavascriptInterface
        fun onFormSubmitted() {
            runOnUiThread {
                showSuccessDialog()
            }
        }
    }

    private fun showSuccessDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_success, null)
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Make background transparent so the rounded corners of bg_rounded_card show correctly
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvMessage = dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage)
        val btnHome = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBackHome)
        val btnExit = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnExitApp)

        tvMessage.text = getString(R.string.submission_success_msg, userName)

        btnHome.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }

        btnExit.setOnClickListener {
            finishAffinity()
        }

        dialog.show()
    }

    private fun injectAutofillScript(view: WebView?, name: String, staffId: String, company: String, dept: String) {
        val nameEsc = JSONObject.quote(name)
        val staffEsc = JSONObject.quote(staffId)
        
        val js = """
            (function() {
                'use strict';

                var NAME  = $nameEsc;
                var STAFF = $staffEsc;
                var AUTO_SUBMIT = $autoSubmitEnabled;

                // ── React-compatible value setter ──────────────────────────────
                function setReactValue(input, val) {
                    var proto = input.tagName === 'TEXTAREA'
                        ? window.HTMLTextAreaElement.prototype
                        : window.HTMLInputElement.prototype;
                    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                    if (desc && desc.set) {
                        desc.set.call(input, val);
                    } else {
                        input.value = val;
                    }
                    ['focus', 'input', 'change', 'blur'].forEach(function(n) {
                        input.dispatchEvent(new Event(n, { bubbles: true, cancelable: true }));
                    });
                }

                function fillInput(el, val) {
                    if (!el || !val) return;
                    if ((el.value || '').trim() === val.trim()) return;
                    setReactValue(el, val);
                    console.log('[AF] Filled: ' + val);
                }

                // ── Fire full event sequence to open a Fluent UI dropdown ────────────────
                function fireOpenEvents(el) {
                    var rect = el.getBoundingClientRect();
                    var cx = Math.round(rect.left + rect.width / 2);
                    var cy = Math.round(rect.top  + rect.height / 2);
                    var base = { bubbles: true, cancelable: true, clientX: cx, clientY: cy };

                    ['pointerover','pointerenter','mouseover','mouseenter',
                     'pointermove','mousemove',
                     'pointerdown','mousedown',
                     'pointerup','mouseup','click'].forEach(function(evtName) {
                        try {
                            var Ctor = evtName.startsWith('pointer') ? PointerEvent : MouseEvent;
                            el.dispatchEvent(new Ctor(evtName, Object.assign({ pointerId: 1 }, base)));
                        } catch(e) {
                            try { el.dispatchEvent(new MouseEvent(evtName, base)); } catch(e2) {}
                        }
                    });
                    try { el.click(); } catch(e) {}
                }

                // ── Click with TouchEvent + Pointer + Mouse (all three for Android WebView) ─
                function clickOption(optEl) {
                    var rect = optEl.getBoundingClientRect();
                    var cx = Math.round(rect.left + rect.width / 2);
                    var cy = Math.round(rect.top  + rect.height / 2);
                    var base = { bubbles: true, cancelable: true, clientX: cx, clientY: cy };

                    // 1. TouchEvent — required for React on Android WebView
                    try {
                        var touch = new Touch({
                            identifier: Date.now(), target: optEl,
                            clientX: cx, clientY: cy, pageX: cx, pageY: cy,
                            screenX: cx, screenY: cy, radiusX: 1, radiusY: 1, rotationAngle: 0, force: 1
                        });
                        optEl.dispatchEvent(new TouchEvent('touchstart',
                            { bubbles:true, cancelable:true, touches:[touch], changedTouches:[touch] }));
                        optEl.dispatchEvent(new TouchEvent('touchend',
                            { bubbles:true, cancelable:true, touches:[], changedTouches:[touch] }));
                    } catch(e) {}

                    // 2. Pointer + Mouse events
                    ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(evtName) {
                        try {
                            var Ctor = evtName.startsWith('pointer') ? PointerEvent : MouseEvent;
                            el.dispatchEvent(new Ctor(evtName, Object.assign({ pointerId: 1 }, base)));
                        } catch(e) {
                            try { el.dispatchEvent(new MouseEvent(evtName, base)); } catch(e2) {}
                        }
                    });

                    // 3. Direct .click()
                    try { optEl.click(); } catch(e) {}

                    console.log('[AF] Clicked: "' + (optEl.innerText || optEl.textContent || '').trim().substring(0,40) + '"');
                }

                // ── Find the FIRST visible option in the currently-open dropdown ──────────
                // No text matching — just find the topmost visible option element.
                function findFirstOption() {
                    // Strategy A: ARIA-annotated option roles
                    var ariaOpts = Array.from(document.querySelectorAll(
                        '[role="option"], [role="listbox"] > *, [role="listbox"] li, ' +
                        '[role="listbox"] div, [role="listbox"] span, ' +
                        '.ms-Dropdown-item, [data-automationid*="DropdownItem"], ' +
                        '[class*="dropdownItem"], [class*="DropdownItem"]'
                    )).filter(function(el) {
                        var r = el.getBoundingClientRect();
                        return r.width > 0 && r.height > 0 && r.height < 120;
                    });

                    if (ariaOpts.length > 0) {
                        ariaOpts.sort(function(a, b) {
                            return a.getBoundingClientRect().top - b.getBoundingClientRect().top;
                        });
                        console.log('[AF] First option (ARIA): "' +
                            (ariaOpts[0].innerText || ariaOpts[0].textContent || '').trim() + '"');
                        return ariaOpts[0];
                    }

                    // Strategy B: Any visible <li> on the page (options often render as li elements)
                    var liOpts = Array.from(document.querySelectorAll('li')).filter(function(el) {
                        var r = el.getBoundingClientRect();
                        return r.width > 0 && r.height > 0 && r.height < 100;
                    });

                    if (liOpts.length > 0) {
                        liOpts.sort(function(a, b) {
                            return a.getBoundingClientRect().top - b.getBoundingClientRect().top;
                        });
                        console.log('[AF] First option (li): "' +
                            (liOpts[0].innerText || liOpts[0].textContent || '').trim() + '"');
                        return liOpts[0];
                    }

                    console.warn('[AF] findFirstOption: no candidates found');
                    return null;
                }

                // ── Get dropdown trigger buttons inside form question containers ──────────
                function getDropdownTriggers() {
                    var all = Array.from(document.querySelectorAll(
                        '[role="combobox"], [aria-haspopup="listbox"], ' +
                        '[aria-haspopup="true"], button[aria-expanded]'
                    )).filter(function(el) {
                        return el.offsetParent !== null &&
                               !el.disabled &&
                               el.type !== 'submit' &&
                               el.type !== 'reset';
                    });

                    // Prefer: only elements inside a form question block (excludes language picker)
                    var inQ = all.filter(function(el) {
                        return el.closest(
                            '[class*="question"], [class*="Question"], ' +
                            '[data-automation-id*="question"], .office-form-question, ' +
                            '[class*="QuestionContent"], div[data-unique-id]'
                        ) !== null;
                    });
                    if (inQ.length > 0) {
                        console.log('[AF] Triggers (question-scoped): ' + inQ.length);
                        return inQ;
                    }

                    // Fallback: exclude elements in top 15% of page (language picker area)
                    var pageH = document.documentElement.scrollHeight || window.innerHeight;
                    var threshold = pageH * 0.15;
                    var byPos = all.filter(function(el) {
                        return (el.getBoundingClientRect().top + window.scrollY) > threshold;
                    });
                    console.log('[AF] Triggers (position-scoped): ' + byPos.length);
                    return byPos;
                }

                // ── Open a dropdown and click its FIRST option, with polling ───────────────
                function selectOptionPolled(trigger, index) {
                    if (!trigger) return;
                    var cur = (trigger.innerText || trigger.textContent || '').trim();
                    var placeholder = cur.toLowerCase();
                    if (cur && placeholder !== 'select your answer' && !placeholder.includes('選擇') && !placeholder.includes('loading')) {
                        console.log('[AF] Dropdown ' + index + ' already has value: "' + cur + '"');
                        return; // already filled
                    }

                    if (window.__af_processing_drop && window.__af_processing_drop[index]) return;
                    if (!window.__af_processing_drop) window.__af_processing_drop = {};
                    window.__af_processing_drop[index] = true;

                    console.log('[AF] Opening dropdown ' + index);
                    fireOpenEvents(trigger);

                    var attempts = 0;
                    var checkInterval = setInterval(function() {
                        attempts++;
                        var opt = findFirstOption();
                        if (opt) {
                            clearInterval(checkInterval);
                            clickOption(opt);
                            
                            // Mark as clicked immediately for instant handover
                            if (!window.__af_clicked_indices) window.__af_clicked_indices = {};
                            window.__af_clicked_indices[index] = true;

                            setTimeout(function() { 
                                window.__af_processing_drop[index] = false; 
                                if (typeof tryFill === 'function') tryFill(); 
                            }, 50); 
                        } else {
                            console.warn('[AF] No first option found, attempt ' + attempts);
                            if (attempts >= 15) { 
                                clearInterval(checkInterval);
                                try {
                                    document.dispatchEvent(new KeyboardEvent('keydown',
                                        { key: 'Escape', bubbles: true }));
                                } catch(e) {}
                                try { trigger.click(); } catch(e) {} 
                                setTimeout(function() { 
                                    window.__af_processing_drop[index] = false; 
                                    if (typeof tryFill === 'function') tryFill();
                                }, 50);
                            }
                        }
                    }, 150);
                }

                // ── Main fill function ────────────────────────────────────────────────────
                function run() {
                    if (window.__af_finished) return;

                    // Text inputs: Q1 Name, Q2 Staff Number
                    var textInputs = Array.from(document.querySelectorAll(
                        'input[type="text"], input:not([type]), textarea'
                    )).filter(function(el) {
                        return el.offsetParent !== null &&
                               el.type !== 'search' &&
                               el.type !== 'hidden' &&
                               !el.readOnly &&
                               !el.disabled;
                    });

                    if (textInputs.length >= 1) fillInput(textInputs[0], NAME);
                    if (textInputs.length >= 2) fillInput(textInputs[1], STAFF);

                    var drops = getDropdownTriggers();
                    
                    if (drops.length >= 1) {
                        var d1 = (drops[0].innerText || drops[0].textContent || '').trim().toLowerCase();
                        var d1HasValue = (d1 && d1 !== 'select your answer' && !d1.includes('選擇') && !d1.includes('loading')) || 
                                         (window.__af_clicked_indices && window.__af_clicked_indices[0]);
                        
                        if (!d1HasValue) {
                            selectOptionPolled(drops[0], 0);
                        } else {
                            if (drops.length >= 2) {
                                var d2 = (drops[1].innerText || drops[1].textContent || '').trim().toLowerCase();
                                var d2HasValue = (d2 && d2 !== 'select your answer' && !d2.includes('選擇') && !d2.includes('loading')) ||
                                                 (window.__af_clicked_indices && window.__af_clicked_indices[1]);
                                
                                if (!d2HasValue) {
                                    selectOptionPolled(drops[1], 1);
                                }
                            }
                        }
                    }

                    var t1ok = textInputs.length >= 1 && textInputs[0].value.trim() === NAME.trim();
                    var t2ok = textInputs.length >= 2 && textInputs[1].value.trim() === STAFF.trim();
                    
                    var d0OkReal = drops.length >= 1 && (drops[0].innerText || drops[0].textContent || '').trim().toLowerCase() !== 'select your answer' && !drops[0].innerText.includes('選擇');
                    var d1OkReal = drops.length >= 2 && (drops[1].innerText || drops[1].textContent || '').trim().toLowerCase() !== 'select your answer' && !drops[1].innerText.includes('選擇');

                    var dropsProcessing = window.__af_processing_drop && (window.__af_processing_drop[0] || window.__af_processing_drop[1]);

                    if (t1ok && t2ok && d0OkReal && d1OkReal && !dropsProcessing) {
                        window.__af_finished = true;
                        
                        if (AUTO_SUBMIT) {
                            var submitBtn = document.querySelector('button[type="submit"], [data-automation-id="submitButton"]');
                            if (submitBtn) {
                                console.log('[AF] Auto-submitting...');
                                submitBtn.click();
                            }
                        }

                        console.log('[AF] All 4 fields confirmed filled in UI. Scrolling...');
                        setTimeout(function() {
                            var submitBtn = document.querySelector('button[type="submit"], [data-automation-id="submitButton"]');
                            if (submitBtn) {
                                submitBtn.scrollIntoView({ behavior: 'smooth', block: 'end' });
                            } else {
                                window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
                            }
                        }, 600); 
                        return true;
                    }
                    return false;
                }

                function checkSuccess() {
                    var successText = [
                        'Your response was submitted',
                        '感謝',
                        'Submitted',
                        '提交'
                    ];
                    
                    var bodyText = document.body.innerText || "";
                    for (var i = 0; i < successText.length; i++) {
                        if (bodyText.includes(successText[i])) {
                            console.log('[AF] Success detected!');
                            if (window.Android && window.Android.onFormSubmitted) {
                                window.Android.onFormSubmitted();
                                return true;
                            }
                        }
                    }
                    return false;
                }

                if (window.__af_success_poll) clearInterval(window.__af_success_poll);
                window.__af_success_poll = setInterval(function() {
                    if (checkSuccess()) {
                        clearInterval(window.__af_success_poll);
                    }
                }, 1000);

                if (window.__af_observer) window.__af_observer.disconnect();
                if (window.__af_poll) clearInterval(window.__af_poll);

                var attempts    = 0;
                var maxAttempts = 20;

                window.__af_clicked_indices = {};

                function tryFill() {
                    attempts++;
                    console.log('[AF] tryFill #' + attempts);
                    if (run()) {
                        console.log('[AF] All fields confirmed filled.');
                        window.__af_textsDone = true;
                    }
                    if (attempts >= maxAttempts) {
                        if (window.__af_observer) window.__af_observer.disconnect();
                        console.warn('[AF] Max attempts reached.');
                    }
                }

                window.__af_textsDone = false;
                window.__af_observer = new MutationObserver(function() {
                    if (!window.__af_textsDone) tryFill();
                });
                window.__af_observer.observe(document.documentElement, { childList: true, subtree: true });

                window.__af_poll = setInterval(function() {
                    if (window.__af_textsDone && attempts > 3) { clearInterval(window.__af_poll); return; }
                    tryFill();
                }, 2000);

                setTimeout(function() {
                    if (window.__af_observer) window.__af_observer.disconnect();
                    if (window.__af_poll) clearInterval(window.__af_poll);
                    console.log('[AF] Autofill stopped after 40 s.');
                }, 40000);

                tryFill();
                console.log('[AF] Script started. name=' + NAME + ' staff=' + STAFF);
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}
