package com.swarmbuilder.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.swarmbuilder.app.R
import com.swarmbuilder.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.btnBuild.setOnClickListener {
            val prompt = binding.etPrompt.text?.toString()?.trim() ?: ""
            if (prompt.isBlank()) {
                binding.tilPrompt.error = getString(R.string.error_prompt_empty)
                return@setOnClickListener
            }
            binding.tilPrompt.error = null
            startBuildActivity(prompt)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startBuildActivity(prompt: String) {
        val intent = Intent(this, BuildActivity::class.java).apply {
            putExtra(BuildActivity.EXTRA_PROMPT, prompt)
        }
        startActivity(intent)
    }
}
