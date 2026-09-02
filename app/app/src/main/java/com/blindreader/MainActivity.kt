package com.blindreader

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.blindreader.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

    private fun pickFile() {
        startActivity(Intent(this, FilePickerActivity::class.java))
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
