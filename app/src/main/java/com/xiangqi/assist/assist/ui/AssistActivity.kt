package com.xiangqi.assist.assist.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xiangqi.assist.R
import com.xiangqi.assist.UiChrome
import com.xiangqi.assist.views.WebviewActivity
import com.xiangqi.assist.assist.AssistConfig
import com.xiangqi.assist.assist.AssistRunControlPolicy
import com.xiangqi.assist.assist.EngineTuningPolicy
import com.xiangqi.assist.assist.ScreenAssistService
import com.xiangqi.assist.assist.SideSelectionMode
import com.xiangqi.assist.assist.SideSelectionPolicy
import com.xiangqi.assist.assist.YoloModelTier
import com.xiangqi.assist.assist.access.AssistAccessibilityService
import com.xiangqi.assist.assist.access.RootHelper
import kotlin.math.roundToInt

/**
 * 悬浮窗辅助控制页：授权（悬浮窗/通知/录屏）→ 启动识别服务；
 * 以及"自动走子"所需的屏幕操作通道（无障碍服务）开关。
 *
 * 棋盘识别全自动（YOLO 检测，免校准），此页只负责权限、服务启停与自动走子开关。
 */
class AssistActivity : AppCompatActivity() {

    private var captureIntentCode = -1
    private var captureIntentData: Intent? = null
    /** “一键准备”环境流水线是否正在等待权限/录屏结果。 */
    private var preparationPending = false
    private var preparationRootAttempted = false
    private var preparationRootInFlight = false
    /** 当前“一键准备”流程是否已经通过模型兼容性门。 */
    private var preparationModelCheckPending = false
    private var preparationModelCheckDone = false
    /** 暂停后系统录屏授权页是否已经发起，避免 onResume/轮询重复弹出。 */
    private var resumeAuthorizationLaunched = false
    private var resumeAuthorizationRequested = false

    private lateinit var btnOverlay: Button
    private lateinit var btnNotify: Button
    private lateinit var btnStartCapture: Button
    private lateinit var btnAssistHelp: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnRootEnable: Button
    private lateinit var btnAutoPlay: Button
    private lateinit var sideModeSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var tvSideHint: TextView
    private lateinit var btnMoveGesture: Button
    private lateinit var btnEngineThreads: Button
    private lateinit var btnYoloModel: Button
    private lateinit var tvYoloHint: TextView
    private lateinit var ballScale: SeekBar
    private lateinit var ballScaleLabel: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvAutoHint: TextView

    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            service?.reconcileAccessibilityShutdown()
            refreshStatus()
            statusHandler.postDelayed(this, 1000)
        }
    }

    private var service: ScreenAssistService? = null
    private var bound = false

    /** root 探测结果（null=尚未探测完；探测在后台线程进行，避免 su 阻塞界面） */
    @Volatile private var rooted: Boolean? = null
    /** 已向用户展示过的模型兼容性提示，避免状态轮询重复弹窗。 */
    private var shownYoloModelMessage: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val cb = binder as? ScreenAssistService.CastBinder
            service = cb?.service()
            bound = true
            refreshStatus()
            service?.reconcileAccessibilityShutdown()
            maybeLaunchResumeAuthorization()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resuming = resumeAuthorizationLaunched
        resumeAuthorizationLaunched = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            if (resuming) {
                service?.acceptCaptureGrant(result.resultCode, result.data)
                preparationPending = false
                preparationModelCheckDone = false
                Toast.makeText(this, "录屏权限已恢复；回到原棋盘应用后继续识别", Toast.LENGTH_LONG).show()
                finish()
                return@registerForActivityResult
            }
            captureIntentCode = result.resultCode
            captureIntentData = result.data
            startCaptureService(resumeCapture = false)
            preparationPending = false
            preparationModelCheckDone = false
            Toast.makeText(this, "准备完成，当前保持暂停；打开悬浮窗并点击开始后才会识别", Toast.LENGTH_LONG).show()
        } else {
            preparationPending = false
            if (resuming) service?.rejectCaptureGrant()
            Toast.makeText(
                this,
                if (resuming) "未恢复录屏权限，仍保持暂停" else "未授予录屏权限，无法识别",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshStatus()
        if (preparationPending) continueEnvironmentPreparation()
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshStatus()
        if (preparationPending) continueEnvironmentPreparation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.assist_activity)
        UiChrome.apply(this, findViewById(R.id.assist_root))

        btnOverlay = findViewById(R.id.btn_overlay_permission)
        btnNotify = findViewById(R.id.btn_notification_permission)
        btnStartCapture = findViewById(R.id.btn_start_capture)
        btnAssistHelp = findViewById(R.id.btn_assist_help)
        btnAccessibility = findViewById(R.id.btn_accessibility)
        btnRootEnable = findViewById(R.id.btn_root_enable)
        btnAutoPlay = findViewById(R.id.btn_auto_play)
        sideModeSwitch = findViewById(R.id.switch_side_mode)
        tvSideHint = findViewById(R.id.tv_side_hint)
        btnMoveGesture = findViewById(R.id.btn_move_gesture)
        btnEngineThreads = findViewById(R.id.btn_engine_threads)
        btnYoloModel = findViewById(R.id.btn_yolo_model)
        tvYoloHint = findViewById(R.id.tv_yolo_hint)
        ballScale = findViewById(R.id.seekbar_ball_scale)
        ballScaleLabel = findViewById(R.id.tv_ball_scale_label)
        tvStatus = findViewById(R.id.tv_auto_status)
        tvAutoHint = findViewById(R.id.tv_auto_hint)
        val cfg = AssistConfig(this)
        ballScale.progress = ((cfg.overlayBallScale - 0.7f) * 100f).roundToInt().coerceIn(0, 80)
        updateBallScaleLabel(cfg.overlayBallScale)
        ballScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val scale = (0.7f + progress / 100f).coerceIn(0.7f, 1.5f)
                updateBallScaleLabel(scale)
                if (fromUser) service?.requestBallScale(scale) ?: run { cfg.overlayBallScale = scale }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        btnOverlay.setOnClickListener {
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        btnNotify.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        btnStartCapture.setOnClickListener { onPrimaryButton() }
        btnAssistHelp.setOnClickListener {
            startActivity(Intent(this, WebviewActivity::class.java).apply {
                putExtra("url", "file:///android_asset/help.html#assist")
            })
        }
        // 普通路径：跳到系统无障碍设置，由用户手动勾选本应用
        btnAccessibility.setOnClickListener {
            if (AssistAccessibilityService.isEnabledInSettings(this)) {
                Toast.makeText(this, "屏幕操作通道已开启；如需关闭请到系统无障碍设置", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runCatching { startActivity(AssistAccessibilityService.openSettingsIntent()) }
                .onFailure { Toast.makeText(this, "无法打开无障碍设置页", Toast.LENGTH_SHORT).show() }
        }
        // root 路径：直接写系统设置的启用列表（等价于手动勾选）
        btnRootEnable.setOnClickListener { enableViaRoot() }
        btnAutoPlay.setOnClickListener { toggleAutoPlay() }
        sideModeSwitch.setOnCheckedChangeListener { _, checked ->
            val mode = if (checked) SideSelectionMode.AUTO else SideSelectionMode.MANUAL
            val svc = service
            if (svc != null) {
                svc.requestSideSelectionMode(mode)
            } else {
                AssistConfig(this).sideSelectionMode = mode
            }
            statusHandler.postDelayed({
                if (!isFinishing && !isDestroyed) refreshStatus()
            }, 120L)
        }
        btnMoveGesture.setOnClickListener { toggleMoveGesture() }
        btnEngineThreads.setOnClickListener { cycleEngineThreads() }
        btnYoloModel.setOnClickListener { showYoloModelChooser() }

        bindService(Intent(this, ScreenAssistService::class.java), connection, Context.BIND_AUTO_CREATE)
        handleIncomingIntent(intent)

        // root 探测放到后台：su 首次调用可能阻塞一两秒
        Thread {
            val r = RootHelper.isRooted(forceRefresh = true)
            rooted = r
            statusHandler.post {
                refreshStatus()
                if (preparationPending) continueEnvironmentPreparation()
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onResume() {
        super.onResume()
        service?.reconcileAccessibilityShutdown()
        refreshStatus()
        service?.refreshAutoAvailability()
        maybeLaunchResumeAuthorization()
        statusHandler.removeCallbacks(statusRunnable)
        statusHandler.postDelayed(statusRunnable, 1000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ScreenAssistService.EXTRA_REQUEST_CAPTURE_RESUME, false) == true) {
            resumeAuthorizationRequested = true
            intent.removeExtra(ScreenAssistService.EXTRA_REQUEST_CAPTURE_RESUME)
            maybeLaunchResumeAuthorization()
        }
    }

    private fun maybeLaunchResumeAuthorization() {
        val svc = service ?: return
        if (!svc.isPrepared() || !svc.isCaptureResumePending() || resumeAuthorizationLaunched) return
        resumeAuthorizationRequested = true
        resumeAuthorizationLaunched = true
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusRunnable)
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        super.onDestroy()
        // 服务继续运行（前台服务），仅解绑
    }

    /**
     * 每次只发起一个系统交互；返回本页后继续下一项。投影准备成功后会建立已暂停会话，
     * 不在此流程自动开始识别；用户仍需在悬浮窗里明确点“开始”。
     */
    private fun continueEnvironmentPreparation() {
        if (!preparationPending || isFinishing || isDestroyed) return
        val runningService = service

        if (service?.isAccessibilityShutdownPending() == true) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "先授予悬浮窗权限，返回后会继续准备", Toast.LENGTH_LONG).show()
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        val cfg = AssistConfig(this)
        if (!preparationModelCheckDone && !preparationModelCheckPending) {
            preparationModelCheckPending = true
            Toast.makeText(this, "正在检查${cfg.yoloModelTier.displayName}识别模型兼容性…", Toast.LENGTH_SHORT).show()
            val modelService = runningService
            if (modelService == null) {
                preparationModelCheckPending = false
                Toast.makeText(this, "运行服务正在连接，稍后会继续检查识别模型", Toast.LENGTH_SHORT).show()
                statusHandler.postDelayed({
                    if (preparationPending) continueEnvironmentPreparation()
                }, 250L)
                return
            }
            modelService.ensureYoloModelReady { result ->
                preparationModelCheckPending = false
                if (!result.success) {
                    preparationPending = false
                    preparationModelCheckDone = false
                    Toast.makeText(this, result.userMessage(), Toast.LENGTH_LONG).show()
                    refreshStatus()
                } else {
                    preparationModelCheckDone = true
                    if (result.wasFallback) {
                        shownYoloModelMessage = result.userMessage()
                        AlertDialog.Builder(this)
                            .setTitle("识别模型已自动回退")
                            .setMessage(result.userMessage())
                            .setPositiveButton("继续准备", null)
                            .show()
                    }
                    continueEnvironmentPreparation()
                }
            }
            return
        }
        if (cfg.autoPlay && !AssistAccessibilityService.isConnected()) {
            if (rooted == null) {
                Toast.makeText(this, "正在检查 root 环境，完成后将自动继续…", Toast.LENGTH_SHORT).show()
                return
            }
            // 有 root 就在后台补齐；没有 root 时系统无障碍设置是唯一必要人工步骤。
            if (rooted == true && !preparationRootAttempted) {
                if (preparationRootInFlight) return
                preparationRootAttempted = true
                preparationRootInFlight = true
                Toast.makeText(this, "正在通过 root 准备屏幕操作通道…", Toast.LENGTH_SHORT).show()
                Thread {
                    RootHelper.enableAccessibility(this)
                    statusHandler.postDelayed({
                        preparationRootInFlight = false
                        refreshStatus()
                        continueEnvironmentPreparation()
                    }, 600L)
                }.apply { isDaemon = true }.start()
                return
            }
            if (!AssistAccessibilityService.isEnabledInSettings(this)) {
                preparationPending = false
                Toast.makeText(
                    this,
                    "自动走子已开启，请在无障碍设置中开启“象棋辅助”，然后返回点一次『一键准备』",
                    Toast.LENGTH_LONG
                ).show()
                runCatching { startActivity(AssistAccessibilityService.openSettingsIntent()) }
                return
            }
            // 已在设置中启用但实例尚未连接：允许先启动识别；服务会继续自愈通道。
        }

        if (runningService?.hasPreparedIntent() == true || runningService?.isAccessibilityShutdownPending() == true) {

            preparationPending = false
            Toast.makeText(
                this,
                "上一会话仍在关闭/等待系统核验；核验完成并显示一键准备后才能重新准备",
                Toast.LENGTH_LONG,
            ).show()
            refreshStatus()
            return
        }
        if (runningService?.isPrepared() == true && runningService.isProjectionAuthorized()) {
            preparationPending = false
            preparationModelCheckDone = false
            Toast.makeText(
                this,
                if (runningService.isCapturing()) "运行环境已准备且正在识别"
                else if (runningService.isCaptureResumePending()) "运行环境仍已准备；继续时需要重新确认屏幕录制权限"
                else "准备完成，当前保持暂停；打开悬浮窗并点击开始，无需重新授权",
                Toast.LENGTH_SHORT,
            ).show()
            refreshStatus()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        resumeAuthorizationLaunched = false
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startCaptureService(resumeCapture: Boolean = false) {
        if (captureIntentData == null) return
        val intent = Intent(this, ScreenAssistService::class.java)
            .setAction(ScreenAssistService.ACTION_START)
            .putExtra(ScreenAssistService.EXTRA_RESULT_CODE, captureIntentCode)
            .putExtra(ScreenAssistService.EXTRA_RESULT_DATA, captureIntentData)
            .putExtra(ScreenAssistService.EXTRA_RESUME_CAPTURE, resumeCapture)
        ContextCompat.startForegroundService(this, intent)
    }

    /**
     * One-button control: an unprepared session runs environment setup and remains paused;
     * the floating window's Start button is the explicit action that begins capture/analysis.
     * Once prepared, this button closes the whole session whether it is running or paused.
     */
    private fun onPrimaryButton() {
        val svc = service
        if (svc?.isAccessibilityShutdownPending() == true) {
            Toast.makeText(this, "正在关闭屏幕操作通道，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val preparedIntent = svc?.hasPreparedIntent() == true
        val prepared = svc?.isPrepared() == true
        if (!preparedIntent && !prepared) {
            preparationPending = true
            preparationRootAttempted = false
            preparationModelCheckDone = false
            preparationModelCheckPending = false
            continueEnvironmentPreparation()
            return
        }
        svc?.requestStopFromControlEntry()
        Toast.makeText(this, "正在关闭识别、屏幕共享、悬浮窗和本应用屏幕操作通道", Toast.LENGTH_SHORT).show()
        statusHandler.postDelayed({ refreshStatus() }, 150L)
    }

    private fun toggleRunFromApp() {
        onPrimaryButton()
    }

    private fun enableViaRoot() {
        if (rooted != true) {
            Toast.makeText(this, "未检测到 root，请用上面第④步手动开启", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "正在通过 root 开启…", Toast.LENGTH_SHORT).show()
        Thread {
            val ret = RootHelper.enableAccessibility(this)
            statusHandler.post {
                val ok = ret.ok || AssistAccessibilityService.isEnabledInSettings(this)
                Toast.makeText(
                    this,
                    if (ok) "已开启屏幕操作通道" else "开启失败：${ret.output.take(80)}",
                    Toast.LENGTH_LONG
                ).show()
                refreshStatus()
            }
        }.apply { isDaemon = true }.start()
    }

    /** ⑥ 自动走子开关（默认开）：与「模式」是两件事——模式管识别与刷新，这个管要不要自动落子 */
    private fun toggleAutoPlay() {
        val cfg = AssistConfig(this)
        val svc = service
        val on = !(svc?.isAutoPlayOn() ?: cfg.autoPlay)
        if (svc != null) {
            // 服务是自动开关的唯一写入者；避免 Activity 先改配置导致服务看不到“用户关闭”事件。
            svc.requestAutoPlay(on)
        } else {
            cfg.autoPlay = on
        }
        if (on && !AssistAccessibilityService.isConnected()) {
            Toast.makeText(this, "已开启自动走子，但「屏幕操作通道」未连接，先用⑤开启", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, if (on) "自动走子：已开启" else "自动走子：已关闭（只提示不走子）", Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    /** 识别模型选择：兼容性探测成功后才写入实际档位；负载只作为提示，不自动降级。 */
    private fun showYoloModelChooser() {
        val cfg = AssistConfig(this)
        val tiers = YoloModelTier.values()
        val labels = tiers.map { tier ->
            "${tier.displayName}：${tier.selectionHint}"
        }.toTypedArray()
        val current = tiers.indexOf(cfg.yoloModelTier).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选择识别模型")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val requested = tiers[which]
                dialog.dismiss()
                btnYoloModel.isEnabled = false
                tvYoloHint.text = "正在检查${requested.displayName}模型兼容性；只检查模型能否加载和执行，不按当前负载回退。"
                val complete: (com.xiangqi.assist.assist.YoloModelSelectionResult) -> Unit = { result ->
                    runOnUiThread {
                        btnYoloModel.isEnabled = true
                        val explain = !result.success || result.wasFallback
                        // refreshStatus 也会展示服务返回的状态；先记账，避免同一结果弹两次。
                        if (explain) shownYoloModelMessage = result.userMessage()
                        refreshStatus()
                        if (explain) {
                            AlertDialog.Builder(this)
                                .setTitle(if (result.success) "模型已自动回退" else "模型不可用")
                                .setMessage(result.userMessage())
                                .setPositiveButton("知道了", null)
                                .show()
                        }
                    }
                }
                val svc = service
                if (svc != null) {
                    svc.requestYoloModelTier(requested, complete)
                } else {
                    // 未绑定服务时不能把“已写入偏好”冒充成兼容性探测通过。
                    btnYoloModel.isEnabled = true
                    tvYoloHint.text = "识别服务尚未连接，暂时无法检查设备兼容性；请稍后重试。"
                    Toast.makeText(this, "识别服务尚未连接，未更改模型设置", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun yoloHint(tier: YoloModelTier): String =
        "${tier.selectionHint}。兼容性失败时按大型→中型→Lite回退；负载、温度和功耗不会强制回退。"

    /**
     * 落子方式开关。默认点击式：先点棋子，再点目标格；只有用户明确切换才用拖动式。
     * 修改后对下一次落子生效，不打断当前识别或引擎计算。
     */
    private fun toggleMoveGesture() {
        val cfg = AssistConfig(this)
        cfg.autoPlayGesture = if (cfg.autoPlayGesture == AssistConfig.GESTURE_TAP) {
            AssistConfig.GESTURE_SWIPE
        } else {
            AssistConfig.GESTURE_TAP
        }
        refreshStatus()
        Toast.makeText(
            this,
            if (cfg.autoPlayGesture == AssistConfig.GESTURE_TAP)
                "落子方式：点击式（先点起点，再点终点）"
            else
                "落子方式：拖动式（仅用于明确支持拖动的棋盘）",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 引擎线程数：自动 → 1 → 2 → … → 8 → 自动（对下一次搜索生效，不打断当前计算）。 */
    private fun cycleEngineThreads() {
        val cfg = AssistConfig(this)
        val next = EngineTuningPolicy.nextSetting(cfg.engineThreads)
        cfg.engineThreads = next
        service?.requestEngineThreads(next)
        refreshStatus()
        val cores = Runtime.getRuntime().availableProcessors()
        Toast.makeText(
            this,
            if (next == EngineTuningPolicy.AUTO)
                "引擎线程：自动（本机 ${cores} 核 → 使用 ${EngineTuningPolicy.autoThreads(cores)} 线程）"
            else "引擎线程：$next（对下一次搜索生效）",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateBallScaleLabel(scale: Float) {
        ballScaleLabel.text = "悬浮球大小：${(scale * 100).roundToInt()}%（范围70%～150%）"
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        btnOverlay.text = if (overlayOk) "悬浮窗：已授权 ✓" else "悬浮窗权限"
        btnOverlay.setTextColor(ContextCompat.getColor(
            this, if (overlayOk) R.color.brand_gold_300 else R.color.brand_text_primary
        ))

        val notifOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        btnNotify.text = if (notifOk) "通知：已就绪 ✓" else "通知权限"
        btnNotify.isEnabled = !notifOk && Build.VERSION.SDK_INT >= 33

        val svc = service
        val prepared = svc?.isPrepared() == true
        val preparedIntent = prepared || svc?.hasPreparedIntent() == true
        val running = prepared && svc?.isRunning() == true
        btnStartCapture.text = AssistRunControlPolicy.primaryLabel(preparedIntent, running)
        btnStartCapture.isEnabled = svc?.isAccessibilityShutdownPending() != true
        btnStartCapture.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (AssistRunControlPolicy.primaryButtonIsClose(preparedIntent))
                    R.color.brand_red_600 else R.color.brand_gold_500
            )
        )

        btnYoloModel.isEnabled = !preparationModelCheckPending

        val accessEnabled = AssistAccessibilityService.isEnabledInSettings(this)
        val accessConnected = AssistAccessibilityService.isConnected()
        btnAccessibility.text = when {
            accessConnected -> "屏幕操作通道：已连接 ✓"
            accessEnabled -> "屏幕操作通道：等待连接"
            else -> "开启屏幕操作通道"
        }

        when (rooted) {
            null -> {
                btnRootEnable.text = "正在检测 root…"
                btnRootEnable.isEnabled = false
            }
            true -> {
                btnRootEnable.text = if (accessEnabled) "root 通道已开启 ✓" else "root 一键开启通道"
                btnRootEnable.isEnabled = !accessEnabled
            }
            false -> {
                btnRootEnable.text = "未检测到 root"
                btnRootEnable.isEnabled = false
            }
        }

        val autoOn = svc?.isAutoPlayOn() ?: AssistConfig(this).autoPlay
        btnAutoPlay.text = if (autoOn) "自动走子：已开启" else "自动走子：已关闭"
        btnAutoPlay.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (autoOn) R.color.brand_green_600 else R.color.brand_navy_700)
        )

        val sideCfg = AssistConfig(this)
        val sideMode = svc?.sideSelectionMode() ?: sideCfg.sideSelectionMode
        val effectiveSideRed = svc?.effectiveSideRed() ?: SideSelectionPolicy.effectiveSideRed(
            sideMode,
            sideCfg.mySideRed,
            sideCfg.autoSideRed,
        )
        sideModeSwitch.setOnCheckedChangeListener(null)
        sideModeSwitch.isChecked = sideMode == SideSelectionMode.AUTO
        sideModeSwitch.text = if (sideMode == SideSelectionMode.AUTO) "自动检测执子方" else "手动选择执子方"
        sideModeSwitch.setOnCheckedChangeListener { _, checked ->
            val mode = if (checked) SideSelectionMode.AUTO else SideSelectionMode.MANUAL
            service?.requestSideSelectionMode(mode) ?: run { AssistConfig(this).sideSelectionMode = mode }
            refreshStatus()
        }
        val sideReady = svc?.isAutoSideDetectionReady() == true
        tvSideHint.text = if (sideMode == SideSelectionMode.AUTO) {
            if (sideReady) {
                "自动检测当前己方：${if (effectiveSideRed) "红方" else "黑方"}；只依据屏幕下半区帅/将的颜色；异常棋面会拒绝并重新识别，面板按钮不可手动点击"
            } else {
                "自动执子方等待稳定有效棋面；检测锚点仅为屏幕下半区帅/将，异常棋面不会猜测或覆盖自动记忆"
            }
        } else {
            "手动记忆当前己方：${if (effectiveSideRed) "红方" else "黑方"}；切换到自动后将使用另一套独立记忆"
        }

        val gesture = AssistConfig(this).autoPlayGesture
        btnMoveGesture.text = if (gesture == AssistConfig.GESTURE_TAP) {
            "落子方式：点击式"
        } else {
            "落子方式：拖动式"
        }

        val threads = AssistConfig(this).engineThreads
        btnEngineThreads.text = "引擎线程：" + if (threads == EngineTuningPolicy.AUTO) {
            "自动（${EngineTuningPolicy.autoThreads(Runtime.getRuntime().availableProcessors())}）"
        } else {
            threads.toString()
        }

        val modelConfig = AssistConfig(this)
        val actualModel = svc?.yoloModelTier() ?: modelConfig.yoloModelTier
        btnYoloModel.text = "识别模型：${actualModel.displayName}"
        val modelMessage = svc?.yoloModelSelectionMessage()?.takeIf { it.isNotBlank() }
        tvYoloHint.text = modelMessage ?: yoloHint(actualModel)
        if (modelMessage != null && modelMessage != shownYoloModelMessage && preparedIntent) {
            shownYoloModelMessage = modelMessage
            AlertDialog.Builder(this)
                .setTitle("识别模型状态")
                .setMessage(modelMessage)
                .setPositiveButton("知道了", null)
                .show()
        }

        tvAutoHint.text = when {
            !accessConnected && !accessEnabled -> "自动落子通道尚未开启"
            !accessConnected -> "操作通道已启用，正在等待系统连接"
            !prepared -> "操作通道已就绪；请先一键准备"
            !running && svc?.isCaptureThrottled() == true ->
                "已暂停；授权保留，屏幕画面未输出，点击继续恢复"
            !running && svc?.isCaptureResumePending() == true ->
                "已暂停；系统已结束屏幕共享，继续时需重新授权"
            !running -> "操作通道已就绪；辅助当前暂停"
            autoOn -> "识别运行中；自动走子已开启"
            else -> "识别运行中；当前只显示建议"
        }

        tvStatus.text = when {
            !preparedIntent -> "当前已关闭 · 尚未准备"
            !prepared && svc?.isAccessibilityShutdownPending() == true -> "正在关闭本应用的屏幕操作通道"
            prepared && !running && svc?.isCaptureThrottled() == true ->
                "已暂停 · 授权保留 · 屏幕画面未输出 · 无障碍仍在监听"
            prepared && !running && svc?.isCaptureResumePending() == true ->
                "已暂停 · 系统已结束屏幕共享 · 继续时需重新授权"
            !running -> "当前已暂停 · 无障碍监听保持开启"
            else -> "运行中 · ${svc?.getStatusText().orEmpty()}"
        }
    }
}
