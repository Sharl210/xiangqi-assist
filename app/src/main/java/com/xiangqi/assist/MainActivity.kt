package com.xiangqi.assist

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.xiangqi.assist.assist.ui.AssistActivity
import com.xiangqi.assist.views.WebviewActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "xiangqiassist"
        private const val KEY_DISCLAIMER_AGREED = "disclaimer_agreed_v1"

        /** 免责声明（首启弹窗 / About / 悬浮窗辅助页共用同一口径） */
        const val DISCLAIMER =
            "免责声明：本应用（象棋辅助）仅供个人学习、技术研究与文化交流使用。" +
            "屏幕识别、悬浮窗建议和自动落子均由用户主动选择启用；" +
            "使用时请遵守当地法律法规、软件许可和所在平台规则。" +
            "因使用本应用产生的后果由使用者自行承担。"

        /** 鸣谢（品牌说明中唯一保留"象棋鱼"字眼处） */
        const val CREDITS =
            "鸣谢：\n" +
            "· 本项目基于开源项目\"象棋鱼\"（作者 zfdang，GitHub: zfdang/chinese-chess-android）二次开发；\n" +
            "· YOLO 棋子检测权重文件来自 VinXiangQi 项目；\n" +
            "· 引擎与对弈框架基座：Pikafish 团队与 DroidFish（Peter Österlund），GPL-3.0 开源授权。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        UiChrome.apply(this, findViewById(R.id.main))

        maybeShowDisclaimer()

        // Bind buttons
        val buttonPlay: Button = findViewById(R.id.button_play)
        val buttonLink: Button = findViewById(R.id.button_link)
        val buttonLearn: Button = findViewById(R.id.button_learn)
        val buttonHelp: Button = findViewById(R.id.button_help)
        val buttonAbout: Button = findViewById(R.id.button_about)

        // Set click listeners
        buttonPlay.setOnClickListener {
            val intent = Intent(this, GameActivity::class.java)
            startActivity(intent)
        }

        buttonLink.setOnClickListener {
            // 悬浮窗辅助：屏幕识别 + 走法指导
            val intent = Intent(this, AssistActivity::class.java)
            startActivity(intent)
        }

        buttonLearn.setOnClickListener {
            // launch manual activity
            val intent = Intent(this, ManualActivity::class.java)
            startActivity(intent)
        }

        buttonHelp.setOnClickListener {
            // 帮助：本地页面（使用说明 + 免责声明）
            val intent = Intent(this, WebviewActivity::class.java).apply {
                putExtra("url", "file:///android_asset/help.html")
            }
            startActivity(intent)
        }

        buttonAbout.setOnClickListener {
            showAboutDialog()
        }
    }

    /** 首次启动弹免责声明：同意后记忆，不同意即退出 */
    private fun maybeShowDisclaimer() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DISCLAIMER_AGREED, false)) return
        AlertDialog.Builder(this)
            .setTitle("免责声明")
            .setMessage(DISCLAIMER)
            .setCancelable(false)
            .setPositiveButton("同意并继续") { d, _ ->
                prefs.edit().putBoolean(KEY_DISCLAIMER_AGREED, true).apply()
                d.dismiss()
            }
            .setNegativeButton("不同意并退出") { _, _ ->
                finishAffinity()
            }
            .show()
    }

    /** 关于：本地对话框（特色功能 + 免责声明 + 鸣谢），不依赖外部站点 */
    private fun showAboutDialog() {
        val text = "象棋辅助 XiangqiAssist v${BuildConfig.VERSION_NAME}\n\n" +
            "特色功能：\n" +
            "· 本地 Pikafish 对弈、局面分析、打谱与复盘；\n" +
            "· 悬浮窗辅助：八帧稳定识别、候选着法、评分与主变；\n" +
            "· 可选无障碍自动落子，支持点击式或拖动式手势。\n\n" +
            DISCLAIMER + "\n\n" +
            CREDITS
        AlertDialog.Builder(this)
            .setTitle("关于 · 象棋辅助")
            .setMessage(text)
            .setPositiveButton("我知道了", null)
            .show()
    }
}
