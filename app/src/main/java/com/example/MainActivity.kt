package com.example

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val dataIntent = result.data
            if (dataIntent != null) {
                val results = if (dataIntent.data != null) {
                    arrayOf(dataIntent.data!!)
                } else if (dataIntent.clipData != null) {
                    val clipData = dataIntent.clipData!!
                    val list = mutableListOf<Uri>()
                    for (i in 0 until clipData.itemCount) {
                        list.add(clipData.getItemAt(i).uri)
                    }
                    list.toTypedArray()
                } else {
                    null
                }
                filePathCallback?.onReceiveValue(results)
            } else {
                filePathCallback?.onReceiveValue(null)
            }
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        context = this,
                        onShowFileChooser = { callback, params ->
                            filePathCallback?.onReceiveValue(null)
                            filePathCallback = callback
                            try {
                                val intent = params.createIntent()
                                fileChooserLauncher.launch(intent)
                                true
                            } catch (e: Exception) {
                                filePathCallback?.onReceiveValue(null)
                                filePathCallback = null
                                false
                            }
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun WebViewScreen(
    context: Context,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                
                // Configure WebView settings
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                
                // Add JS Bridge
                addJavascriptInterface(WebAppInterface(ctx), "AndroidBridge")
                
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        if (url.startsWith("whatsapp://") || url.contains("wa.me") || url.contains("api.whatsapp.com")) {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                ctx.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(ctx, "WhatsApp is not installed on this device", Toast.LENGTH_LONG).show()
                                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                try {
                                    ctx.startActivity(webIntent)
                                } catch (ex: Exception) {
                                    Log.e("WebView", "Could not start web browser", ex)
                                }
                            }
                            return true
                        }
                        
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            return false
                        }
                        
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            ctx.startActivity(intent)
                            return true
                        } catch (e: Exception) {
                            return false
                        }
                    }
                }
                
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        if (filePathCallback == null || fileChooserParams == null) return false
                        return onShowFileChooser(filePathCallback, fileChooserParams)
                    }
                    
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                        Log.d("WebViewConsole", "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                        return true
                    }

                    override fun onJsAlert(
                        view: WebView?,
                        url: String?,
                        message: String?,
                        result: JsResult?
                    ): Boolean {
                        android.app.AlertDialog.Builder(ctx)
                            .setMessage(message)
                            .setPositiveButton("OK") { _, _ -> result?.confirm() }
                            .setOnCancelListener { result?.cancel() }
                            .show()
                        return true
                    }
                }
                
                loadUrl("file:///android_asset/index.html")
            }
        },
        modifier = modifier.fillMaxSize()
    )
}

class WebAppInterface(private val mContext: Context) {

    @JavascriptInterface
    fun writeFile(filename: String, base64Data: String, directory: String): String {
        Log.d("WebAppInterface", "writeFile: filename=$filename, directory=$directory")
        return try {
            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
            
            val dir = mContext.cacheDir
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, filename)
            FileOutputStream(file).use { fos ->
                fos.write(decodedBytes)
            }
            
            val contentUri = FileProvider.getUriForFile(
                mContext,
                "${mContext.packageName}.fileprovider",
                file
            )
            
            val response = JSONObject()
            response.put("uri", contentUri.toString())
            Log.d("WebAppInterface", "writeFile success. uri=$contentUri")
            response.toString()
        } catch (e: Exception) {
            Log.e("WebAppInterface", "Error writing file", e)
            val response = JSONObject()
            response.put("uri", "")
            response.toString()
        }
    }

    @JavascriptInterface
    fun share(title: String, text: String, url: String, dialogTitle: String) {
        Log.d("WebAppInterface", "share: title=$title, text=$text, url=$url")
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
                
                if (url.isNotEmpty()) {
                    val fileUri = Uri.parse(url)
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            
            val chooser = Intent.createChooser(shareIntent, dialogTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            mContext.startActivity(chooser)
        } catch (e: Exception) {
            Log.e("WebAppInterface", "Error sharing", e)
            Toast.makeText(mContext, "Error sharing file", Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun saveApiKey(key: String): Boolean {
        Log.d("WebAppInterface", "saveApiKey called")
        return try {
            val encrypted = SecureStorage.encrypt(key)
            val prefs = mContext.getSharedPreferences("rasm_secure_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("google_ai_studio_api_key", encrypted).commit()
            true
        } catch (e: Exception) {
            Log.e("WebAppInterface", "saveApiKey error", e)
            false
        }
    }

    @JavascriptInterface
    fun getApiKey(): String {
        Log.d("WebAppInterface", "getApiKey called")
        return try {
            val prefs = mContext.getSharedPreferences("rasm_secure_prefs", Context.MODE_PRIVATE)
            val encrypted = prefs.getString("google_ai_studio_api_key", null) ?: return ""
            SecureStorage.decrypt(encrypted)
        } catch (e: Exception) {
            Log.e("WebAppInterface", "getApiKey error", e)
            ""
        }
    }

    @JavascriptInterface
    fun removeApiKey(): Boolean {
        Log.d("WebAppInterface", "removeApiKey called")
        return try {
            val prefs = mContext.getSharedPreferences("rasm_secure_prefs", Context.MODE_PRIVATE)
            prefs.edit().remove("google_ai_studio_api_key").commit()
            true
        } catch (e: Exception) {
            Log.e("WebAppInterface", "removeApiKey error", e)
            false
        }
    }
}

object SecureStorage {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "RASM_Secure_API_Key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val key = keyStore.getKey(ALIAS, null) as? SecretKey
        if (key != null) return key

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        val combined = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
        return Base64.encodeToString(combined, Base64.DEFAULT)
    }

    fun decrypt(encryptedBase64: String): String {
        if (encryptedBase64.isEmpty()) return ""
        val combined = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val iv = ByteArray(12)
        if (combined.size < 12) return ""
        System.arraycopy(combined, 0, iv, 0, 12)
        
        val encryptedBytes = ByteArray(combined.size - 12)
        System.arraycopy(combined, 12, encryptedBytes, 0, encryptedBytes.size)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}
