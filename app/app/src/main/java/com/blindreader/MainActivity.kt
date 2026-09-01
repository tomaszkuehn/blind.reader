package com.blindreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.blindreader.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentUri: Uri? = null

    private val openFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                loadDocument(uri)
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                openFileLauncher.launch(arrayOf("application/pdf", "text/plain", "application/epub+zip"))
            } else {
                Toast.makeText(this, "Brak uprawnień do odczytu plików", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Uruchom serwis od razu, żeby instance był dostępny dla obsługi klawiatury.
        startService(Intent(this, ReaderService::class.java))

        binding.btnOpenFile.setOnClickListener { confirmCommand("open", getString(R.string.open_file)) }
        binding.btnPlay.setOnClickListener { confirmCommand("play", getString(R.string.play)) }
        binding.btnPause.setOnClickListener { confirmCommand("pause", getString(R.string.pause)) }
        binding.btnRestart.setOnClickListener { confirmCommand("restart", getString(R.string.restart)) }
        binding.btnPrev.setOnClickListener { confirmCommand("prev", getString(R.string.prev_paragraph)) }
        binding.btnNext.setOnClickListener { confirmCommand("next", getString(R.string.next_paragraph)) }
        binding.btnNextPage.setOnClickListener { confirmCommand("next_page", getString(R.string.next_page)) }
        binding.btnSpeedDown.setOnClickListener { confirmCommand("speed_down", getString(R.string.speed_down)) }
        binding.btnSpeedUp.setOnClickListener { confirmCommand("speed_up", getString(R.string.speed_up)) }
        binding.btnVolDown.setOnClickListener { confirmCommand("vol_down", getString(R.string.volume_down)) }
        binding.btnVolUp.setOnClickListener { confirmCommand("vol_up", getString(R.string.volume_up)) }
        binding.btnVoice.setOnClickListener { confirmCommand("voice", getString(R.string.voice)) }

        ReaderService.onOpenFile = {
            pickFile()
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
            "open" -> pickFile()
            "play" -> play()
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

    private fun pickFile() {
        if (Build.VERSION.SDK_INT >= 33) {
            openFileLauncher.launch(arrayOf("application/pdf", "text/plain", "application/epub+zip"))
        } else {
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(permission)
            } else {
                openFileLauncher.launch(arrayOf("application/pdf", "text/plain", "application/epub+zip"))
            }
        }
    }

    private fun loadDocument(uri: Uri) {
        currentUri = uri
        binding.txtStatus.text = "Wczytano: ${uri.lastPathSegment}"
        Toast.makeText(this, "Wczytano dokument", Toast.LENGTH_SHORT).show()
        ReaderService.instance?.setDocument(uri)
    }

    private fun play() {
        val uri = currentUri
        if (uri == null) {
            binding.txtStatus.text = getString(R.string.no_file)
            Toast.makeText(this, R.string.no_file, Toast.LENGTH_SHORT).show()
            return
        }
        ReaderService.play(this, uri)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val service = ReaderService.instance
        if (service != null) {
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
