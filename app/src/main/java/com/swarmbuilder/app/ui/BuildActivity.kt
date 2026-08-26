package com.swarmbuilder.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swarmbuilder.app.R
import com.swarmbuilder.app.SwarmBuilderApp
import kotlinx.coroutines.launch
import java.io.File

/**
 * BuildActivity v3
 *
 * New buttons:
 * - 📥 Download APK (when build succeeds)
 * - 🚀 Push to GitHub (when build succeeds)
 * - 📂 View Source Files (browse generated code)
 */
class BuildActivity : AppCompatActivity() {

    private val viewModel: BuildViewModel by viewModels {
        BuildViewModelFactory(application)
    }

    private lateinit var promptInput: EditText
    private lateinit var buildButton: Button
    private lateinit var downloadApkButton: Button
    private lateinit var pushGitButton: Button
    private lateinit var viewSourceButton: Button
    private lateinit var phaseText: TextView
    private lateinit var logList: ListView
    private lateinit var agentStatusContainer: LinearLayout

    private val logAdapter = LogAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_build)

        bindViews()
        observeState()
    }

    private fun bindViews() {
        promptInput = findViewById(R.id.input_prompt)
        buildButton = findViewById(R.id.btn_build)
        downloadApkButton = findViewById(R.id.btn_download_apk)
        pushGitButton = findViewById(R.id.btn_push_git)
        viewSourceButton = findViewById(R.id.btn_view_source)
        phaseText = findViewById(R.id.text_phase)
        logList = findViewById(R.id.list_logs)
        agentStatusContainer = findViewById(R.id.container_agent_status)

        // Initially hide action buttons
        downloadApkButton.visibility = View.GONE
        pushGitButton.visibility = View.GONE
        viewSourceButton.visibility = View.GONE

        logList.adapter = logAdapter

        buildButton.setOnClickListener {
            val prompt = promptInput.text.toString().trim()
            if (prompt.isNotBlank()) {
                viewModel.start(prompt)
                buildButton.isEnabled = false
                buildButton.text = "⏳ Building..."
            } else {
                Toast.makeText(this, "Enter an app description first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe logs
                launch {
                    viewModel.logs.collect { log ->
                        logAdapter.addLog(log)
                    }
                }

                // Observe phase
                launch {
                    viewModel.phase.observe(this@BuildActivity) { phase ->
                        phaseText.text = phase
                    }
                }

                // Observe running state
                launch {
                    viewModel.isRunning.observe(this@BuildActivity) { running ->
                        buildButton.isEnabled = !running
                        buildButton.text = if (running) "⏳ Building..." else "🚀 Build My App"
                    }
                }

                // Observe APK path (show download button)
                launch {
                    viewModel.apkPath.observe(this@BuildActivity) { apkPath ->
                        if (apkPath != null) {
                            downloadApkButton.visibility = View.VISIBLE
                            downloadApkButton.text = "📥 Install APK"
                        }
                    }
                }

                // Observe GitHub URL (show push button)
                launch {
                    viewModel.githubUrl.observe(this@BuildActivity) { url ->
                        if (url != null) {
                            pushGitButton.visibility = View.VISIBLE
                            pushGitButton.text = "🌐 Open on GitHub"
                        }
                    }
                }

                // Observe source files (show view source button)
                launch {
                    viewModel.sourceFiles.observe(this@BuildActivity) { files ->
                        if (files != null && files.isNotEmpty()) {
                            viewSourceButton.visibility = View.VISIBLE
                        }
                    }
                }

                // Observe errors
                launch {
                    viewModel.errorMessage.observe(this@BuildActivity) { error ->
                        if (error != null) {
                            Toast.makeText(this@BuildActivity, error, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        // Download APK button
        downloadApkButton.setOnClickListener {
            val apkPath = viewModel.apkPath.value
            if (apkPath != null) {
                installApk(File(apkPath))
            }
        }

        // Push to Git button
        pushGitButton.setOnClickListener {
            val url = viewModel.githubUrl.value
            if (url != null) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } else {
                // Trigger push if not yet pushed
                val settings = (application as SwarmBuilderApp).userSettings
                if (settings.githubToken.isBlank() || settings.githubUsername.isBlank()) {
                    Toast.makeText(this, "Set GitHub credentials in Settings first", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else {
                    viewModel.pushToGitHub()
                    Toast.makeText(this, "Pushing to GitHub...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // View source button
        viewSourceButton.setOnClickListener {
            val files = viewModel.sourceFiles.value
            if (files != null) {
                val intent = Intent(this, SourceViewerActivity::class.java)
                // Pass files via a static holder (simpler than Parcelable for now)
                SourceViewerActivity.FILES = files
                startActivity(intent)
            }
        }
    }

    private fun installApk(apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(this, "APK file not found", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}
