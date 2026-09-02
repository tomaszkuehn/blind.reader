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
import com.blindreader.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var isSelectingFile = false
    private var files: List<File> = emptyList()
    private var currentFileIndex = 0

    private val supportedExtensions = setOf("pdf", "txt", "epub")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Uruchom serwis od razu, żeby instance był dostępny dla obsługi klawiatury.
        startService(Intent(this, ReaderService::class.java))

        binding.btnOpenFile.setOnClickListener { confirmCommand("open", getString(R.string.open_file)) }
        binding.btnPlay.setOnClickListener {
            if (isSelectingFile) confirmCommand("file_play", getString(R.string.play))
            else confirmCommand("play", getString(R.string.play))
        }
        binding.btnPause.setOnClickListener { confirmCommand("pause", getString(R.string.pause)) }
        binding.btnRestart.setOnClickListener { confirmCommand("restart", getString(R.string.restart)) }
        binding.btnPrev.setOnClickListener {
            if (isSelectingFile) confirmCommand("file_prev", getString(R.string.prev_file))
            else confirmCommand("prev", getString(R.string.prev_paragraph))
        }
        binding.btnNext.setOnClickListener {
            if (isSelectingFile) confirmCommand("file_next", getString(R.string.next_file))
            else confirmCommand("next", getString(R.string.next_paragraph))
        }
        binding.btnNextPage.setOnClickListener { confirmCommand("next_page", getString(R.string.next_page)) }
        binding.btnSpeedDown.setOnClickListener { confirmCommand("speed_down", getString(R.string.speed_down)) }
        binding.btnSpeedUp.setOnClickListener { confirmCommand("speed_up", getString(R.string.speed_up)) }
        binding.btnVolDown.setOnClickListener { confirmCommand("vol_down", getString(R.string.volume_down)) }
        binding.btnVolUp.setOnClickListener { confirmCommand("vol_up", getString(R.string.volume_up)) }
        binding.btnVoice.setOnClickListener { confirmCommand("voice", getString(R.string.voice)) }

        ReaderService.onOpenFile = {
            enterFileSelection()
        }
        ReaderService.onFileCommand = { command ->
            when (command) {
                "file_next" -> nextFile()
                "file_prev" -> prevFile()
                "file_play" -> playSelectedFile()
            }
        }
    }

    private fun confirmCommand(command: String, description: String) {
        val service = ReaderService.instance
        if (service != null) {
            service.handleCommand(command, description)
        } else {
            // Service nie istnieje jeszcze; wykonaj od razu lub uruchom.
            executeDirect(command)
        }
    }

    private fun executeDirect(command: String) {
        when (command) {
            "open" -> enterFileSelection()
            "play" -> { /* serwis nieaktywny — brak pliku do odtworzenia */ }
            "pause" -> ReaderService.pause(this)
            "restart" -> ReaderService.restart(this)
            "prev" -> ReaderService.prev(this)
            "next" -> ReaderService.next(this)
            "next_page" -> ReaderService.nextPage(this)
            "speed_down" -> ReaderService.speedDown(this)
            "speed_up" -> ReaderService.speedUp(this)
            "vol_down" -> ReaderService.volDown(this)
            "vol_up" -> ReaderService.volUp(this)
            "voice" -> ReaderService.nextVoice(this)
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

    private fun enterFileSelection() {
        if (!hasAllFilesAccess()) {
            requestAllFilesAccess()
            return
        }
        loadFiles()
        isSelectingFile = true
        updateButtonStates()
        if (files.isEmpty()) {
            binding.txtStatus.text = "Brak plików w Documents/Reader"
            Toast.makeText(this, "Brak plików w Documents/Reader", Toast.LENGTH_SHORT).show()
        } else {
            announceCurrentFile()
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
        currentFileIndex = 0
    }

    private fun announceCurrentFile(prefix: String? = null) {
        if (files.isEmpty()) return
        val name = files[currentFileIndex].nameWithoutExtension
        binding.txtStatus.text = "${currentFileIndex + 1}/${files.size}: $name"
        val text = if (prefix != null) "$prefix. $name" else name
        ReaderService.instance?.speakText(text)
    }

    private fun nextFile() {
        if (files.isEmpty()) return
        currentFileIndex = (currentFileIndex + 1) % files.size
        announceCurrentFile("Następny plik")
    }

    private fun prevFile() {
        if (files.isEmpty()) return
        currentFileIndex = (currentFileIndex - 1 + files.size) % files.size
        announceCurrentFile("Poprzedni plik")
    }

    private fun playSelectedFile() {
        if (files.isEmpty()) return
        val file = files[currentFileIndex]
        val service = ReaderService.instance
        service?.setDocument(Uri.fromFile(file))
        service?.speakText("Plik wczytany")
        isSelectingFile = false
        updateButtonStates()
    }

    private fun updateButtonStates() {
        binding.btnOpenFile.isEnabled = !isSelectingFile
        binding.btnPause.isEnabled = !isSelectingFile
        binding.btnRestart.isEnabled = !isSelectingFile
        binding.btnNextPage.isEnabled = !isSelectingFile
        binding.btnSpeedDown.isEnabled = !isSelectingFile
        binding.btnSpeedUp.isEnabled = !isSelectingFile
        binding.btnVolDown.isEnabled = !isSelectingFile
        binding.btnVolUp.isEnabled = !isSelectingFile
        binding.btnVoice.isEnabled = !isSelectingFile
        // W trybie wyboru aktywne: prev, next, play
        binding.btnPrev.isEnabled = true
        binding.btnNext.isEnabled = true
        binding.btnPlay.isEnabled = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val service = ReaderService.instance
        if (service != null) {
            if (isSelectingFile) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                        service.handleCommand("file_next", getString(R.string.next_file)); return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                        service.handleCommand("file_prev", getString(R.string.prev_file)); return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        service.handleCommand("file_play", getString(R.string.play)); return true
                    }
                    KeyEvent.KEYCODE_BACK -> { isSelectingFile = false; updateButtonStates(); return true }
                }
                return true
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { service.handleCommand("prev", "Poprzednie zdanie"); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { service.handleCommand("next", "Następne zdanie"); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { service.handleCommand("speed_down", "Zmniejsz prędkość"); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { service.handleCommand("speed_up", "Zwiększ prędkość"); return true }
                KeyEvent.KEYCODE_DPAD_CENTER -> { service.handleCommand("play", "Odtwarzaj"); return true }
                KeyEvent.KEYCODE_ENTER -> { service.handleCommand("play", "Odtwarzaj"); return true }
                KeyEvent.KEYCODE_SPACE -> { service.handleCommand("pause", "Pauza"); return true }
                KeyEvent.KEYCODE_PAGE_DOWN -> { service.handleCommand("next_page", "Następna strona"); return true }
                KeyEvent.KEYCODE_VOLUME_UP -> { service.handleCommand("vol_up", "Zwiększ głośność"); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { service.handleCommand("vol_down", "Zmniejsz głośność"); return true }
                KeyEvent.KEYCODE_V -> { service.handleCommand("voice", "Zmień lektora"); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
