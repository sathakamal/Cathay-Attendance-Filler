package com.msforms.autofill

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.msforms.autofill.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sharedPref = getSharedPreferences("StaffPrefs", Context.MODE_PRIVATE)

        // Load existing settings
        binding.etFullName.setText(sharedPref.getString("full_name", ""))
        binding.etStaffNumber.setText(sharedPref.getString("staff_number", ""))
        binding.etCompany.setText(sharedPref.getString("company", ""))
        binding.etDepartment.setText(sharedPref.getString("department", ""))
        binding.switchAutoSubmit.isChecked = sharedPref.getBoolean("auto_submit", false)

        binding.btnSave.setOnClickListener {
            val editor = sharedPref.edit()
            editor.putString("full_name", binding.etFullName.text.toString())
            editor.putString("staff_number", binding.etStaffNumber.text.toString())
            editor.putString("company", binding.etCompany.text.toString())
            editor.putString("department", binding.etDepartment.text.toString())
            editor.putBoolean("auto_submit", binding.switchAutoSubmit.isChecked)
            editor.apply()

            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
