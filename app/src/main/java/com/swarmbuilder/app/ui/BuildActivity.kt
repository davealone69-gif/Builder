package com.swarmbuilder.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.swarmbuilder.app.R
import com.swarmbuilder.app.databinding.ActivityBuildBinding
import com.swarmbuilder.app.models.LogLevel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.File

class BuildActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROMPT = "extra_prompt"
    }

    private lateinit var binding: ActivityBuildBinding
    private val viewModel: BuildViewModel by viewModels {
        BuildViewModelFactory(application)
    }
    private val logAdapter = LogAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuildBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_building)

        binding.rvLogs.apply {
            layoutManager = LinearLayoutManager(this@BuildActivity)
            adapter = logAdapter
        }

        observeViewModel()

        val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: ""
        if (savedInstanceState == null) {
            viewModel.start(prompt)
        }
    }

    private fun observeViewModel() {
        viewModel.logs.onEach { log ->
            logAdapter.add(log)
            binding.rvLogs.scrollToPosition(logAdapter.itemCount - 1)
        }.launchIn(lifecycleScope)

        viewModel.phase.observe(this) { phase ->
            binding.tvPhase.text = phase
        }

        viewModel.isRunning.observe(this) { running ->
            binding.progressBar.visibility = if (running) View.VISIBLE else View.GONE
            binding.btnInstall.isEnabled = !running
            binding.btnOpenGithub.isEnabled = !running
        }

        viewModel.apkPath.observe(this) { path ->
            binding.btnInstall.visibility = if (path != null) View.VISIBLE else View.GONE
            binding.btnInstall.setOnClickListener { installApk(path!!) }
        }

        viewModel.githubUrl.observe(this) { url ->
            binding.btnOpenGithub.visibility = if (url != null) View.VISIBLE else View.GONE
            binding.btnOpenGithub.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }

        viewModel.errorMessage.observe(this) { err ->
            if (err != null) {
                binding.tvError.visibility = View.VISIBLE
                binding.tvError.text = err
            } else {
                binding.tvError.visibility = View.GONE
            }
        }
    }

    private fun installApk(path: String) {
        val file = File(path)
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
