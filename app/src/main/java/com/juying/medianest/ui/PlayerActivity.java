package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import com.juying.medianest.app.App;
import com.juying.medianest.core.util.L;
import com.juying.medianest.player.PlaySessionKeeper;
import com.juying.medianest.player.Libass;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.juying.medianest.core.policy.BitrateController;
import com.juying.medianest.core.model.Capability;
import com.juying.medianest.core.model.FsEntry;
import com.juying.medianest.ui.ImageLoader;
import com.juying.medianest.player.AuthProxy;
import com.juying.medianest.player.HlsClock;
import com.juying.medianest.player.HlsVtt;
import com.juying.medianest.player.LiveProxy;
import com.juying.medianest.core.policy.SessionPolicy;
import com.juying.medianest.core.model.SubtitleSearchItem;
import com.juying.medianest.core.model.SubtitleTrack;
import com.juying.medianest.core.model.FailureReason;
import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.util.Json;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.policy.NasLevels;
import com.juying.medianest.ui.OsdKeys;
import com.juying.medianest.domain.playback.FallbackPlayback;
import com.juying.medianest.domain.playback.PlaybackCoordinator;
import com.juying.medianest.domain.usecase.MediaUseCases;
import com.juying.medianest.core.model.PlaybackPlan;
import com.juying.medianest.core.model.PlaybackRequest;
import com.juying.medianest.core.model.PlaybackSession;
import com.juying.medianest.core.model.QualityLevel;
import com.juying.medianest.core.model.RouteOption;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.core.model.StreamCandidate;
import com.juying.medianest.core.model.StreamSource;
import com.juying.medianest.core.policy.SubtitlePolicy;
import com.juying.medianest.player.EngineFactory;
import com.juying.medianest.player.EngineSelector;
import com.juying.medianest.player.Exo2Engine;
import com.juying.medianest.player.MediaPlayerEngine;
import com.juying.medianest.player.PlayerEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 播放器：基于 API 1 就存在的 MediaPlayer + SurfaceView。
 *
 * 与直接用 VideoView 的区别：可以在 API 14+ 通过反射调用
 * MediaPlayer.setDataSource(Context, Uri, Map) 给原始流带上 Authorization /
 * Play-Link 请求头；老系统自动退回无头的 setDataSource(String)。
 */
public final class PlayerActivity extends Activity implements SurfaceHolder.Callback {

    /** 连续降级重建会话的最大次数；超过后交给回退状态机换播放方式。 */
    private static final int MAX_STALL_RECOVERIES = 3;
    /**
     * 起播检查节奏。NAS 转码首片实测要约 8 秒才就绪，旧的「8 秒一刀切」会在
     * 首片刚到的瞬间把播放器判死，同时触发新会话重转码，越等越慢。
     * 这里改成 8 秒一次渐进检查：只要代理仍在等上游首片就不判死。
     */
    private static final long PREPARE_FIRST_CHECK_MS = 8000L;
    private static final long PREPARE_STAGE_MS = 8000L;
    /** 兜底上限：48 秒仍没有任何媒体数据才认为真的不可播。 */
    private static final long PREPARE_HARD_CAP_MS = 48000L;
    /** 无在途请求时，媒体流量静默超过这个时长才判定卡死。 */
    private static final long PREPARE_IDLE_LIMIT_MS = 16000L;
    /** 稳定播放超过这个时长后，停滞计数归零（避免长片累计到上限直接跳外部播放器）。 */
    private static final long HEALTHY_RESET_MS = 30000L;
    /** 1080p 解码缓冲按 16 行对齐后多出的填充行（1088-1080），见 extendSurfaceForDecoderPadding。 */
    private static final int DECODER_PAD_ROWS = 8;

    private SurfaceView surface;
    private TextView osdTitle;
    private TextView osdMeta;
    private TextView osdNotice;
    private TextView loading;
    private View loadingIndicator;
    private android.widget.ImageView loadingSpinner;
    private View seekIndicator;
    private android.widget.ImageView seekIcon;
    private TextView seekText;
    private boolean seekHeld = false;
    private int seekDirection = 0;
    private int seekHoldSeconds = 0;
    private long lastSeekDownAt = 0L;
    private final Runnable osdAutoHide = new Runnable() {
        public void run() { collapseOsd(); }
    };
    private FrameLayout osdPanel;
    /** 中心播放/暂停图标（官方 iv_play_state 180×180 @ [870,450]），暂停时显示。 */
    private TextView centerPlay;
    private Typeface iconFont;
    private TextView playerTime;
    /** 字幕容器：libass 渲染的位图覆盖层（双语/花式定位由 libass 原生完成）。 */
    private FrameLayout playerSubtitle;
    /** libass 字幕渲染器：C 层做时间轴/样式解析，Java 层只绘制位图。 */
    private Libass libass;
    /** 承载 libass 位图的视图；不聚焦、不拦截按键。 */
    private android.widget.ImageView subtitleImage;
    private ProgressBar playerProgress;
    /** 官方进度条右端白色圆点（18×18），随播放进度移动。 */
    private View progressKnob;
    /** 进度条焦点容器：聚焦后 ←→ 拖动定位（进度条本身太细，直接聚焦看不见）。 */
    private View progressBox;
    /** 多集片源的兄弟集，起播后异步加载一次；≤1 集时选集相关入口整体隐藏。 */
    private final List<MediaItem> siblingEpisodes = new ArrayList<MediaItem>();
    /** 兄弟集是否已成功读取（失败时按单集处理，不显示选集入口）。 */
    private boolean episodesLoaded = false;
    /** 当前播放的是兄弟集里的第几个（-1 表示没对上）。 */
    private int episodeAt = -1;
    /** 影视 / 直播各自唯一允许聚焦的 OSD 控件，按遥控器循环顺序排列。 */
    private final List<View> osdFocusOrder = new ArrayList<View>();
    private final List<View> controlButtons = new ArrayList<View>();
    /** 官方 tab_page_view：焦点落到标签行时展开的内容区与列表（音频/倍速）。 */
    private View osdTabContent;
    private android.widget.LinearLayout osdTabList;
    /** 选集页：官方是「共 N」+ 数字格（不是纵向列表），单独一组视图。 */
    private TextView osdEpHeader;
    private android.widget.LinearLayout osdEpGrid;
    private View osdEpPrev;
    private View osdEpNext;
    private int epPage = 0;
    private static final int EPISODE_COLUMNS = 15;
    private static final int EPISODE_PAGE_SIZE = EPISODE_COLUMNS * 2;
    private int episodeGridColumns = EPISODE_COLUMNS;
    /** 设置页两列：左列参数组、右列子项（204 实测）。 */
    private android.widget.LinearLayout osdSetLeft;
    private android.widget.LinearLayout osdSetRight;
    /** 左列固定裁切区（400×364）：5 组共 440px，聚焦低处行时整体上移。 */
    private View osdSetLeftClip;
    /** 右列固定裁切区（1320×364），内容上移时由它保留可视边界。 */
    private View osdSetRightClip;
    private final List<View> setLeftItems = new ArrayList<View>();
    /** 高级页中间列（图像显示/声道/音频直通）的行，用于三列焦点导航。 */
    private final List<View> setMidItems = new ArrayList<View>();
    /** 高级页右列容器（说明 + 当前副项选项）。 */
    private android.widget.LinearLayout advancedRight;
    private int setGroup = 0;
    /** 高级页当前副项：0 图像显示 / 1 声道 / 2 音频直通。 */
    private int advancedItem = 0;
    /** 片头已跳过一次 / 片尾已触发，避免 500ms tick 重复执行。 */
    private boolean introSkipped = false;
    private boolean outroTriggered = false;
    private static final String[] SET_GROUPS = {"连播/片头片尾", "画面比例",
            "播放信息", "解码方式", "高级"};
    /** 设置项持久化（与官方一致：改完立即生效并记住）。 */
    private android.content.SharedPreferences playerPrefs;
    /** 重建设置右列后要把焦点放回的子项文字。 */
    private String pendingSettingFocus;
    /** 上次停留的设置选项（中列/右列），对应官方原生焦点记忆：标签↓、左列→会回到它。 */
    private View lastSettingFocus;
    /** 焦点记忆的列与列内下标；右列重建后仍能恢复（官方标签↓回到中列/右列上次项）。 */
    private int lastSettingCol = 0;
    private int lastSettingIndex = -1;
    /** 官方聚焦标签胶囊（200×80），独立视图，定位到当前焦点/展开的标签。 */
    private View tabPill;
    private final List<View> tabListItems = new ArrayList<View>();
    /** 字幕标签内容区缓存的 NAS 轨道（首次展开时异步读取）。 */
    private final List<SubtitleTrack> subtitleTracks = new ArrayList<SubtitleTrack>();
    private boolean subtitleTracksLoading = false;
    /** 上一次字幕轨道读取失败的原因，空态直接上屏，方便电视上排查。 */
    private String subtitleTracksError = null;
    /** 起播阶段已经读过一次轨道，字幕页直接用缓存，避免再次请求（老机型偶发请求挂死）。 */
    private boolean subtitleTracksFetched = false;
    /** 本次播放是否已提示过直连缺失内置字幕（官方 dialog_directlink_tip 只弹一次）。 */
    private boolean directLinkTipShown = false;
    /** 每次加载的令牌，超时/新请求会让旧回调失效。 */
    private int subtitleLoadToken = 0;
    /** 当前展开内容对应的标签 id；0 表示未展开。 */
    private int expandedTabId;
    /** 选择后重建列表时要把焦点放回的行下标，-1 表示不指定。 */
    private int pendingRowFocus = -1;
    /** 标签列表重建中：期间焦点被移除，禁止「焦点离开标签」检查误收起面板。 */
    private boolean tabsRebuilding = false;
    /** 标签行展开时整体上移 400px（官方 y=956 → 556）。 */
    private static final int TAB_ROW_TOP_COLLAPSED = 719;
    private static final int TAB_ROW_TOP_EXPANDED = 319;
    /** 标签列表项的 id 号段（避开 XML 里已有的 id）。 */
    private static final int TAB_ITEM_ID_BASE = 0x50000;
    /** 标签行左右顺序（官方 tab_view：选集/画质/字幕/音频/倍速/设置）。 */
    private static final int[] TAB_IDS = {R.id.btn_episodes, R.id.btn_quality,
            R.id.btn_subtitle, R.id.tab_audio, R.id.tab_speed, R.id.btn_settings};
    /** 标签槽原始左边界（与 XML marginLeft 一致），选中时左右各外扩 9px。 */
    private static final int[] TAB_BASE_LEFT = {36, 280, 524, 768, 1012, 1256};
    /** 上次停留的标签：OSD 收起后按 ↓ 回到它（官方会直接展开该标签的列表）。 */
    private int lastTabId = 0;
    /** OSD 当前是否展开；收起时所有 OSD 控件 GONE，焦点只留在画面上。 */
    private boolean osdVisible = false;
    /** 收起前的有效焦点，唤醒时恢复。 */
    private View lastOsdFocus;
    /** 菜单弹窗打开期间不收起 OSD，关闭后再重新计时。 */
    private boolean menuOpen = false;
    /** 直播场景：OSD 只保留线路选择。 */
    private boolean liveMode = false;
    /** NAS 可用画质挡位与直播线路挡位（全部动态，无本地枚举表）。 */
    private final List<QualityLevel> nasQualities = new ArrayList<QualityLevel>();
    private final List<RouteOption> nasRoutes = new ArrayList<RouteOption>();
    private String qualitySignature = "";
    private String routesSyncedSignature = null;
    private String activeQualityValue = "";
    private String routeLabel = "";
    private String currentRouteGuid = "";
    private MediaUseCases api;
    private long lastReportAt = 0L;
    /** 上一帧播放位置，用于捕捉「播放中突然往回跳」这类会话/授权引发的回退。 */
    private long lastTickPosMs = -1L;
    private boolean exitReported = false;
    /** 外部直播 HLS 已交给系统播放器时，内部 MediaPlayer 的超时不应再弹失败框。 */
    private boolean externalLaunched = false;
    /** 官方播放信息浮层（view_play_info）：开启后覆盖画面上，只保留「关闭」可聚焦。 */
    private View playInfoOverlay = null;
    private android.widget.LinearLayout playInfoColumns = null;
    private TextView playInfoClose = null;
    private boolean playInfoShown = false;
    /** 官方字幕页左列（选择字幕 / 调整字幕）与右列表缩进。 */
    private View osdSubLeft = null;
    private TextView osdSubSelectTab = null;
    private TextView osdSubAdjustTab = null;
    /** 字幕子菜单：0 = 选择字幕，1 = 调整字幕。 */
    private int subMenu = 0;
    /** 官方字幕调整弹窗（偏移/大小/位置）与参数。 */
    private View subAdjustOverlay = null;
    private android.widget.LinearLayout subAdjustPanel = null;
    private boolean subAdjustShown = false;
    private int subAdjustKind = 0;
    private long subOffsetMs = 0L;
    private float subScale = 1f;
    private int subYOffset = 0;
    /** libass 无新帧时沿用上一帧位图，但仍需按新参数重排字幕区域。 */
    private final Rect lastSubtitleRect = new Rect();
    private boolean hasLastSubtitleRect = false;
    private TextView subAdjustValue = null;
    /** 大小列表中与当前档位对应的行，打开面板时把焦点放上去。 */
    private TextView subSizeTarget = null;
    private static final int ADJ_OFFSET = 0;
    private static final int ADJ_SIZE = 1;
    private static final int ADJ_POSITION = 2;
    /** 同一时刻只允许一个阻塞式错误弹窗，避免连续失败时反复抢走遥控器焦点。 */
    private View playErrorView = null;
    private TextView playErrorMsg = null;
    private TextView btnErrorRetry = null;
    private TextView btnErrorExit = null;
    private boolean errorFocusPending = false;
    private Runnable retryOnError = null;
    /** NAS 繁忙等持续性故障时只打断用户一次，后续自动重试不再弹窗。 */
    private boolean fatalShown = false;
    /** 只在用户主动打开顶栏「信息」时展示，绝不写到画面上的字幕层。 */
    private String subtitleStatus = "字幕：关闭";
    /** libass 是否已加载字幕并启用渲染。 */
    private boolean subtitlesEnabled = false;
    /** 首个播放方案是否已预解析过上次字幕（避免起播时两次建会话）。 */
    private boolean subtitlePlanSeeded = false;
    /** 字幕层启用后是否已成功出过一帧；用于首次绘制日志。 */
    private boolean subtitleDrawn = false;
    /** 上一帧渲染用的播放位置；暂停时用它判断是否需要因 seek 重绘。 */
    private long lastSubtitlePosMs = -1L;
    /** seek 后新字幕帧到达前的宽限期，避免短暂空白被误清成永久消失。 */
    private long subtitleSeekGraceUntilMs = 0L;
    /** HLS 播放列表时间轴与分片真实 PTS 的偏差测量（字幕时间基准修正）。 */
    private final HlsClock hlsClock = new HlsClock();
    /** 当前 HLS 播放列表地址，供分片 PTS 采样使用；空表示不是 HLS。 */
    private String currentPlaylistUrl = "";
    /** 字幕事件开始时间表，仅用于同步诊断日志（cue=... delta=...）。 */
    private long[] subCueStarts = null;
    private int lastLoggedCue = -2;
    private long lastSyncLogAt = 0L;
    /** 字幕窗口变空（画面上没有字幕）的起点；用于测量「字幕消失」持续多久。 */
    private long subtitleBlankSinceMs = -1L;
    /** 上一帧非空字幕对应的播放位置；用于量化「渲染层恢复」的间隔。 */
    private long lastFrameNonEmptyPosMs = -1L;
    /** libass 不可用日志的节流时间（连续 tick 时避免刷屏）。 */
    private long lastLibassWarnAt = 0L;
    /** 视频原始分辨率，作为 libass 的 storage size 以正确映射 ASS PlayRes 坐标。 */
    private int subtitleStorageW = 1920;
    private int subtitleStorageH = 1080;
    /** null = 字幕已关闭。内嵌字幕由 NAS 转码烧录，客户端不持有 cue。 */
    private SubtitleTrack activeSubtitle = null;
    private String subtitleFormat = "";
    /** 当前播放会话创建时携带的 subtitle_guid；内嵌轨要靠它让 NAS 生成 VTT rendition。 */
    private volatile String sessionSubtitleGuid = "";
    /** 内嵌轨（NAS 无独立字幕文件）走会话里的 WebVTT rendition 逐段渲染。 */
    private HlsVtt hlsVtt = null;
    /** 上一次交给 libass 的 VTT 窗口文本，避免每 100ms 重载。 */
    private String hlsDoc = "";
    private String qualityLabel = "自动";
    private String currentPositionText = "00:00 / 00:00";

    private final Handler ui = new Handler();
    private SurfaceHolder holder;
    private boolean surfaceReady = false;
    private PlayerEngine player;
    /** 每次 startPlayback 递增：旧内核的异步结果据此丢弃。 */
    private volatile int prepareGeneration = 0;
    private long prepareStartedAt = 0L;
    /** 当前本地鉴权代理，供 prepare 看门狗读取「NAS 是否还在出片」。 */
    private AuthProxy authProxy;
    private boolean prepared = false;
    private volatile boolean planReady = false;
    private boolean startRequested = false;
    private volatile int planGeneration = 0;
    private boolean destroyed = false;
    private boolean backgroundStopped = false;
    private int subtitleRestoreWaits = 0;
    private Runnable releaseUnusedSessionsTask;
    /** 直播已尝试的线路数：换线时给厂商播放器一点销毁缓冲时间（否则会 [AVP] no input）。 */
    private int livePlayAttempts = 0;
    /**
     * 换线时释放旧内核后、创建新 MediaPlayer 前的等待。
     * 真机实测：Java 层 reset() 返回后 mstplayer 仍在清理 DM/STM 线程，
     * 0.4 秒后就 new MediaPlayer() 会直接 initCheck FAIL（prepareAsync 0.5 秒失败）。
     */
    private static final long LIVE_SWITCH_SETTLE_MS = 2500L;
    /** 直播线路「拿到 playlist 后多久没有任何分片字节」判定线路不可用。 */
    private static final long LIVE_PREPARE_IDLE_MS = 15000L;
    /** 直播 playlist 预热最长等待（500ms 一次）：慢线路实测首次要 30 秒以上。 */
    private static final int LIVE_PLAYLIST_WAIT_TICKS = 60;
    /** 非 EO 线路起播前等待首分片预取完成的上限（500ms 一次）：整段在内存才能瞬时出画。 */
    private static final int LIVE_FIRST_SEG_WAIT_TICKS = 24;
    /** 直播换线令牌：旧的等待链/超时回调据此作废，避免一次失败被重复上报烧掉候选。 */
    private int liveAttemptToken = 0;
    /** 已经上报过错误的 prepareGeneration：同一台内核的后续错误回调一律忽略。 */
    private int failedGeneration = -1;

    private MediaItem item;
    private ServerConfig cfg;
    private Capability cap;
    private PlaybackPlan plan;
    private FallbackPlayback fallback;
    private StreamCandidate pendingCandidate;
    private String planNote = "";
    /**
     * 播放内核释放后，SurfaceView 的 Surface 必须整体重建才能再挂新实例。
     * MStar 4.2.1：旧实例 setDisplay(null)+release 后，同一个 Surface 再被
     * 新 MediaPlayer setDisplay 时，硬件视频层初始化会 4 秒超时
     * （mstplayer "flip first frame timeout" → -5003），表现为有声无画。
     */
    private boolean surfaceDirty = false;
    /** 播放会话续期器；不持有 Activity，外部播放期间也能继续续期。 */
    private PlaySessionKeeper sessionKeeper;

    /** 解码停滞自愈状态。真机 API 17 上 OMX 输出缓冲长期不归还时会静默卡死。 */
    private int degradeLevel = 0;
    private BitrateController bitrateController;
    private int stallRecoveries = 0;
    /** 当前档位因解码错误原档重建的次数：只为拦住 -5003 级联，稳定播放后自动归零。 */
    private int sameLevelRetries = 0;
    private boolean userPaused = false;
    private boolean recovering = false;
    private long lastPrematureCompletionMs;
    private int playlistWindow;
    private long playlistOffsetMs;
    private boolean fnosLongSegments = true;
    private String clockPlaylistUrl = "";
    private long lastProgressMs = -1L;
    private boolean seekPending;
    private boolean seekApplyScheduled;
    private long pendingSeekPositionMs = -1L;
    private long seekStartedAtMs;
    private long seekWatchdogGraceUntilMs;
    private boolean engineBuffering;
    private long bufferingSinceMs;
    private long bufferUiStalledSinceMs;
    private long stalledSinceMs;
    private long resumeAtMs = 0L;
    private int stalledTicks = 0;
    /** 解码类硬失败（非卡顿）最多再降几档画质/音轨后重建会话。 */
    private static final int MAX_DEGRADE_LEVEL = 3;
    private static final long SEEK_PENDING_TIMEOUT_MS = 60000L;
    private static final long SEEK_COMPLETE_GRACE_MS = 30000L;
    private static final long SEEK_APPLY_DEBOUNCE_MS = 160L;
    private static final long MEDIA_ACTIVE_STALL_GRACE_MS = 60000L;
    private static final long BUFFER_UI_STALL_MS = 3000L;
    private static final long BUFFER_UI_REQUEST_MS = 1000L;
    private static final long FNOS_PLAYLIST_WINDOW_MS = 4000000L;
    /** 上一次进入 prepared 的时刻，用来区分「连续卡顿」和「长片中途偶发卡顿」。 */
    private long healthySinceMs = 0L;
    /** 本片是否已经用到「厂商内核错误 → 回落 ExoPlayer」的名额（每部片只用一次）。 */
    private boolean engineFallbackUsed = false;
    /** 下一次 startPlayback 是否强制用平台 MediaPlayer（回落时置位，用后立即清除）。 */
    private boolean forcePlatformEngine = false;
    /** 下一次 startPlayback 是否强制用 ExoPlayer（厂商内核失败时的回落，用后立即清除）。 */
    private boolean forceExoEngine = false;
    /** 最近一次真正打开的候选流：回落时沿用同一条流，不重新建选流计划。 */
    private StreamCandidate lastCandidate = null;
    /** 最近一次 open() 请求的起播位置：内核还没出画就失败时，回落从这里续播。 */
    private long lastStartPositionMs = 0L;
    /** open() 时的代理请求计数基准：用来判定内核是否连一次取流都没发出来。 */
    private int mediaRequestsAtOpen = 0;
    /** 内核启动宽限：超过这个时长仍没有任何取流请求，判定为内核初始化失败。 */
    private static final long ENGINE_START_GRACE_MS = 20000L;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        surface = (SurfaceView) findViewById(R.id.player_surface);
        extendSurfaceForDecoderPadding();
        osdTitle = (TextView) findViewById(R.id.osd_title);
        osdMeta = (TextView) findViewById(R.id.osd_meta);
        osdNotice = (TextView) findViewById(R.id.osd_notice);
        loading = (TextView) findViewById(R.id.player_loading);
        osdPanel = (FrameLayout) findViewById(R.id.osd_panel);
        holder = surface.getHolder();
        holder.addCallback(this);

        String itemJson = getIntent().getStringExtra("item");
        L.i("Player", "intent=" + getIntent().toUri(Intent.URI_INTENT_SCHEME));
        if (itemJson == null || itemJson.length() == 0) {
            itemJson = App.from(this).config().loadLastItem();
        }
        item = MediaItem.fromJson(Json.parse(itemJson == null || itemJson.length() == 0 ? "{}" : itemJson));
        // 必须用副本：ConfigStore.load() 返回进程级缓存实例，切档/降级对
        // transcodeResolution / forceTranscode 的改写只能作用于本次播放，
        // 不能泄漏到退出后的下一次播放（否则「自动」会沿用上次失败的低档）。
        cfg = App.from(this).config().load().server.copy();
        // 起播顺手后台回读一次账号偏好：网页端改了首选语言/卡片显示，不必先进设置页。
        // 只更新本地缓存供随后的选轨读取，失败静默，不占用起播时序。
        syncUserPrefsFromServer();
        Capability saved = App.from(this).config().load().capability;
        cap = saved == null ? Capability.baseline() : saved;

        sessionKeeper = new PlaySessionKeeper(cfg, item, App.from(this).useCases(cfg));
        sessionKeeper.setExpiredHook(new Runnable() {
            public void run() {
                ui.post(new Runnable() {
                    public void run() {
                        long pos = 0L;
                        try {
                            if (player != null) {
                                pos = toAbsolutePositionMs(player.getPositionMs());
                            }
                        } catch (Throwable ignored) {
                        }
                        // 会话过期不算解码能力失败：不降画质、不累计停滞次数，
                        // 直接重建 play_link 并续播到当前进度。
                        recoverFromStall(pos, true);
                    }
                });
            }
        });
        sessionKeeper.setRenewedHook(new Runnable() {
            public void run() {
                ui.post(new Runnable() {
                    public void run() {
                        hlsClock.invalidateSegment();
                        if (hlsVtt != null) {
                            hlsVtt.onSessionRenewed();
                        }
                        requestSubtitleFrame();
                    }
                });
            }
        });

        api = App.from(PlayerActivity.this).useCases(cfg);
        // 必须等 item 解析完再绑定：直播 / 影视的 OSD 控件集合不同
        bindPlayerChrome();
        // 多集片源：异步读兄弟集，读到后再显示「选集 / 上一集 / 下一集」
        loadEpisodeContext();
        // 官方 OSD：标题只放片名，上一行由 updateOsdMeta 显示分集信息或当前播放规格。
        osdTitle.setText(item.title == null || item.title.length() == 0
                ? item.displayTitle() : item.title);
        updateOsdMeta();
        // 字幕层是「字幕内容」而不是状态标签：默认无字幕就必须空且不可见。
        // 过去这里写死「字幕：关闭」并常驻，导致关闭字幕后画面一直挂着提示文字。
        clearSubtitleLayer();
        loading.setText("正在准备播放方案…");
        showLoading();
        buildPlan();
        ui.post(progressTicker);
        // play/info 会记住上次选中的字幕；若是外挂字幕则自动加载并按进度渲染
        String localSub = getIntent().getStringExtra("subtitlePath");
        if (localSub != null && localSub.length() > 0) {
            // 指定了本地字幕文件（SRT/VTT/ASS）时优先用它，不再自动恢复 NAS 字幕
            loadLocalSubtitle(localSub);
        } else {
            restoreSavedSubtitle();
        }
    }

    // ------------------------------------------------------------------
    // 选流计划：取流 -> 能力匹配 -> 原始流优先/NAS 解码流回退 -> 解析转码地址
    // ------------------------------------------------------------------

    private void buildPlan() {
        directLinkTipShown = false;
        planReady = false;
        final int generation = ++planGeneration;
        final PlaybackPlan previous = plan;
        new Thread(new Runnable() {
            public void run() {
                if (!isCurrentPlan(generation)) {
                    return;
                }
                try {
                    // 切档/降级会重建会话：先结束上一个会话（停心跳 + 通知 NAS 回收），
                    // 否则旧会话继续占着同片转码槽，新会话建得出但不产片（黑屏有声）。
                    if (sessionKeeper != null) {
                        if (authProxy != null) {
                            authProxy.detachSession();
                        }
                        String current = sessionKeeper.currentPlayLink();
                        sessionKeeper.quitSession();
                        endPlanSessions(previous, current);
                    }
                    if (!isCurrentPlan(generation)) {
                        return;
                    }
                    final MediaUseCases api = App.from(PlayerActivity.this).useCases(cfg);
                    final List<QualityLevel> levels = new ArrayList<QualityLevel>();
                    final List<RouteOption> routes = new ArrayList<RouteOption>();
                    try {
                        levels.addAll(api.qualityLevels(cfg, item));
                    } catch (Throwable t) {
                        L.w("Player", "画质挡位读取失败：" + t);
                    }
                    if (liveMode) {
                        try {
                            routes.addAll(api.liveRoutes(cfg, item));
                        } catch (Throwable t) {
                            L.w("Player", "线路挡位读取失败：" + t);
                        }
                    }
                    if (!subtitlePlanSeeded && !liveMode
                            && (item.subtitleGuid == null || item.subtitleGuid.length() == 0)) {
                        subtitlePlanSeeded = true;
                        String wanted = savedSubtitleGuid();
                        if (wanted == null || wanted.length() > 0) {
                            try {
                                List<SubtitleTrack> tracks = api.listSubtitles(cfg, item);
                                SubtitleTrack chosen = null;
                                if (wanted != null && wanted.length() > 0) {
                                    for (int i = 0; i < tracks.size(); i++) {
                                        SubtitleTrack t = tracks.get(i);
                                        if (wanted.equals(t.guid)) {
                                            chosen = t;
                                            break;
                                        }
                                    }
                                } else {
                                    chosen = matchSubtitleLanguage(tracks,
                                            prefLanguage(KEY_SUBTITLE_LANG));
                                    if (chosen == null) {
                                        for (int i = 0; i < tracks.size(); i++) {
                                            if (tracks.get(i).isDefault) {
                                                chosen = tracks.get(i);
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (chosen != null && chosen.isBurnedIn()) {
                                    item.subtitleGuid = chosen.guid;
                                    L.i("Osd", "字幕预解析：内嵌轨 " + chosen.displayName()
                                            + " 随首个会话一起建");
                                }
                            } catch (Throwable t) {
                                L.w("Osd", "字幕预解析失败（不阻塞起播）：" + t);
                            }
                        }
                    }
                    sessionSubtitleGuid = item.subtitleGuid == null ? "" : item.subtitleGuid;
                    long windowStartMs = resumeAtMs > 0L
                            ? resumeAtMs : item.resumeSeconds * 1000L;
                    if (!liveMode && !fnosLongSegments && "FNOS".equalsIgnoreCase(cfg.apiMode)) {
                        playlistWindow = (int) (windowStartMs / FNOS_PLAYLIST_WINDOW_MS);
                        playlistOffsetMs = (long) playlistWindow * FNOS_PLAYLIST_WINDOW_MS;
                    } else {
                        playlistWindow = 0;
                        playlistOffsetMs = 0L;
                    }
                    if (!isCurrentPlan(generation)) {
                        return;
                    }
                    PlaybackCoordinator.Result built = PlaybackCoordinator.build(
                            api, cfg, item, cap, supportedQualities(levels), degradeLevel,
                            bitrateController, resumeAtMs, playlistWindow, fnosLongSegments);
                    if (!isCurrentPlan(generation)) {
                        endPlanSessions(built.plan, null);
                        return;
                    }
                    bitrateController = built.controller;
                    final PlaybackPlan p = built.plan;
                    final PlaybackRequest playbackRequest = built.request;
                    cfg.transcodeResolution = playbackRequest.resolution();
                    cfg.transcodeBitrateBps = playbackRequest.bitrateBps;
                    cfg.transcodeVideoEncoder = playbackRequest.videoCodec;
                    cfg.transcodeAudioEncoder = playbackRequest.audioCodec;
                    cfg.transcodeAudioChannels = playbackRequest.audioChannels;
                    if (sessionKeeper != null) {
                        sessionKeeper.setPlaybackRequest(playbackRequest);
                    }
                    planNote = built.note;
                    if (built.directFallback) {
                        L.i("Player", "NAS 转码服务不可用，已切换直连原始流");
                    }
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (!isCurrentPlan(generation)) {
                                endPlanSessions(p, null);
                                return;
                            }
                            // 飞牛私有流只认 Authorization 请求头（真机实测 URL/Cookie 均无效）。
                            // 统一改写成带鉴权头的本地回环代理地址，内部 MediaPlayer 与
                            // 外部播放器就都能拿到真实 m3u8 / ts 分片。
                            AuthProxy px = App.from(PlayerActivity.this).proxy(cfg.baseUrl, api.authorizationHeader(cfg));
                            if (px != null) {
                                authProxy = px;
                                // 分片预缓存按影片隔离：同一部片的会话续期只换 hash，
                                // 缓存目录不变，已预取的首段可以继续用。
                                px.setCacheNamespace(item.guid);
                                px.setStartPositionMs(toPlayerPositionMs(playbackRequest.startPositionMs));
                                // 真机实测 /v/media/<hash>/ 会话约 60 秒后统一 410 Gone，
                                // 且持续拉分片不会续命。给代理绑定当前会话目录 + 续期器，
                                // 它会在 40 秒时重新 POST /play/play 换 hash，并把后续
                                // 旧 hash 分片请求透明重映射到新目录，播放不中断。
                                px.setSessionKeeper(sessionKeeper);
                                // 媒体流鉴权失败时由代理触发重新登录，token 在代理内部更新，
                                // 端口不变，在播的播放器不会因换代理而断流。
                                px.setTokenSource(sessionKeeper);
                                for (int i = 0; i < p.candidates.size(); i++) {
                                    StreamCandidate c = p.candidates.get(i);
                                    c.url = px.rewrite(c.url);
                                }
                                L.i("Player", "播放地址已走本地鉴权代理 " + px.baseUrl());
                            }
                            // 直播直链是第三方 https CDN（入口无 .m3u8 后缀、需 302 跳转）。
                            // API<21 的系统 TLS 只有 CBC/3DES/RC4，无法与 CDN 握手，
                            // 厂商播放器走 https 还会在 libmmp 里 native 崩溃；
                            // 统一改走本机回环 HTTP 代理，由代理完成 TLS 与 302、拍平 master 列表。
                            if (liveMode) {
                                for (int i = 0; i < p.candidates.size(); i++) {
                                    StreamCandidate c = p.candidates.get(i);
                                    // 回退状态机按候选 id 前缀识别直播计划：直播没有 NAS
                                    // 解码流，一条线路失败只说明这个源不可用，必须换下一条，
                                    // 而不是拉黑编码后跳到不存在的转码流（否则第一台就 ABORT）。
                                    if (c.id == null || !c.id.startsWith("live:")) {
                                        c.id = "live:" + (c.id == null ? i : c.id);
                                    }
                                    if (android.os.Build.VERSION.SDK_INT < 21) {
                                        c.url = LiveProxy.get().localize(c.url);
                                    }
                                }
                            }
                            applyNasLevels(levels, routes);
                            plan = p;
                            fallback = new FallbackPlayback(plan, host);
                            planReady = true;
                            loading.setText("准备就绪 · " + p.note);
                            if (planNote.length() > 0) {
                                L.i("Player", "播放方案：" + planNote);
                            }
                            if (surfaceReady) {
                                startFallback();
                            }
                        }
                    });
                } catch (final FnException ex) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (!isCurrentPlan(generation)) {
                                return;
                            }
                            hideLoading();
                            if (shouldShowFatal(ex.detail)) {
                                showErrorPanel(FailureReason.CONNECTION_FAILED, ex.detail, new Runnable() {
                                    public void run() {
                                        buildPlan();
                                    }
                                });
                            }
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (!isCurrentPlan(generation)) {
                                return;
                            }
                            loading.setText("准备失败");
                            if (shouldShowFatal(t.toString())) {
                                showErrorPanel(FailureReason.UNKNOWN, t.toString(), new Runnable() {
                                    public void run() {
                                        buildPlan();
                                    }
                                });
                            }
                        }
                    });
                }
            }
        }, "fntv-plan").start();
    }

    private boolean isCurrentPlan(int generation) {
        return !destroyed && generation == planGeneration;
    }

    private static String sessionKey(String url) {
        if (url == null) {
            return "";
        }
        String dir = SessionPolicy.mediaDirOf(url);
        return dir.length() > 0 ? dir : url;
    }

    private void endPlanSessions(PlaybackPlan source, String keepUrl) {
        if (source == null || sessionKeeper == null) {
            return;
        }
        String keep = sessionKey(keepUrl);
        HashSet<String> ended = new HashSet<String>();
        for (int i = 0; i < source.candidates.size(); i++) {
            PlaybackSession session = source.candidates.get(i).session;
            if (session == null || session.isEmpty() || session.url.length() == 0) {
                continue;
            }
            String key = sessionKey(session.url);
            if (key.equals(keep) || !ended.add(key)) {
                continue;
            }
            sessionKeeper.endSessionAsync(session.url);
        }
    }

    private void bindCandidateSession(StreamCandidate candidate) {
        if (candidate == null || candidate.session == null || candidate.session.isEmpty()
                || sessionKeeper == null) {
            return;
        }
        String url = candidate.session.url;
        if (authProxy != null && SessionPolicy.mediaDirOf(url).length() > 0) {
            authProxy.bindSession(url);
        } else {
            sessionKeeper.onSessionBound(url);
        }
    }

    private void releaseUnusedPlanSessions(StreamCandidate selected) {
        if (sessionKeeper == null || !"FNOS".equalsIgnoreCase(cfg.apiMode)) {
            return;
        }
        String keep = selected != null && selected.session != null && !selected.session.isEmpty()
                ? selected.session.url : "";
        if (keep.length() == 0) {
            sessionKeeper.endSessionAsync();
        }
        endPlanSessions(plan, keep);
    }

    private void scheduleUnusedSessionCleanup(final StreamCandidate selected) {
        if (releaseUnusedSessionsTask != null) {
            ui.removeCallbacks(releaseUnusedSessionsTask);
        }
        releaseUnusedSessionsTask = new Runnable() {
            public void run() {
                releaseUnusedSessionsTask = null;
                if (prepared && player != null && lastCandidate == selected && !recovering) {
                    releaseUnusedPlanSessions(selected);
                }
            }
        };
        ui.postDelayed(releaseUnusedSessionsTask, 15000L);
    }

    private void stopSubtitleWork() {
        if (hlsVtt != null) {
            hlsVtt.stop();
            hlsVtt = null;
        }
        hlsDoc = "";
        hlsClock.invalidateSegment();
    }

    private void detachPlaybackSession() {
        if (authProxy != null) {
            authProxy.detachSession();
            authProxy.clearSegmentCache();
        }
    }

    private void startFallback() {
        if (startRequested || fallback == null) {
            return;
        }
        startRequested = true;
        // 直播要走本机代理回源，厂商 M3U8 解析器只等 2~4 秒就报错；
        // 首条线路的 playlist 没预热好就交给它，只会立刻失败。先等预热完成再起播。
        if (liveMode && android.os.Build.VERSION.SDK_INT < 21) {
            awaitLivePlaylist();
            return;
        }
        fallback.start();
    }

    /**
     * 直播起播前先预热首条（以及第二条）线路的 playlist，再交给回退链。
     * 直播一个频道有 8 条候选，全部并发预热会把 CDN 打到 429，
     * 其余线路在切换时由 {@link #host}.play 按需预热。
     * 等待逻辑统一放在 awaitCandidateWarm 里，避免两处各等一遍把起播拖长。
     */
    private void awaitLivePlaylist() {
        if (fallback == null || plan == null || plan.candidates.isEmpty()) {
            return;
        }
        LiveProxy lp = LiveProxy.get();
        lp.warm(plan.candidates.get(0).url);
        if (plan.candidates.size() > 1) {
            lp.warm(plan.candidates.get(1).url);
        }
        showLoading();
        loading.setText("正在连接直播线路…");
        fallback.start();
    }

    private final FallbackPlayback.Host host = new FallbackPlayback.Host() {
        public void play(final StreamCandidate c) {
            final int token = ++liveAttemptToken;
            ui.post(new Runnable() {
                public void run() {
                    notice("正在尝试 " + c.source.label() + "：" + c.label);
                    if (liveMode && android.os.Build.VERSION.SDK_INT < 21) {
                        // 直播线路必须先有 playlist 再交给厂商播放器：
                        // 它连上后 0.5 秒没数据就 prepareAsync 失败。
                        awaitCandidateWarm(c, 0, token);
                        return;
                    }
                    playNow(c);
                }
            });
        }

        /** 该线路的本地 playlist 就绪后（或确认拿不到）再真正起播。 */
        private void awaitCandidateWarm(final StreamCandidate c, final int attempt, final int token) {
            // 状态机已经换到别的线路了：这条迟到的等待链必须作废，
            // 否则一次失败会被两个回调/两处超时重复上报，白白烧掉候选。
            if (token != liveAttemptToken) {
                return;
            }
            LiveProxy lp = LiveProxy.get();
            lp.warm(c.url);
            if (attempt == 0) {
                prewarmNext(c);
            }
            boolean ready = lp.isPlaylistReady(c.url);
            if (ready) {
                // playlist 就绪后，非 EO 线路再等首分片预取完成：整段在内存才能瞬时出画，
                // 否则 MStar 4 秒首帧窗口内下不完 4.6MB 分片，必报 flip first frame timeout / -5003。
                if (!lp.isEdgeOneLine(c.url) && !lp.isFirstSegmentReady(c.url)) {
                    awaitFirstSegment(c, token, 0);
                    return;
                }
                startAfterWarm(c, token);
                return;
            }
            if (lp.hasPlaylistFailed(c.url)) {
                // 连续多次回源失败（实测 EO 线路基本必失败）：别再干等，直接换下一条
                L.w("Player", "线路 playlist 连续回源失败，换下一条 " + c.label);
                if (fallback != null) {
                    fallback.onFailure(FailureReason.PLAYBACK_FAILED, "线路 playlist 回源失败");
                }
                return;
            }
            if (attempt >= LIVE_PLAYLIST_WAIT_TICKS) {
                // 关键：playlist 没就绪绝不能交给厂商播放器——它 0.5 秒就会 [AVP] no input，
                // 白白占掉一次尝试。这里直接算这条线路失败，让状态机往下走。
                L.w("Player", "线路 playlist " + (attempt / 2) + " 秒未就绪，换下一条 " + c.label);
                if (fallback != null) {
                    fallback.onFailure(FailureReason.PLAYBACK_FAILED,
                            "线路 playlist " + (attempt / 2) + " 秒未就绪");
                }
                return;
            }
            // 换线等待期间旧线路可能还在正常出画，别用整屏「正在连接」盖住画面
            if (!prepared) {
                showLoading();
                loading.setText("正在连接直播线路…");
            }
            ui.postDelayed(new Runnable() {
                public void run() {
                    awaitCandidateWarm(c, attempt + 1, token);
                }
            }, 500L);
        }

        /** playlist 就绪后，等首分片预取完成再起播（非 EO）；超时则 best-effort 起播。 */
        private void awaitFirstSegment(final StreamCandidate c, final int token, final int segAttempt) {
            if (token != liveAttemptToken) {
                return;
            }
            LiveProxy lp = LiveProxy.get();
            if (lp.isEdgeOneLine(c.url) || lp.isFirstSegmentReady(c.url)
                    || segAttempt >= LIVE_FIRST_SEG_WAIT_TICKS) {
                startAfterWarm(c, token);
                return;
            }
            showLoading();
            loading.setText("正在缓冲直播首帧…");
            ui.postDelayed(new Runnable() {
                public void run() {
                    awaitFirstSegment(c, token, segAttempt + 1);
                }
            }, 500L);
        }

        /** 预热（含首分片预取）完成后真正起播；换线时先释放旧内核并留足 settle 时间。 */
        private void startAfterWarm(final StreamCandidate c, final int token) {
            if (token != liveAttemptToken) {
                return;
            }
            if (livePlayAttempts++ > 0) {
                // 先释放旧内核，再留出 mstplayer 清理 DM/STM 的时间；
                // 紧接着 new MediaPlayer() 会 initCheck FAIL（0.5 秒 prepareAsync 失败）。
                releasePlayer();
                showLoading();
                loading.setText("正在切换直播线路…");
                ui.postDelayed(new Runnable() {
                    public void run() {
                        if (token == liveAttemptToken) {
                            playNow(c);
                        }
                    }
                }, LIVE_SWITCH_SETTLE_MS);
            } else {
                playNow(c);
            }
        }

        /** 提前预热紧邻的两条线路（只在起播该线路时触发一次）。 */
        private void prewarmNext(StreamCandidate c) {
            if (plan == null) {
                return;
            }
            for (int i = 0; i < plan.candidates.size() - 1; i++) {
                if (plan.candidates.get(i) == c) {
                    // 入口 302 常被 CDN 反爬拖到 6~33 秒（实测），多预热一条，
                    // 换线时就不必再干等一次「冷启动」。并发控制在 3 条以内，避免 429。
                    LiveProxy.get().warm(plan.candidates.get(i + 1).url);
                    if (i + 2 < plan.candidates.size()) {
                        LiveProxy.get().warm(plan.candidates.get(i + 2).url);
                    }
                    return;
                }
            }
        }

        private void playNow(final StreamCandidate c) {
            if (surfaceReady && surfaceDirty) {
                // 旧实例已经解绑过这个 Surface：先换一块全新的 SurfaceView，
                // 否则新 MediaPlayer 的视频层初始化会超时报 -5003（有声无画）。
                surfaceDirty = false;
                surfaceReady = false;
                pendingCandidate = c;
                replaceSurfaceView();
                return;
            }
            if (surfaceReady) {
                startPlayback(c);
            } else {
                pendingCandidate = c;
            }
        }

        public void launchExternal(final StreamCandidate c) {
            ui.post(new Runnable() {
                public void run() {
                    launchExternalWithFreshSession(c);
                }
            });
        }

        public void notice(final String text) {
            ui.post(new Runnable() {
                public void run() {
                    showNotice(text);
                }
            });
        }

        public void fatal(final FailureReason reason, final String detail) {
            if (externalLaunched) {
                L.w("Player", "已交给系统播放器，忽略内部解码失败：" + detail);
                return;
            }
            ui.post(new Runnable() {
                public void run() {
                    hideLoading();
                    // 4K/10bit 等编码只在真正 prepare/解码时才暴露能力不足：
                    // 此时按 NAS 挡位再降一档重建，而不是直接把失败甩给用户。
                    if (tryDegradeAfterFailure(reason, detail)) {
                        return;
                    }
                    // 降档用尽仍失败，且是明确的解码/渲染类错误：一次性换内核再试。
                    if (tryAlternateEngineFallback(reason, detail)) {
                        return;
                    }
                    releasePlayer();
                    if (!shouldShowFatal(detail)) {
                        return;
                    }
                    showErrorPanel(reason, detail, new Runnable() {
                        public void run() {
                            releasePlayer();
                            startRequested = false;
                            buildPlan();
                        }
                    });
                }
            });
        }
    };

    private boolean isErrorPanelShowing() {
        return playErrorView != null && playErrorView.getVisibility() == View.VISIBLE;
    }

    /** 官方 181 错误面板：全屏覆盖视频层，固定「未知错误」文案 + 重试/退出按钮。
     *  不同失败原因只影响内部日志，面板 UI 本身不随错误类型变化（和官方一致）。 */
    private void showErrorPanel(FailureReason reason, String detail, Runnable retry) {
        L.i("Player", "showErrorPanel reason=" + reason + " detail=" + detail);
        if (isNetworkReason(reason)) {
            retryOnError = retry;
            showNetworkUnavailable(new Runnable() {
                public void run() {
                    retryError();
                }
            });
            return;
        }
        retryOnError = retry;
        // 错误面板必须盖住 loading（官方无 loading 残留）
        hideLoading();
        collapseOsd();
        if (playErrorView != null) {
            playErrorView.setVisibility(View.VISIBLE);
        }
        if (playErrorMsg != null) {
            // 官方固定文案 "未知错误"，不按错误类型区分
            playErrorMsg.setText("未知错误");
        }
        // 官方默认焦点在「退出」按钮（tv_exit focused + selected）
        errorFocusPending = true;
        if (playErrorView != null) {
            playErrorView.post(new Runnable() {
                public void run() { focusErrorPanel(); }
            });
        }
    }

    private void focusErrorPanel() {
        if (!isErrorPanelShowing() || btnErrorExit == null) {
            return;
        }
        View focused = getCurrentFocus();
        if (focused != btnErrorRetry && focused != btnErrorExit) {
            btnErrorExit.requestFocus();
        }
        errorFocusPending = false;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && (errorFocusPending || isErrorPanelShowing())) {
            focusErrorPanel();
        }
    }

    private void bindErrorButtonFocus(View button) {
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundDrawable(roundRect(hasFocus ? 0x33FFFFFF : 0x00000000,
                        8, 2, hasFocus ? 0xCCFFFFFF : 0x00000000));
                ((TextView) v).setTextColor(hasFocus ? 0xFFFFFFFF : 0xCCFFFFFF);
                v.setSelected(hasFocus);
            }
        });
    }

    private void hideErrorPanel() {
        retryOnError = null;
        errorFocusPending = false;
        if (playErrorView != null) {
            playErrorView.setVisibility(View.GONE);
        }
        if (surface != null) {
            surface.requestFocus();
        }
    }

    /** 网络类失败走官方 dialog_net_unavailable（650 宽、图标 + 网络异常 + 重试），
     *  其余失败仍走全屏错误面板。 */
    private static boolean isNetworkReason(FailureReason reason) {
        return reason == FailureReason.CONNECTION_FAILED || reason == FailureReason.TIMEOUT
                || reason == FailureReason.NETWORK_INTERRUPTED;
    }

    private void showNetworkUnavailable(final Runnable retry) {
        L.i("Player", "showNetworkUnavailable");
        hideLoading();
        final android.app.Dialog d = newOsdDialog();
        FrameLayout root = dialogRoot(d);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        panel.setBackgroundDrawable(roundRect(0xFF2F3035, 48, 2, 0x33FFFFFF));
        panel.setPadding(38, 48, 38, 68);

        // 官方是 180dp 的 ic_player_no_network 图标；无位图资源时用同尺寸描边圆 + 感叹号。
        TextView mark = pxText("!", 72, 0xCCFFFFFF);
        mark.setGravity(android.view.Gravity.CENTER);
        mark.setBackgroundDrawable(roundRect(0x00000000, 90, 3, 0x66FFFFFF));
        panel.addView(mark, new LinearLayout.LayoutParams(180, 180));

        TextView title = pxText("网络异常\n请检查网络设置后重试", 34, 0xFFFFFFFF);
        title.setGravity(android.view.Gravity.CENTER);
        title.setLineSpacing(6, 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = 30;
        panel.addView(title, tlp);

        TextView btn = dialogButton("重试", new Runnable() {
            public void run() {
                if (retry != null) {
                    retry.run();
                }
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 68);
        blp.topMargin = 52;
        panel.addView(btn, blp);

        root.addView(panel, new FrameLayout.LayoutParams(650,
                FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
        d.show();
        btn.requestFocus();
    }

    /** 官方 dialog_directlink_tip：504 宽、标题 32 + 说明 24 + 「知道了」。 */
    private void showDirectLinkTip() {
        if (isFinishing() || osdDialog != null) {
            return;
        }
        final android.app.Dialog d = newOsdDialog();
        FrameLayout root = dialogRoot(d);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundDrawable(roundRect(0xFF2F3035, 48, 2, 0x33FFFFFF));
        panel.setPadding(32, 32, 32, 32);

        TextView title = pxText("直连播放缺失内置字幕", 32, 0xCCFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView body = pxText("由于网盘方的限制，直连转码播放时可能无法获取内置字幕列表。"
                + "如需切换内置字幕，请切换播放方式为“NAS 代理播放”。", 24, 0x99FFFFFF);
        body.setTypeface(Typeface.DEFAULT_BOLD);
        body.setLineSpacing(8, 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = 16;
        panel.addView(body, blp);

        TextView btn = dialogButton("知道了", null);
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 66);
        okLp.topMargin = 43;
        panel.addView(btn, okLp);

        root.addView(panel, new FrameLayout.LayoutParams(504,
                FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
        d.show();
        btn.requestFocus();
        L.i("Osd", "directlink tip shown");
    }

    private void retryError() {
        hideErrorPanel();
        if (retryOnError != null) {
            Runnable r = retryOnError;
            retryOnError = null;
            r.run();
        }
    }

    private boolean shouldShowFatal(String detail) {
        if (fatalShown || isErrorPanelShowing()) {
            L.w("Player", "失败已提示，自动重试不再打断遥控器：" + detail);
            return false;
        }
        fatalShown = true;
        return true;
    }

    /**
     * 交给系统播放器前必须先拿到新的 play_link。
     *
     * 真机 60 秒干净观察：停滞恢复直接把旧 preset.m3u8 交给
     * com.tcl.common.mediaplayer，上游已经 410 Gone，结果 MediaPlayer error(1,0)。
     * 外部播放器既不能附加 Authorization 头，也不会自己换 URL，所以这里在启动前
     * 重新创建会话，并把续期器/会话目录绑定到代理（外部播放期间本地代理仍在续期）。
     */
    private void launchExternalWithFreshSession(final StreamCandidate c) {
        final int generation = planGeneration;
        new Thread(new Runnable() {
            public void run() {
                String freshTmp = "";
                try {
                    MediaUseCases api = App.from(PlayerActivity.this).useCases(cfg);
                    freshTmp = api.refreshPlayLink(cfg, item);
                } catch (Throwable t) {
                    L.w("Player", "外部播放前重建会话失败，沿用原候选：" + t);
                }
                // Java 6 目标兼容：匿名内部类里引用的局部变量必须是 final。
                final String fresh = freshTmp;
                final String target = fresh.length() > 0 ? fresh : c.url;
                ui.post(new Runnable() {
                    public void run() {
                        if (!isCurrentPlan(generation)) {
                            if (fresh.length() > 0) {
                                sessionKeeper.endSessionAsync(fresh);
                            }
                            return;
                        }
                        AuthProxy px = App.from(PlayerActivity.this).proxy(cfg.baseUrl, api.authorizationHeader(cfg));
                        String playUrl = target;
                        if (px != null) {
                            px.setSessionKeeper(sessionKeeper);
                            px.setTokenSource(sessionKeeper);
                            if (fresh.length() > 0) {
                                sessionKeeper.endSessionAsync();
                            }
                            if (SessionPolicy.mediaDirOf(target).length() > 0) {
                                px.bindSession(target);
                            } else {
                                sessionKeeper.onSessionBound(target);
                            }
                            playUrl = px.rewrite(target);
                        } else {
                            sessionKeeper.onSessionBound(target);
                        }
                        L.i("Player", "外部播放器使用" + (fresh.length() > 0 ? "新" : "原")
                                + "会话地址 " + android.net.Uri.parse(playUrl).getPath());
                        externalLaunched = true;
                        try {
                            if (player != null && prepared) {
                                lastProgressMs = toAbsolutePositionMs(player.getPositionMs());
                                item.playbackPositionSec = (int) (lastProgressMs / 1000L);
                            }
                        } catch (Throwable ignored) {
                        }
                        releasePlayer();
                        stopSubtitleWork();
                        ui.removeCallbacks(prepareWatchdog);
                        ui.removeCallbacks(progressWatchdog);
                        ui.removeCallbacks(progressTicker);
                        ui.removeCallbacks(subtitleTicker);
                        hideLoading();
                        try {
                            startActivity(systemPlayerIntent(playUrl));
                            Ui.toast(PlayerActivity.this, "已交给系统播放器");
                        } catch (Throwable t) {
                            externalLaunched = false;
                            L.e("Player", "系统播放器启动失败", t);
                            detachPlaybackSession();
                            sessionKeeper.endSessionAsync();
                            host.fatal(FailureReason.PLAYBACK_FAILED, "系统播放器启动失败");
                        }
                    }
                });
            }
        }, "fntv-external-refresh").start();
    }

    // ------------------------------------------------------------------
    // MediaPlayer 生命周期
    // ------------------------------------------------------------------

    /**
     * 外部播放时优先锁定设备自带的系统播放器，不依赖 VLC 之类第三方应用。
     * queryIntentActivities 的结果已按 priority 排序，取第一个带 FLAG_SYSTEM 的
     * 候选即可；找不到（例如某些定制 ROM 把播放器做成非系统应用）就退回默认解析。
     */
    private Intent systemPlayerIntent(String url) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        if (url != null && url.indexOf("m3u8") >= 0) {
            // 直播 HLS 直链：不带 video/* MIME，否则系统播放器会被
            // queryIntentActivities 过滤掉，最终报「系统播放器启动失败」。
            i.setData(Uri.parse(url));
        } else {
            i.setDataAndType(Uri.parse(url), "video/*");
        }
        try {
            PackageManager pm = getPackageManager();
            List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
            for (int k = 0; k < list.size(); k++) {
                ResolveInfo ri = list.get(k);
                if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) {
                    continue;
                }
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    continue; // 跳过用户安装的第三方播放器
                }
                i.setComponent(new ComponentName(ai.packageName, ri.activityInfo.name));
                L.i("Player", "使用系统自带播放器 " + ai.packageName + "/" + ri.activityInfo.name);
                break;
            }
        } catch (Throwable t) {
            L.w("Player", "未能锁定系统播放器，使用默认解析：" + t);
        }
        return i;
    }

    private void startPlayback(final StreamCandidate c) {
        if (c == null || c.url == null || c.url.length() == 0) {
            if (fallback != null) {
                fallback.onFailure(FailureReason.PLAYBACK_FAILED, "候选流地址为空");
            }
            return;
        }
        bindCandidateSession(c);
        releasePlayer();
        prepared = false;
        seekPending = false;
        seekApplyScheduled = false;
        pendingSeekPositionMs = -1L;
        seekWatchdogGraceUntilMs = 0L;
        engineBuffering = false;
        bufferingSinceMs = 0L;
        bufferUiStalledSinceMs = 0L;
        stalledSinceMs = 0L;
        currentPlaylistUrl = c.url;
        clockPlaylistUrl = withoutLocalPlaylistQueries(c.url);
        lastCandidate = c;
        updateOsdMeta();
        showLoading();
        loading.setText("正在打开 " + c.source.label() + "…");
        try {
            // 回落标记只对「下一次打开」生效，用掉就清掉；
            // 默认使用启动探测选出的内核（MediaCodec 可用时优先 ExoPlayer）。
            final PlayerEngine engine;
            boolean jellyfinHls = "JELLYFIN".equalsIgnoreCase(cfg.apiMode)
                    && "hls".equalsIgnoreCase(c.protocol);
            if (forceExoEngine || (jellyfinHls && android.os.Build.VERSION.SDK_INT >= 21)) {
                forceExoEngine = false;
                engine = new Exo2Engine(PlayerActivity.this);
            } else if (forcePlatformEngine || jellyfinHls) {
                forcePlatformEngine = false;
                engine = new MediaPlayerEngine(PlayerActivity.this);
            } else {
                engine = EngineFactory.create(PlayerActivity.this);
            }
            player = engine;
            // 内核能力决定 OSD 标签与设置项：换内核后立刻重算可见性
            applyOsdMode();
            final int gen = ++prepareGeneration;
            prepareStartedAt = System.currentTimeMillis();
            L.i("Player", "播放内核=" + engine.name() + " sdk=" + android.os.Build.VERSION.SDK_INT
                    + " gen=" + gen);

            final Map<String, String> headers = new HashMap<String, String>();
            if (cfg.token != null && cfg.token.length() > 0) {
                headers.put("Authorization", api.authorizationHeader(cfg));
                if (!"JELLYFIN".equalsIgnoreCase(cfg.apiMode)) {
                    headers.put("Play-Link", cfg.token);
                }
            }
            engine.setListener(new PlayerEngine.Listener() {
                public void onEnginePrepared() {
                    if (player != engine) {
                        return;
                    }
                    ui.removeCallbacks(prepareWatchdog);
                    prepared = true;
                    if (c.session == null || c.session.isEmpty()) {
                        scheduleUnusedSessionCleanup(c);
                    }
                    // 播放已恢复：清掉「只提示一次」的故障标记 + 隐藏错误面板
                    fatalShown = false;
                    hideErrorPanel();
                    recovering = false;
                    if (userPaused) {
                        try {
                            player.pause();
                        } catch (Throwable ignored) {
                        }
                        if (centerPlay != null) {
                            centerPlay.setVisibility(View.VISIBLE);
                        }
                    }
                    lastProgressMs = -1L;
                    stalledTicks = 0;
                    stalledSinceMs = 0L;
                    bufferUiStalledSinceMs = 0L;
                    healthySinceMs = System.currentTimeMillis();
                    // 起播定位由内核在 open() 内完成，这里清空续播锚点
                    resumeAtMs = 0L;
                    hideLoading();
                    updateOsdMeta();
                    // 转码方案说明只在起播阶段有意义；起播后它与 osdMeta 重复，清空
                    osdNotice.setText("");
                    if (fallback != null) {
                        fallback.onPlaybackStarted();
                    }
                    scheduleProgressCheck();
                    // 官方「在屏幕上显示播放信息」开启时，起播后自动展示浮层
                    if (playerPrefs.getBoolean("info_overlay", false)) {
                        ui.postDelayed(new Runnable() {
                            public void run() {
                                showPlayInfo();
                            }
                        }, 300);
                    }
                    // 起播后 5 秒内字幕对时器不拉播放列表/探测分片：
                    // 这段时间带宽只留给视频分片，避免 115KB m3u8 与首段争抢。
                    if (engine.needsHlsClockCorrection()) {
                        hlsClock.holdOff(5000L);
                    }
                    // 起播（可能带续播 seek）后按真实位置立即定位字幕
                    requestSubtitleFrame();
                    // 首选音频语言需要等内核把音轨填好再匹配
                    ui.postDelayed(new Runnable() {
                        public void run() {
                            applyPreferredAudio();
                        }
                    }, 1200L);
                }

                public void onEngineSeekComplete() {
                    if (player == engine) {
                        long pos = toAbsolutePositionMs(player.getPositionMs());
                        boolean matched = !seekPending
                                || Math.abs(pos - pendingSeekPositionMs) <= 1500L;
                        if (matched) {
                            seekPending = false;
                            seekWatchdogGraceUntilMs = SystemClock.elapsedRealtime()
                                    + SEEK_COMPLETE_GRACE_MS;
                        }
                        lastProgressMs = pos;
                        stalledTicks = 0;
                        requestSubtitleFrame();
                    }
                }

                public void onEngineError(FailureReason reason, int what, int extra, String detail) {
                    L.w("Player", "内核错误 " + reason + " what=" + what + " extra=" + extra
                            + " " + detail);
                    // 已释放的旧内核仍可能投递排队中的错误回调，不能算到新方案头上。
                    if (player != engine) {
                        L.i("Player", "忽略已释放内核的错误回调");
                        return;
                    }
                    // 会话过期续期失败时，代理会如实回 410；此刻 onError 与
                    // sessionKeeper 的过期回调可能同时到达。若已经在重建方案，
                    // 这里直接吞掉，避免一边重建一边又切到外部播放器。
                    if (recovering) {
                        L.i("Player", "正在重建播放方案，忽略本次重复错误回调");
                        return;
                    }
                    if (fnosLongSegments && authProxy != null && authProxy.virtualModeFailed()) {
                        long pos = 0L;
                        try {
                            pos = toAbsolutePositionMs(player.getPositionMs());
                        } catch (Throwable ignored) {
                        }
                        usePlaylistWindowFallback(pos);
                        return;
                    }
                    // 同一台内核在释放过程中还会再抛一次 what=-38（实测紧跟在 -5003 后 16ms），
                    // 不拦住会被状态机当成第二次失败，白白烧掉下一条候选。
                    if (failedGeneration == prepareGeneration) {
                        L.i("Player", "同一内核重复错误回调，忽略 " + reason);
                        return;
                    }
                    failedGeneration = prepareGeneration;
                    if (fallback != null) {
                        fallback.onFailure(reason, "what=" + what + " extra=" + extra);
                    }
                }

                public void onEngineCompletion() {
                    if (player != engine) {
                        return;
                    }
                    long pos = toAbsolutePositionMs(player.getPositionMs());
                    long expected = resolveDurationMs();
                    L.i("Player", "播放结束 pos=" + pos + "ms dur="
                            + expected + "ms autoplay="
                            + playerPrefs.getBoolean("autoplay", true));
                    // MStar 的 HLS 解析器在 2048 个 URL 处会提前回调 completion，
                    // 但底层分片仍可能继续下载。未到服务端总时长时不能清进度、
                    // 连播或弹出结束页，否则长片会在约 68 分钟被提前结束。
                    if (expected > 0L && pos + 10000L < expected) {
                        if (fnosLongSegments && authProxy != null && authProxy.virtualModeFailed()) {
                            usePlaylistWindowFallback(pos);
                            return;
                        }
                        long windowEnd = playlistOffsetMs + FNOS_PLAYLIST_WINDOW_MS;
                        if (!fnosLongSegments && isWindowedFnosPlaylist()
                                && pos >= windowEnd - 15000L
                                && expected > windowEnd + 10000L) {
                            advancePlaylistWindow(pos);
                            return;
                        }
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastPrematureCompletionMs >= 5000L) {
                            lastPrematureCompletionMs = now;
                            L.w("Player", "忽略内核提前结束回调，继续播放 @" + pos
                                    + "ms（总时长 " + expected + "ms）");
                            if (!player.isPlaying()) {
                                player.play();
                            }
                            scheduleProgressCheck();
                        }
                        return;
                    }
                    // 网页端：播放结束向 NAS 回传 ts=0，清除继续观看记录
                    reportOnExit(true);
                    // 自动连播在真实播完时也必须生效：未设片尾时长时片尾跳过不会触发
                    if (!liveMode && playerPrefs.getBoolean("autoplay", true)
                            && autoplayNextEpisode()) {
                        return;
                    }
                    showPlaybackEndedDialog();
                }

                public void onEngineVideoSize(int width, int height) {
                    if (width > 0 && height > 0) {
                        subtitleStorageW = width;
                        subtitleStorageH = height;
                        updateOsdMeta();
                        L.i("Player", "视频分辨率 " + width + "x" + height
                                + "（字幕画布基准，字幕 scale=1）");
                    }
                }

                public void onEngineBuffering(boolean buffering) {
                    if (player != engine) {
                        return;
                    }
                    if (buffering) {
                        if (!engineBuffering) {
                            bufferingSinceMs = SystemClock.elapsedRealtime();
                        }
                        engineBuffering = true;
                        showLoading();
                    } else {
                        engineBuffering = false;
                        bufferingSinceMs = 0L;
                        bufferUiStalledSinceMs = 0L;
                        lastProgressMs = toAbsolutePositionMs(player.getPositionMs());
                        lastTickPosMs = lastProgressMs;
                        stalledTicks = 0;
                        stalledSinceMs = 0L;
                        hideLoading();
                    }
                }
            });
            // 直播没有可续播的位置：给 0 即可，避免厂商播放器把上次进度当成 seek 目标。
            long startPosition = liveMode ? 0L
                    : (resumeAtMs > 0 ? resumeAtMs : item.resumeSeconds * 1000L);
            lastStartPositionMs = startPosition;
            long playerStartPosition = toPlayerPositionMs(startPosition);
            AuthProxy pxOpen = authProxy;
            mediaRequestsAtOpen = pxOpen == null ? 0 : pxOpen.mediaStatus().requests;
            engine.setVideoSurfaceHolder(holder);
            if (liveMode) {
                // 这条线路从现在开始被播放器使用，空闲计时从这里起算
                LiveProxy.get().markPlaying(c.url);
            }
            engine.open(c.url, headers, playerStartPosition);
            ui.removeCallbacks(prepareWatchdog);
            // 直播的失败判据在 LiveProxy 侧（分片连续失败/长时间无字节），
            // 每 1 秒查一次即可快速换线；点播首片本来就慢，沿用 8 秒首查。
            ui.postDelayed(prepareWatchdog, liveMode ? 1000L : PREPARE_FIRST_CHECK_MS);
        } catch (Throwable t) {
            L.e("Player", "打开流失败", t);
            if (fallback != null) {
                fallback.onFailure(FailureReason.PLAYBACK_FAILED, t.toString());
            }
        }
    }

    private void releasePlayer() {
        ui.removeCallbacks(prepareWatchdog);
        ui.removeCallbacks(progressWatchdog);
        ui.removeCallbacks(applyPendingSeek);
        if (releaseUnusedSessionsTask != null) {
            ui.removeCallbacks(releaseUnusedSessionsTask);
            releaseUnusedSessionsTask = null;
        }
        seekApplyScheduled = false;
        prepareGeneration++;
        // 内核自己按代际丢弃迟到回调；这里只需要通知它停止并释放解码器
        PlayerEngine p = player;
        player = null;
        if (p != null) {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder("releasePlayer(" + p.name() + ") <- ");
            for (int i = 1; i < Math.min(st.length, 7); i++) {
                sb.append(st[i].getMethodName()).append(' ');
            }
            L.d("Player", sb.toString());
            try {
                p.release();
            } catch (Throwable ignored) {
            }
            // 这块 Surface 已经被旧内核解绑过，下一次起播前要整体换 SurfaceView，
            // 否则 MStar 的视频层初始化会 4 秒超时并报 -5003（有声无画）。
            surfaceDirty = true;
        }
        prepared = false;
        // 新会话的分片时间基准可能不同：作废分片定位并立刻重测，但保留上次 skew，
        // 避免每次续期/降档后字幕先错 1~2 秒再跳回来。
        hlsClock.invalidateSegment();
    }

    // ------------------------------------------------------------------
    // Surface + OSD + 遥控器
    // ------------------------------------------------------------------

    public void surfaceCreated(SurfaceHolder h) {
        // 重建 SurfaceView 时旧 Holder 的回调可能迟到，直接丢弃
        if (h != holder) {
            return;
        }
        surfaceReady = true;
        surfaceDirty = false;
        if (player != null) {
            player.setVideoSurfaceHolder(h);
        }
        if (pendingCandidate != null) {
            StreamCandidate c = pendingCandidate;
            pendingCandidate = null;
            startPlayback(c);
        }
        if (planReady) {
            startFallback();
        }
    }

    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        if (h != holder) {
            return;
        }
        // 真机（MStar 4.2.1）：surfaceCreated 时 Surface 可能还没完成 layout，
        // 此时挂视频层会被 setVideoSurfaceTexture 拒绝（-22），解码继续但画面全黑。
        // layout 完成后这里再挂一次；准备阶段由内核自己推迟到 onPrepared。
        if (player != null && h != null
                && h.getSurface() != null && h.getSurface().isValid()) {
            player.setVideoSurfaceHolder(h);
        }
    }

    public void surfaceDestroyed(SurfaceHolder h) {
        if (h != holder) {
            return;
        }
        surfaceReady = false;
        releasePlayer();
    }

    /**
     * 换一块全新的 SurfaceView（同为根 FrameLayout 的第一个子 View，z 序不变）。
     *
     * MStar 4.2.1 实测：旧 MediaPlayer 走 setDisplay(null)+release 之后，
     * 同一个 Surface 交给新实例 setDisplay 时，硬件视频层初始化会等 4 秒后超时，
     * mstplayer 打 "flip first frame timeout" 并抛 -5003；音频照常解码，于是
     * 用户看到「黑屏但有声音」。全新 Surface 上没有这个残留状态，可正常出画。
     */
    private void replaceSurfaceView() {
        ViewGroup parent = surface == null ? null : (ViewGroup) surface.getParent();
        if (parent == null) {
            // 视图已脱离窗口（几乎只在尾帧销毁竞态里出现）：按原 Holder 继续起播
            surfaceReady = true;
            StreamCandidate c = pendingCandidate;
            pendingCandidate = null;
            if (c != null) {
                startPlayback(c);
            }
            return;
        }
        int index = parent.indexOfChild(surface);
        ViewGroup.LayoutParams oldLp = surface.getLayoutParams();
        boolean hadFocus = surface.isFocused();
        parent.removeView(surface);
        SurfaceView fresh = new SurfaceView(this);
        FrameLayout.LayoutParams lp;
        if (oldLp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) oldLp;
            lp = new FrameLayout.LayoutParams(old.width, old.height);
            lp.gravity = old.gravity;
            lp.leftMargin = old.leftMargin;
            lp.topMargin = old.topMargin;
            lp.rightMargin = old.rightMargin;
            lp.bottomMargin = old.bottomMargin;
        } else {
            lp = new FrameLayout.LayoutParams(
                    oldLp == null ? ViewGroup.LayoutParams.MATCH_PARENT : oldLp.width,
                    oldLp == null ? ViewGroup.LayoutParams.MATCH_PARENT : oldLp.height);
        }
        fresh.setLayoutParams(lp);
        fresh.setFocusable(true);
        fresh.setFocusableInTouchMode(true);
        parent.addView(fresh, Math.max(0, Math.min(index, parent.getChildCount())));
        surface = fresh;
        holder = fresh.getHolder();
        holder.addCallback(this);
        if (hadFocus) {
            fresh.requestFocus();
        }
        L.i("Player", "已重建 SurfaceView（复用旧 Surface 会导致视频层初始化超时 -5003）");
        // surfaceCreated 会带着 pendingCandidate 起播；2 秒兜底只用于定位问题
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!surfaceReady && pendingCandidate != null) {
                    L.w("Player", "新 SurfaceView 2 秒内未回调 surfaceCreated，继续等待");
                }
            }
        }, 2000L);
    }

    private void showLoading() {
        if (loadingIndicator != null) {
            loadingIndicator.setVisibility(View.VISIBLE);
            loadingIndicator.bringToFront();
        }
        if (loading != null) {
            loading.setVisibility(View.VISIBLE);
            loading.bringToFront();
        }
        if (loadingSpinner != null) {
            loadingSpinner.setVisibility(View.VISIBLE);
            loadingSpinner.bringToFront();
            if (loadingSpinner.getAnimation() == null) {
                RotateAnimation spin = new RotateAnimation(0f, 360f,
                        Animation.RELATIVE_TO_SELF, 0.5f,
                        Animation.RELATIVE_TO_SELF, 0.5f);
                spin.setDuration(900L);
                spin.setRepeatCount(Animation.INFINITE);
                loadingSpinner.startAnimation(spin);
            }
        }
    }

    private void hideLoading() {
        if (loadingIndicator != null) {
            loadingIndicator.setVisibility(View.GONE);
        }
        if (loadingSpinner != null) {
            loadingSpinner.clearAnimation();
            loadingSpinner.setVisibility(View.GONE);
        }
        if (loading != null) {
            loading.setVisibility(View.GONE);
        }
    }

    private void showSeekIndicator(int seconds) {
        if (seekIndicator == null || seekIcon == null || seekText == null) {
            return;
        }
        boolean forward = seconds > 0;
        seekIcon.setImageResource(forward ? R.drawable.ic_seek_forward : R.drawable.ic_seek_rewind);
        seekText.setText((forward ? "快进 " : "快退 ") + Math.abs(seconds) + " 秒");
        seekIndicator.setVisibility(View.VISIBLE);
        ui.removeCallbacks(seekIndicatorClear);
        ui.postDelayed(seekIndicatorClear, 1200L);
    }

    private final Runnable seekIndicatorClear = new Runnable() {
        public void run() {
            if (seekIndicator != null) {
                seekIndicator.setVisibility(View.GONE);
            }
        }
    };

    private final Runnable seekHoldStop = new Runnable() {
        public void run() {
            stopSeekHold();
        }
    };

    private final Runnable applyPendingSeek = new Runnable() {
        public void run() {
            seekApplyScheduled = false;
            if (player == null || !prepared || pendingSeekPositionMs < 0L) {
                seekPending = false;
                pendingSeekPositionMs = -1L;
                return;
            }
            long target = pendingSeekPositionMs;
            long playerTarget = toPlayerPositionMs(target);
            if (authProxy != null) {
                authProxy.warmSeek(playerTarget);
            }
            showLoading();
            player.seekTo(playerTarget);
            subtitleSeekGraceUntilMs = SystemClock.elapsedRealtime()
                    + (hlsVtt != null ? 4000L : 800L);
            resetSubtitleTimeline();
            requestSubtitleFrame();
            L.i("Osd", "seek-applied-target=" + target + "ms");
        }
    };

    private void stopSeekHold() {
        seekHeld = false;
        seekDirection = 0;
        seekHoldSeconds = 0;
        lastSeekDownAt = 0L;
        ui.removeCallbacks(seekHoldStop);
        ui.removeCallbacks(applyPendingSeek);
        seekApplyScheduled = false;
    }

    private void showNotice(String text) {
        osdNotice.setText(text);
        // 内部提示不主动唤醒 OSD；与官方一致 2.5 秒后自动消失
        ui.removeCallbacks(noticeClear);
        ui.postDelayed(noticeClear, 2500L);
    }

    private final Runnable noticeClear = new Runnable() {
        public void run() {
            if (osdNotice != null) {
                osdNotice.setText("");
            }
        }
    };

    // ------------------------------------------------------------------
    // 网页端播放器同款 UI：顶栏 / 字幕状态 / 进度条 / 控制栏
    // ------------------------------------------------------------------

    private void bindPlayerChrome() {
        playerTime = (TextView) findViewById(R.id.player_time);
        loadingSpinner = (android.widget.ImageView) findViewById(R.id.player_loading_spinner);
        loadingIndicator = findViewById(R.id.player_loading_indicator);
        seekIndicator = findViewById(R.id.player_seek_indicator);
        seekIcon = (android.widget.ImageView) findViewById(R.id.player_seek_icon);
        seekText = (TextView) findViewById(R.id.player_seek_text);
        playerSubtitle = (FrameLayout) findViewById(R.id.player_subtitle);
        // libass 位图承载层：初始 match_parent，出帧后收缩成「字幕脏区」大小，
        // 只贴那一小块，避免每帧上传/合成整屏 1920x1080。
        subtitleImage = new android.widget.ImageView(this);
        FrameLayout.LayoutParams subLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        subLp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        subtitleImage.setLayoutParams(subLp);
        subtitleImage.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        subtitleImage.setFocusable(false);
        subtitleImage.setClickable(false);
        playerSubtitle.addView(subtitleImage);
        playerProgress = (ProgressBar) findViewById(R.id.player_progress);
        progressKnob = findViewById(R.id.player_progress_knob);
        progressBox = findViewById(R.id.player_progress_box);
        // OSD 收起后把焦点交回画面，按上/下键即可重新唤出控制栏
        surface.setFocusable(true);
        surface.setFocusableInTouchMode(true);

        bindControl(R.id.btn_episodes, new View.OnClickListener() {
            public void onClick(View v) { showEpisodesMenu(); }
        });
        bindControl(R.id.btn_quality, new View.OnClickListener() {
            public void onClick(View v) { showQualityMenu(); }
        });
        bindControl(R.id.btn_subtitle, new View.OnClickListener() {
            public void onClick(View v) { showSubtitleMenu(); }
        });
        bindControl(R.id.btn_settings, new View.OnClickListener() {
            public void onClick(View v) { showOptions(); }
        });
        // 官方标签行「音频 / 倍速」：焦点落到标签即展开下方内容区（tab_page_view）
        bindControl(R.id.tab_audio, new View.OnClickListener() {
            public void onClick(View v) {
                expandTab(R.id.tab_audio);
            }
        });
        bindControl(R.id.tab_speed, new View.OnClickListener() {
            public void onClick(View v) {
                expandTab(R.id.tab_speed);
            }
        });
        osdTabContent = findViewById(R.id.osd_tab_content);
        osdTabList = (android.widget.LinearLayout) findViewById(R.id.osd_tab_list);
        osdEpHeader = (TextView) findViewById(R.id.osd_ep_header);
        osdEpGrid = (android.widget.LinearLayout) findViewById(R.id.osd_ep_grid);
        osdEpPrev = findViewById(R.id.osd_ep_prev);
        osdEpNext = findViewById(R.id.osd_ep_next);
        osdSetLeft = (android.widget.LinearLayout) findViewById(R.id.osd_set_left);
        osdSetRight = (android.widget.LinearLayout) findViewById(R.id.osd_set_right);
        osdSetLeftClip = findViewById(R.id.osd_set_left_clip);
        osdSetRightClip = findViewById(R.id.osd_set_right_clip);
        playerPrefs = getSharedPreferences("player_settings", MODE_PRIVATE);
        subOffsetMs = Math.max(-30000L, Math.min(30000L,
                playerPrefs.getLong("subtitle_offset_ms", 0L)));
        subScale = Math.max(0.5f, Math.min(2f,
                playerPrefs.getFloat("subtitle_scale", 1f)));
        subYOffset = Math.max(-400, Math.min(400,
                playerPrefs.getInt("subtitle_y_offset", 0)));
        if (surface != null) {
            applySurfaceAspect(playerPrefs.getString("aspect", "默认"));
        }
        osdEpPrev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (epPage > 0) {
                    epPage--;
                    buildEpisodeGrid();
                    if (!tabListItems.isEmpty()) {
                        tabListItems.get(0).requestFocus();
                    }
                }
            }
        });
        osdEpNext.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if ((epPage + 1) * EPISODE_PAGE_SIZE < siblingEpisodes.size()) {
                    epPage++;
                    buildEpisodeGrid();
                    if (!tabListItems.isEmpty()) {
                        tabListItems.get(0).requestFocus();
                    }
                }
            }
        });
        View.OnFocusChangeListener arrowFocus = new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundColor(hasFocus
                        ? getResources().getColor(R.color.osd_row_focus) : 0x00000000);
                ((TextView) v).setTextColor(hasFocus
                        ? getResources().getColor(R.color.osd_on_light)
                        : getResources().getColor(R.color.text));
            }
        };
        osdEpPrev.setOnFocusChangeListener(arrowFocus);
        osdEpNext.setOnFocusChangeListener(arrowFocus);
        tabPill = findViewById(R.id.osd_tab_pill);
        // 标签行任一标签获得焦点 → 记住它并展开其内容；焦点离开标签行/列表 → 收起
        for (int i = 0; i < TAB_IDS.length; i++) {
            final View tab = findViewById(TAB_IDS[i]);
            if (tab == null) {
                continue;
            }
            tab.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        lastTabId = v.getId();
                        expandTab(v.getId());
                    } else {
                        ui.postDelayed(new Runnable() {
                            public void run() {
                                collapseTabsIfFocusLeft();
                            }
                        }, 32);
                    }
                    // 官方只有标签本身聚焦时才显示浅色胶囊；焦点进入列表后胶囊消失
                    refreshTabPill();
                }
            });
        }
        // 中心播放/暂停图标（官方 iv_play_state）：暂停时显示，不参与焦点
        iconFont = Typeface.createFromAsset(getAssets(), "official_icons.ttf");
        centerPlay = (TextView) findViewById(R.id.player_center_play);
        if (centerPlay != null) {
            centerPlay.setTypeface(iconFont);
            centerPlay.setText("\uEA85"); // play_circle
        }

        // 唯一一份焦点顺序表；isFocusableControl 会按「仍存在 + 可见」实时过滤，
        // 已移除或按场景隐藏的控件永远不会被焦点命中。
        osdFocusOrder.clear();
        osdFocusOrder.add(progressBox);
        // 标签行左右顺序必须跟随官方视觉顺序：选集/原画/字幕/音频/倍速/设置
        int[] rowOrder = {R.id.btn_episodes, R.id.btn_quality, R.id.btn_subtitle,
                R.id.tab_audio, R.id.tab_speed, R.id.btn_settings};
        List<View> bound = new ArrayList<View>(controlButtons);
        controlButtons.clear();
        for (int i = 0; i < rowOrder.length; i++) {
            View v = findViewById(rowOrder[i]);
            if (v != null && bound.contains(v) && !controlButtons.contains(v)) {
                controlButtons.add(v);
            }
        }
        osdFocusOrder.addAll(controlButtons);
        applyOsdMode();
        relinkFocus();
        // 官方 181 播放错误面板：全屏覆盖视频，图标 + 消息 + 重试/退出。
        // 默认 gone，只有 fatal/error 时显示；默认焦点在「退出」。
        playErrorView = findViewById(R.id.play_error_view);
        playErrorMsg = (TextView) findViewById(R.id.tv_error_msg);
        btnErrorRetry = (TextView) findViewById(R.id.tv_retry);
        btnErrorExit = (TextView) findViewById(R.id.tv_exit);
        if (btnErrorRetry != null) {
            bindErrorButtonFocus(btnErrorRetry);
            btnErrorRetry.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    retryError();
                }
            });
        }
        if (btnErrorExit != null) {
            bindErrorButtonFocus(btnErrorExit);
            btnErrorExit.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    exitPlayer();
                }
            });
            // 官方默认焦点在退出，且 selected=true 让按钮显示选中态
            btnErrorExit.setSelected(true);
        }
        // 官方播放信息浮层：标题 + 「关闭」按钮 + 动态列（view_play_info）。
        playInfoOverlay = findViewById(R.id.play_info_overlay);
        playInfoColumns = (android.widget.LinearLayout) findViewById(R.id.play_info_columns);
        playInfoClose = (TextView) findViewById(R.id.play_info_close);
        if (playInfoClose != null) {
            playInfoClose.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    playerPrefs.edit().putBoolean("info_overlay", false).commit();
                    hidePlayInfo();
                    if (expandedTabId == R.id.btn_settings) {
                        buildSettingOptions();
                    }
                }
            });
        }
        // 官方字幕页左列：选择字幕 / 调整字幕（400×80 两行）
        osdSubLeft = findViewById(R.id.osd_sub_left);
        osdSubSelectTab = (TextView) findViewById(R.id.osd_sub_tab_select);
        osdSubAdjustTab = (TextView) findViewById(R.id.osd_sub_tab_adjust);
        if (osdSubSelectTab != null) {
            osdSubSelectTab.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    subMenu = 0;
                    pendingRowFocus = 0;
                    rebuildTabList();
                }
            });
            osdSubAdjustTab.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    subMenu = 1;
                    pendingRowFocus = 0;
                    rebuildTabList();
                }
            });
            installSubTileFocus(osdSubSelectTab);
            installSubTileFocus(osdSubAdjustTab);
        }
        subAdjustOverlay = findViewById(R.id.sub_adjust_overlay);
        subAdjustPanel = (android.widget.LinearLayout) findViewById(R.id.sub_adjust_panel);
        // 官方进入播放时 OSD 不展开（204 实测），按上/下键才唤出
        if (surface != null) {
            surface.requestFocus();
        }
    }

    /** 影视 OSD 对齐官方：标题/集信息/进度条/时间 + 标签行（选集/自动/字幕/音频/倍速/设置）；
     *  直播 OSD 仅保留线路选择。顶栏官方无屏内入口，始终隐藏（返回走遥控 BACK）。 */
    private void applyOsdMode() {
        String t = item == null || item.mediaType == null ? "" : item.mediaType.toLowerCase();
        liveMode = t.indexOf("live") >= 0 || t.indexOf("channel") >= 0;
        boolean vod = !liveMode;
        progressBox.setVisibility(vod ? View.VISIBLE : View.GONE);
        playerTime.setVisibility(vod ? View.VISIBLE : View.GONE);
        playerProgress.setVisibility(vod ? View.VISIBLE : View.GONE);
        osdMeta.setVisibility(vod ? View.VISIBLE : View.GONE);
        // 常驻标签：选集 / 原画 / 字幕 / 音频 / 倍速 / 设置；倍速按内核能力隐藏。
        boolean speedSupported = player != null && player.supportsSpeed();
        findViewById(R.id.btn_quality).setVisibility(vod ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_subtitle).setVisibility(vod ? View.VISIBLE : View.GONE);
        findViewById(R.id.tab_audio).setVisibility(vod ? View.VISIBLE : View.GONE);
        findViewById(R.id.tab_speed).setVisibility(vod && speedSupported ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_settings).setVisibility(vod ? View.VISIBLE : View.GONE);
        // 内核不支持倍速时，已展开的倍速面板和焦点记忆一起收掉。
        if (!speedSupported) {
            if (expandedTabId == R.id.tab_speed) {
                collapseTabs();
            }
            if (lastTabId == R.id.tab_speed) {
                lastTabId = R.id.tab_audio;
            }
        }
        // 选集按兄弟集决定（updateEpisodeButtons）；直播隐藏选集。
        if (liveMode) {
            findViewById(R.id.btn_episodes).setVisibility(View.GONE);
        }
        relinkFocus();
        // 标签可见性变化后重新排槽位，隐藏项不留空缺口
        refreshTabPill();
    }

    /** 兜底焦点链：只在当前可见控件之间循环，隐藏控件不设任何 nextFocus 目标。 */
    private void relinkFocus() {
        List<View> visible = new ArrayList<View>();
        for (int i = 0; i < osdFocusOrder.size(); i++) {
            View v = osdFocusOrder.get(i);
            if (v.getVisibility() == View.VISIBLE) {
                visible.add(v);
            }
        }
        for (int i = 0; i < osdFocusOrder.size(); i++) {
            View v = osdFocusOrder.get(i);
            if (visible.isEmpty() || v.getVisibility() != View.VISIBLE) {
                v.setNextFocusLeftId(View.NO_ID);
                v.setNextFocusRightId(View.NO_ID);
                v.setNextFocusUpId(View.NO_ID);
                v.setNextFocusDownId(View.NO_ID);
                continue;
            }
            int at = visible.indexOf(v);
            int prev = visible.get(at > 0 ? at - 1 : visible.size() - 1).getId();
            int next = visible.get(at + 1 < visible.size() ? at + 1 : 0).getId();
            v.setNextFocusLeftId(prev);
            v.setNextFocusUpId(prev);
            v.setNextFocusRightId(next);
            v.setNextFocusDownId(next);
        }
    }

    /** 展开标签内容区（官方 tab_page_view）：选集 / 画质 / 字幕 / 音频 / 倍速。 */
    private void expandTab(int tabId) {
        if (liveMode || osdTabContent == null) {
            return;
        }
        if (tabId != R.id.btn_episodes && tabId != R.id.btn_quality
                && tabId != R.id.btn_subtitle && tabId != R.id.tab_audio
                && tabId != R.id.tab_speed && tabId != R.id.btn_settings) {
            collapseTabs();
            return;
        }
        if (expandedTabId == tabId) {
            // 已展开时重建列表（如字幕页切换子菜单）也要保证元信息行隐藏
            setOsdMetaVisible(false);
            return;
        }
        expandedTabId = tabId;
        // 字幕页左列（选择字幕/调整字幕）只在字幕标签出现，切到其他标签要收起
        applySubtitlePanelInsets();
        setTabRowTop(TAB_ROW_TOP_EXPANDED);
        setOsdMetaVisible(false);
        osdTabContent.setVisibility(View.VISIBLE);
        osdTabList.removeAllViews();
        osdEpGrid.removeAllViews();
        tabListItems.clear();
        // 官方两页列表首行同为 y=676（204 实测：列表 636 + 40），行高 80、行距 88
        osdTabList.setPadding(0, 40, 0, 0);
        boolean episodes = tabId == R.id.btn_episodes;
        boolean settings = tabId == R.id.btn_settings;
        osdTabList.setVisibility(episodes || settings ? View.GONE : View.VISIBLE);
        osdSetLeftClip.setVisibility(settings ? View.VISIBLE : View.GONE);
        osdSetRightClip.setVisibility(settings ? View.VISIBLE : View.GONE);
        if (!settings) {
            // 离开设置页时清掉列状态，否则选集数字格会被 settingsColumn() 当成选项列
            setLeftItems.clear();
            setMidItems.clear();
            advancedRight = null;
            lastSettingFocus = null;
            lastSettingCol = 0;
            lastSettingIndex = -1;
            osdSetLeft.setTranslationY(0);
            osdSetRight.setTranslationY(0);
        }
        if (episodes) {
            epPage = Math.max(0, episodeAt / EPISODE_PAGE_SIZE);
            buildEpisodeGrid();
            pendingRowFocus = episodeAt >= 0 ? episodeAt % EPISODE_PAGE_SIZE : 0;
        } else if (settings) {
            osdEpHeader.setVisibility(View.GONE);
            osdEpGrid.setVisibility(View.GONE);
            osdEpPrev.setVisibility(View.GONE);
            osdEpNext.setVisibility(View.GONE);
            buildSettingsPanel();
        } else {
            osdEpHeader.setVisibility(View.GONE);
            osdEpGrid.setVisibility(View.GONE);
            osdEpPrev.setVisibility(View.GONE);
            osdEpNext.setVisibility(View.GONE);
            if (tabId == R.id.btn_quality) {
                buildQualityRows();
            } else if (tabId == R.id.btn_subtitle) {
                buildSubtitleRows();
            } else if (tabId == R.id.tab_audio) {
                buildAudioRows();
            } else if (tabId == R.id.tab_speed) {
                buildSpeedRows();
            }
        }
        View tab = findViewById(tabId);
        if (!tabListItems.isEmpty()) {
            tab.setNextFocusDownId(tabListItems.get(0).getId());
            for (int i = 0; i < tabListItems.size(); i++) {
                View row = tabListItems.get(i);
                row.setNextFocusUpId(tabId);
                row.setNextFocusLeftId(View.NO_ID);
                row.setNextFocusRightId(View.NO_ID);
                row.setNextFocusDownId(i + 1 < tabListItems.size()
                        ? tabListItems.get(i + 1).getId() : row.getId());
            }
        }
        if (pendingRowFocus >= 0 && pendingRowFocus < tabListItems.size()) {
            tabListItems.get(pendingRowFocus).requestFocus();
        }
        pendingRowFocus = -1;
        if (tabId == R.id.btn_episodes) {
            linkEpisodeFocus();
        }
        refreshTabPill();
    }

    /** 焦点离开标签行与列表就收起（从标签移到列表项时不会收起）。 */
    private void collapseTabsIfFocusLeft() {
        if (tabsRebuilding) {
            return;
        }
        View f = getCurrentFocus();
        if (f == null || f.getParent() == null) {
            // 焦点短暂丢失（列表重建瞬间）时不要收起，交给重建逻辑恢复焦点
            return;
        }
        if (tabListItems.contains(f) || isTabView(f)) {
            return;
        }
        collapseTabs();
    }

    private boolean isTabView(View v) {
        if (v == null) {
            return false;
        }
        for (int i = 0; i < TAB_IDS.length; i++) {
            if (v.getId() == TAB_IDS[i]) {
                return true;
            }
        }
        return false;
    }

    private void collapseTabs() {
        if (expandedTabId == 0) {
            return;
        }
        expandedTabId = 0;
        if (osdTabList != null) {
            osdTabList.removeAllViews();
        }
        if (osdEpGrid != null) {
            osdEpGrid.removeAllViews();
            osdEpGrid.setVisibility(View.GONE);
            osdEpHeader.setVisibility(View.GONE);
            osdEpPrev.setVisibility(View.GONE);
            osdEpNext.setVisibility(View.GONE);
        }
        if (osdSetLeft != null) {
            osdSetLeft.removeAllViews();
            osdSetRight.removeAllViews();
            osdSetLeftClip.setVisibility(View.GONE);
            osdSetRightClip.setVisibility(View.GONE);
            osdSetLeft.setTranslationY(0);
            osdSetRight.setTranslationY(0);
            setLeftItems.clear();
        }
        tabListItems.clear();
        if (osdTabContent != null) {
            osdTabContent.setVisibility(View.GONE);
        }
        setTabRowTop(TAB_ROW_TOP_COLLAPSED);
        setOsdMetaVisible(true);
        relinkFocus();
        refreshTabPill();
    }

    /** 官方展开内容区时隐藏「编码信息 / 标题 / 进度条 / 时间」，收起时恢复。 */
    private void setOsdMetaVisible(boolean visible) {
        int vis = visible ? View.VISIBLE : View.GONE;
        int[] ids = {R.id.osd_meta, R.id.osd_title, R.id.player_progress_box, R.id.player_time};
        for (int i = 0; i < ids.length; i++) {
            View v = findViewById(ids[i]);
            if (v != null) {
                v.setVisibility(vis);
            }
        }
    }

    /** OSD 标题上一行：优先显示分集信息，没有时显示当前候选流的分辨率与编码。 */
    private void updateOsdMeta() {
        if (osdMeta == null || item == null) {
            return;
        }
        // 官方上一行是「第 6 季 · 第 1 集 · Steel Ball Run」：分集信息 + 副标题
        String ep = "";
        if (item.seasonCount > 0 || item.episodeCount > 0) {
            ep = getString(R.string.season_label, item.seasonCount) + " · "
                    + getString(R.string.episode_label, item.episodeCount);
            if (item.subtitle != null && item.subtitle.length() > 0) {
                ep = ep + " · " + item.subtitle;
            }
        } else if (item.subtitle != null && item.subtitle.length() > 0) {
            ep = item.subtitle;
        }
        String technical = currentTechnicalLine();
        osdMeta.setText(ep.length() > 0 ? ep + " · " + technical : technical);
    }

    /** 当前实际候选流的分辨率与视频编码；不再回退展示原片规格。 */
    private String currentTechnicalLine() {
        int w = 0;
        int h = 0;
        String codec = "";
        if (player != null) {
            w = player.getVideoWidth();
            h = player.getVideoHeight();
        }
        if ((w <= 0 || h <= 0) && lastCandidate != null) {
            w = lastCandidate.width;
            h = lastCandidate.height;
        }
        if (lastCandidate != null) {
            codec = lastCandidate.videoCodec;
        }
        AuthProxy.TsMetadata actual = authProxy == null ? null : authProxy.mediaMetadata();
        if (actual != null) {
            if (!actual.videoPresent) return "无视频轨";
            if (actual.videoCodec.length() > 0) codec = actual.videoCodec;
        }
        String resolution = w > 0 && h > 0 ? w + "×" + h : "未知分辨率";
        String encoding = codec != null && codec.length() > 0 ? prettyCodec(codec) : "未知编码";
        int bitrateKbps = lastCandidate != null && lastCandidate.bitrateKbps > 0
                ? lastCandidate.bitrateKbps : (item == null ? 0 : item.bitrateKbps);
        if (lastCandidate != null
                && lastCandidate.source == StreamSource.NAS_TRANSCODE
                && cfg.transcodeBitrateBps > 0) {
            bitrateKbps = cfg.transcodeBitrateBps / 1000;
        }
        return resolution + " / " + encoding
                + (bitrateKbps > 0 ? " / " + fmtBitrate(bitrateKbps) : "");
    }

    /** 官方标签胶囊：标签获得焦点或该标签已展开时显示（焦点进入面板后胶囊仍留在选中标签）。
     *  胶囊 198×70，相对 180×64 的标签槽左右各外扩 9px、上下各外扩 3px（204 实测）。 */
    private void refreshTabPill() {
        if (tabPill == null) {
            return;
        }
        View active = null;
        int slot = 0;
        for (int i = 0; i < TAB_IDS.length; i++) {
            View v = findViewById(TAB_IDS[i]);
            if (v == null || v.getVisibility() != View.VISIBLE) {
                continue;
            }
            // 胶囊只跟随真实焦点：焦点进入选集/列表后标签恢复常态，
            // 否则标签胶囊与网格/列表行会同时高亮，出现两个焦点
            boolean on = v.hasFocus();
            // 官方选中标签自身 bounds 变宽到 198（左右各扩 9px），文字仍居中；
            // 隐藏的标签不占槽位，后续标签依次前移，避免留下空缺口
            if (v.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                int wantW = on ? 198 : 180;
                int wantL = TAB_BASE_LEFT[Math.min(slot, TAB_BASE_LEFT.length - 1)]
                        - (on ? 9 : 0);
                if (lp.width != wantW || lp.leftMargin != wantL) {
                    lp.width = wantW;
                    lp.leftMargin = wantL;
                    v.setLayoutParams(lp);
                }
            }
            slot++;
            if (v instanceof TextView) {
                ((TextView) v).setTextColor(getResources().getColor(
                        on ? R.color.osd_on_light : R.color.text));
            }
            if (on && active == null) {
                active = v;
            }
        }
        if (active == null) {
            tabPill.setVisibility(View.GONE);
            return;
        }
        tabPill.setVisibility(View.VISIBLE);
        positionTabPill(active);
        // 标签行整体上移/下移时布局要下一帧才生效，布局完成后再校准一次
        final int activeId = active.getId();
        tabPill.post(new Runnable() {
            public void run() {
                View v = findViewById(activeId);
                if (v != null && tabPill.getVisibility() == View.VISIBLE) {
                    positionTabPill(v);
                }
            }
        });
    }

    private void positionTabPill(View tab) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tabPill.getLayoutParams();
        // 标签是绝对定位，以 leftMargin/topMargin 为准，避免布局未刷新时 getLeft 还是旧值
        FrameLayout.LayoutParams tlp = tab.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) tab.getLayoutParams() : null;
        boolean widened = tlp != null && tlp.width == 198;
        int left = (tlp != null ? tlp.leftMargin : tab.getLeft()) - (widened ? 0 : 9);
        int top = (tlp != null ? tlp.topMargin : tab.getTop()) - 3;
        if (lp.leftMargin == left && lp.topMargin == top) {
            return;
        }
        lp.leftMargin = left;
        lp.topMargin = top;
        tabPill.setLayoutParams(lp);
    }

    /** OSD 里的浅色胶囊/行背景。 */
    private android.graphics.drawable.Drawable pill(int color, int radius) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** 标签行整体上下移动：官方收起 y=956 / 展开 y=556，相对面板 top=237。 */
    private void setTabRowTop(int top) {
        int[] ids = {R.id.btn_episodes, R.id.btn_quality, R.id.btn_subtitle,
                R.id.tab_audio, R.id.tab_speed, R.id.btn_settings};
        for (int i = 0; i < ids.length; i++) {
            View v = findViewById(ids[i]);
            if (v == null) {
                continue;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            lp.topMargin = top;
            v.setLayoutParams(lp);
        }
    }

    /** 选集页：每行 15 集、每页最多两行，当前集白底强调。 */
    private void buildEpisodeGrid() {
        osdEpGrid.removeAllViews();
        tabListItems.clear();
        osdEpGrid.setVisibility(View.VISIBLE);
        osdEpHeader.setVisibility(View.VISIBLE);
        if (!episodesLoaded) {
            osdEpHeader.setText("正在读取剧集…");
            osdEpPrev.setVisibility(View.GONE);
            osdEpNext.setVisibility(View.GONE);
            osdEpPrev.setFocusable(false);
            osdEpNext.setFocusable(false);
            return;
        }
        int total = siblingEpisodes.size();
        osdEpHeader.setText("共 " + total);
        int pages = total == 0 ? 0 : (total + EPISODE_PAGE_SIZE - 1) / EPISODE_PAGE_SIZE;
        if (epPage >= pages) {
            epPage = Math.max(0, pages - 1);
        }
        boolean hasPrev = total > 0 && epPage > 0;
        boolean hasNext = total > 0 && (epPage + 1) * EPISODE_PAGE_SIZE < total;
        osdEpPrev.setVisibility(hasPrev ? View.VISIBLE : View.GONE);
        osdEpNext.setVisibility(hasNext ? View.VISIBLE : View.GONE);
        osdEpPrev.setFocusable(hasPrev);
        osdEpNext.setFocusable(hasNext);
        int from = epPage * EPISODE_PAGE_SIZE;
        int to = Math.min(total, from + EPISODE_PAGE_SIZE);
        int pageCount = to - from;
        episodeGridColumns = Math.max(1, Math.min(EPISODE_COLUMNS, pageCount));
        int availableWidth = getResources().getDisplayMetrics().widthPixels - 84;
        int cellGap = 12;
        if (episodeGridColumns > 1) {
            int fittedGap = (availableWidth - 102 * episodeGridColumns)
                    / (episodeGridColumns - 1);
            cellGap = Math.max(12, Math.min(22, fittedGap));
        }
        int cellWidth = pageCount == 0 ? 102
                : Math.min(102, (availableWidth - cellGap * (episodeGridColumns - 1))
                        / episodeGridColumns);
        LinearLayout row = null;
        for (int i = from; i < to; i++) {
            if ((i - from) % EPISODE_COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                android.widget.LinearLayout.LayoutParams rowLp =
                        new android.widget.LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, 64);
                rowLp.topMargin = i == from ? 0 : 12;
                osdEpGrid.addView(row, rowLp);
            }
            final MediaItem ep = siblingEpisodes.get(i);
            final TextView cell = new TextView(this);
            int number = ep.episodeCount > 0 ? ep.episodeCount : i + 1;
            cell.setText(String.valueOf(number));
            cell.setTextSize(26);
            cell.setGravity(android.view.Gravity.CENTER);
            cell.setFocusable(true);
            cell.setFocusableInTouchMode(true);
            cell.setClickable(true);
            cell.setId(TAB_ITEM_ID_BASE + i);
            final boolean selected = i == episodeAt;
            // 当前集只用品牌蓝文字标记：白底专属于焦点，否则「标签胶囊 + 当前集白底」会被看成两个焦点
            cell.setTextColor(getResources().getColor(
                    selected ? R.color.player_brand : R.color.text));
            cell.setBackground(pill(0x17FFFFFF, 6));
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(cellWidth, 64);
            lp.rightMargin = cellGap;
            row.addView(cell, lp);
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openEpisode(ep);
                }
            });
            cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackground(hasFocus
                            ? pill(getResources().getColor(R.color.osd_row_focus), 6)
                            : pill(0x17FFFFFF, 6));
                    ((TextView) v).setTextColor(getResources().getColor(
                            hasFocus ? R.color.osd_on_light
                                    : (selected ? R.color.player_brand : R.color.text)));
                }
            });
            tabListItems.add(cell);
        }
        linkEpisodeFocus();
    }

    private boolean isEpisodeArrow(View view) {
        return view == osdEpPrev || view == osdEpNext;
    }

    private void linkEpisodeFocus() {
        if (osdEpPrev == null || osdEpNext == null || tabListItems.isEmpty()) {
            return;
        }
        View first = tabListItems.get(0);
        View last = tabListItems.get(tabListItems.size() - 1);
        boolean hasPrev = osdEpPrev.getVisibility() == View.VISIBLE;
        boolean hasNext = osdEpNext.getVisibility() == View.VISIBLE;
        first.setNextFocusLeftId(hasPrev ? osdEpPrev.getId() : View.NO_ID);
        last.setNextFocusRightId(hasNext ? osdEpNext.getId() : View.NO_ID);
        osdEpPrev.setNextFocusRightId(first.getId());
        osdEpPrev.setNextFocusDownId(first.getId());
        osdEpPrev.setNextFocusUpId(R.id.btn_episodes);
        osdEpNext.setNextFocusLeftId(last.getId());
        osdEpNext.setNextFocusDownId(last.getId());
        osdEpNext.setNextFocusUpId(R.id.btn_episodes);
    }

    /** 设置页：左列参数组（40px 图标槽 + 文字，行距 88），右列按选中组渲染子项。 */
    private void buildSettingsPanel() {
        osdSetLeft.removeAllViews();
        osdSetLeft.setTranslationY(0);
        osdSetRight.removeAllViews();
        setLeftItems.clear();
        tabListItems.clear();
        lastSettingFocus = null;
        lastSettingCol = 0;
        lastSettingIndex = -1;
        final String[] icons = {"⇄", "▭", "≡", "▤", "⚙"};
        for (int i = 0; i < SET_GROUPS.length; i++) {
            final int group = i;
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            android.widget.LinearLayout.LayoutParams glp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 80);
            glp.bottomMargin = 8;
            row.setLayoutParams(glp);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(36, 0, 0, 0);
            row.setId(TAB_ITEM_ID_BASE + 200 + i);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            final TextView icon = new TextView(this);
            icon.setText(icons[i]);
            icon.setTextSize(24);
            icon.setGravity(android.view.Gravity.CENTER);
            row.addView(icon, new android.widget.LinearLayout.LayoutParams(40, 40));
            final TextView label = new TextView(this);
            label.setText(SET_GROUPS[i]);
            label.setTextSize(22);
            label.setIncludeFontPadding(false);
            label.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.addView(label, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 49));
            row.setBackground(pill(0x17FFFFFF, 8));
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean hasFocus) {
                    // 官方左列随焦点切换右侧选项（无需按确认）
                    if (hasFocus && setGroup != group) {
                        setGroup = group;
                        // 切换参数组后官方回到该组首项，不沿用上一组的记忆
                        lastSettingFocus = null;
                        lastSettingCol = 0;
                        lastSettingIndex = -1;
                        buildSettingOptions();
                    }
                    // 每次焦点变化重刷整列：官方焦点条是唯一白底，选中组只用品牌蓝文字
                    applyLeftRowStyles();
                    if (hasFocus) {
                        // 官方 ScrollView：焦点不在首组时整列上移 78px（实测行位 686/774/862/950）
                        final View target = v;
                        osdSetLeft.post(new Runnable() {
                            public void run() {
                                scrollSettingRowIntoView(target);
                            }
                        });
                    }
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    setGroup = group;
                    buildSettingOptions();
                }
            });
            osdSetLeft.addView(row);
            setLeftItems.add(row);
            tabListItems.add(row);
        }
        buildSettingOptions();
    }

    private void buildSettingOptions() {
        for (int i = tabListItems.size() - 1; i >= 0; i--) {
            if (!setLeftItems.contains(tabListItems.get(i))) {
                tabListItems.remove(i);
            }
        }
        setMidItems.clear();
        advancedRight = null;
        lastSettingFocus = null;
        osdSetRight.setTranslationY(0);
        osdSetRight.removeAllViews();
        String keep = pendingSettingFocus;
        pendingSettingFocus = null;
        if (setGroup == 0) {
            buildAutoPlaySettings();
        } else if (setGroup == 1) {
            buildAspectSettings();
        } else if (setGroup == 2) {
            buildInfoSettings();
        } else if (setGroup == 3) {
            buildDecodeSettings();
        } else {
            buildAdvancedSettings();
        }
        if (keep != null) {
            for (int i = 0; i < osdSetRight.getChildCount(); i++) {
                View child = osdSetRight.getChildAt(i);
                if (keep.equals(child.getTag())) {
                    child.requestFocus();
                    break;
                }
            }
        }
        applyLeftRowStyles();
    }

    /**
     * 左列行样式（官方实测）：聚焦行整条 #EBEBEB 白底 + 深色图标文字；
     * 非聚焦行暗色圆角底；当前参数组用品牌蓝文字标记，其余为 70% 白。
     * 必须整列重刷，否则切换组时上一条会残留焦点色、出现两个高亮。
     */
    private void applyLeftRowStyles() {
        for (int i = 0; i < setLeftItems.size(); i++) {
            View row = setLeftItems.get(i);
            if (!(row instanceof android.view.ViewGroup)) {
                continue;
            }
            android.view.ViewGroup g = (android.view.ViewGroup) row;
            if (g.getChildCount() < 2) {
                continue;
            }
            boolean focused = row.hasFocus();
            TextView icon = (TextView) g.getChildAt(0);
            TextView label = (TextView) g.getChildAt(1);
            row.setBackground(focused
                    ? pill(getResources().getColor(R.color.osd_row_focus), 8)
                    : pill(0x17FFFFFF, 8));
            int color = focused
                    ? getResources().getColor(R.color.osd_on_light)
                    : (i == setGroup ? getResources().getColor(R.color.player_brand) : 0xB3FFFFFF);
            icon.setTextColor(color);
            label.setTextColor(color);
        }
    }

    private void addSettingTitle(String text, int topMargin) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(22);
        tv.setTextColor(getResources().getColor(R.color.text));
        tv.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 49);
        lp.topMargin = topMargin;
        osdSetRight.addView(tv, lp);
    }

    private void addSettingSpacer(int height) {
        View v = new View(this);
        osdSetRight.addView(v, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, height));
    }

    private void buildAutoPlaySettings() {
        final boolean auto = playerPrefs.getBoolean("autoplay", true);
        addSettingTitle("自动连播", 0);
        addSettingSpacer(20);
        addSettingOption("开启", auto, new View.OnClickListener() {
            public void onClick(View v) {
                playerPrefs.edit().putBoolean("autoplay", true).commit();
                buildSettingOptions();
            }
        });
        addSettingOption("关闭", !auto, new View.OnClickListener() {
            public void onClick(View v) {
                playerPrefs.edit().putBoolean("autoplay", false).commit();
                buildSettingOptions();
            }
        });
        addSettingTitle("跳过片头片尾", 20);
        addSettingSpacer(20);
        addTrailerRow("片头时长", false);
        addTrailerRow("片尾时长", true);
    }

    /** 片头/片尾时长行：右侧显示「未设置」或「X 分 Y 秒 ›」，点击打开官方几何对话框。 */
    private void addTrailerRow(String label, final boolean outro) {
        addSettingOption(label, trailerText(outro), false, new View.OnClickListener() {
            public void onClick(View v) {
                showTrailerDialog(outro);
            }
        });
    }

    /** 官方按「剧名」记忆片头/片尾时长（对话框里的生效范围即当前剧集）。 */
    private String trailerScope() {
        String t = item == null ? "" : item.title;
        if (t == null || t.length() == 0) {
            t = item == null ? "unknown" : item.guid;
        }
        return t;
    }

    private String trailerKey(boolean outro) {
        return (outro ? "trailer_outro_" : "trailer_intro_") + trailerScope();
    }

    private int trailerSeconds(boolean outro) {
        return playerPrefs.getInt(trailerKey(outro), 0);
    }

    private void setTrailerSeconds(boolean outro, int seconds) {
        playerPrefs.edit().putInt(trailerKey(outro), Math.max(0, seconds)).commit();
    }

    private static String durationText(int seconds) {
        int s = Math.max(0, seconds);
        return (s / 60) + " 分 " + (s % 60) + " 秒";
    }

    private String trailerText(boolean outro) {
        int s = trailerSeconds(outro);
        return (s <= 0 ? "未设置" : durationText(s)) + " ›";
    }

    private int playedSeconds() {
        try {
            return player != null && prepared
                    ? (int) (toAbsolutePositionMs(player.getPositionMs()) / 1000L) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int remainingSeconds() {
        try {
            if (player == null || !prepared) {
                return 0;
            }
            return (int) Math.max(0L,
                    (resolveDurationMs() - toAbsolutePositionMs(player.getPositionMs())) / 1000L);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 片头/片尾时长设置：按官方 204 对话框几何（800×579，居中）还原。 */
    private void showTrailerDialog(final boolean outro) {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        final FrameLayout root = new FrameLayout(this);
        root.setBackground(pill(0xF2141414, 12));

        addDialogChild(root, dialogText(outro ? "片尾时长" : "片头时长", 22, R.color.text),
                44, 24, 600, 43);
        String scope = "生效范围: 《 " + (item == null ? "" : item.title) + " 》";
        if (item != null && item.subtitle != null && item.subtitle.length() > 0) {
            scope += "  " + item.subtitle;
        }
        addDialogChild(root, dialogText(scope, 16, R.color.text_muted), 44, 67, 712, 33);

        View line = new View(this);
        line.setBackgroundColor(0x33FFFFFF);
        addDialogChild(root, line, 44, 124, 712, 1);

        final TextView value = dialogText(durationText(trailerSeconds(outro)), 28, R.color.text);
        value.setGravity(android.view.Gravity.CENTER);
        addDialogChild(root, value, 40, 151, 720, 54);

        final int remain = outro ? remainingSeconds() : playedSeconds();
        // 官方该行：左侧 40px 起「当前播放剩余时间：X 分 Y 秒」，右侧 32px 收边「设为片尾」
        final FrameLayout remainRow = new FrameLayout(this);
        remainRow.setBackground(pill(0x1FFFFFFF, 8));
        remainRow.setFocusable(true);
        remainRow.setClickable(true);
        remainRow.setFocusableInTouchMode(true);
        final TextView remainLeft = dialogText((outro ? "当前播放剩余时间：" : "当前播放时间：")
                + durationText(remain), 20, R.color.text);
        remainLeft.setGravity(android.view.Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams rllp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT);
        rllp.leftMargin = 40;
        remainRow.addView(remainLeft, rllp);
        final TextView remainRight = dialogText(outro ? "设为片尾" : "设为片头", 20, R.color.text);
        remainRight.setGravity(android.view.Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams rrlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT);
        rrlp.rightMargin = 32;
        rrlp.gravity = android.view.Gravity.RIGHT;
        remainRow.addView(remainRight, rrlp);
        remainRow.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View view, boolean hasFocus) {
                view.setBackground(pill(hasFocus ? 0xFFFFFFFF : 0x1FFFFFFF, 8));
                int color = getResources().getColor(
                        hasFocus ? R.color.osd_on_light : R.color.text);
                remainLeft.setTextColor(color);
                remainRight.setTextColor(color);
                expandDialogItem(view, hasFocus);
            }
        });
        addDialogChild(root, remainRow, 44, 231, 712, 98);
        remainRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setTrailerSeconds(outro, remain);
                value.setText(durationText(remain));
            }
        });

        final TextView custom = dialogText(
                "自定义    ‹  " + durationText(trailerSeconds(outro)) + "  ›", 20, R.color.text);
        custom.setGravity(android.view.Gravity.CENTER_VERTICAL);
        custom.setPadding(44, 0, 24, 0);
        prepareDialogItem(custom, 0x1FFFFFFF);
        custom.setFocusableInTouchMode(true);
        addDialogChild(root, custom, 44, 353, 712, 102);
        custom.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                int step;
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    step = 5;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    step = -5;
                } else {
                    // ↑/↓ 留给焦点移动（官方能走到「重置 / 确定」）
                    return false;
                }
                int next = Math.max(0, Math.min(3600, trailerSeconds(outro) + step));
                setTrailerSeconds(outro, next);
                value.setText(durationText(next));
                custom.setText("自定义    ‹  " + durationText(next) + "  ›");
                return true;
            }
        });

        final TextView reset = dialogButton("重置");
        prepareDialogItem(reset, 0x1FFFFFFF);
        addDialogChild(root, reset, 144, 479, 250, 68);
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setTrailerSeconds(outro, 0);
                value.setText(durationText(0));
                custom.setText("自定义    ‹  " + durationText(0) + "  ›");
            }
        });

        final TextView confirm = dialogButton("确定");
        prepareDialogItem(confirm, 0x1FFFFFFF);
        addDialogChild(root, confirm, 406, 479, 250, 68);
        confirm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        dialog.setContentView(root, new FrameLayout.LayoutParams(800, 579));
        android.view.Window win = dialog.getWindow();
        if (win != null) {
            // Window 没有 setBackground()；setBackgroundDrawable 在 API 16 deprecated 但无替代
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setLayout(800, 579);
            win.setGravity(android.view.Gravity.CENTER);
        }
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) {
                menuOpen = false;
                // 先恢复 OSD（它会把焦点放到进度条），再重建右列并按 pendingSettingFocus 落回原行
                showOsd();
                pendingSettingFocus = outro ? "片尾时长" : "片头时长";
                buildSettingOptions();
            }
        });
        menuOpen = true;
        dialog.show();
        custom.requestFocus();
    }

    private TextView dialogText(String text, int sizeSp, int colorRes) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sizeSp);
        v.setTextColor(getResources().getColor(colorRes));
        v.setSingleLine(false);
        return v;
    }

    private TextView dialogButton(String text) {
        TextView v = dialogText(text, 20, R.color.text);
        v.setGravity(android.view.Gravity.CENTER);
        v.setFocusable(true);
        v.setClickable(true);
        return v;
    }

    /** 对话框行的聚焦态：白底 + 深色文字，左右各外扩 14px（官方 204 实测）。 */
    private void prepareDialogItem(final TextView v, final int normalBg) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setFocusableInTouchMode(true);
        v.setBackground(pill(normalBg, 8));
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View view, boolean hasFocus) {
                view.setBackground(pill(hasFocus ? 0xFFFFFFFF : normalBg, 8));
                ((TextView) view).setTextColor(getResources().getColor(
                        hasFocus ? R.color.osd_on_light : R.color.text));
                expandDialogItem(view, hasFocus);
            }
        });
    }

    private void expandDialogItem(View v, boolean expanded) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (!(p instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) p;
        int[] base = (int[]) v.getTag();
        if (base == null) {
            base = new int[]{lp.leftMargin, lp.width};
            v.setTag(base);
        }
        lp.leftMargin = base[0] - (expanded ? 14 : 0);
        lp.width = base[1] + (expanded ? 28 : 0);
        v.setLayoutParams(lp);
    }

    private void addDialogChild(FrameLayout root, View child, int left, int top, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = left;
        lp.topMargin = top;
        root.addView(child, lp);
    }

    private void buildAspectSettings() {
        final String current = playerPrefs.getString("aspect", "默认");
        final String[] values = {"默认", "4:3", "16:9", "21:9"};
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            addSettingOption(value, value.equals(current), new View.OnClickListener() {
                public void onClick(View v) {
                    playerPrefs.edit().putString("aspect", value).commit();
                    applySurfaceAspect(value);
                    buildSettingOptions();
                }
            });
        }
    }

    private void buildInfoSettings() {
        final boolean on = playerPrefs.getBoolean("info_overlay", false);
        addSettingTitle("在屏幕上显示播放信息", 0);
        addSettingSpacer(20);
        addSettingOption("开启", on, new View.OnClickListener() {
            public void onClick(View v) {
                playerPrefs.edit().putBoolean("info_overlay", true).commit();
                showPlayInfo();
                buildSettingOptions();
            }
        });
        addSettingOption("关闭", !on, new View.OnClickListener() {
            public void onClick(View v) {
                playerPrefs.edit().putBoolean("info_overlay", false).commit();
                hidePlayInfo();
                buildSettingOptions();
            }
        });
    }

    // ------------------------------------------------------------------
    // 官方播放信息浮层（play_info_view / view_play_info）
    // ------------------------------------------------------------------

    /** 显示浮层：收起 OSD，焦点落在「关闭」（官方面板唯一可聚焦控件）。 */
    private void showPlayInfo() {
        if (playInfoOverlay == null) {
            return;
        }
        buildPlayInfoRows();
        if (osdVisible) {
            collapseOsd();
        }
        playInfoShown = true;
        playInfoOverlay.setVisibility(View.VISIBLE);
        // 布局完成后再次请求焦点：可见性刚变 VISIBLE 时同步 requestFocus 可能被系统拒绝
        playInfoOverlay.post(new Runnable() {
            public void run() {
                if (playInfoShown && playInfoClose != null) {
                    boolean ok = playInfoClose.requestFocus();
                    L.i("Osd", "play-info focus close=" + ok);
                }
            }
        });
        L.i("Osd", "play-info on");
    }

    /** 关闭浮层并把焦点还给画面，遥控器不会丢焦点。 */
    private void hidePlayInfo() {
        if (playInfoOverlay == null || !playInfoShown) {
            return;
        }
        playInfoShown = false;
        playInfoOverlay.setVisibility(View.GONE);
        if (surface != null) {
            surface.requestFocus();
        }
        L.i("Osd", "play-info off");
    }

    /** 按官方 view_play_info 的列宽与行序填充：左侧播放/视频/音频、第二列解码、分隔线、
     *  媒体源信息、视频块、音频块。取不到的运行期指标（缓冲/丢帧/文件大小）不显示行。 */
    private void buildPlayInfoRows() {
        if (playInfoColumns == null) {
            return;
        }
        playInfoColumns.removeAllViews();
        int w = player == null ? 0 : player.getVideoWidth();
        int h = player == null ? 0 : player.getVideoHeight();
        if ((w <= 0 || h <= 0) && lastCandidate != null) {
            w = lastCandidate.width;
            h = lastCandidate.height;
        }
        if ((w <= 0 || h <= 0) && item != null) {
            w = item.width;
            h = item.height;
        }
        String container = lastCandidate != null && lastCandidate.container.length() > 0
                ? lastCandidate.container : (item == null ? "" : item.container);
        String vCodec = lastCandidate != null && lastCandidate.videoCodec.length() > 0
                ? lastCandidate.videoCodec : (item == null ? "" : item.videoCodec);
        String aCodec = lastCandidate != null && lastCandidate.audioCodec.length() > 0
                ? lastCandidate.audioCodec : (item == null ? "" : item.audioCodec);
        AuthProxy.TsMetadata actual = authProxy == null ? null : authProxy.mediaMetadata();
        boolean videoMissing = false;
        if (actual != null) {
            if (actual.videoPresent) {
                if (actual.videoCodec.length() > 0) vCodec = actual.videoCodec;
            } else {
                videoMissing = true;
                vCodec = "";
                w = 0;
                h = 0;
            }
            if (actual.audioPresent && actual.audioCodec.length() > 0) {
                aCodec = actual.audioCodec;
            }
        }
        int bitrate = lastCandidate != null && lastCandidate.bitrateKbps > 0
                ? lastCandidate.bitrateKbps : (item == null ? 0 : item.bitrateKbps);
        boolean transcode = lastCandidate != null
                && lastCandidate.source == com.juying.medianest.core.model.StreamSource.NAS_TRANSCODE;
        if (transcode && cfg.transcodeBitrateBps > 0) {
            bitrate = cfg.transcodeBitrateBps / 1000;
        }
        int channels = lastCandidate != null && lastCandidate.audioChannels > 0
                ? lastCandidate.audioChannels
                : (transcode ? 0 : (item == null ? 0 : item.audioChannels));
        boolean hdr = lastCandidate != null ? lastCandidate.hdr : (item != null && item.hdr);

        android.widget.LinearLayout col1 = infoColumn(320, 0);
        boolean jellyfin = "JELLYFIN".equalsIgnoreCase(cfg.apiMode);
        infoRow(col1, "播放类型", transcode
                ? (jellyfin ? "Jellyfin 转码" : "NAS 代理播放")
                : (jellyfin ? "Jellyfin 直连" : "NAS 直连播放"), 28, true);
        if (transcode) {
            infoRow(col1, "转码原因", transcodeReason(), 28, true);
        }
        if (videoMissing) {
            infoRow(col1, "视频轨", "未检测到", 24, true);
        }
        if (w > 0 && h > 0) {
            infoRow(col1, "分辨率", w + "×" + h, 24, true);
        }
        if (bitrate > 0) {
            infoRow(col1, transcode ? "目标码率" : "码率", fmtBitrate(bitrate), 24, true);
        }
        if (vCodec.length() > 0) {
            infoRow(col1, "视频编码", prettyCodec(vCodec), 24, true);
        }
        infoRow(col1, "视频动态范围", hdr ? "HDR" : "SDR", 24, true);
        if (aCodec.length() > 0) {
            infoRow(col1, "音频编码", prettyCodec(aCodec), 24, true);
        }
        if (channels > 0) {
            infoRow(col1, "音频声道", String.valueOf(channels), 24, true);
        }

        android.widget.LinearLayout col2 = infoColumn(300, 20);
        boolean soft = "软解".equals(playerPrefs.getString("decode", "硬解"));
        infoRow(col2, "解码方式", soft ? "软解（客户端软解码）" : "硬解（客户端硬解码）", 24, true);
        float fps = player == null ? 0f : player.getVideoFrameRate();
        if (fps > 0f) {
            infoRow(col2, "帧率", trimFloat(fps) + " fps", 24, true);
        }

        View divider = new View(this);
        divider.setBackgroundColor(0x26FFFFFF);
        android.widget.LinearLayout.LayoutParams dlp =
                new android.widget.LinearLayout.LayoutParams(1, android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        dlp.leftMargin = 40;
        dlp.rightMargin = 40;
        playInfoColumns.addView(divider, dlp);

        android.widget.LinearLayout col3 = infoColumn(346, 40);
        infoHeader(col3, "媒体源信息");
        if (container.length() > 0) {
            infoRow(col3, transcode ? "输出容器" : "封装容器",
                    container.replace(".", "").toUpperCase(java.util.Locale.US), 24, true);
        }
        if (item != null) {
            if (item.videoCodec.length() > 0) {
                infoRow(col3, "源编码", prettyCodec(item.videoCodec), 24, true);
            }
            if (item.width > 0 && item.height > 0) {
                infoRow(col3, "源分辨率", item.width + "×" + item.height, 24, true);
            }
            if (item.bitDepth > 0) {
                infoRow(col3, "源位深", item.bitDepth + " bit", 24, true);
            }
            if (item.bitrateKbps > 0) {
                infoRow(col3, "源码率", fmtBitrate(item.bitrateKbps), 24, true);
            }
        }

        android.widget.LinearLayout col4 = infoColumn(286, 24);
        infoHeader(col4, "视频");
        if (videoMissing) {
            infoRow(col4, "视频轨", "未检测到", 24, true);
        }
        if (vCodec.length() > 0) {
            infoRow(col4, "编解码器", prettyCodec(vCodec), 24, true);
        }
        infoRow(col4, "动态范围", hdr ? "HDR" : "SDR", 24, true);
        if (w > 0 && h > 0) {
            infoRow(col4, "分辨率", w + "×" + h, 24, true);
        }
        if (bitrate > 0) {
            infoRow(col4, transcode ? "目标码率" : "码率", fmtBitrate(bitrate), 24, true);
        }
        if (fps > 0f) {
            infoRow(col4, "帧率", trimFloat(fps) + " fps", 24, true);
        }

        android.widget.LinearLayout col5 = infoColumn(286, 24);
        infoHeader(col5, "音频");
        if (channels > 0) {
            infoRow(col5, "声道", String.valueOf(channels), 24, true);
        }
        if (aCodec.length() > 0) {
            infoRow(col5, "编解码器", prettyCodec(aCodec), 24, true);
        }
        int sampleRate = player == null ? 0 : player.getAudioSampleRate();
        if (sampleRate > 0) {
            infoRow(col5, "采样率", (sampleRate / 1000) + " kHz", 24, true);
        }
    }

    /** 转码原因：走 NAS 代理播放时按官方文案给出（能力探测选档触发转码）。 */
    private String transcodeReason() {
        return activeQualityValue.length() > 0 && !"auto".equalsIgnoreCase(activeQualityValue)
                ? "根据视频质量设置降低码率" : "视频格式转换";
    }

    private android.widget.LinearLayout infoColumn(int width, int startMargin) {
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(width,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = startMargin;
        playInfoColumns.addView(col, lp);
        return col;
    }

    /** 官方小标题（媒体源信息 / 视频 / 音频）：28px 粗体白色。 */
    private void infoHeader(android.widget.LinearLayout col, String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 28);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(0xFFFFFFFF);
        v.setIncludeFontPadding(false);
        col.addView(v);
    }

    /** 官方 item_player_info：键 24px #CCFFFFFF，值粗体白色；行间距 12px。 */
    private void infoRow(android.widget.LinearLayout col, String key, String value,
                         int size, boolean bold) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        TextView k = new TextView(this);
        k.setText(key + "：");
        k.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24);
        k.setTextColor(0xCCFFFFFF);
        k.setIncludeFontPadding(false);
        k.setSingleLine(true);
        row.addView(k);
        TextView val = new TextView(this);
        val.setText(value == null ? "" : value);
        val.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
        val.setTextColor(0xFFFFFFFF);
        val.setIncludeFontPadding(false);
        val.setSingleLine(true);
        val.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (bold) {
            val.setTypeface(Typeface.DEFAULT_BOLD);
        }
        row.addView(val);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 12;
        col.addView(row, lp);
    }

    private static String fmtBitrate(int kbps) {
        if (kbps >= 1000) {
            return String.format(java.util.Locale.US, "%.1f Mbps", kbps / 1000f);
        }
        return kbps + " Kbps";
    }

    private static String trimFloat(float f) {
        return String.format(java.util.Locale.US, f == Math.round(f) ? "%.0f" : "%.2f", f);
    }

    private static String prettyCodec(String codec) {
        if (codec == null) {
            return "";
        }
        String c = codec.toLowerCase(java.util.Locale.US);
        if (c.indexOf("hevc") >= 0 || c.indexOf("h265") >= 0 || c.indexOf("265") >= 0) {
            return "HEVC";
        }
        if (c.indexOf("avc") >= 0 || c.indexOf("h264") >= 0 || c.indexOf("264") >= 0) {
            return "H.264";
        }
        if (c.indexOf("av1") >= 0) {
            return "AV1";
        }
        if (c.indexOf("vp9") >= 0) {
            return "VP9";
        }
        if (c.indexOf("aac") >= 0) {
            return "AAC";
        }
        if (c.indexOf("eac3") >= 0 || c.indexOf("e-ac-3") >= 0) {
            return "E-AC-3";
        }
        if (c.indexOf("ac3") >= 0 || c.indexOf("ac-3") >= 0) {
            return "AC3";
        }
        if (c.indexOf("flac") >= 0) {
            return "FLAC";
        }
        if (c.indexOf("opus") >= 0) {
            return "Opus";
        }
        if (c.indexOf("mp3") >= 0) {
            return "MP3";
        }
        return codec.toUpperCase(java.util.Locale.US);
    }

    private void buildDecodeSettings() {
        // 内核不支持软解时只暴露硬解，历史保存的「软解」按硬解显示
        final boolean softOk = player != null && player.supportsSoftwareDecode();
        final String current = softOk ? playerPrefs.getString("decode", "硬解") : "硬解";
        addSettingOption("硬解", "效率高，功耗低，推荐选择", "硬解".equals(current),
                new View.OnClickListener() {
                    public void onClick(View v) {
                        playerPrefs.edit().putString("decode", "硬解").commit();
                        buildSettingOptions();
                    }
                });
        if (softOk) {
            addSettingOption("软解", "兼容更多视频格式", "软解".equals(current),
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            playerPrefs.edit().putString("decode", "软解").commit();
                            buildSettingOptions();
                        }
                    });
        }
    }

    /** 高级页三列（204 实测）：副项列 x=520（行 676/764/852），右列 x=1200 宽 640；
     *  图像显示先显示提示文案（高 122）再列选项，声道/音频直通直接从列顶排。 */
    private void buildAdvancedSettings() {
        setMidItems.clear();
        final String[] items = {"图像显示", "声道", "音频直通"};
        android.widget.LinearLayout columns = new android.widget.LinearLayout(this);
        columns.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        osdSetRight.addView(columns, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT));

        android.widget.LinearLayout mid = new android.widget.LinearLayout(this);
        mid.setOrientation(android.widget.LinearLayout.VERTICAL);
        columns.addView(mid, new android.widget.LinearLayout.LayoutParams(640,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            View row = createTabRow(items[i], null, false, true, false, true,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            advancedItem = which;
                            rebuildAdvancedOptions();
                        }
                    });
            row.setTag(items[i]);
            mid.addView(row);
            setMidItems.add(row);
        }

        advancedRight = new android.widget.LinearLayout(this);
        advancedRight.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(640,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        rlp.leftMargin = 40;
        columns.addView(advancedRight, rlp);
        rebuildAdvancedOptions();
    }

    /** 按当前副项重建右列：图像显示带三行提示（结束后选项从 y=798 起）。 */
    private void rebuildAdvancedOptions() {
        if (advancedRight == null) {
            return;
        }
        for (int i = tabListItems.size() - 1; i >= 0; i--) {
            View v = tabListItems.get(i);
            if (settingsColumn(v) == 2) {
                tabListItems.remove(i);
            }
        }
        advancedRight.removeAllViews();
        lastSettingFocus = null;
        final String key;
        final String[] values;
        if (advancedItem == 0) {
            key = "image_mode";
            values = new String[]{"自动", "SDR", "HDR"};
            TextView hint = new TextView(this);
            hint.setText("图像模式需解码器和显示设备同时支持。卡顿或色彩异常时，建议切换为较低规格。"
                    + "播放不支持的格式时，将自动转码并色调映射（需开启 GPU 加速）。");
            hint.setTextSize(16);
            hint.setTextColor(getResources().getColor(R.color.text_muted));
            hint.setLineSpacing(0, 1.2f);
            advancedRight.addView(hint, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 122));
        } else if (advancedItem == 1) {
            key = "audio_channel";
            values = new String[]{"立体声", "环绕声"};
        } else {
            key = "audio_passthrough";
            values = new String[]{"自动", "强制开启", "关闭"};
        }
        String current = playerPrefs.getString(key, values[0]);
        for (int i = 0; i < values.length; i++) {
            addAdvancedOption(values[i], values[i].equals(current), key, values[i]);
        }
    }

    private void addAdvancedOption(final String label, boolean selected, final String key,
                                   final String value) {
        View.OnClickListener click = new View.OnClickListener() {
            public void onClick(View v) {
                playerPrefs.edit().putString(key, value).commit();
                rebuildAdvancedOptions();
                for (int i = 0; i < tabListItems.size(); i++) {
                    View row = tabListItems.get(i);
                    if (settingsColumn(row) == 2 && label.equals(row.getTag())) {
                        row.requestFocus();
                        return;
                    }
                }
            }
        };
        View row = createTabRow(label, null, selected, true, false, true, click);
        row.setTag(label);
        advancedRight.addView(row);
    }

    /** 左右列内容超过 364 高时，按焦点行整体上移（官方 ScrollView 滚动行为）。 */
    private void scrollSettingRowIntoView(View row) {
        if (row == null) {
            return;
        }
        int leftAt = setLeftItems.indexOf(row);
        if (leftAt >= 0) {
            // 官方左列固定行为：首组在 676，其余组整列上移 78px（686/774/862/950）
            osdSetLeft.setTranslationY(leftAt == 0 ? 0 : -78);
            return;
        }
        // 高级页中列/右列官方不随焦点滚动（三列静止、行位自然 676/764/852）。
        // 高级模式下 osdSetRight 只装一个 columns 面板且列高写死，若走居中算法会把整块面板误上移。
        if (setMidItems.contains(row)) {
            osdSetRight.setTranslationY(0);
            return;
        }
        if (advancedRight != null) {
            View p = row;
            while (p != null) {
                if (p == advancedRight) {
                    osdSetRight.setTranslationY(0);
                    return;
                }
                android.view.ViewParent pp = p.getParent();
                p = pp instanceof View ? (View) pp : null;
            }
        }
        scrollRowIntoView(row, osdSetRight, osdSetRightClip);
    }

    /**
     * 把 row 滚动到 clip 的 364px 可视区内：
     * 内容不超出时保持自然位置（官方画面比例等四行正好放下，不滚动）；
     * 内容超出时把焦点行垂直居中（官方连播列实测：开启 745 / 关闭 818 /
     * 片头 872 / 片尾 960），再钳制到 [最大上滚量, 0]，避免回退时卡在负偏移。
     */
    private void scrollRowIntoView(View row, View container, View clip) {
        if (row == null || container == null || clip == null) {
            return;
        }
        int limit = clip.getHeight();
        if (limit <= 0) {
            return;
        }
        // 用子项实际占据的 bottom 作为内容高度：osdSetRight 布局高度写死为 640，
        // 大于 clip 的 364，若直接取 container.getHeight() 会把画面比例（仅 4 行）
        // 误判为溢出而错误滚动。官方只有内容真的超出时才滚动。
        int content = 0;
        if (container instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) container;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                int b = c.getTop() + c.getHeight();
                if (b > content) {
                    content = b;
                }
            }
        }
        if (content <= limit) {
            container.setTranslationY(0);
            return;
        }
        // 用屏幕坐标读取真实位置：container 在 clip 内可能有顶部偏移（连播 745 起、
        // 画面比例 676 起），且当前 translationY 仍为上一次的值，需扣掉才是布局位置。
        int[] clipLoc = new int[2];
        clip.getLocationOnScreen(clipLoc);
        int[] rowLoc = new int[2];
        row.getLocationOnScreen(rowLoc);
        float cur = container.getTranslationY();
        int layoutTop = (int) (rowLoc[1] - clipLoc[1] - cur);
        int rowH = row.getHeight();
        // 把焦点行垂直居中到 364px 可视区（官方连播实测：开启 745 / 关闭 818 / 片头 872 / 片尾 960）
        int target = limit / 2 - (layoutTop + rowH / 2);
        // 钳制：上滚不超过「内容底 = clip 底」，下滚不超过自然位（不露出顶部空白）
        int[] contLoc = new int[2];
        container.getLocationOnScreen(contLoc);
        int contLayoutTop = (int) (contLoc[1] - clipLoc[1] - cur);
        int maxUp = contLayoutTop + content - limit;
        if (target < -maxUp) {
            target = -maxUp;
        }
        if (target > 0) {
            target = 0;
        }
        container.setTranslationY(target);
    }

    private void addSettingOption(String label, boolean selected, View.OnClickListener click) {
        addSettingOption(label, null, selected, click);
    }

    private void addSettingOption(String label, String detail, boolean selected,
                                  View.OnClickListener click) {
        final String keepLabel = label;
        View.OnClickListener wrapped = click == null ? null : new View.OnClickListener() {
            public void onClick(View v) {
                pendingSettingFocus = keepLabel;
                click.onClick(v);
            }
        };
        View row = createTabRow(label, detail, selected, true, false, true, wrapped);
        row.setTag(label);
        osdSetRight.addView(row);
    }

    /** 画面比例：重设 SurfaceView 的实际画布并居中，硬件视频层按完整缓冲缩放。 */
    private void applySurfaceAspect(String value) {
        if (surface == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) surface.getParent();
        int pw = parent == null ? 0 : parent.getWidth();
        int ph = parent == null ? 0 : parent.getHeight();
        if (pw <= 0 || ph <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            pw = dm.widthPixels;
            ph = dm.heightPixels;
        }
        float target = 0f;
        if ("4:3".equals(value)) {
            target = 4f / 3f;
        } else if ("16:9".equals(value)) {
            target = 16f / 9f;
        } else if ("21:9".equals(value)) {
            target = 21f / 9f;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        lp.gravity = android.view.Gravity.CENTER;
        if (target <= 0f) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.leftMargin = 0;
            lp.topMargin = 0;
            lp.rightMargin = 0;
            lp.bottomMargin = 0;
        } else {
            int w;
            int h;
            if ((float) pw / ph > target) {
                h = ph;
                w = Math.round(h * target);
            } else {
                w = pw;
                h = Math.round(w / target);
            }
            lp.width = w;
            lp.height = h;
            lp.leftMargin = (pw - w) / 2;
            lp.topMargin = (ph - h) / 2;
            lp.rightMargin = pw - w - lp.leftMargin;
            lp.bottomMargin = ph - h - lp.topMargin;
        }
        surface.setScaleX(1f);
        surface.setScaleY(1f);
        surface.setLayoutParams(lp);
        L.i("Player", "aspect=" + value + " surface=" + lp.width + "x" + lp.height
                + " margin=" + lp.leftMargin + "," + lp.topMargin);
    }

    private void moveGridFocus(int dir) {
        View f = getCurrentFocus();
        int at = tabListItems.indexOf(f);
        if (at < 0) {
            return;
        }
        int columns = episodeGridColumns;
        int row = at / columns;
        int column = at % columns;
        int nextColumn = column + dir;
        if (nextColumn >= 0 && nextColumn < columns) {
            int next = row * columns + nextColumn;
            if (next < tabListItems.size()) {
                tabListItems.get(next).requestFocus();
                return;
            }
        }
        if (dir > 0 && osdEpNext != null
                && osdEpNext.getVisibility() == View.VISIBLE) {
            osdEpNext.requestFocus();
        } else if (dir < 0 && osdEpPrev != null
                && osdEpPrev.getVisibility() == View.VISIBLE) {
            osdEpPrev.requestFocus();
        }
    }

    private boolean moveGridFocusVertical(int dir) {
        View f = getCurrentFocus();
        int at = tabListItems.indexOf(f);
        if (at < 0) {
            return false;
        }
        int next = at + dir * episodeGridColumns;
        if (next >= 0 && next < tabListItems.size()) {
            tabListItems.get(next).requestFocus();
            return true;
        }
        return false;
    }

    private boolean isLeftRow(View v) {
        return setLeftItems.contains(v);
    }

    /** 设置页列号：0 左组列 / 1 高级副项列 / 2 选项列。 */
    private int settingsColumn(View v) {
        if (v == null) {
            return -1;
        }
        if (setLeftItems.contains(v)) {
            return 0;
        }
        if (setMidItems.contains(v)) {
            return 1;
        }
        return 2;
    }

    /** 设置页 ←/→：左组 → 高级副项（无副项则选项）→ 选项；反向逐级回退。 */
    private void moveSettingsPaneFocus(boolean toRight) {
        View f = getCurrentFocus();
        if (f == null) {
            return;
        }
        int col = settingsColumn(f);
        if (col == 0) {
            if (!toRight) {
                return;
            }
            if (!setMidItems.isEmpty()) {
                // 高级页 → 中列上次停留的副项（官方 _g6/_g8：图像显示/声道/音频直通）
                int at = Math.min(advancedItem, setMidItems.size() - 1);
                setMidItems.get(at).requestFocus();
                return;
            }
            // 官方右列会恢复本组上次停留的选项（未访问过才落到首项）
            View remembered = rememberedSettingView();
            if (remembered != null && settingsColumn(remembered) == 2) {
                remembered.requestFocus();
                return;
            }
            focusFirstInColumn(2);
            return;
        }
        if (col == 1) {
            if (toRight) {
                View remembered = rememberedSettingView();
                if (remembered != null && settingsColumn(remembered) == 2) {
                    remembered.requestFocus();
                    return;
                }
                focusFirstInColumn(2);
                return;
            }
            View left = setGroup < setLeftItems.size() ? setLeftItems.get(setGroup) : null;
            if (left != null) {
                left.requestFocus();
            }
            return;
        }
        if (toRight) {
            return;
        }
        if (!setMidItems.isEmpty()) {
            int at = Math.min(advancedItem, setMidItems.size() - 1);
            setMidItems.get(at).requestFocus();
            return;
        }
        View left = setGroup < setLeftItems.size() ? setLeftItems.get(setGroup) : null;
        if (left != null) {
            left.requestFocus();
        }
    }

    private boolean focusFirstInColumn(int column) {
        for (int i = 0; i < tabListItems.size(); i++) {
            if (settingsColumn(tabListItems.get(i)) == column) {
                tabListItems.get(i).requestFocus();
                return true;
            }
        }
        return false;
    }

    /** 某一列的可见行（按 tabListItems 顺序），用于设置页上下移动。 */
    private List<View> settingsColumnItems(int column) {
        List<View> out = new ArrayList<View>();
        for (int i = 0; i < tabListItems.size(); i++) {
            if (settingsColumn(tabListItems.get(i)) == column) {
                out.add(tabListItems.get(i));
            }
        }
        return out;
    }

    /**
     * 上次停留的设置项（官方焦点记忆）。右列会随高级副项重建，
     * 视图引用可能已失效，因此再按「列 + 列内下标」从当前列表里取。
     */
    private View rememberedSettingView() {
        if (lastSettingFocus != null && lastSettingFocus.isShown()
                && tabListItems.contains(lastSettingFocus)) {
            return lastSettingFocus;
        }
        if (lastSettingCol <= 0 || lastSettingIndex < 0) {
            return null;
        }
        List<View> col = settingsColumnItems(lastSettingCol);
        if (lastSettingIndex >= col.size()) {
            return null;
        }
        View v = col.get(lastSettingIndex);
        return v.isShown() ? v : null;
    }

    /** 画质列表：自动 + NAS 挡位，当前选择打勾；应用后重建会话并保留焦点。 */
    private void buildQualityRows() {
        final List<String> values = new ArrayList<String>();
        final List<String> labels = new ArrayList<String>();
        values.add("");
        labels.add("自动");
        for (int i = 0; i < nasQualities.size(); i++) {
            values.add(nasQualities.get(i).value);
            labels.add(nasQualities.get(i).label);
        }
        for (int i = 0; i < labels.size(); i++) {
            final int index = i;
            final String value = values.get(i);
            final String label = labels.get(i);
            boolean selected = value.equals(activeQualityValue == null ? "" : activeQualityValue);
            osdTabList.addView(createTabRow(label, selected, true, false,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            if (value.equals(activeQualityValue)) {
                                return;
                            }
                            activeQualityValue = value;
                            qualityLabel = value.length() == 0 ? "自动" : label;
                            updateQualityButton();
                            routesSyncedSignature = null;
                            pendingRowFocus = index;
                            selectQuality(value);
                        }
                    }));
        }
    }

    /** 字幕页：官方左列「选择字幕 / 调整字幕」+ 右侧列表（轨道、调整项、添加字幕）。 */
    private void buildSubtitleRows() {
        applySubtitlePanelInsets();
        if (subMenu == 1) {
            buildSubtitleAdjustRows();
            return;
        }
        if (subtitleTracks.isEmpty() && !subtitleTracksLoading && !subtitleTracksFetched) {
            subtitleTracksLoading = true;
            subtitleTracksError = null;
            final int token = ++subtitleLoadToken;
            osdTabList.addView(createTabRow("正在读取字幕…", false, false, false, null));
            // 老机型偶发请求不回包：9 秒后强制收尾，保证面板不会永远停在读取中
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (token != subtitleLoadToken || !subtitleTracksLoading) {
                        return;
                    }
                    subtitleTracksLoading = false;
                    subtitleTracksError = "读取超时";
                    if (expandedTabId == R.id.btn_subtitle) {
                        rebuildTabList();
                    }
                }
            }, 9000L);
            new Thread(new Runnable() {
                public void run() {
                    final List<SubtitleTrack> fresh = new ArrayList<SubtitleTrack>();
                    final String[] err = new String[1];
                    try {
                        fresh.addAll(api.listSubtitles(cfg, item));
                    } catch (Throwable t) {
                        err[0] = t.getMessage();
                        L.w("Player", "字幕列表读取失败：" + t);
                    }
                    ui.post(new Runnable() {
                        public void run() {
                            if (token != subtitleLoadToken) {
                                return;
                            }
                            subtitleTracksLoading = false;
                            subtitleTracksFetched = true;
                            subtitleTracksError = err[0];
                            subtitleTracks.clear();
                            subtitleTracks.addAll(fresh);
                            if (expandedTabId == R.id.btn_subtitle) {
                                rebuildTabList();
                            }
                        }
                    });
                }
            }, "fntv-tab-subtitles").start();
            return;
        }
        String current = activeSubtitle == null ? "" : activeSubtitle.guid;
        final List<SubtitleTrack> tracks = new ArrayList<SubtitleTrack>(subtitleTracks);
        if (tracks.isEmpty()) {
            // 官方空态：当前没有字幕可设置；读取失败时把原因上屏，便于电视端排查
            TextView empty = new TextView(this);
            empty.setText(subtitleTracksError == null
                    ? "当前没有字幕可设置" : "字幕读取失败：" + subtitleTracksError);
            empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26);
            empty.setTextColor(0xCCFFFFFF);
            empty.setIncludeFontPadding(false);
            android.widget.LinearLayout.LayoutParams elp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 80);
            elp.leftMargin = 36;
            osdTabList.addView(empty, elp);
            // 直连播放读不到内置字幕时，按官方 dialog_directlink_tip 提示一次
            if (subtitleTracksError == null && !directLinkTipShown
                    && lastCandidate != null
                    && lastCandidate.source == com.juying.medianest.core.model.StreamSource.ORIGINAL) {
                directLinkTipShown = true;
                ui.post(new Runnable() {
                    public void run() {
                        showDirectLinkTip();
                    }
                });
            }
        } else {
            osdTabList.addView(createTabRow("关闭字幕", current.length() == 0, true, false,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            if (activeSubtitle != null) {
                                disableSubtitles();
                            }
                            pendingRowFocus = 0;
                            rebuildTabList();
                        }
                    }));
            for (int i = 0; i < tracks.size(); i++) {
                final int index = i;
                final SubtitleTrack track = tracks.get(i);
                boolean selected = track.guid.equals(current);
                View row = createTabRow(track.displayName(), selected, true, false,
                        new View.OnClickListener() {
                            public void onClick(View v) {
                                if (!track.guid.equals(activeSubtitle == null ? "" : activeSubtitle.guid)) {
                                    selectSubtitle(track);
                                }
                                pendingRowFocus = index + 1;
                                rebuildTabList();
                            }
                        });
                // 官方 dialog_subtitle_delete：长按字幕轨弹出删除确认
                row.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        showDeleteSubtitleDialog(track);
                        return true;
                    }
                });
                osdTabList.addView(row);
            }
        }
        osdTabList.addView(createTabRow("添加字幕 ›", false, true, false,
                new View.OnClickListener() {
                    public void onClick(View v) {
                        showAddSubtitleActions();
                    }
                }));
    }

    /** 字幕页左列与右列表缩进：字幕页左列 400、右列表 x=520 宽 1320；其他标签恢复 385/1150。 */
    private void applySubtitlePanelInsets() {
        boolean subtitleTab = expandedTabId == R.id.btn_subtitle;
        if (osdSubLeft != null) {
            osdSubLeft.setVisibility(subtitleTab ? View.VISIBLE : View.GONE);
        }
        if (osdTabList != null) {
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) osdTabList.getLayoutParams();
            int left = subtitleTab ? 520 : 385;
            int width = subtitleTab ? 1320 : 1150;
            if (lp.leftMargin != left || lp.width != width) {
                lp.leftMargin = left;
                lp.width = width;
                osdTabList.setLayoutParams(lp);
            }
        }
        if (subtitleTab) {
            updateSubTileState();
        }
    }

    private void updateSubTileState() {
        if (osdSubSelectTab == null || osdSubAdjustTab == null) {
            return;
        }
        osdSubSelectTab.setBackgroundColor(
                subMenu == 0 ? 0x33FFFFFF : 0x17FFFFFF);
        osdSubAdjustTab.setBackgroundColor(
                subMenu == 1 ? 0x33FFFFFF : 0x17FFFFFF);
    }

    private void installSubTileFocus(final TextView tile) {
        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    v.setBackgroundColor(getResources().getColor(R.color.osd_row_focus));
                    ((TextView) v).setTextColor(getResources().getColor(R.color.osd_on_light));
                } else {
                    updateSubTileState();
                    ((TextView) v).setTextColor(getResources().getColor(R.color.text));
                }
            }
        });
    }

    /** 官方「调整字幕」子菜单：调整位置 / 调整大小 / 调整偏移 / 重置。 */
    private void buildSubtitleAdjustRows() {
        if (SubtitlePolicy.supportsPositionAdjustment(subtitleFormat)) {
            osdTabList.addView(createTabRow("调整位置", false, true, false,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            openSubAdjust(ADJ_POSITION);
                        }
                    }));
        }
        osdTabList.addView(createTabRow("调整大小", false, true, false,
                new View.OnClickListener() {
                    public void onClick(View v) {
                        openSubAdjust(ADJ_SIZE);
                    }
                }));
        osdTabList.addView(createTabRow("调整偏移", false, true, false,
                new View.OnClickListener() {
                    public void onClick(View v) {
                        openSubAdjust(ADJ_OFFSET);
                    }
                }));
        osdTabList.addView(createTabRow("重置", false, true, false,
                new View.OnClickListener() {
                    public void onClick(View v) {
                        subOffsetMs = 0L;
                        subScale = 1f;
                        subYOffset = 0;
                        saveSubtitleAdjust();
                        requestSubtitleFrame();
                        refreshSubtitleGeometry();
                        Ui.toast(PlayerActivity.this, "字幕设置已重置");
                    }
                }));
        if (activeSubtitle == null) {
            Ui.toast(PlayerActivity.this, "请先选择字幕");
        }
    }

    // ------------------------------------------------------------------
    // 官方字幕调整弹窗（dialog_subtitle_adjust_offset / size / position）
    // ------------------------------------------------------------------

    private void openSubAdjust(int kind) {
        if (subAdjustOverlay == null || subAdjustPanel == null) {
            return;
        }
        subAdjustKind = kind;
        subAdjustShown = true;
        subAdjustValue = null;
        subAdjustPanel.removeAllViews();
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) subAdjustPanel.getLayoutParams();
        lp.gravity = (kind == ADJ_SIZE ? android.view.Gravity.CENTER_VERTICAL
                : android.view.Gravity.BOTTOM) | android.view.Gravity.END;
        lp.width = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT;
        lp.height = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT;
        lp.rightMargin = 40;
        lp.bottomMargin = 40;
        lp.topMargin = 0;
        subAdjustPanel.setLayoutParams(lp);
        subAdjustPanel.setMinimumWidth(kind == ADJ_SIZE ? 260 : 437);
        subAdjustPanel.setMinimumHeight(kind == ADJ_SIZE ? 520 : 300);
        if (kind == ADJ_SIZE) {
            buildSubSizePanel();
        } else {
            buildSubArrowPanel(kind);
        }
        subAdjustOverlay.setVisibility(View.VISIBLE);
        subAdjustPanel.setFocusable(true);
        subAdjustPanel.setFocusableInTouchMode(true);
        subAdjustPanel.requestFocus();
        // 大小列表默认把焦点放在当前档位，方向键即可逐档切换
        if (kind == ADJ_SIZE && subSizeTarget != null) {
            subSizeTarget.requestFocus();
        }
    }

    /** 偏移/位置：60px 数字 + 左右（上下）箭头 + 32px 操作提示，官方右下 437×300 面板。 */
    private void buildSubArrowPanel(int kind) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams rowLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        subAdjustPanel.addView(row, rowLp);

        TextView left = new TextView(this);
        left.setText(kind == ADJ_OFFSET ? "◀" : "▲");
        left.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 36);
        left.setTextColor(0xFFFFFFFF);
        left.setIncludeFontPadding(false);
        left.setPadding(16, 0, 16, 0);
        row.addView(left);

        subAdjustValue = new TextView(this);
        subAdjustValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 60);
        subAdjustValue.setTypeface(Typeface.DEFAULT_BOLD);
        subAdjustValue.setTextColor(0xFFFFFFFF);
        subAdjustValue.setIncludeFontPadding(false);
        subAdjustValue.setGravity(android.view.Gravity.CENTER);
        row.addView(subAdjustValue, new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView right = new TextView(this);
        right.setText(kind == ADJ_OFFSET ? "▶" : "▼");
        right.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 36);
        right.setTextColor(0xFFFFFFFF);
        right.setIncludeFontPadding(false);
        right.setPadding(16, 0, 16, 0);
        row.addView(right);

        TextView hint = new TextView(this);
        hint.setText(kind == ADJ_OFFSET ? "按左右调整时间偏移" : "按上下调整字幕位置");
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 32);
        hint.setTextColor(0xCCFFFFFF);
        hint.setIncludeFontPadding(false);
        hint.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams hlp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = 24;
        subAdjustPanel.addView(hint, hlp);
        updateSubAdjustValue();
    }

    /** 大小：官方右侧 260×520 列表，90%/100%/125%/150%/200%。 */
    private void buildSubSizePanel() {
        final float[] scales = {0.9f, 1f, 1.25f, 1.5f, 2f};
        subSizeTarget = null;
        for (int i = 0; i < scales.length; i++) {
            final float scale = scales[i];
            final TextView row = new TextView(this);
            row.setText(Math.round(scale * 100) + "%");
            row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 28);
            row.setTextColor(getResources().getColor(R.color.text));
            if (Math.abs(subScale - scale) < 0.01f) {
                row.setTypeface(Typeface.DEFAULT_BOLD);
                subSizeTarget = row;
            }
            row.setIncludeFontPadding(false);
            row.setGravity(android.view.Gravity.CENTER);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    subScale = scale;
                    saveSubtitleAdjust();
                    refreshSubtitleGeometry();
                    closeSubAdjust();
                }
            });
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackgroundColor(hasFocus
                            ? getResources().getColor(R.color.osd_row_focus) : 0x00000000);
                    row.setTextColor(hasFocus
                            ? getResources().getColor(R.color.osd_on_light)
                            : getResources().getColor(R.color.text));
                }
            });
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 80);
            lp.topMargin = i == 0 ? 0 : 8;
            subAdjustPanel.addView(row, lp);
        }
    }

    private void updateSubAdjustValue() {
        if (subAdjustValue == null) {
            return;
        }
        if (subAdjustKind == ADJ_OFFSET) {
            subAdjustValue.setText(String.format(java.util.Locale.US, "%+.1fs", subOffsetMs / 1000f));
        } else {
            subAdjustValue.setText(String.format(java.util.Locale.US, "%+d", subYOffset));
        }
    }

    private void stepSubAdjust(int direction) {
        if (subAdjustKind == ADJ_OFFSET) {
            subOffsetMs += direction * 100L;
            if (subOffsetMs > 30000L) {
                subOffsetMs = 30000L;
            }
            if (subOffsetMs < -30000L) {
                subOffsetMs = -30000L;
            }
        } else {
            subYOffset += direction * 24;
            if (subYOffset > 400) {
                subYOffset = 400;
            }
            if (subYOffset < -400) {
                subYOffset = -400;
            }
        }
        saveSubtitleAdjust();
        if (subAdjustKind == ADJ_OFFSET) {
            requestSubtitleFrame();
        } else {
            refreshSubtitleGeometry();
        }
        updateSubAdjustValue();
    }

    private void saveSubtitleAdjust() {
        if (playerPrefs == null) {
            return;
        }
        playerPrefs.edit()
                .putLong("subtitle_offset_ms", subOffsetMs)
                .putFloat("subtitle_scale", subScale)
                .putInt("subtitle_y_offset", subYOffset)
                .apply();
    }

    private void refreshSubtitleGeometry() {
        if (!hasLastSubtitleRect || subtitleImage == null || playerSubtitle == null
                || subtitleImage.getDrawable() == null) {
            return;
        }
        int w = playerSubtitle.getWidth();
        int h = playerSubtitle.getHeight();
        if (w <= 0 || h <= 0) {
            w = surface != null ? surface.getWidth() : 0;
            h = surface != null ? surface.getHeight() : 0;
        }
        applySubtitleGeometry(w, h);
    }

    private void applySubtitleGeometry(int w, int h) {
        if (!hasLastSubtitleRect || subtitleImage == null || lastSubtitleRect.isEmpty()) {
            return;
        }
        int rw = Math.max(1, Math.round(lastSubtitleRect.width() * subScale));
        int rh = Math.max(1, Math.round(lastSubtitleRect.height() * subScale));
        int rl = lastSubtitleRect.left + (lastSubtitleRect.width() - rw) / 2;
        int rt = lastSubtitleRect.top + (lastSubtitleRect.height() - rh) + subYOffset;
        if (rt < 0) {
            rt = 0;
        }
        if (w > 0 && rt + rh > h) {
            rt = Math.max(0, h - rh);
        }
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) subtitleImage.getLayoutParams();
        if (lp.width != rw || lp.height != rh || lp.leftMargin != rl || lp.topMargin != rt) {
            lp.width = rw;
            lp.height = rh;
            lp.leftMargin = rl;
            lp.topMargin = rt;
            subtitleImage.setLayoutParams(lp);
        }
    }

    private void closeSubAdjust() {
        subAdjustShown = false;
        subAdjustValue = null;
        if (subAdjustPanel != null) {
            // 弹窗内的行仍持有焦点，隐藏后系统不会立刻转移，先显式清除
            subAdjustPanel.clearFocus();
        }
        if (subAdjustOverlay != null) {
            subAdjustOverlay.setVisibility(View.GONE);
        }
        if (expandedTabId == R.id.btn_subtitle) {
            rebuildTabList();
            // 关闭弹窗后焦点回到「调整字幕」列表首行
            if (!tabListItems.isEmpty()) {
                tabListItems.get(0).requestFocus();
            }
        }
    }

    // ------------------------------------------------------------------
    // 官方字幕弹窗：添加字幕 / 搜索字幕 / 添加 NAS 字幕文件 / 删除字幕
    // 官方客户端在本机把设计稿 1920 宽当作 density=1，因此布局 dp 值与字号 sp
    // 都直接当 px 用；这里统一用 COMPLEX_UNIT_PX，避免 240dpi 下被放大 1.5 倍。
    // ------------------------------------------------------------------

    private android.app.Dialog osdDialog;
    private String subtitleSearchLan = "zh";

    private void dismissOsdDialog() {
        if (osdDialog != null) {
            try {
                osdDialog.dismiss();
            } catch (Throwable ignored) {
                // 已随 Activity 销毁
            }
            osdDialog = null;
        }
    }

    private android.app.Dialog newOsdDialog() {
        dismissOsdDialog();
        android.app.Dialog d = new android.app.Dialog(this,
                android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x99000000));
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        }
        osdDialog = d;
        return d;
    }

    private static android.graphics.drawable.GradientDrawable roundRect(int color, int radiusPx) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private static android.graphics.drawable.GradientDrawable roundRect(int color, int radiusPx,
                                                                        int strokePx, int strokeColor) {
        android.graphics.drawable.GradientDrawable g = roundRect(color, radiusPx);
        g.setStroke(strokePx, strokeColor);
        return g;
    }

    private FrameLayout dialogRoot(android.app.Dialog d) {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        d.setContentView(root);
        return root;
    }

    /** 官方 IconView 用的图标字体（assets/iconfont.ttf），与官方码点一一对应。 */
    private Typeface officialIconTypeface;

    private Typeface iconTypeface() {
        if (officialIconTypeface == null) {
            try {
                officialIconTypeface = Typeface.createFromAsset(getAssets(), "iconfont.ttf");
            } catch (Throwable t) {
                officialIconTypeface = iconFont != null ? iconFont : Typeface.DEFAULT;
            }
        }
        return officialIconTypeface;
    }

    /** 按官方 icon_ 字符串资源里的码点输出图标字形（字号同样按 px 传入）。 */
    private TextView iconGlyph(String glyph, int sizePx, int color) {
        TextView v = new TextView(this);
        v.setText(glyph);
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx);
        v.setTextColor(color);
        v.setTypeface(iconTypeface());
        v.setIncludeFontPadding(false);
        v.setGravity(android.view.Gravity.CENTER);
        return v;
    }

    /** 官方 dp/sp 数值在本机当作 px 用；统一入口避免再写出 SP 缩放。 */
    private TextView pxText(String text, int sizePx, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        return v;
    }

    /** 官方 icon_arrow_right_s / icon_download / icon_check 码点。 */
    private static final String ICON_ARROW_RIGHT = "\uE85F";
    private static final String ICON_DOWNLOAD = "\uEAF4";
    private static final String ICON_CHECK = "\uEAC5";

    /** 官方弹窗按钮：68dp 高、26sp、最大圆角；聚焦白底深色字。 */
    private TextView dialogButton(String label, final Runnable action) {
        final TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26);
        b.setTextColor(0xCCFFFFFF);
        b.setIncludeFontPadding(false);
        b.setGravity(android.view.Gravity.CENTER);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        b.setBackgroundDrawable(roundRect(0x00000000, 68));
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                b.setBackgroundDrawable(roundRect(hasFocus ? 0xFFFFFFFF : 0x00000000, 68));
                b.setTextColor(hasFocus ? 0xFF101114 : 0xCCFFFFFF);
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dismissOsdDialog();
                if (action != null) {
                    action.run();
                }
            }
        });
        return b;
    }

    /** 官方 dialog_add_subtitle_action：504 宽、标题 32、三个 66 高按钮。 */
    private void showAddSubtitleActions() {
        final android.app.Dialog d = newOsdDialog();
        FrameLayout root = dialogRoot(d);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundDrawable(roundRect(0xFF2F3035, 48, 1, 0x26FFFFFF));
        panel.setPadding(32, 48, 32, 32);
        root.addView(panel, new FrameLayout.LayoutParams(504,
                FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));

        TextView title = pxText("添加字幕", 32, 0xFFFFFFFF);
        title.setGravity(android.view.Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(408, 48));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(440,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = 43;
        panel.addView(buttons, clp);

        TextView search = addActionButton(buttons, "搜索字幕", 0, new Runnable() {
            public void run() {
                showSearchSubtitleDialog();
            }
        });
        addActionButton(buttons, "添加 NAS 字幕文件", 16, new Runnable() {
            public void run() {
                showNasSubtitleBrowser();
            }
        });
        addActionButton(buttons, "取消", 32, null);

        d.show();
        search.requestFocus();
    }

    /** 官方 bg_add_subtitle_action_button：66 高、28sp，聚焦白底圆角 18。 */
    private TextView addActionButton(LinearLayout parent, String label, int topMargin,
                                     final Runnable action) {
        final TextView b = pxText(label, 28, 0xFFFFFFFF);
        b.setGravity(android.view.Gravity.CENTER);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        b.setBackgroundDrawable(roundRect(0xB2525459, 16));
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundDrawable(roundRect(hasFocus ? 0xFFFFFFFF : 0xB2525459,
                        hasFocus ? 18 : 16));
                b.setTextColor(hasFocus ? 0xFF101114 : 0xFFFFFFFF);
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dismissOsdDialog();
                if (action != null) {
                    action.run();
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 66);
        lp.topMargin = topMargin;
        parent.addView(b, lp);
        return b;
    }

    /** 官方 dialog_search_subtitle：1260×940、语言按钮 + 结果列表。 */
    private void showSearchSubtitleDialog() {
        if (item.mediaGuid == null || item.mediaGuid.length() == 0) {
            Ui.toast(this, "当前媒体缺少 media_guid，无法搜索字幕");
            return;
        }
        final android.app.Dialog d = newOsdDialog();
        final FrameLayout root = dialogRoot(d);
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundDrawable(roundRect(0xEB2B2D33, 48, 2, 0x26FFFFFF));
        root.addView(panel, new FrameLayout.LayoutParams(1260, 940, android.view.Gravity.CENTER));

        // 头部：文件名 + 语言按钮（300×60，右侧 28 图标）
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(40, 40, 40, 40);
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = pxText(item.fileName != null && item.fileName.length() > 0
                ? item.fileName : (item.title == null ? "搜索字幕" : item.title), 32, 0xFFFFFFFF);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.rightMargin = 40;
        header.addView(title, tlp);

        final TextView langLabel = pxText(subtitleLanLabel(), 28, 0xFFFFFFFF);
        langLabel.setSingleLine(true);
        final LinearLayout langBtn = new LinearLayout(this);
        langBtn.setOrientation(LinearLayout.HORIZONTAL);
        langBtn.setGravity(android.view.Gravity.CENTER_VERTICAL);
        langBtn.setPadding(24, 0, 24, 0);
        langBtn.setFocusable(true);
        langBtn.setFocusableInTouchMode(true);
        langBtn.setClickable(true);
        langBtn.setBackgroundDrawable(roundRect(0xCC2F3035, 16, 2, 0x14FFFFFF));
        langBtn.addView(langLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView arrow = iconGlyph(ICON_ARROW_RIGHT, 28, 0xCCFFFFFF);
        langBtn.addView(arrow, new LinearLayout.LayoutParams(28, 28));
        header.addView(langBtn, new LinearLayout.LayoutParams(300, 60));

        View divider = new View(this);
        divider.setBackgroundColor(0x4DFFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dlp.leftMargin = 40;
        dlp.rightMargin = 40;
        panel.addView(divider, dlp);

        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, 40, 0, 36);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(listBox, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 官方 empty_subtitle：192 图标 + 24sp 说明，在列表区内居中
        final LinearLayout stateBox = new LinearLayout(this);
        stateBox.setOrientation(LinearLayout.VERTICAL);
        stateBox.setGravity(android.view.Gravity.CENTER);
        final android.widget.ImageView stateIcon = new android.widget.ImageView(this);
        stateIcon.setImageResource(R.drawable.empty_sub);
        stateIcon.setVisibility(View.GONE);
        stateBox.addView(stateIcon, new LinearLayout.LayoutParams(192, 192));
        final TextView stateText = pxText("正在搜索…", 24, 0xA6FFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = 24;
        stateBox.addView(stateText, slp);
        listBox.addView(stateBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        langBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showLanguagePopup(d, root, langBtn, langLabel, listBox, stateBox);
            }
        });
        langBtn.setOnFocusChangeListener(new android.view.View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundDrawable(roundRect(hasFocus ? 0xFFFFFFFF : 0xCC2F3035, 16, 2,
                        0x14FFFFFF));
                langLabel.setTextColor(hasFocus ? 0xFF101114 : 0xFFFFFFFF);
                arrow.setTextColor(hasFocus ? 0xFF101114 : 0xCCFFFFFF);
            }
        });

        d.show();
        langBtn.requestFocus();
        searchSubtitlesInto(listBox, stateBox);
    }

    /** 官方 dialog_subtitle_language：392 宽下拉列表，勾选当前语言。 */
    private void showLanguagePopup(final android.app.Dialog d, final FrameLayout root,
                                   final View anchor, final TextView langLabel,
                                   final LinearLayout listBox, final LinearLayout stateBox) {
        final LinearLayout pop = new LinearLayout(this);
        pop.setOrientation(LinearLayout.VERTICAL);
        pop.setBackgroundDrawable(roundRect(0xCC101114, 16, 1, 0x14FFFFFF));
        pop.setPadding(16, 40, 16, 40);

        int[] a = new int[2];
        int[] r = new int[2];
        anchor.getLocationInWindow(a);
        root.getLocationInWindow(r);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(392,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        plp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        plp.topMargin = a[1] - r[1] + anchor.getHeight() + 8;
        plp.rightMargin = r[0] + root.getWidth() - (a[0] + anchor.getWidth());
        root.addView(pop, plp);

        final String[] lans = {"zh", "en"};
        final String[] labels = {"简体中文", "English"};
        for (int i = 0; i < lans.length; i++) {
            final String lan = lans[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(24, 0, 24, 0);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            row.setBackgroundDrawable(roundRect(0x00000000, 16));
            TextView check = iconGlyph(ICON_CHECK, 32, 0xFFFFFFFF);
            check.setVisibility(lan.equals(subtitleSearchLan) ? View.VISIBLE : View.INVISIBLE);
            row.addView(check, new LinearLayout.LayoutParams(32, 32));
            final TextView label = pxText(labels[i], 26, 0xFFFFFFFF);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.leftMargin = 12;
            row.addView(label, llp);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 68);
            if (i > 0) {
                rlp.topMargin = 24;
            }
            pop.addView(row, rlp);
            final TextView checkRef = check;
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackgroundDrawable(roundRect(hasFocus ? 0xFFFFFFFF : 0x00000000, 16));
                    label.setTextColor(hasFocus ? 0xFF101114 : 0xFFFFFFFF);
                    checkRef.setTextColor(hasFocus ? 0xFF101114 : 0xFFFFFFFF);
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    root.removeView(pop);
                    langLabel.setText("zh".equals(lan) ? "简体中文" : "English");
                    subtitleSearchLan = lan;
                    searchSubtitlesInto(listBox, stateBox);
                }
            });
        }

        d.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            public boolean onKey(android.content.DialogInterface dialog, int keyCode,
                                 android.view.KeyEvent event) {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    root.removeView(pop);
                    d.setOnKeyListener(null);
                    anchor.requestFocus();
                    return true;
                }
                return false;
            }
        });
        pop.getChildAt(0).requestFocus();
    }

    private String subtitleLanLabel() {
        return "zh".equals(subtitleSearchLan) ? "简体中文" : "English";
    }

    /** 异步请求在线字幕并填充结果列表。 */
    private void searchSubtitlesInto(final LinearLayout listBox, final LinearLayout stateBox) {
        final String lan = subtitleSearchLan;
        setSearchState(stateBox, false, "正在搜索…");
        while (listBox.getChildCount() > 1) {
            listBox.removeViewAt(1);
        }
        new Thread(new Runnable() {
            public void run() {
                final List<SubtitleSearchItem> results = new ArrayList<SubtitleSearchItem>();
                String err = null;
                try {
                    results.addAll(api.searchSubtitles(cfg, item.mediaGuid, lan));
                } catch (Throwable t) {
                    err = t.getMessage();
                    L.w("Player", "在线字幕搜索失败：" + t);
                }
                final String error = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (osdDialog == null || !osdDialog.isShowing()) {
                            return;
                        }
                        if (subtitleSearchLan != lan) {
                            return; // 期间切换了语言
                        }
                        while (listBox.getChildCount() > 1) {
                            listBox.removeViewAt(1);
                        }
                        if (error != null) {
                            setSearchState(stateBox, false, "搜索失败：" + error);
                            return;
                        }
                        if (results.isEmpty()) {
                            setSearchState(stateBox, true, "当前没有字幕可设置");
                            return;
                        }
                        stateBox.setVisibility(View.GONE);
                        for (int i = 0; i < results.size(); i++) {
                            listBox.addView(searchResultRow(results.get(i)), resultRowLp());
                        }
                        if (listBox.getChildCount() > 1) {
                            listBox.getChildAt(1).requestFocus();
                        }
                    }
                });
            }
        }, "fntv-sub-search").start();
    }

    /** 官方 empty_subtitle 空态：图标 + 24sp 说明；搜索中只显示文案。 */
    private void setSearchState(LinearLayout stateBox, boolean withIcon, String text) {
        stateBox.setVisibility(View.VISIBLE);
        stateBox.getChildAt(0).setVisibility(withIcon ? View.VISIBLE : View.GONE);
        ((TextView) stateBox.getChildAt(1)).setText(text);
    }

    /** 官方 item_search_subtitle：92 高、左右 40、底部 12。 */
    private LinearLayout.LayoutParams resultRowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 92);
        lp.leftMargin = 40;
        lp.rightMargin = 40;
        lp.bottomMargin = 12;
        return lp;
    }

    private View searchResultRow(final SubtitleSearchItem s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(24, 0, 24, 0);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setBackgroundDrawable(roundRect(0x08FFFFFF, 16));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(column, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView name = pxText(s.displayName(), 28, 0xFFFFFFFF);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        column.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView detail = pxText(s.detail(), 24, 0xCCFFFFFF);
        detail.setSingleLine(true);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = 8;
        column.addView(detail, dlp);

        FrameLayout tail = new FrameLayout(this);
        final TextView icon = iconGlyph(ICON_DOWNLOAD, 36, 0xCCFFFFFF);
        tail.addView(icon, new FrameLayout.LayoutParams(36, 36,
                android.view.Gravity.CENTER));
        final ProgressBar spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        tail.addView(spinner, new FrameLayout.LayoutParams(36, 36,
                android.view.Gravity.CENTER));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(48, 48);
        tlp.leftMargin = 16;
        row.addView(tail, tlp);

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundDrawable(roundRect(hasFocus ? 0xFFFFFFFF : 0x08FFFFFF, 16));
                name.setTextColor(hasFocus ? 0xFF101114 : 0xFFFFFFFF);
                detail.setTextColor(hasFocus ? 0x99101114 : 0xCCFFFFFF);
                icon.setTextColor(hasFocus ? 0xFF101114 : 0xCCFFFFFF);
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                downloadSearchedSubtitle(s, icon, spinner);
            }
        });
        return row;
    }

    private void downloadSearchedSubtitle(final SubtitleSearchItem s, final View icon,
                                          final View spinner) {
        icon.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    api.downloadOnlineSubtitle(cfg, item.mediaGuid, s.trimId);
                } catch (Throwable t) {
                    err = t.getMessage();
                    L.w("Player", "字幕下载失败：" + t);
                }
                final String error = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (error != null) {
                            spinner.setVisibility(View.GONE);
                            icon.setVisibility(View.VISIBLE);
                            Ui.toast(PlayerActivity.this, "字幕下载失败：" + error);
                        } else {
                            dismissOsdDialog();
                            Ui.toast(PlayerActivity.this, "字幕已下载：" + s.displayName());
                            refreshSubtitleTracks();
                        }
                    }
                });
            }
        }, "fntv-sub-download").start();
    }

    /** 官方「添加 NAS 字幕文件」：浏览授权目录，选中字幕后 PUT /subtitle/mark。 */
    private void showNasSubtitleBrowser() {
        if (item.mediaGuid == null || item.mediaGuid.length() == 0) {
            Ui.toast(this, "当前媒体缺少 media_guid，无法添加 NAS 字幕");
            return;
        }
        final android.app.Dialog d = newOsdDialog();
        FrameLayout root = dialogRoot(d);
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundDrawable(roundRect(0xCC2F3035, 28, 1, 0x26FFFFFF));
        panel.setPadding(0, 40, 0, 40);
        root.addView(panel, new FrameLayout.LayoutParams(1225, 924, android.view.Gravity.CENTER));

        // 头部：当前目录（超长从左侧截断）+ 关闭按钮
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(60, 0, 60, 0);
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView pathLabel = pxText("", 36, 0xFFFFFFFF);
        pathLabel.setSingleLine(true);
        pathLabel.setTypeface(Typeface.DEFAULT_BOLD);
        pathLabel.setEllipsize(android.text.TextUtils.TruncateAt.START);
        header.addView(pathLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView close = pxText("关闭", 28, 0xCCFFFFFF);
        close.setGravity(android.view.Gravity.CENTER);
        close.setPadding(48, 0, 48, 0);
        close.setFocusable(true);
        close.setFocusableInTouchMode(true);
        close.setClickable(true);
        close.setBackgroundDrawable(roundRect(0xFF2F3035, 64));
        close.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                close.setBackgroundDrawable(roundRect(hasFocus ? 0xFFFFFFFF : 0xFF2F3035, 64));
                close.setTextColor(hasFocus ? 0xFF101114 : 0xCCFFFFFF);
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dismissOsdDialog();
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 64);
        clp.leftMargin = 20;
        header.addView(close, clp);
        nasCloseBtn = close;

        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(listBox, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.leftMargin = 20;
        slp.rightMargin = 20;
        slp.topMargin = 20;
        slp.bottomMargin = 20;
        panel.addView(scroll, slp);

        // 底部按键提示（官方 select_nas_subtitle_left/right_hint）
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(android.view.Gravity.CENTER_VERTICAL);
        footer.setPadding(60, 0, 60, 0);
        panel.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        footer.addView(pxText("按【OK键】添加字幕或进入文件夹", 24, 0xA6FFFFFF),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        View fdiv = new View(this);
        fdiv.setBackgroundColor(0x4DFFFFFF);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(1, 22);
        flp.leftMargin = 36;
        flp.rightMargin = 36;
        footer.addView(fdiv, flp);
        footer.addView(pxText("长按【返回键】关闭", 24, 0xA6FFFFFF),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        // 返回键：短按回上一级，长按关闭
        d.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            public boolean onKey(android.content.DialogInterface dialog, int keyCode,
                                 android.view.KeyEvent event) {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    String parent = nasCurPath == null ? null : parentPath(nasCurPath);
                    if (event.getRepeatCount() > 0 || parent == null || !underNasRoot(parent)) {
                        dismissOsdDialog();
                        return true;
                    }
                    loadNasDirectory(listBox, pathLabel, parent);
                    return true;
                }
                return false;
            }
        });

        d.show();
        loadNasDirectory(listBox, pathLabel, null);
    }

    /** 当前浏览的 NAS 目录；null 表示还在授权根目录（可能展示多个根）。 */
    private String nasCurPath;
    /** 授权根目录：返回到根之上会触发 Permission Error，直接关闭弹窗。 */
    private final List<String> nasRoots = new ArrayList<String>();
    /** NAS 浏览弹窗的关闭按钮，出错时把焦点放到这里，避免焦点丢失。 */
    private TextView nasCloseBtn;

    /** 载入目录：path 为空时先取授权根目录。 */
    private void loadNasDirectory(final LinearLayout listBox, final TextView pathLabel,
                                  final String path) {
        showBrowserStatus(listBox, "正在读取…");
        new Thread(new Runnable() {
            public void run() {
                final List<FsEntry> entries = new ArrayList<FsEntry>();
                final List<String> roots = new ArrayList<String>();
                String err = null;
                try {
                    if (path == null) {
                        roots.addAll(api.authorizedDirs(cfg));
                    } else {
                        entries.addAll(api.listDirectory(cfg, path));
                    }
                } catch (Throwable t) {
                    err = t.getMessage();
                    L.w("Player", "NAS 目录读取失败：" + t);
                }
                final String error = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (osdDialog == null || !osdDialog.isShowing()) {
                            return;
                        }
                        if (error != null) {
                            showBrowserStatus(listBox, "读取失败：" + error);
                            if (nasCloseBtn != null) {
                                nasCloseBtn.requestFocus();
                            }
                            return;
                        }
                        while (listBox.getChildCount() > 0) {
                            listBox.removeViewAt(0);
                        }
                        if (path == null) {
                            nasCurPath = null;
                            nasRoots.clear();
                            nasRoots.addAll(roots);
                            if (roots.isEmpty()) {
                                pathLabel.setText("添加 NAS 字幕文件");
                                showBrowserStatus(listBox, "没有可浏览的目录");
                                return;
                            }
                            if (roots.size() == 1) {
                                loadNasDirectory(listBox, pathLabel, roots.get(0));
                                return;
                            }
                            pathLabel.setText("添加 NAS 字幕文件");
                            addBrowserRoots(listBox, pathLabel, roots);
                            return;
                        }
                        nasCurPath = path;
                        pathLabel.setText(path);
                        addBrowserEntries(listBox, pathLabel, path, entries);
                    }
                });
            }
        }, "fntv-nas-browse").start();
    }

    private void showBrowserStatus(LinearLayout listBox, String text) {
        while (listBox.getChildCount() > 0) {
            listBox.removeViewAt(0);
        }
        TextView t = pxText(text, 24, 0xA6FFFFFF);
        t.setGravity(android.view.Gravity.CENTER);
        listBox.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 80));
    }

    private void addBrowserRoots(final LinearLayout listBox, final TextView pathLabel,
                                 List<String> roots) {
        for (int i = 0; i < roots.size(); i++) {
            final String p = roots.get(i);
            listBox.addView(nasRow(p, true, new Runnable() {
                public void run() {
                    loadNasDirectory(listBox, pathLabel, p);
                }
            }));
        }
        if (listBox.getChildCount() > 0) {
            listBox.getChildAt(0).requestFocus();
        }
    }

    private void addBrowserEntries(final LinearLayout listBox, final TextView pathLabel,
                                   final String dir, List<FsEntry> entries) {
        int firstFile = -1;
        for (int i = 0; i < entries.size(); i++) {
            final FsEntry e = entries.get(i);
            if (!e.isDir && !e.isSubtitleFile()) {
                continue;
            }
            final String full = joinPath(dir, e.filename);
            if (e.isDir) {
                listBox.addView(nasRow(e.filename, true, new Runnable() {
                    public void run() {
                        loadNasDirectory(listBox, pathLabel, full);
                    }
                }));
            } else {
                if (firstFile < 0) {
                    firstFile = listBox.getChildCount();
                }
                listBox.addView(nasRow(e.filename, false, new Runnable() {
                    public void run() {
                        markNasSubtitle(full);
                    }
                }));
            }
        }
        if (listBox.getChildCount() == 0) {
            showBrowserStatus(listBox, "该目录下没有字幕文件");
            return;
        }
        listBox.getChildAt(firstFile >= 0 ? firstFile : 0).requestFocus();
    }

    /** 官方 item_nas_file：92 高、padding 16、60×60 图标、28 粗体文件名。 */
    private View nasRow(String label, boolean dir, final Runnable action) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(16, 16, 16, 16);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setBackgroundDrawable(roundRect(0x00000000, 16));

        final TextView dirIcon;
        if (dir) {
            dirIcon = iconGlyph(ICON_ARROW_RIGHT, 40, 0xCCFFFFFF);
            row.addView(dirIcon, new LinearLayout.LayoutParams(60, 60));
        } else {
            dirIcon = null;
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageResource(R.drawable.ic_subtitle);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            row.addView(iv, new LinearLayout.LayoutParams(60, 60));
        }

        final TextView name = pxText(label, 28, 0xFFFFFFFF);
        name.setSingleLine(true);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = 18;
        row.addView(name, nlp);

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                row.setBackgroundDrawable(roundRect(hasFocus ? 0xFFFFFFFF : 0x00000000, 16));
                name.setTextColor(hasFocus ? 0xFF101114 : 0xFFFFFFFF);
                if (dirIcon != null) {
                    dirIcon.setTextColor(hasFocus ? 0xFF101114 : 0xCCFFFFFF);
                }
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                action.run();
            }
        });
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 92));
        return row;
    }

    private static String joinPath(String dir, String name) {
        if (dir == null || dir.length() == 0) {
            return "/" + name;
        }
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private static String parentPath(String dir) {
        if (dir == null || dir.length() == 0 || "/".equals(dir)) {
            return null;
        }
        String p = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
        int at = p.lastIndexOf('/');
        return at <= 0 ? "/" : p.substring(0, at);
    }

    /** 是否仍在授权根目录之下（含根目录本身）。 */
    private boolean underNasRoot(String path) {
        for (int i = 0; i < nasRoots.size(); i++) {
            String r = nasRoots.get(i);
            if (r == null || r.length() == 0) {
                continue;
            }
            if (path.equals(r) || path.startsWith(r.endsWith("/") ? r : r + "/")) {
                return true;
            }
        }
        return false;
    }

    private void markNasSubtitle(final String filepath) {
        dismissOsdDialog();
        Ui.toast(this, "正在添加字幕…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    List<String> paths = new ArrayList<String>();
                    paths.add(filepath);
                    api.markSubtitles(cfg, item.mediaGuid, paths);
                } catch (Throwable t) {
                    err = t.getMessage();
                    L.w("Player", "NAS 字幕标记失败：" + t);
                }
                final String error = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (error != null) {
                            Ui.toast(PlayerActivity.this, "添加字幕失败：" + error);
                        } else {
                            Ui.toast(PlayerActivity.this, "字幕已添加");
                            refreshSubtitleTracks();
                        }
                    }
                });
            }
        }, "fntv-sub-mark").start();
    }

    /** 重新拉取 subtitle_streams 并刷新当前字幕页。 */
    private void refreshSubtitleTracks() {
        subtitleTracksLoading = true;
        subtitleTracksFetched = false;
        subtitleTracksError = null;
        final int token = ++subtitleLoadToken;
        ui.postDelayed(new Runnable() {
            public void run() {
                if (token != subtitleLoadToken || !subtitleTracksLoading) {
                    return;
                }
                subtitleTracksLoading = false;
                subtitleTracksError = "读取超时";
                if (expandedTabId == R.id.btn_subtitle) {
                    rebuildTabList();
                }
            }
        }, 9000L);
        new Thread(new Runnable() {
            public void run() {
                final List<SubtitleTrack> fresh = new ArrayList<SubtitleTrack>();
                try {
                    fresh.addAll(api.listSubtitles(cfg, item));
                } catch (Throwable t) {
                    L.w("Player", "刷新字幕轨道失败：" + t);
                }
                ui.post(new Runnable() {
                    public void run() {
                        if (token != subtitleLoadToken) {
                            return;
                        }
                        subtitleTracksLoading = false;
                        subtitleTracksFetched = true;
                        subtitleTracks.clear();
                        subtitleTracks.addAll(fresh);
                        if (expandedTabId == R.id.btn_subtitle) {
                            rebuildTabList();
                        }
                    }
                });
            }
        }, "fntv-sub-refresh").start();
    }

    /** 官方 dialog_subtitle_delete：标题 + 文件名 + 删除/取消。 */
    private void showDeleteSubtitleDialog(final SubtitleTrack track) {
        if (track == null || track.guid.length() == 0) {
            return;
        }
        final android.app.Dialog d = newOsdDialog();
        FrameLayout root = dialogRoot(d);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundDrawable(roundRect(0xCC2F3035, 16, 1, 0x26FFFFFF));
        panel.setPadding(32, 32, 32, 32);
        root.addView(panel, new FrameLayout.LayoutParams(534,
                FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));

        TextView title = new TextView(this);
        title.setText("删除字幕");
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24);
        title.setTextColor(0xA6FFFFFF);
        title.setIncludeFontPadding(false);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        name.setText(track.displayName());
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 28);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(0xA6FFFFFF);
        name.setMaxLines(2);
        name.setIncludeFontPadding(false);
        name.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = 24;
        panel.addView(name, nlp);

        View divider = new View(this);
        divider.setBackgroundColor(0x4DFFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dlp.topMargin = 28;
        dlp.bottomMargin = 24;
        panel.addView(divider, dlp);

        final TextView del = new TextView(this);
        del.setText("删除");
        del.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26);
        del.setTextColor(0xCCFFFFFF);
        del.setIncludeFontPadding(false);
        del.setGravity(android.view.Gravity.CENTER);
        del.setFocusable(true);
        del.setFocusableInTouchMode(true);
        del.setClickable(true);
        del.setBackgroundDrawable(roundRect(0x00000000, 68));
        del.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                del.setBackgroundDrawable(roundRect(hasFocus ? 0xFFBF0023 : 0x00000000, 68));
                del.setTextColor(hasFocus ? 0xFFFFFFFF : 0xCCFFFFFF);
            }
        });
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                deleteSubtitleTrack(track);
            }
        });
        panel.addView(del, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 68));

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 68);
        clp.topMargin = 6;
        panel.addView(dialogButton("取消", null), clp);

        d.show();
        del.requestFocus();
    }

    private void deleteSubtitleTrack(final SubtitleTrack track) {
        dismissOsdDialog();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    api.deleteSubtitle(cfg, track.guid);
                } catch (Throwable t) {
                    err = t.getMessage();
                    L.w("Player", "删除字幕失败：" + t);
                }
                final String error = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (error != null) {
                            Ui.toast(PlayerActivity.this, "删除字幕失败：" + error);
                            return;
                        }
                        if (activeSubtitle != null && track.guid.equals(activeSubtitle.guid)) {
                            disableSubtitles();
                        }
                        Ui.toast(PlayerActivity.this, "字幕已删除");
                        refreshSubtitleTracks();
                    }
                });
            }
        }, "fntv-sub-del").start();
    }

    /** 音频列表：来自内核暴露的音轨，默认勾选当前音轨。 */
    private void buildAudioRows() {
        List<String> labels = player == null
                ? new ArrayList<String>() : player.audioTrackLabels();
        if (labels.isEmpty()) {
            labels.add("默认音轨");
        }
        int current = player == null ? -1 : player.currentAudioTrack();
        for (int i = 0; i < labels.size(); i++) {
            final int index = i;
            final String label = labels.get(i);
            boolean selected = current < 0 ? i == 0 : i == current;
            osdTabList.addView(createTabRow(label, selected, true, false,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            if (player != null) {
                                player.selectAudioTrack(index);
                            }
                            showNotice("音轨：" + label);
                            pendingRowFocus = index;
                            rebuildTabList();
                        }
                    }));
        }
    }

    /** 倍速列表：官方 0.5 / 0.75 / 1.0 / 1.25 / 1.5x。 */
    private void buildSpeedRows() {
        final float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f};
        float current = player == null ? 1f : player.getSpeed();
        for (int i = 0; i < speeds.length; i++) {
            final int index = i;
            final float speed = speeds[i];
            final boolean supported = player != null && player.supportsSpeed();
            osdTabList.addView(createTabRow(formatSpeed(speed),
                    Math.abs(current - speed) < 0.01f, supported, true,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            if (player != null && player.supportsSpeed()) {
                                player.setSpeed(speed);
                                updateSpeedTabLabel(speed);
                                pendingRowFocus = index;
                                rebuildTabList();
                            } else {
                                Ui.toast(PlayerActivity.this, "当前内核不支持倍速");
                            }
                        }
                    }));
        }
    }

    private static String formatSpeed(float speed) {
        if (Math.abs(speed - Math.round(speed)) < 0.01f) {
            return ((int) Math.round(speed)) + ".0x";
        }
        return speed + "x";
    }

    /** 官方倍速标签直接显示当前倍速（1.0x 时仍显示「倍速」，204 实测）。 */
    private void updateSpeedTabLabel(float speed) {
        TextView t = (TextView) findViewById(R.id.tab_speed);
        if (t != null) {
            t.setText(Math.abs(speed - 1f) < 0.01f ? "倍速" : formatSpeed(speed));
        }
    }

    private void rebuildTabList() {
        final View before = getCurrentFocus();
        final boolean beforeTile = before == osdSubSelectTab || before == osdSubAdjustTab;
        final boolean beforeTab = isTabView(before);
        final int tab = expandedTabId;
        // 重建期间先移除焦点控件，若让「焦点离开标签」检查立刻执行会误收起整个面板
        tabsRebuilding = true;
        try {
            expandedTabId = 0;
            applySubtitlePanelInsets();
            setOsdMetaVisible(false);
            osdTabList.removeAllViews();
            tabListItems.clear();
            expandTab(tab);
            restoreTabFocusAfterRebuild(before, beforeTile, beforeTab, tab);
        } finally {
            tabsRebuilding = false;
        }
        // 弹窗关闭后焦点常在本帧末尾才被系统清掉，下一帧再补一次
        ui.postDelayed(new Runnable() {
            public void run() {
                restoreTabFocusAfterRebuild(before, beforeTile, beforeTab, tab);
            }
        }, 32L);
    }

    /** 列表重建会移除当时聚焦的行；这里把焦点放回左列、标签或新列表首行，避免焦点丢失。 */
    private void restoreTabFocusAfterRebuild(View before, boolean beforeTile,
                                             boolean beforeTab, int tab) {
        if (!osdVisible) {
            return;
        }
        View now = getCurrentFocus();
        boolean lost = now == null || now.getParent() == null || !now.isShown()
                || now == getWindow().getDecorView() || now.getId() == android.R.id.content;
        if (!lost) {
            return;
        }
        if (beforeTile) {
            TextView tile = subMenu == 1 ? osdSubAdjustTab : osdSubSelectTab;
            if (tile != null && tile.requestFocus()) {
                return;
            }
        }
        if (beforeTab && before != null && before.requestFocus()) {
            return;
        }
        if (!tabListItems.isEmpty() && tabListItems.get(0).requestFocus()) {
            return;
        }
        View t = findViewById(tab != 0 ? tab : lastTabId);
        if (t != null) {
            t.requestFocus();
        }
    }

    /** 一行 = [✓][文字]，行高 80、行距 88，与官方 routine_list_tile 一致。
     *  204 实测：两页首行均 y=676，行自带约 9% 白色半透明底（over 视频亮度实测 +18）；
     *  列表行（音频/字幕/画质/倍速）勾选 [421,y][453,y+32]、文字墨迹 x=453（紧贴勾选）；
     *  设置选项行勾选 [556,y+24][588,y+56]、文字边界 x=588 但墨迹从 x=617 起（TextView 内 29px 内边距）。 */
    private View createTabRow(final String label, boolean selected, boolean enabled,
                              boolean speedRow, View.OnClickListener click) {
        return createTabRow(label, null, selected, enabled, speedRow, false, click);
    }

    private View createTabRow(final String label, final String detail, boolean selected,
                              boolean enabled, boolean speedRow, View.OnClickListener click) {
        return createTabRow(label, detail, selected, enabled, speedRow, false, click);
    }

    private View createTabRow(final String label, final String detail, boolean selected,
                              boolean enabled, boolean speedRow, boolean optionGap,
                              View.OnClickListener click) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 80);
        lp.bottomMargin = 8;
        row.setLayoutParams(lp);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(36, 0, 36, 0);
        row.setBackgroundColor(0x17FFFFFF);
        row.setId(TAB_ITEM_ID_BASE + tabListItems.size());
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setOnClickListener(click);

        // 官方勾选是 32×32 ImageView 内的完整图形，不用字体字形（26sp "✓" 会被 32px 视图裁掉）
        final CheckView check = new CheckView(this);
        check.setChecked(selected);
        check.setCheckColor(getResources().getColor(R.color.text));
        // 官方勾选墨迹相对 32×32 槽位上移约 5px，与文字视觉中线对齐
        check.setTranslationY(-5f);
        row.addView(check, new android.widget.LinearLayout.LayoutParams(32, 32));

        final TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(22);
        text.setIncludeFontPadding(false);
        text.setPadding(optionGap ? 29 : 0, 0, 0, 0);
        text.setTextColor(getResources().getColor(
                enabled ? R.color.text : R.color.text_muted));
        // 官方文字框高 49、在 80 行内垂直居中，字形默认顶部对齐（墨迹落在 y+19..y+49）
        android.widget.LinearLayout.LayoutParams tlp =
                new android.widget.LinearLayout.LayoutParams(0, 49, 1f);
        row.addView(text, tlp);

        final TextView detailView;
        if (detail != null) {
            detailView = new TextView(this);
            detailView.setText(detail);
            detailView.setTextSize(22);
            detailView.setSingleLine(true);
            detailView.setTextColor(getResources().getColor(R.color.text_muted));
            detailView.setPadding(0, 0, 0, 0);
            row.addView(detailView, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            detailView = null;
        }

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                // 官方聚焦行是整条白底 + 深色文字与勾选，左侧收紧 22px
                v.setBackgroundColor(hasFocus
                        ? getResources().getColor(R.color.osd_row_focus) : 0x17FFFFFF);
                v.setPadding(hasFocus ? 14 : 36, 0, 36, 0);
                int size = hasFocus ? 34 : 32;
                android.widget.LinearLayout.LayoutParams clp =
                        (android.widget.LinearLayout.LayoutParams) check.getLayoutParams();
                if (clp.width != size) {
                    clp.width = size;
                    clp.height = size;
                    check.setLayoutParams(clp);
                }
                check.setCheckColor(getResources().getColor(
                        hasFocus ? R.color.osd_on_light : R.color.text));
                text.setTextColor(getResources().getColor(
                        hasFocus ? R.color.osd_on_light : R.color.text));
                if (detailView != null) {
                    detailView.setTextColor(getResources().getColor(
                            hasFocus ? R.color.osd_on_light : R.color.text_muted));
                }
                // 高级页：副项获得焦点即切换右列（官方 204 行为）
                if (hasFocus && setMidItems.contains(v)) {
                    int at = setMidItems.indexOf(v);
                    if (advancedItem != at) {
                        advancedItem = at;
                        rebuildAdvancedOptions();
                    }
                }
                // 右列内容超过一屏（连播页的片尾时长）时随焦点滚动
                if (hasFocus) {
                    if (osdSetLeftClip != null && osdSetLeftClip.isShown()
                            && !setLeftItems.contains(v)) {
                        lastSettingFocus = v;
                        int col = settingsColumn(v);
                        if (col > 0) {
                            lastSettingCol = col;
                            lastSettingIndex = settingsColumnItems(col).indexOf(v);
                        }
                    }
                    final View target = v;
                    osdSetRight.post(new Runnable() {
                        public void run() {
                            scrollSettingRowIntoView(target);
                        }
                    });
                }
            }
        });
        tabListItems.add(row);
        return row;
    }

    /** 官方勾选图形：32×32 内的完整折线对勾（204 墨迹约 23×16），随焦点/选中换色。 */
    private static final class CheckView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private boolean checked;

        CheckView(android.content.Context context) {
            super(context);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            paint.setColor(0xFFFFFFFF);
        }

        void setChecked(boolean value) {
            checked = value;
            invalidate();
        }

        void setCheckColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        protected void onDraw(android.graphics.Canvas canvas) {
            if (!checked) {
                return;
            }
            float w = getWidth();
            float h = getHeight();
            paint.setStrokeWidth(Math.max(2f, w * 0.095f));
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(w * 0.34f, h * 0.50f);
            path.lineTo(w * 0.54f, h * 0.68f);
            path.lineTo(w * 0.97f, h * 0.26f);
            canvas.drawPath(path, paint);
        }
    }

    private void prepareControl(View v, View.OnClickListener click) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, android.view.MotionEvent ev) {
                if (ev.getAction() == android.view.MotionEvent.ACTION_UP) {
                    view.requestFocus();
                    resetOsdAutoHide();
                    view.performClick();
                }
                return true;
            }
        });
    }

    private void bindControl(int id, View.OnClickListener click) {
        View v = findViewById(id);
        prepareControl(v, click);
        controlButtons.add(v);
    }

    private void togglePlay() {
        L.i("Osd", "togglePlay requested prepared=" + (player != null && prepared));
        if (player == null || !prepared) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            userPaused = true;
            bufferUiStalledSinceMs = 0L;
            hideLoading();
            if (centerPlay != null) centerPlay.setVisibility(View.VISIBLE);
        } else {
            player.play();
            userPaused = false;
            if (centerPlay != null) centerPlay.setVisibility(View.GONE);
            lastProgressMs = -1L;
            stalledTicks = 0;
            scheduleProgressCheck();
        }
        // 暂停/继续后按当前位置立即重绘，避免画面恢复而字幕仍停在上一帧
        requestSubtitleFrame();
    }

    /** 左右键：单击 ±10 秒，长按重复事件累计显示总时长。 */
    private void seekBy(int seconds, boolean reveal, int displaySeconds) {
        L.i("Osd", "seek-key=" + seconds + " osdVisible=" + osdVisible
                + " prepared=" + (player != null && prepared) + " live=" + liveMode);
        if (player == null || !prepared || liveMode) {
            return; // 直播流没有时长，左右键不做无效 seek
        }
        long currentPos = toAbsolutePositionMs(player.getPositionMs());
        long basePos = seekPending && pendingSeekPositionMs >= 0L
                ? pendingSeekPositionMs : currentPos;
        long pos = basePos + seconds * 1000L;
        long dur = resolveDurationMs();
        long target = Math.max(0L, Math.min(pos, dur > 0 ? dur : pos));
        if (!fnosLongSegments && isWindowedFnosPlaylist()) {
            int targetWindow = (int) (target / FNOS_PLAYLIST_WINDOW_MS);
            if (targetWindow != playlistWindow) {
                switchPlaylistWindow(targetWindow, target);
                showSeekIndicator(displaySeconds);
                if (reveal) {
                    showOsd();
                }
                return;
            }
        }
        seekPending = true;
        pendingSeekPositionMs = target;
        seekStartedAtMs = SystemClock.elapsedRealtime();
        seekWatchdogGraceUntilMs = seekStartedAtMs + SEEK_COMPLETE_GRACE_MS;
        lastProgressMs = -1L;
        stalledTicks = 0;
        stalledSinceMs = 0L;
        if (sessionKeeper != null) {
            sessionKeeper.updatePosition(target);
        }
        if (authProxy != null) {
            authProxy.setStartPositionMs(toPlayerPositionMs(target));
        }
        subtitleSeekGraceUntilMs = SystemClock.elapsedRealtime()
                + (hlsVtt != null ? 4000L : 800L);
        // 记录跳转前的基准：新位置首句字幕的延迟要跟这里对齐排查
        // （旧 skew 是否被保留、跳转前预取到哪一段）。
        L.i("SubSync", "seek→" + seconds + "s 起点=" + currentPos
                + "ms 目标=" + target + "ms 旧skew=" + hlsClock.skewMs() + "ms " + hlsClock.describe()
                + (hlsVtt != null ? " " + hlsVtt.stats() : ""));
        ui.removeCallbacks(applyPendingSeek);
        seekApplyScheduled = true;
        ui.postDelayed(applyPendingSeek, SEEK_APPLY_DEBOUNCE_MS);
        showSeekIndicator(displaySeconds);
        if (reveal) {
            showOsd();
        }
    }

    // ------------------------------------------------------------------
    // OSD 状态机：上/下键或 seek 唤出，焦点落在进度条；5 秒无操作或返回键收起。
    // ------------------------------------------------------------------

    /** 展开 OSD，焦点落到进度条（官方模型），并把倍速标签刷新为当前值。 */
    private void showOsd() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(new Runnable() {
                public void run() { showOsd(); }
            });
            return;
        }
        osdVisible = true;
        osdPanel.setVisibility(View.VISIBLE);
        updateSpeedTabLabel(player == null ? 1f : player.getSpeed());
        // 官方播放器无屏内顶栏（返回走遥控 BACK）
        View focused = validFocusedControl();
        if (focused == null) {
            focusDefaultControl();
        } else {
            lastOsdFocus = focused;
        }
        L.i("Osd", "show focus=" + controlText(validFocusedControl())
                + " live=" + liveMode + " controls=" + visibleControlNames());
        resetOsdAutoHide();
    }

    private void resetOsdAutoHide() {
        ui.removeCallbacks(osdAutoHide);
        if (osdVisible && !menuOpen && !subAdjustShown && !playInfoShown) {
            ui.postDelayed(osdAutoHide, OsdKeys.TIMEOUT_MS);
        }
    }

    /** 收起 OSD：隐藏整个面板，清空残留焦点，但绝不改动播放状态。 */
    private void collapseOsd() {
        if (subAdjustShown) {
            closeSubAdjust();
            return;
        }
        if (!osdVisible || menuOpen) {
            return;
        }
        ui.removeCallbacks(osdAutoHide);
        View focused = validFocusedControl();
        if (focused != null) {
            lastOsdFocus = focused;
            focused.clearFocus();
        }
        osdVisible = false;
        collapseTabs();
        osdPanel.setVisibility(View.GONE);
        // 焦点交给始终可见的画面，避免留在已隐藏的控件上
        surface.requestFocus();
        boolean playing = false;
        try {
            playing = player != null && prepared && player.isPlaying();
        } catch (Throwable ignored) {
        }
        L.i("Osd", "hide timeout userPaused=" + userPaused + " playing=" + playing);
    }

    /** 返回控件的简短名称，用于 OSD 焦点诊断日志。 */
    private String controlText(View v) {
        if (v == progressBox) {
            return "进度条";
        }
        if (v instanceof TextView) {
            return ((TextView) v).getText().toString().trim();
        }
        return v == null ? "surface" : "view";
    }

    /** 当前「仍存在且可见」的 OSD 控件清单，用于验证焦点链没有残留项。 */
    private String visibleControlNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < osdFocusOrder.size(); i++) {
            View v = osdFocusOrder.get(i);
            if (isFocusableControl(v)) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(controlText(v));
            }
        }
        return sb.toString();
    }

    /** 控件是否仍在焦点表内、且当前可见可聚焦。 */
    private boolean isFocusableControl(View v) {
        // isShown() 会连同祖先可见性一起判断：顶栏隐藏时里面的「返回/信息」也必须跳过
        return osdVisible && v != null && osdFocusOrder.contains(v)
                && v.isShown() && v.isFocusable() && v.isEnabled();
    }

    private View validFocusedControl() {
        View f = getCurrentFocus();
        return isFocusableControl(f) ? f : null;
    }

    /** 官方 OSD 每次唤出都把焦点放在进度条上。 */
    private void focusDefaultControl() {
        if (progressBox != null && progressBox.getVisibility() == View.VISIBLE) {
            progressBox.requestFocus();
            lastOsdFocus = progressBox;
        }
    }

    private long clampPosition(long ms) {
        long dur = 0L;
        try {
            dur = resolveDurationMs();
        } catch (Throwable ignored) {
        }
        if (ms < 0L) {
            ms = 0L;
        }
        if (dur > 0L && ms > dur - 500L) {
            ms = Math.max(0L, dur - 500L);
        }
        return ms;
    }

    private void exitPlayer() {
        reportOnExit(false);
        releasePlayer();
        finish();
    }

    /**
     * 遥控器模型完全对齐官方（204 实测）：
     * <ul>
     *   <li>OSD 收起：确认 = 播放/暂停（不唤出 OSD）；左右 = 直接 ±10 秒（不唤出 OSD）；上下 = 唤出 OSD；</li>
     *   <li>OSD 展开：确认 = 播放/暂停（进度条焦点）或触发当前控件；左右 = 进度条定位 / 标签行内移动；</li>
     *   <li>下：进度条 → 上次停留的标签（自动展开其列表）→ 列表首行 → 下一行；</li>
     *   <li>上：列表 → 所在标签 → 进度条（同时收起面板）；进度条上按上键官方无响应；</li>
     *   <li>返回：收起 OSD（面板一起收）；OSD 已收起时退出播放器；</li>
     *   <li>OSD 显示后 5 秒无操作自动收起；任何按键或触摸操作都会重新计时。</li>
     * </ul>
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean repeat = event.getRepeatCount() > 0;

        if (isErrorPanelShowing()) {
            if (down && !repeat) {
                if (TvKeys.isBack(code)) {
                    exitPlayer();
                } else if (OsdKeys.isOk(code)) {
                    View focused = getCurrentFocus();
                    if (focused == btnErrorRetry) {
                        retryError();
                    } else if (focused == btnErrorExit) {
                        exitPlayer();
                    } else {
                        focusErrorPanel();
                    }
                } else if (code == OsdKeys.KEY_DPAD_LEFT
                        || code == OsdKeys.KEY_DPAD_RIGHT
                        || code == OsdKeys.KEY_DPAD_UP
                        || code == OsdKeys.KEY_DPAD_DOWN) {
                    View focused = getCurrentFocus();
                    View target = focused == btnErrorRetry ? btnErrorExit : btnErrorRetry;
                    if (target != null) target.requestFocus();
                }
            }
            return true;
        }

        // INFO 打开播放信息；MENU 只唤醒 OSD，不抢占播放信息入口。
        if (code == 165 /* KEYCODE_INFO */) {
            if (down && !repeat) {
                if (playInfoShown) {
                    playerPrefs.edit().putBoolean("info_overlay", false).commit();
                    hidePlayInfo();
                } else {
                    showPlayInfo();
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (down && !repeat) {
                if (playInfoShown) hidePlayInfo();
                showOsd();
            }
            return true;
        }

        // 播放信息浮层打开时只保留「关闭」：OK/BACK 关闭，方向键不穿透到 OSD。
        if (playInfoShown) {
            if (TvKeys.isBack(code) || OsdKeys.isOk(code)) {
                if (down && !repeat) {
                    playerPrefs.edit().putBoolean("info_overlay", false).commit();
                    hidePlayInfo();
                }
                return true;
            }
            if (OsdKeys.isPlayPause(code)) {
                if (down && !repeat) {
                    togglePlay();
                }
                return true;
            }
            if (code == OsdKeys.KEY_DPAD_UP || code == OsdKeys.KEY_DPAD_DOWN
                    || code == OsdKeys.KEY_DPAD_LEFT || code == OsdKeys.KEY_DPAD_RIGHT) {
                return true;
            }
        }

        // 字幕调整弹窗：偏移/位置用方向键 ±，BACK/OK 关闭；大小列表仍由焦点行导航。
        if (subAdjustShown) {
            if (TvKeys.isBack(code)) {
                if (down && !repeat) {
                    closeSubAdjust();
                }
                return true;
            }
            if (subAdjustKind == ADJ_SIZE) {
                return super.dispatchKeyEvent(event);
            }
            if (code == OsdKeys.KEY_DPAD_LEFT || code == OsdKeys.KEY_DPAD_RIGHT
                    || code == OsdKeys.KEY_DPAD_UP || code == OsdKeys.KEY_DPAD_DOWN) {
                if (down && !repeat) {
                    stepSubAdjust((code == OsdKeys.KEY_DPAD_LEFT
                            || code == OsdKeys.KEY_DPAD_UP) ? -1 : 1);
                }
                return true;
            }
            if (OsdKeys.isOk(code)) {
                if (down && !repeat) {
                    closeSubAdjust();
                }
                return true;
            }
            return true;
        }

        // 焦点异常掉到根视图时兜底回标签栏：否则方向键会被当成 seek、OK 会被当成播放/暂停。
        if (osdVisible && down && !menuOpen) {
            resetOsdAutoHide();
            View cf = getCurrentFocus();
            boolean lost = cf == null || cf.getParent() == null || !cf.isShown()
                    || cf == getWindow().getDecorView() || cf.getId() == android.R.id.content;
            if (lost) {
                View target = findViewById(lastTabId);
                if (target == null || !target.isShown()) {
                    target = findViewById(R.id.btn_subtitle);
                }
                if (target != null) {
                    target.requestFocus();
                }
            }
        }

        if (TvKeys.isBack(code)) {
            if (down && !repeat && !menuOpen) {
                if (osdVisible) {
                    collapseOsd();
                } else {
                    exitPlayer();
                }
            }
            return true;
        }
        if (TvKeys.isMenu(code)) {
            if (down && !repeat) {
                showOsd();
                showOptions();
            }
            return true;
        }

        if (OsdKeys.isSeekLeft(code) || OsdKeys.isSeekRight(code)) {
            if (!down) {
                if (seekHeld) {
                    ui.removeCallbacks(seekHoldStop);
                    ui.postDelayed(seekHoldStop, 200L);
                }
                return true;
            }
            View f = getCurrentFocus();
            if (osdVisible && isTabView(f)) {
                moveTabFocus(OsdKeys.isSeekRight(code) ? 1 : -1);
                return true;
            }
            if (osdVisible && isEpisodeArrow(f)) {
                if (OsdKeys.isSeekRight(code) && f == osdEpPrev && !tabListItems.isEmpty()) {
                    tabListItems.get(0).requestFocus();
                } else if (OsdKeys.isSeekLeft(code) && f == osdEpNext
                        && !tabListItems.isEmpty()) {
                    tabListItems.get(tabListItems.size() - 1).requestFocus();
                }
                return true;
            }
            if (osdVisible && tabListItems.contains(f)) {
                // 选集按 15 列排列，左右键只在本行格间移动；其余列表行官方保持焦点不动
                if (expandedTabId == R.id.btn_episodes) {
                    moveGridFocus(OsdKeys.isSeekRight(code) ? 1 : -1);
                } else if (!setLeftItems.isEmpty()) {
                    moveSettingsPaneFocus(OsdKeys.isSeekRight(code));
                } else if (expandedTabId == R.id.btn_subtitle && OsdKeys.isSeekLeft(code)) {
                    // 字幕页：左键回到左列（选择字幕 / 调整字幕），与官方一致
                    TextView tile = subMenu == 1 ? osdSubAdjustTab : osdSubSelectTab;
                    if (tile != null) {
                        tile.requestFocus();
                    }
                }
                return true;
            }
            if (osdVisible && (f == osdSubSelectTab || f == osdSubAdjustTab)) {
                // 左列：→ 进入右侧列表首行；← 回到标签行「字幕」
                if (OsdKeys.isSeekRight(code)) {
                    if (!tabListItems.isEmpty()) {
                        focusRow(0);
                    } else {
                        focusDownInOsd();
                    }
                } else {
                    View subTab = findViewById(R.id.btn_subtitle);
                    if (subTab != null) {
                        subTab.requestFocus();
                    }
                }
                return true;
            }
            int direction = OsdKeys.isSeekRight(code) ? 1 : -1;
            long now = SystemClock.elapsedRealtime();
            if (!seekHeld || seekDirection != direction || now - lastSeekDownAt > 800L) {
                seekHoldSeconds = 10;
            } else {
                seekHoldSeconds += 10;
            }
            seekHeld = true;
            seekDirection = direction;
            lastSeekDownAt = now;
            ui.removeCallbacks(seekHoldStop);
            seekBy(direction * 10, true, direction * seekHoldSeconds);
            return true;
        }

        if (code == OsdKeys.KEY_DPAD_DOWN || code == OsdKeys.KEY_DPAD_UP) {
            if (!down || repeat) {
                return true;
            }
            if (!osdVisible) {
                showOsd();
                return true;
            }
            if (code == OsdKeys.KEY_DPAD_DOWN) {
                focusDownInOsd();
            } else {
                focusUpInOsd();
            }
            return true;
        }

        if (OsdKeys.isOk(code)) {
            if (down && !repeat) {
                View f = getCurrentFocus();
                if (!osdVisible || f == null || f == progressBox) {
                    togglePlay();
                } else if (f == osdSubSelectTab || f == osdSubAdjustTab) {
                    f.performClick();
                } else if (isTabView(f)) {
                    lastTabId = f.getId();
                    expandTab(f.getId());
                    if (!tabListItems.isEmpty()) {
                        focusRow(0);
                    } else {
                        f.performClick();
                    }
                } else if (isEpisodeArrow(f)) {
                    f.performClick();
                } else if (tabListItems.contains(f)) {
                    f.performClick();
                }
            }
            return true;
        }
        if (OsdKeys.isPlayPause(code)) {
            if (down && !repeat) {
                togglePlay();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void focusRow(int index) {
        if (index >= 0 && index < tabListItems.size()) {
            if (tabListItems.get(index).requestFocus()) {
                return;
            }
            // 首行可能是不可聚焦的空态提示，顺序找下一个可聚焦行
            for (int i = index + 1; i < tabListItems.size(); i++) {
                if (tabListItems.get(i).requestFocus()) {
                    return;
                }
            }
            for (int i = index - 1; i >= 0; i--) {
                if (tabListItems.get(i).requestFocus()) {
                    return;
                }
            }
        }
        // 列表里没有可聚焦项时留在标签上，避免焦点掉到根视图
        View tab = findViewById(expandedTabId);
        if (tab != null) {
            tab.requestFocus();
        }
    }

    /** ↓：进度条 → 上次停留的标签（焦点监听会自动展开列表）；标签 → 列表首行；列表 → 下一行。 */
    private void focusDownInOsd() {
        View f = getCurrentFocus();
        // 字幕页左列：选择字幕 ↓ 调整字幕 ↓ 右侧列表首行
        if (f == osdSubSelectTab) {
            if (osdSubAdjustTab != null) {
                osdSubAdjustTab.requestFocus();
            }
            return;
        }
        if (f == osdSubAdjustTab) {
            if (!tabListItems.isEmpty()) {
                focusRow(0);
            }
            return;
        }
        if (isEpisodeArrow(f)) {
            if (!tabListItems.isEmpty()) {
                focusRow(f == osdEpPrev ? 0 : tabListItems.size() - 1);
            }
            return;
        }
        if (isTabView(f)) {
            // 官方：设置标签 ↓ 回到上次停留的设置项；首次进入落到右列首项
            if (f.getId() == R.id.btn_settings && osdSetLeftClip != null
                    && osdSetLeftClip.isShown()) {
                View remembered = rememberedSettingView();
                if (remembered != null) {
                    remembered.requestFocus();
                    return;
                }
                if (focusFirstInColumn(2)) {
                    return;
                }
            }
            if (!tabListItems.isEmpty()) {
                focusRow(0);
            }
            return;
        }
        if (f != null && tabListItems.contains(f)) {
            if (expandedTabId == R.id.btn_episodes) {
                moveGridFocusVertical(1);
                return;
            }
            if (!setLeftItems.isEmpty()) {
                // 设置页各列独立上下移动，不跨列（tabListItems 按列交错，不能用线性下标）
                java.util.List<View> same = settingsColumnItems(settingsColumn(f));
                int at = same.indexOf(f);
                if (at >= 0 && at + 1 < same.size()) {
                    focusRow(tabListItems.indexOf(same.get(at + 1)));
                }
                return;
            }
            int at = tabListItems.indexOf(f);
            int next = at + 1;
            if (next < tabListItems.size()) {
                focusRow(next);
            }
            return;
        }
        View tab = findViewById(lastTabId != 0 ? lastTabId : R.id.btn_episodes);
        if (tab == null || !tab.isShown()) {
            tab = findViewById(R.id.btn_quality);
        }
        if (tab == null || !tab.isShown()) {
            return;
        }
        lastTabId = tab.getId();
        tab.requestFocus();
    }

    /** ↑：列表 → 所在标签；标签 → 进度条并收起面板；进度条上官方无响应。 */
    private void focusUpInOsd() {
        View f = getCurrentFocus();
        // 字幕页左列：调整字幕 ↑ 选择字幕 ↑ 标签行「字幕」
        if (f == osdSubAdjustTab) {
            if (osdSubSelectTab != null) {
                osdSubSelectTab.requestFocus();
            }
            return;
        }
        if (f == osdSubSelectTab) {
            View tab = findViewById(R.id.btn_subtitle);
            if (tab != null) {
                tab.requestFocus();
            }
            return;
        }
        if (isEpisodeArrow(f)) {
            findViewById(R.id.btn_episodes).requestFocus();
            return;
        }
        if (f != null && tabListItems.contains(f)) {
            if (isLeftRow(f)) {
                int at = setLeftItems.indexOf(f);
                if (at <= 0) {
                    // 官方 181 实测：左列首行 ↑ 回到标签行「设置」，标签保持展开并显示胶囊
                    osdSetLeft.setTranslationY(0);
                    osdSetRight.setTranslationY(0);
                    View tab = findViewById(R.id.btn_settings);
                    if (tab != null) {
                        tab.requestFocus();
                    }
                    return;
                }
                focusRow(tabListItems.indexOf(setLeftItems.get(at - 1)));
                return;
            }
            if (!setLeftItems.isEmpty()) {
                int col = settingsColumn(f);
                // 同列优先上行（官方 GridView 行为），到顶后再退回上一列
                java.util.List<View> same = settingsColumnItems(col);
                int at0 = same.indexOf(f);
                if (at0 > 0) {
                    focusRow(tabListItems.indexOf(same.get(at0 - 1)));
                    return;
                }
                // 列首行 ↑：官方原生 focusSearch 落到标签行「设置」
                osdSetLeft.setTranslationY(0);
                osdSetRight.setTranslationY(0);
                View tab = findViewById(expandedTabId != 0 ? expandedTabId : R.id.btn_settings);
                if (tab != null) {
                    tab.requestFocus();
                }
                return;
            }
            int at = tabListItems.indexOf(f);
            if (expandedTabId == R.id.btn_episodes) {
                if (moveGridFocusVertical(-1)) {
                    return;
                }
                View tab = findViewById(expandedTabId != 0 ? expandedTabId : lastTabId);
                if (tab != null) {
                    tab.requestFocus();
                }
                return;
            }
            if (at <= 0) {
                View tab = findViewById(expandedTabId != 0 ? expandedTabId : lastTabId);
                if (tab != null) {
                    tab.requestFocus();
                }
                return;
            }
            if (!isLeftRow(tabListItems.get(at - 1))) {
                focusRow(at - 1);
                return;
            }
            View left = setGroup < setLeftItems.size() ? setLeftItems.get(setGroup) : null;
            if (left != null) {
                int li = tabListItems.indexOf(left);
                if (li >= 0) {
                    focusRow(li);
                }
            }
            return;
        }
        if (isTabView(f)) {
            collapseTabs();
            if (progressBox != null && progressBox.isShown()) {
                progressBox.requestFocus();
            }
        }
    }

    /** ←/→ 在标签行内移动；焦点监听会把内容区切到新标签。 */
    private void moveTabFocus(int dir) {
        View cur = getCurrentFocus();
        List<Integer> visible = new ArrayList<Integer>();
        int at = -1;
        for (int i = 0; i < TAB_IDS.length; i++) {
            View v = findViewById(TAB_IDS[i]);
            if (v != null && v.isShown()) {
                if (v == cur) {
                    at = visible.size();
                }
                visible.add(TAB_IDS[i]);
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        // 官方标签行到两端即停，不循环
        int next = visible.get(at < 0 ? 0 : Math.max(0, Math.min(visible.size() - 1, at + dir)));
        View tab = findViewById(next);
        lastTabId = next;
        tab.requestFocus();
    }

    /** 菜单统一入口：打开期间返回键交给弹窗处理。 */
    private void showMenu(AlertDialog.Builder builder) {
        final AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) {
                menuOpen = false;
                showOsd();
            }
        });
        menuOpen = true;
        dialog.show();
    }

    // ------------------------------------------------------------------
    // NAS 挡位：画质来自 tag/list + stream/list，线路来自 play/info.live_channels。
    // 客户端不写死任何画质 / 线路档位；画质挡位变化会触发线路重同步。
    // ------------------------------------------------------------------

    /** 画质切换 / 菜单刷新时异步重新读取 NAS 挡位。 */
    private void loadNasLevels() {
        new Thread(new Runnable() {
            public void run() {
                final MediaUseCases source = App.from(PlayerActivity.this).useCases(cfg);
                final List<QualityLevel> qualities = new ArrayList<QualityLevel>();
                final List<RouteOption> routes = new ArrayList<RouteOption>();
                try {
                    qualities.addAll(source.qualityLevels(cfg, item));
                } catch (Throwable t) {
                    L.w("Player", "NAS 画质挡位读取失败：" + t);
                }
                if (liveMode) {
                    try {
                        routes.addAll(source.liveRoutes(cfg, item));
                    } catch (Throwable t) {
                        L.w("Player", "NAS 线路挡位读取失败：" + t);
                    }
                }
                ui.post(new Runnable() {
                    public void run() { applyNasLevels(qualities, routes); }
                });
            }
        }, "fntv-levels").start();
    }

    /**
     * 按本机实际解码能力过滤 NAS 画质挡位。
     * cap.maxHeight 有解码器报告时就是报告值；没有报告时是 DeviceProbe 按系统版本
     * 给出的保守上限（API<21 为 1080）。两者都已落在 cap 上，这里不再另设档位表。
     */
    private List<QualityLevel> supportedQualities(List<QualityLevel> levels) {
        int maxH = cap == null ? 0 : cap.maxHeight;
        List<QualityLevel> out = NasLevels.supported(levels, maxH);
        if (levels != null && out.size() != levels.size()) {
            StringBuilder hidden = new StringBuilder();
            for (int i = 0; i < levels.size(); i++) {
                if (!out.contains(levels.get(i))) {
                    if (hidden.length() > 0) {
                        hidden.append(',');
                    }
                    hidden.append(levels.get(i).value);
                }
            }
            L.i("Player", "画质挡位按设备上限 " + maxH + " 过滤，隐藏 " + hidden);
        }
        return out;
    }

    /** 统一落盘：画质挡位变化会触发线路挡位按 NAS 当前返回重同步。 */
    private void applyNasLevels(List<QualityLevel> qualities, List<RouteOption> routes) {
        // 先按本机解码上限过滤：151 只有 1080P 解码能力，NAS 的 4K 档必须从菜单消失，
        // 否则用户选中它只会被请求侧夹回 1080（选中值≠实际请求），留下无效选项。
        List<QualityLevel> playable = supportedQualities(qualities);
        String sig = NasLevels.signature(playable);
        boolean changed = NasLevels.needsRouteSync(qualitySignature, sig);
        qualitySignature = sig;
        nasQualities.clear();
        nasQualities.addAll(playable);
        L.i("Player", "画质挡位 raw=" + (qualities == null ? -1 : qualities.size())
                + " usable=" + nasQualities.size() + " signature=" + sig);
        if (activeQualityValue.length() > 0) {
            boolean stillThere = false;
            for (int i = 0; i < nasQualities.size(); i++) {
                if (activeQualityValue.equals(nasQualities.get(i).value)) {
                    stillThere = true;
                    break;
                }
            }
            if (!stillThere) {
                // NAS 已下架该挡位：回到自动，不保留失效的选择
                activeQualityValue = "";
                cfg.transcodeResolution = "";
                cfg.forceTranscode = false;
                qualityLabel = "自动";
            }
        }
        if (liveMode && (changed || routesSyncedSignature == null)) {
            syncRoutesFromNas(routes);
            routesSyncedSignature = sig;
            L.i("Player", "画质挡位 " + sig + "，线路挡位已同步 " + nasRoutes.size() + " 条");
        }
        updateQualityButton();
        if (expandedTabId == R.id.btn_quality) {
            rebuildTabList();
        }
    }

    private void syncRoutesFromNas(List<RouteOption> fresh) {
        nasRoutes.clear();
        nasRoutes.addAll(NasLevels.syncRoutes(fresh, nasQualities, activeQualityValue));
        int at = NasLevels.activeRouteIndex(nasRoutes, item.mediaGuid);
        if (at >= 0) {
            currentRouteGuid = nasRoutes.get(at).guid;
            routeLabel = nasRoutes.get(at).name;
        }
    }

    private void updateQualityButton() {
        TextView q = (TextView) findViewById(R.id.btn_quality);
        if (q != null && q.getVisibility() == View.VISIBLE) {
            q.setText(qualityLabel);
        }
    }

    /**
     * 画质菜单选择入口：所有档位都交 NAS 转码，客户端不再做本地显示缩放。
     * 选中档位后重建会话；「自动」不下发 resolution，由能力探测选设备最高可解档位。
     */
    private void selectQuality(String value) {
        cfg.transcodeResolution = value;
        cfg.forceTranscode = value.length() > 0;
        bitrateController = null;
        showNotice("清晰度：" + qualityLabel);
        loadNasLevels();
        rebuildPlayback("清晰度：" + qualityLabel);
        rebuildTabList();
    }

    /**
     * 解码器把 1080p 按 16 行对齐成 1088 输出，最后 8 行是填充（实测未写入的 YUV=全 0，
     * 转成 RGB 后是暗绿色）。视频层若只有屏幕高度 1080，整块 1088 缓冲会被压进 1080，
     * 填充行被缩到屏幕最底部形成一条绿线（全屏播放、各档位都会出现）。
     * 把视频层向下多留 8px（超出屏幕底边，窗口为全屏主题，多出部分被显示端裁掉），
     * 1088 缓冲与视频层保持 1:1，填充行落到屏幕外。
     */
    private void extendSurfaceForDecoderPadding() {
        if (surface == null) {
            return;
        }
        ViewGroup.LayoutParams lp = surface.getLayoutParams();
        if (lp == null) {
            return;
        }
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        if (dm.heightPixels <= 0) {
            return;
        }
        lp.height = dm.heightPixels + DECODER_PAD_ROWS;
        surface.setLayoutParams(lp);
    }

    /** 清晰度菜单：挡位项完全来自 NAS，只有「自动」是一个开关而非固定挡位。 */
    private void showQualityMenu() {
        if (nasQualities.isEmpty()) {
            Ui.toast(this, "当前设备无可用的清晰度挡位");
            loadNasLevels();
            return;
        }
        final List<String> values = new ArrayList<String>();
        final List<String> labels = new ArrayList<String>();
        values.add("");
        labels.add("自动（原画优先）");
        for (int i = 0; i < nasQualities.size(); i++) {
            QualityLevel q = nasQualities.get(i);
            values.add(q.value);
            labels.add(q.label);
        }
        L.i("Osd", "quality-menu usable=" + nasQualities.size()
                + " items=" + labels.size() + " values=" + joinCsv(values));
        showMenu(new AlertDialog.Builder(this)
                .setTitle("清晰度 · 当前 " + qualityLabel + " · 本机可选 " + nasQualities.size() + " 档")
                .setItems(labels.toArray(new String[labels.size()]),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                String v = values.get(which);
                                activeQualityValue = v;
                                qualityLabel = v.length() == 0 ? "自动" : labels.get(which);
                                // 画质变化 → 线路挡位随 NAS 重新同步
                                routesSyncedSignature = null;
                                selectQuality(v);
                            }
                        })
                .setNegativeButton("取消", null));
    }

    /** 线路菜单：每次打开都重新从 NAS 拉取，保证挡位与服务端同步。 */
    private void showRouteMenu() {
        new Thread(new Runnable() {
            public void run() {
                final List<RouteOption> fresh = new ArrayList<RouteOption>();
                try {
                    fresh.addAll(api.liveRoutes(cfg, item));
                } catch (Throwable t) {
                    L.w("Player", "线路挡位刷新失败：" + t);
                }
                ui.post(new Runnable() {
                    public void run() {
                        if (!fresh.isEmpty()) {
                            syncRoutesFromNas(fresh);
                            routesSyncedSignature = qualitySignature;
                        }
                        openRouteDialog();
                    }
                });
            }
        }, "fntv-routes").start();
    }

    private void openRouteDialog() {
        if (nasRoutes.isEmpty()) {
            L.i("Osd", "route-menu nas=0 unavailable");
            Ui.toast(this, "暂无可切换线路");
            return;
        }
        L.i("Osd", "route-menu nas=" + nasRoutes.size() + " active=" + routeLabel);
        String[] labels = new String[nasRoutes.size()];
        for (int i = 0; i < nasRoutes.size(); i++) {
            RouteOption r = nasRoutes.get(i);
            labels[i] = r.displayName()
                    + (r.guid.equals(currentRouteGuid) ? " ✓" : "");
        }
        showMenu(new AlertDialog.Builder(this)
                .setTitle("线路选择 · 当前 " + (routeLabel.length() > 0 ? routeLabel : "自动")
                        + " · NAS 共 " + nasRoutes.size() + " 条")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        switchLiveRoute(nasRoutes.get(which));
                    }
                })
                .setNegativeButton("取消", null));
    }

    private void switchLiveRoute(final RouteOption r) {
        currentRouteGuid = r.guid;
        routeLabel = r.name;
        item.mediaGuid = r.guid;
        osdNotice.setText("切换线路至 " + labelOfRoute(r));
        showOsd();
        releasePlayer();
        prepared = false;
        startRequested = false;
        externalLaunched = false;
        loading.setText("正在切换线路…");
        showLoading();
        // 线路地址本身来自 NAS，构造候选交给系统播放器（老 TV 内部 MediaPlayer 解不了外部 HLS）
        final StreamCandidate c = StreamCandidate.original(r.path, item);
        c.id = "live:" + r.guid;
        c.label = "直播直链 · " + r.name;
        ui.post(new Runnable() {
            public void run() { launchExternalWithFreshSession(c); }
        });
    }

    private String labelOfRoute(RouteOption r) {
        return r.quality.length() > 0 ? r.name + " · " + NasLevels.labelOf(r.quality) : r.name;
    }

    private String joinCsv(List<String> vals) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vals.get(i));
        }
        return sb.toString();
    }

    /** 每 500ms 刷新进度条与时间，每 10 秒向 NAS 上报一次播放进度。 */
    private final Runnable progressTicker = new Runnable() {
        public void run() {
            if (player != null && prepared) {
                try {
                    long pos = toAbsolutePositionMs(player.getPositionMs());
                    long dur = resolveDurationMs();
                    long sessionPos = seekPending && pendingSeekPositionMs >= 0L
                            ? pendingSeekPositionMs : pos;
                    if (sessionKeeper != null) {
                        sessionKeeper.updatePosition(sessionPos);
                    }
                    if (authProxy != null) {
                        authProxy.setStartPositionMs(toPlayerPositionMs(sessionPos));
                    }
                    // 网页端重建会话时传 startTimestamp = Math.floor(currentTime)，
                    // 让 NAS 从当前位置生成新 HLS，避免按滞后 ts 回退
                    item.playbackPositionSec = Math.max(0, (int) (sessionPos / 1000L));
                    long uiNow = SystemClock.elapsedRealtime();
                    if (lastTickPosMs >= 0L && pos + 300 < lastTickPosMs) {
                        L.w("Player", "播放位置回退 " + lastTickPosMs + "ms -> " + pos
                                + "ms（会话/授权续期或 seek 触发）");
                        bufferUiStalledSinceMs = 0L;
                        showLoading();
                    } else if (lastTickPosMs < 0L || pos > lastTickPosMs + 100L) {
                        bufferUiStalledSinceMs = 0L;
                        if (!engineBuffering && !seekPending) {
                            hideLoading();
                        }
                    } else if (!userPaused && !recovering) {
                        if (bufferUiStalledSinceMs <= 0L) {
                            bufferUiStalledSinceMs = uiNow;
                        }
                        if (uiNow - bufferUiStalledSinceMs >= BUFFER_UI_STALL_MS
                                && isPlayerWaiting()) {
                            showLoading();
                        }
                    }
                    lastTickPosMs = pos;
                    if (pos < 2000) {
                        introSkipped = false;
                        outroTriggered = false;
                    }
                    applyTrailerSkip(pos, dur);
                    if (dur > 0) {
                        int permille = (int) (1000L * pos / dur);
                        playerProgress.setProgress(permille);
                        syncProgressKnob(permille);
                    }
                    currentPositionText = clock(pos) + " / " + clock(dur);
                    playerTime.setText(currentPositionText);
                    if (System.currentTimeMillis() - lastReportAt >= 10000L) {
                        lastReportAt = System.currentTimeMillis();
                        reportProgressAsync((int) (pos / 1000L), false);
                    }
                } catch (Throwable ignored) {
                }
            }
            ui.postDelayed(this, 500L);
        }
    };

    /** 片头/片尾时长生效：播到片头结束自动跳过；到片尾按自动连播设置切集或暂停。 */
    private void applyTrailerSkip(long pos, long dur) {
        if (liveMode || player == null || pos < 0 || dur <= 0) {
            return;
        }
        int introMs = trailerSeconds(false) * 1000;
        if (introMs > 0 && !introSkipped && pos < introMs) {
            introSkipped = true;
            try {
                player.seekTo(toPlayerPositionMs(introMs));
            } catch (Throwable ignored) {
            }
        }
        int outroMs = trailerSeconds(true) * 1000;
        if (outroMs > 0 && !outroTriggered && outroMs < dur && pos >= dur - outroMs) {
            outroTriggered = true;
            if (playerPrefs.getBoolean("autoplay", true)) {
                switchEpisode(1);
            } else {
                try {
                    player.pause();
                } catch (Throwable ignored) {
                }
                userPaused = true;
                showNotice("片尾已跳过");
            }
        }
    }

    private long toAbsolutePositionMs(long playerPositionMs) {
        return liveMode ? playerPositionMs : playerPositionMs + playlistOffsetMs;
    }

    private long toPlayerPositionMs(long absolutePositionMs) {
        return liveMode ? absolutePositionMs : Math.max(0L, absolutePositionMs - playlistOffsetMs);
    }

    private boolean isWindowedFnosPlaylist() {
        return !liveMode && "FNOS".equalsIgnoreCase(cfg.apiMode)
                && currentPlaylistUrl != null && currentPlaylistUrl.indexOf("window=") >= 0;
    }

    private static String withoutLocalPlaylistQueries(String url) {
        if (url == null) {
            return "";
        }
        int query = url.indexOf('?');
        if (query < 0) {
            return url;
        }
        String[] pairs = url.substring(query + 1).split("&");
        StringBuilder out = new StringBuilder(url.length());
        out.append(url, 0, query);
        boolean first = true;
        for (int i = 0; i < pairs.length; i++) {
            if (pairs[i].startsWith("window=") || pairs[i].startsWith("long=")) {
                continue;
            }
            out.append(first ? '?' : '&').append(pairs[i]);
            first = false;
        }
        return out.toString();
    }

    private long resolveDurationMs() {
        if (item != null && item.durationSeconds > 0L) {
            return item.durationSeconds * 1000L;
        }
        if (player != null) {
            long duration = player.getDurationMs();
            if (duration > 0L) {
                return duration + (isWindowedFnosPlaylist() ? playlistOffsetMs : 0L);
            }
        }
        return item != null && item.runtimeMinutes > 0
                ? item.runtimeMinutes * 60000L : 0L;
    }

    private static String clock(long ms) {
        int s = Math.max(0, (int) (ms / 1000L));
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    /** 官方进度条圆点：18px 白圆，圆心跟随已播比例（轨道 x=80..1840）。 */
    private void syncProgressKnob(int permille) {
        if (progressKnob == null) {
            return;
        }
        int p = Math.max(0, Math.min(1000, permille));
        int left = 80 + Math.round(1760f * p / 1000f) - 9;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) progressKnob.getLayoutParams();
        if (lp.leftMargin != left) {
            lp.leftMargin = left;
            progressKnob.setLayoutParams(lp);
        }
    }

    private void reportProgressAsync(final int seconds, final boolean ended) {
        new Thread(new Runnable() {
            public void run() {
                if (api != null && item != null) {
                    api.reportProgress(cfg, item, seconds, ended, currentPlaylistUrl);
                }
            }
        }, "fntv-play-record").start();
    }

    /** 退出 / 播放完成时上报：网页端播放结束回传 ts=0。 */
    private void reportOnExit(boolean ended) {
        if (exitReported) {
            return;
        }
        exitReported = true;
        int pos = 0;
        try {
            if (player != null && prepared) {
                pos = (int) (toAbsolutePositionMs(player.getPositionMs()) / 1000L);
            } else if (lastProgressMs > 0L) {
                pos = (int) (lastProgressMs / 1000L);
            }
        } catch (Throwable ignored) {
        }
        reportProgressAsync(pos, ended);
    }

    // ------------------------------------------------------------------
    // 字幕：状态与「画面字幕层」彻底分离
    //   * 外挂字幕（extra_file=1）：从 NAS /subtitle/dl 取 WebVTT/SRT 原文，按进度渲染
    //   * 内嵌字幕（extra_file=0）：/subtitle/dl 一律 404，改为读取会话里的
    //     WebVTT rendition（subtitle.m3u8），按播放进度逐段交给 libass
    //   * 关闭字幕：清 guid、清 cue、隐藏字幕层、停掉定时器 —— 真正删除字幕，
    //     不再往画面上写「字幕：关闭」之类常驻提示文字。
    // ------------------------------------------------------------------

    private void clearSubtitleFrame() {
        subtitleDrawn = false;
        lastFrameNonEmptyPosMs = -1L;
        if (subtitleImage != null) {
            subtitleImage.setImageBitmap(null);
        }
        hasLastSubtitleRect = false;
        lastSubtitleRect.setEmpty();
        if (playerSubtitle != null) {
            playerSubtitle.setVisibility(View.GONE);
        }
    }

    /** 把字幕层恢复成「无字幕」：清 libass track、隐藏容器、停定时器。 */
    private void clearSubtitleLayer() {
        ui.removeCallbacks(subtitleTicker);
        subtitlesEnabled = false;
        subtitleDrawn = false;
        subCueStarts = null;
        lastLoggedCue = -2;
        subtitleBlankSinceMs = -1L;
        lastFrameNonEmptyPosMs = -1L;
        hlsDoc = "";
        if (hlsVtt != null) {
            hlsVtt.stop();
            hlsVtt = null;
        }
        if (libass != null) {
            libass.clear();
        }
        if (subtitleImage != null) {
            subtitleImage.setImageBitmap(null);
        }
        hasLastSubtitleRect = false;
        lastSubtitleRect.setEmpty();
        if (playerSubtitle != null) {
            playerSubtitle.setVisibility(View.GONE);
        }
    }

    /**
     * 每 100ms 让 libass 按「播放器真实位置」渲染一帧。
     *
     * 时间基准只有一个：player.getCurrentPosition()。绝不用自增 ticker 或墙钟推算，
     * 否则一旦暂停/缓冲/seek/降档重建，累计误差就会越来越大（以前「越放越对不上」
     * 的根因就在这类隐式时间源上）。返回 null 表示无变化，跳过重绘。
     */
    private final Runnable subtitleTicker = new Runnable() {
        public void run() {
            if (subtitlesEnabled && player != null && prepared
                    && libass != null && playerSubtitle != null) {
                if (seekPending || engineBuffering) {
                    ui.postDelayed(this, 100L);
                    return;
                }
                try {
                    int pos = (int) toAbsolutePositionMs(player.getPositionMs());
                    // seek/续播造成的跳变要单独记一笔：字幕「落后于画面」时，
                    // 先看跳变后第一次出字要多久、这段时间里是否缺分片。
                    if (lastSubtitlePosMs >= 0 && Math.abs(pos - lastSubtitlePosMs) > 3000) {
                        L.i("SubSync", "seek 跳变 " + lastSubtitlePosMs + "ms -> " + pos
                                + "ms 旧skew=" + hlsClock.skewMs() + "ms " + hlsClock.describe()
                                + (hlsVtt != null ? " " + hlsVtt.stats() : ""));
                    }
                    // 暂停时位置不变：只有 seek 之后才需要重绘一次
                    if (!userPaused || pos != lastSubtitlePosMs) {
                        lastSubtitlePosMs = pos;
                        // 只有系统 MediaPlayer 的 HLS 位置按播放列表时间推进，
                        // 分片真实 PTS 与它差 1~2 秒，需要 pos - skew 回到画面时间；
                        // ExoPlayer 的位置本身就是媒体时间轴，再叠加校正
                        // 反而会把字幕整体推前 1~2 秒，所以这里按内核区分。
                        long skew = 0L;
                        if (player.needsHlsClockCorrection()
                                && currentPlaylistUrl != null
                                && currentPlaylistUrl.toLowerCase(java.util.Locale.US).contains(".m3u8")) {
                            hlsClock.sampleAsync(clockPlaylistUrl, pos);
                            skew = hlsClock.skewMs();
                        }
                        // 字幕调整的「偏移」直接作用在字幕时间轴上（正数=字幕延后）
                        long subTime = pos - skew + subOffsetMs;
                        updateHlsVtt(subTime);
                        logSubtitleSync(pos, subTime);
                        renderLibassFrame(subTime);
                    }
                } catch (Throwable ignored) {
                }
            }
            ui.postDelayed(this, 100L);
        }
    };

    /**
     * 同步诊断：打印「当前字幕事件时间戳 / 播放器位置 / 分片 PTS 基准 / 偏差」。
     * 事件切换或每 10 秒输出一次，用来区分固定偏移与累计漂移。
     */
    private void logSubtitleSync(int pos, long subTime) {
        long[] starts = subCueStarts;
        int cue = -2;
        if (starts != null && starts.length > 0) {
            int lo = 0;
            int hi = starts.length - 1;
            if (subTime < starts[0]) {
                cue = -1;
            } else {
                while (lo < hi) {
                    int mid = (lo + hi + 1) >>> 1;
                    if (starts[mid] <= subTime) {
                        lo = mid;
                    } else {
                        hi = mid - 1;
                    }
                }
                cue = lo;
            }
        }
        long now = System.currentTimeMillis();
        if (cue == lastLoggedCue && now - lastSyncLogAt < 10000L) {
            return;
        }
        lastLoggedCue = cue;
        lastSyncLogAt = now;
        L.i("SubSync", "pos=" + pos + "ms sub=" + subTime + "ms skew="
                + hlsClock.skewMs() + "ms cue=" + (cue >= 0 ? starts[cue] : -1)
                + (cue >= 0 ? " delta=" + (subTime - starts[cue]) + "ms" : "")
                + " " + hlsClock.describe()
                + (hlsVtt != null ? " " + hlsVtt.stats() : ""));
    }

    private void resetSubtitleTimeline() {
        hlsClock.invalidateSegment();
        if (hlsVtt != null) {
            hlsVtt.onSeek();
        }
        if (libass != null) {
            libass.clear();
        }
        clearSubtitleFrame();
        subCueStarts = null;
        hlsDoc = "";
        lastLoggedCue = -2;
        lastSubtitlePosMs = -1;
        subtitleBlankSinceMs = -1L;
    }

    /** 立即用当前位置重绘一帧：seek / 起播 / 暂停 / 降档重建后都要调用。 */
    private void requestSubtitleFrame() {
        lastSubtitlePosMs = -1;
        if (!subtitlesEnabled || player == null || !prepared || libass == null
                || playerSubtitle == null) {
            return;
        }
        ui.removeCallbacks(subtitleTicker);
        ui.post(subtitleTicker);
    }

    /**
     * 把 libass 渲染的位图画到覆盖层。所有时间轴/定位/样式/双语都在 C 层完成，
     * Java 侧没有解析任何 cue；字幕间隙 libass 返回 empty，立即清空。
     *
     * 性能：只把「字幕脏区」上传成小位图，覆盖层也只占这一块区域。
     * 过去每次把 1920x1080 全屏位图 setPixels + 全屏贴图，老 TV 上会把
     * UI/GPU 压满，字幕追不上画面。
     */
    private void renderLibassFrame(long posMs) {
        if (libass == null) {
            return;
        }
        // 先保证覆盖层可见：ticker 只在字幕启用时运行，容器被别处隐藏后
        // 若等「有变化」的帧才恢复，正好遇到静止画面就会一直看不见字幕。
        if (playerSubtitle.getVisibility() != View.VISIBLE) {
            playerSubtitle.setVisibility(View.VISIBLE);
        }
        int w = playerSubtitle.getWidth();
        int h = playerSubtitle.getHeight();
        if (w <= 0 || h <= 0) {
            // 字幕容器初始是 GONE（无字幕时不占位），GONE 视图 getWidth()==0，
            // 若只依赖它，setSize 永远不会执行、bitmap 永远为 null、render 永远
            // 返回 null，而容器又要等 render 成功才 VISIBLE —— 死循环导致字幕永不显示。
            w = surface.getWidth();
            h = surface.getHeight();
        }
        if (w > 0 && h > 0) {
            // 尺寸变化时（横竖屏/首次布局）重设画布；storage 用视频原始分辨率。
            libass.setSize(w, h, subtitleStorageW, subtitleStorageH);
        }
        Libass.Frame frame = libass.render(posMs);
        if (frame == null) {
            // 位图未变，但大小/位置可能刚被调整，仍需重排上一帧。
            refreshSubtitleGeometry();
            return;
        }
        if (frame.empty) {
            if (SystemClock.elapsedRealtime() < subtitleSeekGraceUntilMs
                    && subtitleImage.getDrawable() != null) {
                return;
            }
            hasLastSubtitleRect = false;
            lastSubtitleRect.setEmpty();
            if (subtitleImage.getDrawable() != null) {
                subtitleImage.setImageBitmap(null);
            }
            return;
        }
        subtitleSeekGraceUntilMs = 0L;
        // 帧级证据：从「有字幕帧」到下一帧之间隔了多久。若 doc 里明明有 cue
        // 却长时间渲染不出帧，问题在渲染层；若是 doc 本身为空，问题在拉取/解析。
        long frameGap = lastFrameNonEmptyPosMs < 0 ? -1L : posMs - lastFrameNonEmptyPosMs;
        lastFrameNonEmptyPosMs = posMs;
        if (frameGap >= 4000L) {
            L.i("SubSync", "字幕帧恢复 pos=" + posMs + "ms 间隔=" + frameGap
                    + "ms rect=" + frame.rect);
        }
        // 保存未缩放的脏区，后续无新帧时也能即时应用大小/位置调整。
        lastSubtitleRect.set(frame.rect);
        hasLastSubtitleRect = true;
        applySubtitleGeometry(w, h);
        subtitleImage.setImageBitmap(frame.bitmap);
        if (!subtitleDrawn) {
            subtitleDrawn = true;
            L.i("Player", "字幕首帧出画 pos=" + posMs + "ms canvas=" + w + "x" + h
                    + " rect=" + frame.rect);
        }
    }

    /** 字幕轨道固定从 NAS 的 stream/list 读取，菜单内用 ✓ 标出当前状态。 */
    private void showSubtitleMenu() {
        new Thread(new Runnable() {
            public void run() {
                final List<SubtitleTrack> tracks = new ArrayList<SubtitleTrack>();
                try {
                    tracks.addAll(api.listSubtitles(cfg, item));
                } catch (Throwable t) {
                    L.w("Player", "字幕列表读取失败：" + t);
                }
                ui.post(new Runnable() {
                    public void run() {
                        String current = activeSubtitle == null ? "" : activeSubtitle.guid;
                        final List<String> labels = new ArrayList<String>();
                        labels.add("关闭字幕" + (current.length() == 0 ? " ✓" : ""));
                        for (int i = 0; i < tracks.size(); i++) {
                            String name = tracks.get(i).displayName();
                            labels.add(name + (tracks.get(i).guid.equals(current) ? " ✓" : ""));
                        }
                        L.i("Osd", "subtitle-menu nas=" + tracks.size()
                                + " current=" + (current.length() == 0 ? "off" : "on"));
                        showMenu(new AlertDialog.Builder(PlayerActivity.this)
                                .setTitle("字幕 · 来自 NAS" + (tracks.isEmpty() ? "（暂无轨道）" : ""))
                                .setItems(labels.toArray(new String[labels.size()]),
                                        new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface d, int which) {
                                                if (which == 0) {
                                                    disableSubtitles();
                                                } else {
                                                    selectSubtitle(tracks.get(which - 1));
                                                }
                                            }
                                        })
                                .setNegativeButton("取消", null));
                    }
                });
            }
        }, "fntv-subtitles").start();
    }

    /** 关闭字幕：静默删除，不弹提示、不往画面写字；内嵌烧录字幕需重建会话去掉烧录。 */
    private void disableSubtitles() {
        boolean wasBurnedIn = activeSubtitle != null && activeSubtitle.isBurnedIn();
        item.subtitleGuid = "";
        activeSubtitle = null;
        subtitleFormat = "";
        subtitleStatus = "字幕：关闭";
        saveSubtitleGuid(""); // 记住「关闭」，下次进入不再自动开字幕
        clearSubtitleLayer();
        L.i("Osd", "subtitle off layer=gone cues=0");
        if (wasBurnedIn) {
            rebuildPlayback(""); // 让 NAS 重新建会话时不再烧录字幕
        }
    }

    /** 按条目记住字幕选择（空串 = 用户主动关闭，null = 尚无记录）。 */
    private String savedSubtitleGuid() {
        return getSharedPreferences("fntv_subtitles", MODE_PRIVATE)
                .getString(item.guid, null);
    }

    private void saveSubtitleGuid(String guid) {
        getSharedPreferences("fntv_subtitles", MODE_PRIVATE).edit()
                .putString(item.guid, guid).commit();
    }

    private void selectSubtitle(final SubtitleTrack track) {
        item.subtitleGuid = track.guid;
        saveSubtitleGuid(track.guid);
        activeSubtitle = track;
        subtitleFormat = track.format == null ? "" : track.format;
        subtitleStatus = "字幕：" + track.displayName();
        clearSubtitleLayer(); // 先删掉上一条字幕，避免切换瞬间残留
        // 不依赖 stream/list 的 extra_file 标注：直接实测 NAS 能否给出字幕文本。
        // 能给出就本地渲染（直连模式下也有字幕、播放不中断）；
        // 给不出（内嵌 ASS，实测 404）才退回 NAS 转码烧录。
        loadExternalSubtitle(track);
    }

    /** 从 NAS 下载外挂字幕原文并解析成时间轴；下载期间用户改选/关闭则丢弃结果。 */
    private void loadExternalSubtitle(final SubtitleTrack track) {
        if (track.isBurnedIn()) {
            // 内嵌轨（容器内 ASS，实测 /subtitle/dl 全部 404）：
            // 不浪费一次必然失败的下载，直接让会话带上 subtitle_guid，
            // 从 NAS 的 HLS WebVTT rendition 逐段读取。
            startEmbeddedSubtitle(track);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                String content = "";
                try {
                    content = api.subtitleContent(cfg, track.guid, track.format);
                } catch (Throwable t) {
                    L.w("Player", "外挂字幕下载失败：" + t);
                }
                final String text = content;
                ui.post(new Runnable() {
                    public void run() {
                        if (activeSubtitle == null
                                || !track.guid.equals(activeSubtitle.guid)) {
                            return; // 用户在等待期间已关闭或改选
                        }
                        if (!applySubtitleText(text, "nas/"
                                + (text.indexOf("Dialogue:") >= 0 ? "ass" : "vtt/srt"))) {
                            // NAS 没给有效文本 / libass 未解析出事件：退回服务端烧录
                            Ui.toast(PlayerActivity.this, "字幕由 NAS 输出：" + track.displayName());
                            rebuildPlayback("");
                            return;
                        }
                        Ui.toast(PlayerActivity.this, "字幕：" + track.displayName());
                    }
                });
            }
        }, "fntv-subtitle-text").start();
    }

    /**
     * 内嵌字幕轨：NAS 只有「play/play 带 subtitle_guid」时才在会话目录生成
     * subtitle.m3u8（WebVTT rendition），因此先按需重建会话，再逐段读取。
     */
    private void startEmbeddedSubtitle(final SubtitleTrack track) {
        subtitleStatus = "字幕：" + track.displayName();
        boolean match = track.guid.equals(sessionSubtitleGuid);
        L.i("Osd", "subtitle embedded track=" + track.displayName()
                + " session=" + (match ? "ok" : "rebuild"));
        if (!match) {
            rebuildPlayback("");
        }
        enableHlsVtt();
    }

    /** 启用 HLS WebVTT 渲染：cue 由 ticker 按播放进度逐段补进 libass。 */
    private void enableHlsVtt() {
        if (hlsVtt != null) {
            hlsVtt.stop();
        }
        hlsVtt = new HlsVtt();
        hlsDoc = "";
        subCueStarts = null;
        lastLoggedCue = -2;
        subtitleBlankSinceMs = -1L;
        lastFrameNonEmptyPosMs = -1L;
        if (libass == null) {
            libass = new Libass();
        }
        subtitlesEnabled = true;
        playerSubtitle.setVisibility(View.VISIBLE);
        ui.removeCallbacks(subtitleTicker);
        ui.post(subtitleTicker);
        L.i("Osd", "subtitle on engine=LIBASS source=nas/hls-vtt");
    }

    /** 把播放列表地址换算成会话目录（.../v/media/&lt;hash&gt;/），代理会负责续期重映射。 */
    private String hlsBase() {
        String u = currentPlaylistUrl;
        if (u == null || u.indexOf(".m3u8") < 0) {
            return "";
        }
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        int slash = u.lastIndexOf('/');
        return slash > 0 ? u.substring(0, slash + 1) : "";
    }

    /** 按当前字幕时间更新窗口：拉取分片，必要时只重载「附近几十秒」的 cue。 */
    private void updateHlsVtt(long subTime) {
        HlsVtt v = hlsVtt;
        if (v == null) {
            return;
        }
        String base = hlsBase();
        if (base.length() == 0) {
            return;
        }
        v.setBase(base);
        v.tick(subTime);
        if (v.cueCount() == 0) {
            if (libass != null) {
                libass.clear();
            }
            clearSubtitleFrame();
            subCueStarts = null;
            hlsDoc = "";
            lastLoggedCue = -2;
            if (subtitleBlankSinceMs < 0L) {
                subtitleBlankSinceMs = subTime;
            }
            return;
        }
        String doc = v.windowVtt(subTime);
        if (doc.equals(hlsDoc)) {
            return;
        }
        hlsDoc = doc;
        if (doc.length() == 0) {
            // 记录「字幕消失」起点：窗口内已没有 cue（可能是正常对白间隙，
            // 也可能是 VTT 拉取失败）。恢复时按持续时长区分，>3 秒才可能是缺陷。
            if (subtitleBlankSinceMs < 0) {
                subtitleBlankSinceMs = subTime;
                // 空白起点就记下「这一段 VTT 是否已经拉到」：恢复时只按这个
                // 判断是缺料还是对白间隙，避免把正常静默算成字幕消失。
                if (hlsVtt != null) {
                    L.i("SubSync", "字幕空白开始 sub=" + subTime + "ms 缺分片="
                            + (!hlsVtt.hasSegmentAt(subTime)) + " " + hlsVtt.stats());
                }
            }
            // 窗口内没有 cue：清掉旧事件，否则上一句会一直挂在画面上
            if (subCueStarts != null) {
                if (libass != null) {
                    libass.clear();
                }
                clearSubtitleFrame();
                subCueStarts = null;
                lastLoggedCue = -2;
            }
            return;
        }
        if (subtitleBlankSinceMs >= 0) {
            long blank = subTime - subtitleBlankSinceMs;
            if (blank >= 3000L) {
                L.i("SubSync", "字幕恢复 sub=" + subTime + "ms 空白=" + blank
                        + "ms " + (hlsVtt != null ? hlsVtt.stats() : ""));
            }
            subtitleBlankSinceMs = -1L;
        }
        if (libass == null) {
            libass = new Libass();
        }
        if (!libass.available()) {
            // 不把 doc 记成「已加载」：引擎恢复后必须能重新装载，
            // 否则 hlsDoc 命中会让字幕永远不再出现。
            hlsDoc = "";
            long now = System.currentTimeMillis();
            if (now - lastLibassWarnAt > 10000L) {
                lastLibassWarnAt = now;
                L.w("Player", "libass engine unavailable（hls-vtt）");
            }
            return;
        }
        try {
            if (player.getVideoWidth() > 0 && player.getVideoHeight() > 0) {
                subtitleStorageW = player.getVideoWidth();
                subtitleStorageH = player.getVideoHeight();
            }
        } catch (Throwable ignored) {
        }
        if (!libass.load(doc)) {
            hlsDoc = "";
            return;
        }
        subCueStarts = Libass.cueStarts(doc);
        lastLoggedCue = -2;
    }

    /**
     * 本地字幕文件（如 /sdcard/movie.srt）：走与 NAS 字幕完全相同的
     * SRT/VTT→ASS 转换 + libass 渲染链路。
     */
    private void loadLocalSubtitle(final String path) {
        new Thread(new Runnable() {
            public void run() {
                String text = "";
                try {
                    java.io.File f = new java.io.File(path);
                    byte[] buf = new byte[(int) f.length()];
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    int off = 0;
                    while (off < buf.length) {
                        int n = in.read(buf, off, buf.length - off);
                        if (n <= 0) {
                            break;
                        }
                        off += n;
                    }
                    in.close();
                    text = com.juying.medianest.core.util.NetUtil.decodeText(buf);
                } catch (Throwable t) {
                    L.w("Player", "本地字幕读取失败：" + t);
                }
                final String content = text;
                ui.post(new Runnable() {
                    public void run() {
                        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.US);
                        subtitleFormat = lower.endsWith(".vtt") ? "vtt"
                                : (lower.endsWith(".ass") ? "ass" : "srt");
                        if (!applySubtitleText(content, "local/" + path)) {
                            Ui.toast(PlayerActivity.this, "本地字幕加载失败：" + path);
                            return;
                        }
                        Ui.toast(PlayerActivity.this, "字幕：" + path);
                    }
                });
            }
        }, "fntv-local-subtitle").start();
    }

    /** 把字幕文本交给 libass 并启动渲染；返回 false 表示无法本地渲染。 */
    private boolean applySubtitleText(String text, String sourceTag) {
        if (libass == null) {
            libass = new Libass();
        }
        if (!libass.available()) {
            Ui.toast(PlayerActivity.this, "字幕引擎不可用（libass 加载失败）");
            L.w("Player", "libass engine unavailable");
            return false;
        }
        if (!libass.load(text)) {
            L.w("Player", "libass 未解析出字幕事件：" + sourceTag);
            return false;
        }
        // storage 尺寸取视频原始分辨率，让 ASS 的 PlayRes 坐标正确映射
        try {
            if (player.getVideoWidth() > 0 && player.getVideoHeight() > 0) {
                subtitleStorageW = player.getVideoWidth();
                subtitleStorageH = player.getVideoHeight();
            }
        } catch (Throwable ignored) {
        }
        subtitlesEnabled = true;
        subCueStarts = Libass.cueStarts(text);
        lastLoggedCue = -2;
        // 先让容器可见并完成布局，renderLibassFrame 才能拿到非零尺寸；
        // 此时还没有位图，覆盖层完全透明，不会遮挡画面。
        playerSubtitle.setVisibility(View.VISIBLE);
        ui.removeCallbacks(subtitleTicker);
        ui.post(subtitleTicker);
        L.i("Osd", "subtitle on engine=LIBASS source=" + sourceTag
                + " storage=" + subtitleStorageW + "x" + subtitleStorageH
                + " bytes=" + text.length());
        return true;
    }

    /** 设置 → 播放偏好：首选字幕 / 音频语言（0 任意语言、1 简体中文、2 English）。 */
    private static final String KEY_SUBTITLE_LANG = "subtitle_lang";
    private static final String KEY_AUDIO_LANG = "audio_lang";

    /**
     * 起播回读账号偏好（后台线程，失败静默）：
     * 首选语言写回 subtitle_lang/audio_lang，卡片显示项写回 score/watched/resolution，
     * 让网页端改动在直接起播时就能影响选轨与后续列表渲染，不必先打开设置页。
     */
    private void syncUserPrefsFromServer() {
        if (cfg == null || cfg.baseUrl == null || cfg.baseUrl.length() == 0) {
            return;
        }
        if ((cfg.token == null || cfg.token.length() == 0) && !cfg.hasCredentials()) {
            return;
        }
        if ("JELLYFIN".equalsIgnoreCase(cfg.apiMode)) {
            return;
        }
        final ServerConfig target = cfg.copy();
        final SharedPreferences prefs = getSharedPreferences("official_settings", MODE_PRIVATE);
        Thread thread = new Thread(new Runnable() {
            public void run() {
                MediaUseCases nas = App.from(PlayerActivity.this).useCases(target);
                try {
                    Map<String, String> info = nas.fetchUserPrefs(target);
                    prefs.edit()
                            .putInt(KEY_SUBTITLE_LANG, prefIndex(info.get("lan")))
                            .putInt(KEY_AUDIO_LANG, prefIndex(info.get("audio_lan")))
                            .apply();
                    L.i("Player", "起播回读偏好 lan=" + info.get("lan")
                            + " audio_lan=" + info.get("audio_lan"));
                } catch (Throwable t) {
                    L.w("Player", "起播回读首选语言失败（沿用本地）：" + t);
                }
                try {
                    String card = nas.fetchUserData(target, "list:card:setting");
                    if (card != null && card.length() > 0) {
                        Map<String, Object> m = Json.parseObject(card);
                        prefs.edit()
                                .putBoolean("score",
                                        Json.bool(m, "score", prefs.getBoolean("score", true)))
                                .putBoolean("watched",
                                        Json.bool(m, "watched", prefs.getBoolean("watched", false)))
                                .putBoolean("resolution",
                                        Json.bool(m, "resolution", prefs.getBoolean("resolution", true)))
                                .apply();
                    }
                } catch (Throwable t) {
                    L.w("Player", "起播回读卡片显示配置失败（沿用本地）：" + t);
                }
            }
        }, "fntv-user-prefs");
        thread.setDaemon(true);
        thread.start();
    }

    /** 服务端语言码 → 本地索引（0 任意语言、1 简体中文、2 English）。 */
    private static int prefIndex(String code) {
        if (code == null || code.length() == 0 || "any".equalsIgnoreCase(code)) {
            return 0;
        }
        String lower = code.toLowerCase(java.util.Locale.US);
        if (lower.startsWith("zh")) {
            return 1;
        }
        if (lower.startsWith("en")) {
            return 2;
        }
        return 0;
    }

    private int prefLanguage(String key) {
        return getSharedPreferences("official_settings", MODE_PRIVATE).getInt(key, 1);
    }

    private boolean languageMatches(String text, int pref) {
        if (text == null || text.length() == 0 || pref == 0) {
            return false;
        }
        String s = text.toLowerCase(java.util.Locale.US);
        if (pref == 1) {
            return s.contains("中文") || s.contains("简体") || s.contains("chinese")
                    || s.contains("chi") || s.contains("zh") || s.contains("chs");
        }
        return s.contains("english") || s.contains("英文")
                || java.util.regex.Pattern.compile("\\beng?\\b").matcher(s).find();
    }

    private SubtitleTrack matchSubtitleLanguage(List<SubtitleTrack> tracks, int pref) {
        for (int i = 0; i < tracks.size(); i++) {
            SubtitleTrack t = tracks.get(i);
            if (languageMatches(t.language, pref) || languageMatches(t.displayName(), pref)) {
                return t;
            }
        }
        return null;
    }

    /** 起播后用首选音频语言自动选轨；用户手动切换仍优先，之后不会被再次覆盖。 */
    private void applyPreferredAudio() {
        if (player == null || !prepared) {
            return;
        }
        int pref = prefLanguage(KEY_AUDIO_LANG);
        if (pref == 0) {
            return;
        }
        List<String> labels = player.audioTrackLabels();
        int current = player.currentAudioTrack();
        for (int i = 0; i < labels.size(); i++) {
            if (!languageMatches(labels.get(i), pref)) {
                continue;
            }
            if (i != current) {
                player.selectAudioTrack(i);
                L.i("Player", "首选音频语言命中：" + labels.get(i));
            }
            return;
        }
    }

    /** play/info 记住的上次字幕：延迟等 buildPlan 补齐 subtitle_guid 后自动恢复渲染。 */
    private void restoreSavedSubtitle() {
        ui.postDelayed(new Runnable() {
            public void run() {
                restoreSavedSubtitleNow();
            }
        }, 1500L);
    }

    private void restoreSavedSubtitleNow() {
        String remembered = savedSubtitleGuid();
        // 用户主动关闭过（记住的是空串）就保持关闭
        if (remembered != null && remembered.length() == 0) {
            return;
        }
        // 用户选择优先于 play/info；两者都没有时才跟随 NAS 的默认字幕轨
        final String wanted = remembered != null ? remembered : item.subtitleGuid;
        if (liveMode || activeSubtitle != null || destroyed) {
            return;
        }
        if (!planReady && !fatalShown && subtitleRestoreWaits++ < 32) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    restoreSavedSubtitleNow();
                }
            }, 250L);
            return;
        }
        subtitleRestoreWaits = 0;
        item.subtitleGuid = wanted;
        new Thread(new Runnable() {
            public void run() {
                final List<SubtitleTrack> tracks = new ArrayList<SubtitleTrack>();
                try {
                    tracks.addAll(api.listSubtitles(cfg, item));
                } catch (Throwable t) {
                    L.w("Player", "字幕状态恢复失败：" + t);
                }
                SubtitleTrack hit = null;
                boolean byPref = false;
                // 设置里的「首选字幕语言」：用户没手动选过这条片源时优先匹配语言
                if (remembered == null && prefLanguage(KEY_SUBTITLE_LANG) != 0) {
                    hit = matchSubtitleLanguage(tracks, prefLanguage(KEY_SUBTITLE_LANG));
                    byPref = hit != null;
                }
                if (hit == null) {
                    for (int i = 0; i < tracks.size(); i++) {
                        if (tracks.get(i).guid.equals(wanted)) {
                            hit = tracks.get(i);
                            break;
                        }
                    }
                }
                if (hit == null && wanted.length() == 0) {
                    // play/info 的 subtitle_guid 是空串（实测 4K 片源普遍如此），
                    // 而 stream/list 里标了 is_default 的字幕才是网页端自动选的轨道。
                    for (int i = 0; i < tracks.size(); i++) {
                        if (tracks.get(i).isDefault) {
                            hit = tracks.get(i);
                            break;
                        }
                    }
                }
                if (byPref) {
                    L.i("Player", "首选字幕语言命中：" + hit.displayName()
                            + " lang=" + hit.language);
                }
                final SubtitleTrack found = hit;
                ui.post(new Runnable() {
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        // 起播阶段已经拿到完整轨道，直接缓存给字幕页用，避免二次请求
                        if (subtitleTracks.isEmpty()) {
                            subtitleTracks.clear();
                            subtitleTracks.addAll(tracks);
                            subtitleTracksFetched = true;
                            subtitleTracksError = null;
                            subtitleTracksLoading = false;
                            if (expandedTabId == R.id.btn_subtitle) {
                                rebuildTabList();
                            }
                        }
                        // 用户在等待期间已手动改选 / 关闭，就丢弃这次自动恢复
                        if (found == null || activeSubtitle != null
                                || !wanted.equals(item.subtitleGuid)) {
                            return;
                        }
                        // 内嵌轨要靠重建会话让 NAS 烧录，必须把 guid 写回去
                        item.subtitleGuid = found.guid;
                        activeSubtitle = found;
                        subtitleFormat = found.format == null ? "" : found.format;
                        subtitleStatus = "字幕：" + found.displayName();
                        L.i("Osd", "subtitle restore=" + (wanted.length() == 0 ? "default" : "saved")
                                + " track=" + found.displayName()
                                + " burned=" + found.isBurnedIn());
                        // 同样先实测文本下载：直连模式下 NAS 不烧录字幕，
                        // 只有客户端按时间轴渲染才能保证字幕真的出现。
                        loadExternalSubtitle(found);
                    }
                });
            }
        }, "fntv-subtitle-restore").start();
    }

    // ------------------------------------------------------------------
    // 选集 / 上一集 / 下一集
    // ------------------------------------------------------------------

    /**
     * 起播后异步读取同一季的兄弟集。单集 / 电影 / 直播没有 seasonGuid，
     * 直接返回 —— 此时选集入口保持隐藏，符合「单集片源不出现选集」。
     */
    private void loadEpisodeContext() {
        final String seasonGuid = item == null ? "" : item.seasonGuid;
        if (liveMode || seasonGuid == null || seasonGuid.length() == 0) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final List<MediaItem> eps = new ArrayList<MediaItem>();
                try {
                    eps.addAll(api.listEpisodes(cfg, seasonGuid));
                } catch (Throwable t) {
                    L.w("Player", "兄弟集读取失败：" + t.getMessage());
                }
                ui.post(new Runnable() {
                    public void run() {
                        episodesLoaded = true;
                        siblingEpisodes.clear();
                        siblingEpisodes.addAll(eps);
                        episodeAt = -1;
                        for (int i = 0; i < siblingEpisodes.size(); i++) {
                            if (item.guid.equals(siblingEpisodes.get(i).guid)) {
                                episodeAt = i;
                                break;
                            }
                        }
                        updateEpisodeControls();
                        if (expandedTabId == R.id.btn_episodes) {
                            epPage = Math.max(0, episodeAt / EPISODE_PAGE_SIZE);
                            buildEpisodeGrid();
                            pendingRowFocus = episodeAt >= 0 ? episodeAt % EPISODE_PAGE_SIZE : 0;
                            if (pendingRowFocus >= 0 && pendingRowFocus < tabListItems.size()) {
                                tabListItems.get(pendingRowFocus).requestFocus();
                            }
                            pendingRowFocus = -1;
                        }
                        L.i("Player", "选集加载 " + siblingEpisodes.size() + " 集，当前第 "
                                + (episodeAt + 1) + " 集");
                    }
                });
            }
        }, "fntv-siblings").start();
    }

    /** 只有「同一季里确实有多集」才显示选集入口；单集片源整体隐藏。 */
    private void updateEpisodeControls() {
        View list = findViewById(R.id.btn_episodes);
        boolean multi = !liveMode && episodesLoaded && siblingEpisodes.size() > 1 && episodeAt >= 0;
        if (!multi) {
            list.setVisibility(View.GONE);
            relinkFocus();
            if (osdVisible && validFocusedControl() == null) {
                focusDefaultControl();
            }
            return;
        }
        // 官方 OSD 不再显示「上一集 / 下一集」独立按钮（切换走「选集」弹层）
        list.setVisibility(View.VISIBLE);
        ((TextView) list).setText("选集");
        // 可见控件变化后重设焦点兜底链；当前焦点若已隐藏，立刻修正
        relinkFocus();
        if (osdVisible && validFocusedControl() == null) {
            focusDefaultControl();
        }
    }

    /** 上一集 / 下一集：按兄弟集顺序切换，边界只提示不切。 */
    private void switchEpisode(int delta) {
        if (!episodesLoaded) {
            L.w("Player", "切集失败：选集尚未加载 delta=" + delta);
            osdNotice.setText("正在读取剧集…");
            return;
        }
        if (siblingEpisodes.size() <= 1 || episodeAt < 0) {
            L.w("Player", "切集失败：兄弟集=" + siblingEpisodes.size()
                    + " 当前序号=" + episodeAt);
            osdNotice.setText("当前没有可切换的剧集");
            return;
        }
        int next = episodeAt + delta;
        if (next < 0) {
            L.i("Player", "切集忽略：已经是第一集");
            osdNotice.setText("已经是第一集");
            return;
        }
        if (next >= siblingEpisodes.size()) {
            L.i("Player", "切集忽略：已经是最后一集（共 " + siblingEpisodes.size() + " 集）");
            osdNotice.setText("已经是最后一集");
            return;
        }
        openEpisode(siblingEpisodes.get(next));
    }

    /**
     * 播完自动切下一集。返回 true 表示已发起切换；任何不可切的原因都留日志，
     * 不再像以前那样静默失败。
     */
    private boolean autoplayNextEpisode() {
        if (!episodesLoaded) {
            L.w("Player", "自动连播跳过：选集尚未加载");
            return false;
        }
        if (siblingEpisodes.size() <= 1 || episodeAt < 0) {
            L.w("Player", "自动连播跳过：兄弟集=" + siblingEpisodes.size()
                    + " 当前序号=" + episodeAt);
            return false;
        }
        int next = episodeAt + 1;
        if (next >= siblingEpisodes.size()) {
            L.i("Player", "自动连播结束：已经是最后一集（共 " + siblingEpisodes.size() + " 集）");
            return false;
        }
        MediaItem ep = siblingEpisodes.get(next);
        L.i("Player", "自动连播：切入第 " + (next + 1) + " 集 " + ep.displayTitle());
        openEpisode(ep);
        return true;
    }

    /** 官方 dialog_play_end：全屏遮罩 + 播放结束 + 490×280 封面 + 标题/副标题 + 三按钮。 */
    private void showPlaybackEndedDialog() {
        if (osdDialog != null) {
            return;
        }
        final android.app.Dialog d = newOsdDialog();
        FrameLayout root = dialogRoot(d);

        View mask = new View(this);
        mask.setBackgroundColor(0xCC000000);
        root.addView(mask, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER);
        root.addView(panel, panelLp);

        TextView ended = pxText("播放结束", 48, 0xCCFFFFFF);
        ended.setGravity(android.view.Gravity.CENTER);
        panel.addView(ended, marginLp(900, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 36));

        // 490×280 圆角封面（官方 play_end_image_bg：16px 圆角、占位色 #ff23242b）
        FrameLayout imageBox = new FrameLayout(this);
        imageBox.setBackgroundDrawable(roundRect(0xFF23242B, 16));
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setImageResource(R.drawable.big_video_placeholder);
        imageBox.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.addView(imageBox, new LinearLayout.LayoutParams(490, 280));
        loadEndedBackdrop(cover, d);

        MediaItem next = null;
        if (!liveMode && episodesLoaded && episodeAt >= 0
                && episodeAt + 1 < siblingEpisodes.size()) {
            next = siblingEpisodes.get(episodeAt + 1);
        }

        TextView title = pxText(item.displayTitle(), 28, 0xCCFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(title, marginLp(700, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0));

        TextView sub = pxText(next == null ? ""
                : "即将播放下个视频 " + next.displayTitle(), 24, 0xCCFFFFFF);
        sub.setGravity(android.view.Gravity.CENTER);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(sub, marginLp(900, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0));
        sub.setVisibility(next == null ? View.GONE : View.VISIBLE);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(android.view.Gravity.CENTER);
        panel.addView(buttons, marginLp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 60, 0));

        final MediaItem nextFinal = next;
        TextView nextBtn = null;
        if (next != null) {
            nextBtn = playEndButton("下个视频", new Runnable() {
                public void run() {
                    dismissOsdDialog();
                    openEpisode(nextFinal);
                }
            });
            buttons.addView(nextBtn, playEndButtonLp());
        }
        final TextView replay = playEndButton("重新播放", new Runnable() {
            public void run() {
                dismissOsdDialog();
                if (player != null) {
                    player.seekTo(0L);
                    player.play();
                }
            }
        });
        buttons.addView(replay, playEndButtonLp());
        TextView exit = playEndButton("退出播放", new Runnable() {
            public void run() {
                dismissOsdDialog();
                finish();
            }
        });
        buttons.addView(exit, playEndButtonLp());

        final TextView focusTarget = nextBtn != null ? nextBtn : replay;
        d.setOnKeyListener(new DialogInterface.OnKeyListener() {
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    dismissOsdDialog();
                    finish();
                    return true;
                }
                return false;
            }
        });
        d.show();
        focusTarget.requestFocus();
    }

    private void loadEndedBackdrop(final ImageView cover, final android.app.Dialog owner) {
        final String existing = item.backdropPath;
        if (existing != null && existing.length() > 0) {
            new ImageLoader(this).load(api.imageUrl(cfg, existing, 980), cover,
                    api.authorizationHeader(cfg));
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    final String backdrop = api.showBackdrop(cfg, item.guid);
                    if (backdrop == null || backdrop.length() == 0) {
                        return;
                    }
                    item.backdropPath = backdrop;
                    ui.post(new Runnable() {
                        public void run() {
                            if (owner == osdDialog && owner.isShowing() && !isFinishing()) {
                                new ImageLoader(PlayerActivity.this).load(
                                        api.imageUrl(cfg, backdrop, 980), cover,
                                        api.authorizationHeader(cfg));
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }, "fntv-ended-backdrop").start();
    }

    private LinearLayout.LayoutParams marginLp(int w, int h, int left, int top, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = left;
        p.topMargin = top;
        p.rightMargin = right;
        return p;
    }

    /** 官方 dialog_play_end 按钮：40sp、80 高、左右 44 内边距、圆角 62、聚焦白底深色字。 */
    private TextView playEndButton(String label, final Runnable action) {
        final TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 40);
        b.setTextColor(0xFFFFFFFF);
        b.setIncludeFontPadding(false);
        b.setGravity(android.view.Gravity.CENTER);
        b.setPadding(44, 0, 44, 0);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        b.setBackgroundDrawable(roundRect(0xB2525459, 62));
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                b.setBackgroundDrawable(roundRect(hasFocus ? 0xFFFFFFFF : 0xB2525459, 62));
                b.setTextColor(hasFocus ? 0xFF101114 : 0xFFFFFFFF);
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                action.run();
            }
        });
        return b;
    }

    private LinearLayout.LayoutParams playEndButtonLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 80);
        p.leftMargin = 20;
        p.rightMargin = 20;
        return p;
    }

    /** 与详情页一致：新开一个播放器 Activity，退出前上报当前集进度。 */
    private void openEpisode(MediaItem ep) {
        L.i("Player", "切换剧集 -> " + ep.displayTitle());
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("item", Json.stringify(ep.toJson()));
        App.from(this).config().saveLastItem(Json.stringify(ep.toJson()));
        reportOnExit(false);
        startActivity(i);
        finish();
    }

    private void showEpisodesMenu() {
        if (!episodesLoaded) {
            osdNotice.setText("正在读取剧集…");
            return;
        }
        if (siblingEpisodes.size() <= 1) {
            Ui.toast(this, "当前条目没有选集");
            return;
        }
        String[] labels = new String[siblingEpisodes.size()];
        for (int i = 0; i < siblingEpisodes.size(); i++) {
            MediaItem ep = siblingEpisodes.get(i);
            String name = ep.episodeCount > 0 ? "第 " + ep.episodeCount + " 集" : ep.displayTitle();
            labels[i] = (i == episodeAt ? "▶ " : "　 ") + name;
        }
        showMenu(new AlertDialog.Builder(this)
                .setTitle("选集")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == episodeAt) {
                            return;
                        }
                        openEpisode(siblingEpisodes.get(which));
                    }
                })
                .setNegativeButton("取消", null));
    }

    /** 字幕/清晰度切换后重建播放会话并从当前位置续播。 */
    private void rebuildPlayback(String notice) {
        int pos = 0;
        try {
            if (player != null) {
                pos = (int) toAbsolutePositionMs(player.getPositionMs());
            }
        } catch (Throwable ignored) {
        }
        resumeAtMs = pos;
        // 先停心跳：旧会话不再被续命，配合 buildPlan 里的 quitSession 释放 NAS 转码槽。
        if (sessionKeeper != null) {
            sessionKeeper.stopHeartbeat();
        }
        releasePlayer();
        stallRecoveries = 0;
        degradeLevel = 0;
        userPaused = false;
        startRequested = false;
        prepared = false;
        osdNotice.setText(notice);
        loading.setText("正在应用设置…");
        showLoading();
        showOsd();
        buildPlan();
    }

    /**
     * 起播看门狗（渐进等待）。
     *
     * 判据不再只看时间，而是问本地代理：
     *  - 有分片请求在途且已等待 >1 秒 → NAS 正在转码预热，继续等；
     *  - 距上次收到媒体字节 <16 秒 → 还在出数据，继续等；
     *  - 两者都不满足且已过 16 秒 → 真的没数据，交给回退状态机重试/换流；
     *  - 48 秒硬上限兜底。
     */
    private final Runnable prepareWatchdog = new Runnable() {
        public void run() {
            if (prepared || player == null) {
                return;
            }
            if (liveMode) {
                // 直播不经 NAS 鉴权代理，判据直接问直播代理：
                // 分片连续失败（实测 EO 线路必然 ECONNRESET）或长时间没有任何字节，
                // 就立刻判这条线路不可用，交给回退链换源，不必像点播那样等 16 秒。
                long el = System.currentTimeMillis() - prepareStartedAt;
                LiveProxy lp = LiveProxy.get();
                if (lp.isLineDead(currentPlaylistUrl)) {
                    L.w("Player", "直播线路分片连续失败，判定不可用（" + (el / 1000L) + "s）");
                    // 先释放这台内核：它随时可能迟到地报 onPrepared，会把状态机标成
                    // 「已成功」，之后同一台内核再抛的错误就会直接 ABORT 掉整个计划。
                    releasePlayer();
                    if (fallback != null) {
                        fallback.onFailure(FailureReason.PLAYBACK_FAILED, "直播线路连续取分片失败");
                    }
                    return;
                }
                long idle = lp.idleSinceLastByteMs(currentPlaylistUrl);
                if (idle >= LIVE_PREPARE_IDLE_MS) {
                    L.w("Player", "直播线路 " + (idle / 1000L) + " 秒没有分片数据，判定不可用");
                    releasePlayer();
                    if (fallback != null) {
                        fallback.onFailure(FailureReason.PLAYBACK_FAILED,
                                "直播线路 " + (idle / 1000L) + " 秒无数据");
                    }
                    return;
                }
                loading.setText("正在缓冲直播画面… " + (el / 1000L) + "s");
                ui.postDelayed(this, 1000L);
                return;
            }
            long elapsed = System.currentTimeMillis() - prepareStartedAt;
            // 16s/48s 这套阈值是给 MediaPlayer 调的（它就绪后立刻持续拉流）。
            // ExoPlayer 先解析主列表、再按缓冲水位逐段请求，NAS 冷转码时
            // 代理侧的「已交付字节」会长时间不动，沿用旧阈值会把正常预热的会话误杀。
            boolean legacyPlayer = player.needsHlsClockCorrection();
            long idleLimit = legacyPlayer ? PREPARE_IDLE_LIMIT_MS : PREPARE_IDLE_LIMIT_MS * 4;
            long hardCap = legacyPlayer ? PREPARE_HARD_CAP_MS : PREPARE_HARD_CAP_MS * 3;
            AuthProxy.MediaStatus st = authProxy == null ? null : authProxy.mediaStatus();
            boolean inFlight = st != null && st.inFlight > 0;
            // idleMs 用 Long.MAX 表示「还没有任何请求」；此时按已耗时计，避免
            // 刚起播尚未发出请求的第一帧就被当成「长时间无媒体活动」误杀。
            long idle = (st == null || st.idleMs < 0L || st.idleMs == Long.MAX_VALUE)
                    ? elapsed : st.idleMs;
            // 明确的内核初始化失败：ExoPlayer 连第一次取流请求都没发出来。
            // 老平台的 codec 初始化会在 MediaCodec.start() 里卡死（例如 MStar/UMP
            // gralloc 分配失败后 ACodec 不回调也不抛错），内核不会上报任何错误。
            // 这里的判据不是「慢」而是「一条请求都没有」：只要引擎碰过 URL，
            // 代理的请求计数就会增加，正常冷转码只会慢在首片，不会零请求。
            // 老设备上 MediaCodec 能力枚举单次可达 20 秒以上（mediaserver 里逐个
            // allocateNode），这段零请求是正常的，最多宽限 45 秒；超过仍卡住才判定失败。
            if (!legacyPlayer && "exoplayer2".equals(player.name())
                    && Exo2Engine.isCodecQueryInProgress()
                    && Exo2Engine.codecQueryAgeMs() < 45000L) {
                ui.postDelayed(this, PREPARE_STAGE_MS);
                return;
            }
            if (!legacyPlayer && !"JELLYFIN".equalsIgnoreCase(cfg.apiMode)
                    && elapsed >= ENGINE_START_GRACE_MS && st != null
                    && !inFlight && st.requests - mediaRequestsAtOpen <= 0) {
                EngineFactory.markEngineBroken(player.name(),
                        "启动 " + (elapsed / 1000L) + " 秒未发出取流请求");
                L.w("Player", "内核 " + player.name() + " 启动 " + (elapsed / 1000L)
                        + " 秒未发出任何取流请求（codec/renderer 初始化失败），交回退链处理");
                if (switchToAlternateEngine("内核启动 " + (elapsed / 1000L)
                        + " 秒未发出取流请求（codec/renderer 初始化失败）")) {
                    return;
                }
                if (fallback != null) {
                    fallback.onFailure(FailureReason.DECODER_ERROR,
                            "内核启动 " + (elapsed / 1000L) + " 秒未发出取流请求"
                                    + "（codec/renderer 初始化失败）");
                }
                return;
            }
            if (elapsed >= hardCap) {
                // MStar 等老平台：mstplayer 要攒够一段才回调 prepared，而 NAS 冷转码
                // 首片实测能到 40 秒以上（回落到 MediaPlayer 时更明显）。只要代理
                // 还有在途请求或刚收到过字节，就把上限放宽到 144 秒，避免把正常
                // 预热的会话判死——这正是上一轮 19:15 误杀的直接原因。
                if (legacyPlayer && elapsed < PREPARE_HARD_CAP_MS * 3
                        && (inFlight || idle < idleLimit)) {
                    loading.setText("NAS 正在转码，等待首段就绪… " + (elapsed / 1000L) + "s");
                    L.i("Player", "prepare 超过 " + (elapsed / 1000L) + " 秒但 NAS 仍在出片"
                            + "（在途=" + (inFlight ? "1" : "0") + " 空闲=" + (idle / 1000L)
                            + "s），继续等待");
                    ui.postDelayed(this, PREPARE_STAGE_MS);
                    return;
                }
                L.w("Player", "prepareAsync 超过 " + (elapsed / 1000L) + " 秒仍未 prepared："
                        + "在途请求=" + (inFlight ? "1" : "0") + " 空闲=" + (idle / 1000L)
                        + "s 请求数=" + (st == null ? -1 : st.requests)
                        + " 失败=" + (st == null ? -1 : st.failures));
                if (fallback != null) {
                    fallback.onFailure(FailureReason.PLAYBACK_FAILED,
                            "prepareAsync 超过 " + (elapsed / 1000L) + " 秒无媒体数据");
                }
                return;
            }
            if (inFlight && st.waitingMs >= 1000L) {
                loading.setText("NAS 正在转码，等待首段就绪… " + (elapsed / 1000L) + "s");
                L.i("Player", "prepare 等待中：首片请求在途 " + (st.waitingMs / 1000L)
                        + "s，累计 " + (elapsed / 1000L) + "s（NAS 转码预热）");
                ui.postDelayed(this, PREPARE_STAGE_MS);
                return;
            }
            if (idle < idleLimit) {
                L.i("Player", "prepare 等待中：媒体流量空闲 " + (idle / 1000L)
                        + "s，累计 " + (elapsed / 1000L) + "s，继续等待");
                ui.postDelayed(this, PREPARE_STAGE_MS);
                return;
            }
            // 媒体请求已经发出，但这么久一帧都没出：老平台上是 codec 初始化卡死
            // （MStar/UMP gralloc 分配返回 -22 后 ACodec 既不回调也不抛错），
            // 与「缓冲慢」不同——慢缓冲至少会推进位置或有首帧。按解码失败处理，
            // 让回退链别再原地重试同一个内核。
            if (!legacyPlayer && lastProgressMs <= 0L) {
                EngineFactory.markEngineBroken(player.name(),
                        "首帧未出 " + (elapsed / 1000L) + " 秒");
                L.w("Player", "内核 " + player.name() + " 已取流但 " + (elapsed / 1000L)
                        + " 秒零首帧（codec/渲染初始化失败）");
                if (switchToAlternateEngine("内核 " + (elapsed / 1000L) + " 秒未出首帧（codec 初始化失败）")) {
                    return;
                }
                if (fallback != null) {
                    fallback.onFailure(FailureReason.DECODER_ERROR,
                            "内核 " + (elapsed / 1000L) + " 秒未出首帧（codec 初始化失败）");
                }
                return;
            }
            L.w("Player", "prepareAsync 无媒体活动 " + (idle / 1000L) + "s（累计 "
                    + (elapsed / 1000L) + "s），判定当前流不可用");
            if (fallback != null) {
                fallback.onFailure(FailureReason.PLAYBACK_FAILED,
                        "prepareAsync 等待 " + (elapsed / 1000L) + " 秒无媒体数据");
            }
        }
    };

    /**
     * 播放进度看门狗。真机 API 17 上出现
     * [OMX.MS.AVC.Decoder] Timed out waiting for output buffers: 0/4 时，
     * MediaPlayer 既不回调 onError 也不回调 onCompletion，画面静止、
     * getCurrentPosition() 不再推进。每 5 秒采样一次，连续 3 次（约 15 秒）
     * 无推进即判定解码停滞，自动降级重建会话，避免只能靠用户拔电源。
     */
    private final Runnable progressWatchdog = new Runnable() {
        public void run() {
            if (player == null || !prepared || userPaused || recovering) {
                scheduleProgressCheck();
                return;
            }
            long pos = -1L;
            try {
                pos = toAbsolutePositionMs(player.getPositionMs());
            } catch (Throwable ignored) {
            }
            long now = SystemClock.elapsedRealtime();
            if (engineBuffering) {
                if (bufferingSinceMs <= 0L
                        || now - bufferingSinceMs < MEDIA_ACTIVE_STALL_GRACE_MS) {
                    showLoading();
                    stalledTicks = 0;
                    stalledSinceMs = 0L;
                    scheduleProgressCheck();
                    return;
                }
                engineBuffering = false;
                bufferingSinceMs = 0L;
            }
            if (seekPending && pos >= 0L && pendingSeekPositionMs >= 0L
                    && Math.abs(pos - pendingSeekPositionMs) <= 1500L) {
                seekPending = false;
            }
            if (seekPending && now - seekStartedAtMs >= SEEK_PENDING_TIMEOUT_MS) {
                seekPending = false;
            }
            if (seekPending || now < seekWatchdogGraceUntilMs) {
                boolean moved = pos >= 0L
                        && (lastProgressMs < 0L || Math.abs(pos - lastProgressMs) > 400L);
                if (moved) {
                    lastProgressMs = pos;
                    if (!engineBuffering) {
                        hideLoading();
                    }
                } else if (seekPending || isPlayerWaiting()) {
                    showLoading();
                }
                stalledTicks = 0;
                stalledSinceMs = 0L;
                scheduleProgressCheck();
                return;
            }
            if (pos >= 0L && lastProgressMs >= 0L && pos < lastProgressMs - 1500L) {
                lastProgressMs = pos;
                stalledTicks = 0;
                stalledSinceMs = 0L;
                showLoading();
                scheduleProgressCheck();
                return;
            }
            if (pos >= 0 && (lastProgressMs < 0 || pos > lastProgressMs + 400)) {
                lastProgressMs = pos;
                stalledTicks = 0;
                stalledSinceMs = 0L;
                if (!engineBuffering) {
                    hideLoading();
                }
                // 稳定播放超过 30 秒后，原档重建名额重新可用，避免长片只遇到一次
                // 瞬时错误后就永久失去「原档重试」保护。
                if (sameLevelRetries > 0
                        && System.currentTimeMillis() - healthySinceMs >= HEALTHY_RESET_MS) {
                    sameLevelRetries = 0;
                }
            } else {
                if (stalledSinceMs <= 0L) {
                    stalledSinceMs = now;
                }
                boolean mediaActive = isMediaActive();
                if (mediaActive && now - stalledSinceMs < MEDIA_ACTIVE_STALL_GRACE_MS) {
                    if (isPlayerWaiting()) {
                        showLoading();
                    }
                    stalledTicks = 0;
                    scheduleProgressCheck();
                    return;
                }
                stalledTicks++;
                L.w("Player", "播放进度未推进 " + stalledTicks + "/3（pos=" + pos
                        + " last=" + lastProgressMs + "）");
            }
            if (stalledTicks >= 3) {
                stalledTicks = 0;
                recoverFromStall(pos, false);
                return;
            }
            scheduleProgressCheck();
        }
    };

    private boolean isPlayerWaiting() {
        if (liveMode || authProxy == null) {
            return false;
        }
        AuthProxy.MediaStatus status = authProxy.mediaStatus();
        return status != null && status.inFlight > 0
                && status.waitingMs >= BUFFER_UI_REQUEST_MS;
    }

    private boolean isMediaActive() {
        if (liveMode) {
            return LiveProxy.get().idleSinceLastByteMs(currentPlaylistUrl) < PREPARE_IDLE_LIMIT_MS;
        }
        AuthProxy.MediaStatus status = authProxy == null ? null : authProxy.mediaStatus();
        return status != null
                && (status.inFlight > 0
                || (status.idleMs >= 0L && status.idleMs < PREPARE_IDLE_LIMIT_MS));
    }

    private void scheduleProgressCheck() {
        ui.removeCallbacks(progressWatchdog);
        ui.postDelayed(progressWatchdog, 5000L);
    }

    /**
     * 解码停滞 / 会话失效的分级自愈。
     *
     * 两类触发源：
     *  (1) progressWatchdog 连续 15 秒无进度 → OMX output buffer 超时；
     *  (2) 上游 410 Gone 如实回给播放器后触发 onError → 走回退状态机。
     *
     * 二者共同点：旧的 play_link 已经不可再用，第一动作必须是重新
     * POST /play/play 生成新会话（buildPlan 内部会做），而不是把旧 URL 直接
     * 交给外部系统播放器。真机 60 秒干净观察里，旧实现正是卡在这里：
     * 「检测到停滞 → 第 4 次 → 启动系统播放器 → preset.m3u8 410 → error(1,0)」。
     *
     * 计数按「本次连续卡顿」而不是整个 Activity 生命周期：
     * 只要中间稳定播放超过 HEALTHY_RESET_MS，就把计数归零，长片播放到第 10 分钟
     * 偶发一次过期也能重新走完整恢复链。
     *
     * @param sessionDead true 表示已确认是会话过期（不是解码能力问题），不降画质
     */
    private void recoverFromStall(long pos, boolean sessionDead) {
        if (recovering) {
            return;
        }
        // 直播没有「降画质重转码」这一说：一条线路长时间无数据就直接换下一条，
        // 全部线路都失败才由回退状态机给出最终提示。
        if (liveMode) {
            L.w("Player", "直播画面停滞，切换下一条线路");
            showNotice("信号中断，正在切换线路…");
            recovering = false;
            // 这台内核已经卡死，但可能迟到地补一个 onPrepared；那样状态机会被标成
            // 「已成功」，同一条线路随后的错误就会直接 ABORT。先释放，回调自然作废。
            releasePlayer();
            if (fallback != null) {
                fallback.onDecoderStall("直播线路长时间无数据");
            } else {
                rebuildPlanForRecovery();
            }
            return;
        }
        recovering = true;
        resumeAtMs = Math.max(0L, pos);
        long healthyMs = System.currentTimeMillis() - healthySinceMs;
        if (!sessionDead && healthyMs >= HEALTHY_RESET_MS && stallRecoveries > 0) {
            L.i("Player", "已稳定播放 " + (healthyMs / 1000L)
                    + " 秒，重置连续停滞计数（原 " + stallRecoveries + "）");
            stallRecoveries = 0;
        }
        if (sessionDead) {
            // 会话过期是可自愈的环境变化，与画质/音轨无关，不降档、不累计解码失败。
            stallRecoveries = 0;
            L.w("Player", "播放会话已失效，重建 play_link 并续播 @ " + resumeAtMs + "ms");
            showNotice("播放地址已过期，正在重新连接并续播…");
            rebuildPlanForRecovery();
            return;
        }

        stallRecoveries++;
        L.w("Player", "检测到解码停滞 @ " + resumeAtMs + "ms，第 "
                + stallRecoveries + " 次自动恢复");
        if (stallRecoveries <= MAX_STALL_RECOVERIES) {
            // 第一次卡顿先在当前画质/音轨原档重建：多数 40 秒会话重建后的卡顿只掉一帧，
            // 直接降档会让用户一次抖动就掉到 720P，观感断崖式变糊。
            boolean sameLevel = stallRecoveries == 1;
            if (!sameLevel) {
                if (bitrateController != null) {
                    cfg.transcodeBitrateBps = bitrateController.onStall(SystemClock.uptimeMillis());
                    L.i("Player", "播放卡顿，码率降至 " + cfg.transcodeBitrateBps + " bps");
                }
                degradeLevel = Math.min(3, Math.max(degradeLevel + 1, stallRecoveries - 1));
                sameLevelRetries = 0;
            }
            showNotice(sameLevel ? "播放卡顿：正在按原画质重连并续播…"
                    : "播放卡顿：正在降低画质/音轨规格并续播（第 " + stallRecoveries + " 次）");
            L.w("Player", sameLevel ? "先按原档重建一次（不降档）"
                    : "降级到第 " + degradeLevel + " 档重建");
            rebuildPlanForRecovery();
            return;
        }
        recovering = false;
        L.w("Player", "连续 " + MAX_STALL_RECOVERIES
                + " 次降级仍卡死，交给回退状态机换播放方式（外部地址会先重建会话）");
        if (fallback != null) {
            fallback.onDecoderStall("解码器长时间无响应（OMX output buffer 超时），"
                    + "已尝试 " + MAX_STALL_RECOVERIES + " 次画质/音轨降级仍无法继续");
        } else {
            host.fatal(FailureReason.DECODER_TIMEOUT, "解码器无响应，且没有可用的备选播放方式");
        }
    }

    /**
     * 解码/格式类硬失败后按 NAS 挡位再降一档重建会话。
     * 设备能力只在真正解码时才暴露不足（如 4K H.264 → error(1,-5003)），
     * 旧逻辑此时已没有静态备选候选，只能弹失败框；这里先降档重试。
     */
    private boolean tryDegradeAfterFailure(FailureReason reason, String detail) {
        if (liveMode) {
            // 直播没有 NAS 转码挡位可降：解码失败基本是线路数据残缺（实测 EO 分片
            // 经常被 CDN 重置，解复用后报 error(1,-5003)），降画质只会重建同一批线路。
            // 交给回退状态机换下一条线路。
            return false;
        }
        if (reason == null || !reason.isCodecFailure()
                || reason == FailureReason.NO_ORIGINAL_STREAM) {
            return false;
        }
        if (degradeLevel >= MAX_DEGRADE_LEVEL) {
            return false;
        }
        if (sameLevelRetries < 1) {
            // -5003 常常是会话/解码器未完全释放、或上游短暂 502 伪装成解码失败。
            // 先在同一档位完整释放后重建一次，避免 720→540→480 的连续降档级联。
            sameLevelRetries++;
            stallRecoveries = 0;
            fatalShown = false;
            userPaused = false;
            startRequested = false;
            planReady = false;
            resumeAtMs = lastProgressMs > 0 ? lastProgressMs : lastStartPositionMs;
            L.w("Player", "解码失败，先按原档重建一次（不降档）：" + detail);
            showNotice("播放异常，正在按原画质重试…");
            releasePlayer();
            buildPlan();
            return true;
        }
        sameLevelRetries = 0;
        if (bitrateController != null) {
            cfg.transcodeBitrateBps = bitrateController.onStall(SystemClock.uptimeMillis());
            cfg.forceTranscode = true;
            L.i("Player", "解码失败，码率降至 " + cfg.transcodeBitrateBps + " bps 后重建");
        }
        degradeLevel++;
        stallRecoveries = 0;
        fatalShown = false;
        userPaused = false;
        startRequested = false;
        planReady = false;
        // 还没出过画面时（内核初始化就失败）没有进度可续，沿用本次请求的起播位置，
        // 避免降档重建后从片头重放。
        resumeAtMs = lastProgressMs > 0 ? lastProgressMs : lastStartPositionMs;
        L.w("Player", "解码失败，降级到第 " + degradeLevel + " 档重建播放方案：" + detail);
        showNotice("解码失败，正在降低画质/音轨后重试（第 " + degradeLevel + " 档）");
        releasePlayer();
        buildPlan();
        return true;
    }

    /**
     * 内核级回落：厂商 MediaPlayer 在真正解码/渲染时报错（且框架 MediaCodec 可用），
     * 或 ExoPlayer 出错需要退回厂商管线时，在同一条流、同一进度上换内核再试一次。
     *
     * <p>只在明确的解码/渲染类错误上触发（不含超时、缓冲慢、网络错误），每部片只回落一次；
     * 回落沿用当前候选流与本地鉴权代理地址，不重建选流计划、不发新的 /play/play，
     * 因此字幕（HlsVtt + libass）与 OSD 状态保持不变。
     *
     * <p>151 没有 media.codec 服务，{@link EngineSelector#canFallbackToExo()} 为 false，
     * 厂商内核失败后不会切 Exo（只会卡在 MediaCodec 初始化），直接交给降档与停滞自愈。
     */
    private boolean tryAlternateEngineFallback(FailureReason reason, String detail) {
        if (engineFallbackUsed) {
            return false;
        }
        PlayerEngine cur = player;
        if (cur == null) {
            return false;
        }
        if (EngineSelector.MEDIA_PLAYER.equals(cur.name())
                && !EngineSelector.canFallbackToExo()) {
            return false;
        }
        if (lastCandidate == null || lastCandidate.url == null || lastCandidate.url.length() == 0) {
            return false;
        }
        // 排除了 DECODER_TIMEOUT：卡顿/无响应不属于「硬解不可用」，继续交给降档与停滞自愈。
        if (reason != FailureReason.DECODER_ERROR
                && reason != FailureReason.UNSUPPORTED_FORMAT
                && reason != FailureReason.AUDIO_DECODER_ERROR) {
            return false;
        }
        return switchToAlternateEngine(detail);
    }

    /**
     * 立刻换另一个内核接管当前候选流（同一条代理地址、同一进度）：
     * 厂商 MediaPlayer ↔ ExoPlayer 二选一。
     *
     * <p>用于「内核硬解不可用」这类与码率/分辨率无关的失败：原地降档只会再搭一个
     * 冷启动的 NAS 会话，既慢又不解决问题，所以这里直接换内核；降档逻辑仍然保留给
     * 真正的编码/规格不兼容（{@link #tryDegradeAfterFailure}）。
     */
    private boolean switchToAlternateEngine(String detail) {
        if (engineFallbackUsed) {
            return false;
        }
        PlayerEngine cur = player;
        if (cur == null) {
            return false;
        }
        boolean toExo = EngineSelector.MEDIA_PLAYER.equals(cur.name());
        if (toExo && !EngineSelector.canFallbackToExo()) {
            return false;
        }
        if (lastCandidate == null || lastCandidate.url == null || lastCandidate.url.length() == 0) {
            return false;
        }
        engineFallbackUsed = true;
        long pos = lastProgressMs > 0 ? lastProgressMs : lastStartPositionMs;
        L.w("Player", "内核 " + cur.name() + " 解码/渲染失败，跳过降档切换为 "
                + (toExo ? "ExoPlayer" : "平台 MediaPlayer") + " 续播 @" + pos + "ms：" + detail);
        showNotice(toExo
                ? "平台播放器无法硬解，已切换 ExoPlayer 续播"
                : "当前内核无法硬解，已切换为平台播放器续播");
        resumeAtMs = Math.max(0L, pos);
        if (toExo) {
            forceExoEngine = true;
        } else {
            forcePlatformEngine = true;
        }
        startPlayback(lastCandidate);
        return true;
    }

    private void usePlaylistWindowFallback(long absolutePositionMs) {
        if (!fnosLongSegments) {
            return;
        }
        fnosLongSegments = false;
        playlistWindow = (int) (Math.max(0L, absolutePositionMs) / FNOS_PLAYLIST_WINDOW_MS);
        playlistOffsetMs = (long) playlistWindow * FNOS_PLAYLIST_WINDOW_MS;
        resumeAtMs = Math.max(0L, absolutePositionMs);
        recovering = true;
        L.w("Player", "虚拟长分片不可用，切换窗口兼容模式 @" + resumeAtMs + "ms");
        resetSubtitleTimeline();
        releasePlayer();
        prepared = false;
        startRequested = false;
        planReady = false;
        showNotice("虚拟长分片不可用，正在切换兼容模式…");
        buildPlan();
    }

    private void advancePlaylistWindow(long absolutePositionMs) {
        switchPlaylistWindow(playlistWindow + 1,
                Math.max(playlistOffsetMs + FNOS_PLAYLIST_WINDOW_MS, absolutePositionMs));
    }

    private void switchPlaylistWindow(int window, long absolutePositionMs) {
        if (recovering) {
            return;
        }
        recovering = true;
        playlistWindow = window;
        playlistOffsetMs = (long) playlistWindow * FNOS_PLAYLIST_WINDOW_MS;
        resumeAtMs = absolutePositionMs;
        L.w("Player", "切换 MStar 分片窗口 " + playlistWindow + " @" + resumeAtMs + "ms");
        resetSubtitleTimeline();
        releasePlayer();
        prepared = false;
        startRequested = false;
        planReady = false;
        loading.setText("正在续播下一段…");
        showLoading();
        buildPlan();
    }

    /** 释放播放器并按新的转码参数重新创建播放会话，续播到 resumeAtMs。 */
    private void rebuildPlanForRecovery() {
        releasePlayer();
        startRequested = false;
        planReady = false;
        buildPlan();
    }

    private void showOptions() {
        String[] opts = {"切换为 NAS 解码流", "重新加载播放方案", "重新检测设备能力", "返回片库"};
        showMenu(new AlertDialog.Builder(this)
                .setTitle("播放设置")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                if (fallback != null) {
                                    fallback.onFailure(FailureReason.DECODER_ERROR, "用户在菜单里手动切换");
                                }
                                break;
                            case 1:
                                releasePlayer();
                                stallRecoveries = 0;
                                degradeLevel = 0;
                                resumeAtMs = 0L;
                                playlistWindow = 0;
                                playlistOffsetMs = 0L;
                                userPaused = false;
                                startRequested = false;
                                buildPlan();
                                break;
                            case 2:
                                App.from(PlayerActivity.this).config().invalidateCapability();
                                Intent i = new Intent(PlayerActivity.this, BootstrapActivity.class);
                                i.putExtra("redetect", true);
                                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                startActivity(i);
                                finish();
                                break;
                            default:
                                finish();
                        }
                    }
                }));
    }

    protected void onPause() {
        super.onPause();
        if (seekApplyScheduled) {
            ui.removeCallbacks(applyPendingSeek);
            applyPendingSeek.run();
        }
        stopSeekHold();
        if (player != null && player.isPlaying()) {
            player.pause();
            userPaused = true; // 切后台不能算解码停滞
        }
    }

    protected void onResume() {
        super.onResume();
        if (externalLaunched) {
            externalLaunched = false;
            planGeneration++;
            detachPlaybackSession();
            sessionKeeper.endSessionAsync();
            stopSubtitleWork();
            releasePlayer();
            finish();
            return;
        }
        if (backgroundStopped) {
            backgroundStopped = false;
            userPaused = false;
            startRequested = false;
            prepared = false;
            showLoading();
            loading.setText("正在恢复播放…");
            buildPlan();
            ui.post(progressTicker);
            ui.post(subtitleTicker);
            return;
        }
        userPaused = false;
        lastProgressMs = -1L;
        stalledTicks = 0;
        stalledSinceMs = 0L;
        bufferUiStalledSinceMs = 0L;
        if (engineBuffering) {
            bufferingSinceMs = SystemClock.elapsedRealtime();
        }
        if (player != null && prepared) {
            try {
                if (!player.isPlaying()) {
                    player.play();
                }
            } catch (Throwable ignored) {
            }
            scheduleProgressCheck();
        }
    }

    protected void onStop() {
        super.onStop();
        if (externalLaunched) {
            if (authProxy != null) {
                authProxy.releaseSessionWhenIdle(60000L);
            }
            return;
        }
        if (isFinishing() || destroyed) {
            return;
        }
        backgroundStopped = true;
        try {
            if (player != null) {
                long pos = toAbsolutePositionMs(player.getPositionMs());
                if (pos > 0L) {
                    resumeAtMs = pos;
                    lastProgressMs = pos;
                }
            }
        } catch (Throwable ignored) {
        }
        planGeneration++;
        planReady = false;
        startRequested = false;
        prepared = false;
        ui.removeCallbacks(progressTicker);
        ui.removeCallbacks(subtitleTicker);
        detachPlaybackSession();
        String current = sessionKeeper == null ? "" : sessionKeeper.currentPlayLink();
        if (sessionKeeper != null) {
            sessionKeeper.endSessionAsync();
        }
        endPlanSessions(plan, current);
        releasePlayer();
        stopSubtitleWork();
    }

    protected void onDestroy() {
        destroyed = true;
        planGeneration++;
        reportOnExit(false);
        dismissOsdDialog();
        hideErrorPanel();
        stopSeekHold();
        ui.removeCallbacks(seekIndicatorClear);
        ui.removeCallbacks(osdAutoHide);
        ui.removeCallbacks(progressTicker);
        ui.removeCallbacks(subtitleTicker);
        ui.removeCallbacks(prepareWatchdog);
        ui.removeCallbacks(progressWatchdog);
        if (sessionKeeper != null) {
            sessionKeeper.clearExpiredHook();
            sessionKeeper.clearRenewedHook();
            if (!externalLaunched) {
                detachPlaybackSession();
                String current = sessionKeeper.currentPlayLink();
                sessionKeeper.endSessionAsync();
                endPlanSessions(plan, current);
            }
        } else if (authProxy != null && !externalLaunched) {
            detachPlaybackSession();
        }
        if (externalLaunched && authProxy != null) {
            authProxy.releaseSessionWhenIdle(60000L);
        }
        releasePlayer();
        stopSubtitleWork();
        hlsClock.stop();
        if (libass != null) {
            libass.release();
            libass = null;
        }
        super.onDestroy();
    }
}
