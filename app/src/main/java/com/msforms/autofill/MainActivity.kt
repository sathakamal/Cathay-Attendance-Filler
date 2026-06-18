package com.msforms.autofill

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.msforms.autofill.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnScan.setOnClickListener {
            startActivity(Intent(this, ScannerActivity::class.java))
        }

        binding.baseSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val sharedPref = getSharedPreferences("StaffPrefs", android.content.Context.MODE_PRIVATE)
        val name = sharedPref.getString("full_name", "")
        if (!name.isNullOrEmpty()) {
            binding.tvWelcome.text = name
            binding.welcomeCard.visibility = android.view.View.VISIBLE
        } else {
            binding.welcomeCard.visibility = android.view.View.GONE
        }
    }
}
