package com.example.embyandroid

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()

    private lateinit var loginView: LinearLayout
    private lateinit var listViewContainer: LinearLayout
    private lateinit var playerViewContainer: LinearLayout
    private lateinit var serverInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var listTitle: TextView
    private lateinit var backButton: Button
    private lateinit var listView: ListView
    private lateinit var playerView: PlayerView

    private var server = ""
    private var token = ""
    private var userId = ""
    private var showingLibraries = true
    private val rowNames = ArrayList<String>()
    private val rowIds = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>

    private var player: ExoPlayer? = null
    private var playingItemId = ""
    private var triedFallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        loginView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        serverInput = EditText(this).apply {
            hint = "服务器地址，如 http://地址:8096"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        userInput = EditText(this).apply {
            hint = "用户名"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        passwordInput = EditText(this).apply {
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val loginButton = Button(this).apply {
            text = "登录"
            setOnClickListener { doLogin() }
        }
        loginView.addView(serverInput)
        loginView.addView(userInput)
        loginView.addView(passwordInput)
        loginView.addView(loginButton)

        listViewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        backButton = Button(this).apply {
            text = "返回"
            setOnClickListener {
                if (!showingLibraries) {
                    loadLibraries()
                } else {
                    showLogin()
                }
            }
        }
        listTitle = TextView(this).apply {
            text = "媒体库"
            textSize = 18f
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rowNames)
        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setOnItemClickListener { _, _, position, _ ->
                if (position < rowIds.size) {
                    if (showingLibraries) {
                        loadItems(rowIds[position])
                    } else {
                        playItem(rowIds[position])
                    }
                }
            }
        }
        listViewContainer.addView(backButton)
        listViewContainer.addView(listTitle)
        listViewContainer.addView(listView)

        playerViewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val playerButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val backFromPlayerButton = Button(this).apply {
            text = "返回"
            setOnClickListener {
                releasePlayer()
                showList()
            }
        }
        playerButtons.addView(backFromPlayerButton)
        playerView = PlayerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        playerViewContainer.addView(playerButtons)
        playerViewContainer.addView(playerView)

        root.addView(loginView)
        root.addView(listViewContainer)
        root.addView(playerViewContainer)
        setContentView(root)
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLogin() {
        loginView.visibility = View.VISIBLE
        listViewContainer.visibility = View.GONE
        playerViewContainer.visibility = View.GONE
    }

    private fun showList() {
        loginView.visibility = View.GONE
        listViewContainer.visibility = View.VISIBLE
        playerViewContainer.visibility = View.GONE
    }

    private fun showPlayer() {
        loginView.visibility = View.GONE
        listViewContainer.visibility = View.GONE
        playerViewContainer.visibility = View.VISIBLE
    }

    private fun doLogin() {
        server = serverInput.text.toString().trim().trimEnd('/')
        val username = userInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
            showToast("请填完整服务器、用户名和密码")
            return
        }
        val json = JSONObject()
        json.put("Username", username)
        json.put("Pw", password)
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$server/Users/AuthenticateByName")
            .header(
                "X-Emby-Authorization",
                "MediaBrowser Client=\"EmbyAndroid\", Device=\"Android\", " +
                    "DeviceId=\"emby-android-1\", Version=\"1.0\""
            )
            .post(body)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                showToast("登录失败：网络错误（${e.message}）")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyText = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        showToast("登录失败：HTTP ${it.code} ${it.message}")
                        return
                    }
                    try {
                        val obj = JSONObject(bodyText)
                        token = obj.getString("AccessToken")
                        userId = obj.getJSONObject("User").getString("Id")
                        runOnUiThread { showList() }
                        loadLibraries()
                    } catch (e: Exception) {
                        showToast("登录失败：解析错误（${e.message}）")
                    }
                }
            }
        })
    }

    private fun enqueueGet(url: String, onOk: (JSONObject) -> Unit) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                showToast("网络错误：${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyText = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        showToast("请求失败：HTTP ${it.code} ${it.message}")
                        return
                    }
                    try {
                        onOk(JSONObject(bodyText))
                    } catch (e: Exception) {
                        showToast("解析失败：${e.message}")
                    }
                }
            }
        })
    }

    private fun loadLibraries() {
        showingLibraries = true
        runOnUiThread { listTitle.text = "媒体库" }
        val url = "$server/Users/$userId/Views?api_key=$token"
        enqueueGet(url) { obj ->
            val items = obj.optJSONArray("Items")
            val names = ArrayList<String>()
            val ids = ArrayList<String>()
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    names.add(item.optString("Name"))
                    ids.add(item.optString("Id"))
                }
            }
            runOnUiThread {
                rowNames.clear()
                rowNames.addAll(names)
                rowIds.clear()
                rowIds.addAll(ids)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun loadItems(libraryId: String) {
        showingLibraries = false
        runOnUiThread { listTitle.text = "影片" }
        val url = "$server/Users/$userId/Items?ParentId=$libraryId&Recursive=true" +
            "&IncludeItemTypes=Movie,Episode&Fields=RunTimeTicks,MediaStreams&api_key=$token"
        enqueueGet(url) { obj ->
            val items = obj.optJSONArray("Items")
            val names = ArrayList<String>()
            val ids = ArrayList<String>()
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val name = item.optString("Name")
                    val display = if (item.has("ProductionYear")) {
                        "$name (${item.optInt("ProductionYear")})"
                    } else {
                        name
                    }
                    names.add(display)
                    ids.add(item.optString("Id"))
                }
            }
            runOnUiThread {
                rowNames.clear()
                rowNames.addAll(names)
                rowIds.clear()
                rowIds.addAll(ids)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun playItem(itemId: String) {
        playingItemId = itemId
        triedFallback = false
        showPlayer()
        startPlayback(directStreamUrl(itemId))
    }

    private fun directStreamUrl(itemId: String): String {
        return "$server/Videos/$itemId/stream?static=true&api_key=$token"
    }

    private fun hlsUrl(itemId: String): String {
        return "$server/Videos/$itemId/master.m3u8?api_key=$token"
    }

    private fun startPlayback(url: String) {
        releasePlayer()
        val newPlayer = ExoPlayer.Builder(this).build()
        player = newPlayer
        playerView.player = newPlayer
        newPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!triedFallback) {
                    triedFallback = true
                    newPlayer.setMediaItem(MediaItem.fromUri(hlsUrl(playingItemId)))
                    newPlayer.prepare()
                    newPlayer.play()
                } else {
                    showToast("播放失败：${error.message}")
                }
            }
        })
        newPlayer.setMediaItem(MediaItem.fromUri(url))
        newPlayer.prepare()
        newPlayer.play()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        playerView.player = null
    }
}
