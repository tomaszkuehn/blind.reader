package com.blindreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.blindreader.databinding.ActivityFilePickerBinding
import java.io.File

class FilePickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilePickerBinding
    private var files: List<File> = emptyList()
    private var currentIndex = 0

    private val supportedExtensions = setOf("pdf", "txt", "epub")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!hasAllFilesAccess()) {
            requestAllFilesAccess()
        } else {
            loadFiles()
        }
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAllFilesAccess() && files.isEmpty()) {
            loadFiles()
        }
    }

    private fun loadFiles() {
        val dir = File(Environment.getExternalStorageDirectory(), "Documents/Reader")
        files = if (dir.exists()) {
            dir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in supportedExtensions }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        } else {
            emptyList()
        }
        currentIndex = 0
        if (files.isEmpty()) {
            binding.txtStatus.text = "Brak plików w Documents/Reader"
            Toast.makeText(this, "Brak plików w Documents/Reader", Toast.LENGTH_SHORT).show()
        } else {
            announceCurrent()
        }
    }

    private fun announceCurrent() {
        if (files.isEmpty()) return
        val name = files[currentIndex].nameWithoutExtension
        binding.txtStatus.text = "${currentIndex + 1}/${files.size}: $name"
        ReaderService.instance?.speakText(name)
    }

    private fun nextFile() {
        if (files.isEmpty()) return
        currentIndex = (currentIndex + 1) % files.size
        announceCurrent()
    }

    private fun prevFile() {
        if (files.isEmpty()) return
        currentIndex = (currentIndex - 1 + files.size) % files.size
        announceCurrent()
    }

    private fun playSelected() {
        if (files.isEmpty()) return
        val file = files[currentIndex]
        val uri = Uri.fromFile(file)
        ReaderService.instance?.playFile(uri)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { nextFile(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> { prevFile(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { playSelected(); return true }
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
