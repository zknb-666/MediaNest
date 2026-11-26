package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import com.juying.medianest.app.App;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.ui.ImageLoader;
import com.juying.medianest.domain.repository.ConfigRepository;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.policy.QualityBadges;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.domain.usecase.MediaUseCases;

import java.util.ArrayList;
import java.util.List;

/**
 * 官方飞牛 TV 1.5.1 搜索页复刻。
 *
 * 左侧 666px：搜索框、清空、退格、6×6 字母数字键盘、系统键盘。
 * 右侧 1254px：搜索历史（含清空历史）或结果网格（4 列 252×481 卡片）。
 * 尺寸全部来自 1920×1080 参考机实测；输入即时请求 /v/api/v1/search/list。
 */
public final class OfficialSearchActivity extends Activity {

    private static final int COLOR_BG = 0xFF15171A;
    private static final int COLOR_CARD = 0xFF23242B;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_SECONDARY = 0xFFD0D1D1;
    private static final int COLOR_HINT = 0xFF707175;
    private static final int COLOR_MUTED = 0xFFA8A9AB;

    private static final int ID_KEYBOARD = 0x0F000001;
    private static final int ID_RESULTS = 0x0F000002;
    private static final int ID_HISTORY = 0x0F000003;

    private static final String KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";

    private final Handler handler = new Handler();
    private final List<MediaItem> results = new ArrayList<MediaItem>();
    private final List<String> history = new ArrayList<String>();
    private final StringBuilder query = new StringBuilder();

    private float scale;
    private ConfigRepository store;
    private ServerConfig cfg;
    private MediaUseCases api;
    private ImageLoader imageLoader;
    private Typeface icons;
    private SharedPreferences prefs;

    private TextView input;
    private GridView keyboard;

    private FrameLayout root;
    private FrameLayout historyPanel;
    private TextView historyTitle;
    private View historyClear;
    private LinearLayout chipRow;
    private ImageView historyEmpty;
    private HorizontalScrollView chipScroll;

    private FrameLayout resultsPanel;
    private TextView countLabel;
    private GridView resultsGrid;
    private ResultAdapter resultAdapter;
    private ImageView resultsEmpty;

    private FrameLayout errorOverlay;
    private TextView errorRetry;

    private int searchSeq;
    private Runnable pendingSearch;
    private boolean destroyed;
    /** 键盘最后聚焦的键位；官方从结果网格按左键会回到这个键。 */
    private int lastKeyPos = 14;
    private EditText imeInput;
    private boolean suppressIme;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scale = Math.max(0.1f, getResources().getDisplayMetrics().widthPixels / 1920f);
        store = App.from(this).config();
        ConfigCodec.ConfigBundle bundle = store.load();
        cfg = bundle.server;
        api = App.from(this).useCases(cfg);
        imageLoader = new ImageLoader(this);
        icons = Typeface.createFromAsset(getAssets(), "official_icons.ttf");
        prefs = getSharedPreferences("official_search", Context.MODE_PRIVATE);
        loadHistory();

        setContentView(buildRoot());
        refreshInput();
        showHistory(true);
        handler.post(new Runnable() {
            public void run() {
                focusDefaultKey();
            }
        });
    }

    protected void onPause() {
        super.onPause();
        if (imeInput != null) {
            imeInput.setVisibility(View.GONE);
            imeInput.setFocusable(false);
            imeInput.setFocusableInTouchMode(false);
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && imeInput != null) {
            imm.hideSoftInputFromWindow(imeInput.getWindowToken(), 0);
        }
    }

    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------- 布局 ----------------

    private int s(int designPx) {
        return Math.round(designPx * scale);
    }

    private float sf(int designPx) {
        return designPx * scale;
    }

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);
        this.root = root;

        root.addView(buildKeyboardPanel(), new FrameLayout.LayoutParams(s(666),
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(buildHistoryPanel(), panelParams());
        root.addView(buildResultsPanel(), panelParams());
        return root;
    }

    private FrameLayout.LayoutParams panelParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                s(1254), FrameLayout.LayoutParams.MATCH_PARENT);
        lp.leftMargin = s(666);
        return lp;
    }

    private View buildKeyboardPanel() {
        FrameLayout panel = new FrameLayout(this);

        // 搜索框：520×88 圆角胶囊，左侧放大镜 + 输入文本（官方 EditText 不可聚焦）。
        FrameLayout pill = new FrameLayout(this);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(COLOR_CARD);
        pillBg.setCornerRadius(sf(44));
        pill.setBackground(pillBg);
        TextView icon = glyph("\uEB23", 46, COLOR_HINT);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(s(48), s(48));
        iconLp.leftMargin = s(42);
        iconLp.topMargin = s(20);
        pill.addView(icon, iconLp);
        input = new TextView(this);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(36));
        input.setTextColor(COLOR_TEXT);
        input.setSingleLine(true);
        input.setEllipsize(TextUtils.TruncateAt.END);
        input.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams inputLp = new FrameLayout.LayoutParams(s(376), s(48));
        inputLp.leftMargin = s(121);
        inputLp.topMargin = s(20);
        pill.addView(input, inputLp);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(s(520), s(88));
        pillLp.leftMargin = s(96);
        pillLp.topMargin = s(96);
        panel.addView(pill, pillLp);

        View clear = buildPillButton("\uEB58", "清空", new View.OnClickListener() {
            public void onClick(View v) {
                query.setLength(0);
                refreshInput();
                showHistory(true);
            }
        });
        FrameLayout.LayoutParams clearLp = new FrameLayout.LayoutParams(s(253), s(64));
        clearLp.leftMargin = s(96);
        clearLp.topMargin = s(224);
        panel.addView(clear, clearLp);

        View backspace = buildPillButton("\uEAE1", "退格", new View.OnClickListener() {
            public void onClick(View v) {
                if (query.length() > 0) {
                    query.setLength(query.length() - 1);
                }
                refreshInput();
                if (query.length() == 0) {
                    showHistory(true);
                } else {
                    requestSearch();
                }
            }
        });
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(s(253), s(64));
        backLp.leftMargin = s(363);
        backLp.topMargin = s(224);
        panel.addView(backspace, backLp);

        keyboard = new GridView(this);
        keyboard.setId(ID_KEYBOARD);
        keyboard.setNumColumns(6);
        keyboard.setColumnWidth(s(76));
        keyboard.setHorizontalSpacing(s(12));
        keyboard.setVerticalSpacing(s(12));
        keyboard.setStretchMode(GridView.NO_STRETCH);
        keyboard.setSelector(new ColorDrawable(Color.TRANSPARENT));
        keyboard.setClipChildren(false);
        keyboard.setFocusable(true);
        keyboard.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        keyboard.setAdapter(new KeyAdapter());
        keyboard.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                appendKey(KEYS.charAt(position));
            }
        });
        FrameLayout.LayoutParams keyLp = new FrameLayout.LayoutParams(s(520), s(528));
        keyLp.leftMargin = s(96);
        keyLp.topMargin = s(328);
        panel.addView(keyboard, keyLp);

        TextView system = new TextView(this);
        system.setText("系统键盘");
        system.setGravity(Gravity.CENTER);
        system.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(34));
        system.setTextColor(0xFFB2B3B5);
        system.setFocusable(true);
        system.setClickable(true);
        system.setBackground(buttonBg(false));
        system.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                ((TextView) v).setBackground(buttonBg(hasFocus));
                ((TextView) v).setTextColor(hasFocus ? 0xFF15171A : 0xFFB2B3B5);
                v.startAnimation(hasFocus ? scaleAnim(1.1f) : scaleAnim(1f / 1.1f));
            }
        });
        system.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSystemKeyboard();
            }
        });
        FrameLayout.LayoutParams sysLp = new FrameLayout.LayoutParams(s(520), s(88));
        sysLp.leftMargin = s(96);
        sysLp.topMargin = s(896);
        panel.addView(system, sysLp);
        return panel;
    }

    private GradientDrawable buttonBg(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? 0xFFFFFFFF : COLOR_CARD);
        bg.setCornerRadius(sf(12));
        return bg;
    }

    private View buildPillButton(String glyphText, String labelText,
                                 View.OnClickListener click) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setFocusable(true);
        box.setClickable(true);
        final TextView icon = glyph(glyphText, 36, COLOR_MUTED);
        final TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(40));
        label.setTextColor(COLOR_MUTED);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(s(36), s(37));
        box.addView(icon, iconLp);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = s(16);
        box.addView(label, labelLp);
        box.setBackground(pillButtonBg(false));
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                box.setBackground(pillButtonBg(hasFocus));
                int color = hasFocus ? 0xFF15171A : COLOR_MUTED;
                icon.setTextColor(color);
                label.setTextColor(color);
                box.startAnimation(hasFocus ? scaleAnim(1.1f) : scaleAnim(1f / 1.1f));
            }
        });
        box.setOnClickListener(click);
        return box;
    }

    private GradientDrawable pillButtonBg(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? 0xFFFFFFFF : Color.TRANSPARENT);
        bg.setCornerRadius(sf(32));
        return bg;
    }

    private View buildHistoryPanel() {
        historyPanel = new FrameLayout(this);

        historyTitle = new TextView(this);
        historyTitle.setText("搜索历史");
        historyTitle.setTextColor(COLOR_TEXT_SECONDARY);
        historyTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(44));
        historyTitle.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(s(400), s(63));
        titleLp.leftMargin = s(30);
        titleLp.topMargin = s(96);
        historyPanel.addView(historyTitle, titleLp);

        TextView clear = new TextView(this);
        clear.setText("清空");
        clear.setGravity(Gravity.CENTER);
        clear.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(38));
        clear.setTextColor(COLOR_TEXT_SECONDARY);
        clear.setFocusable(true);
        clear.setClickable(true);
        clear.setBackground(historyClearBg(false));
        clear.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                ((TextView) v).setBackground(historyClearBg(hasFocus));
                ((TextView) v).setTextColor(hasFocus ? 0xFF15171A : COLOR_TEXT_SECONDARY);
                v.startAnimation(hasFocus ? scaleAnim(1.1f) : scaleAnim(1f / 1.1f));
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                history.clear();
                saveHistory();
                fillChips();
            }
        });
        FrameLayout.LayoutParams clearLp = new FrameLayout.LayoutParams(s(148), s(64));
        clearLp.leftMargin = s(1010);
        clearLp.topMargin = s(96);
        historyPanel.addView(clear, clearLp);
        historyClear = clear;

        chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setClipChildren(false);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setClipChildren(false);
        chipScroll.addView(chipRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(s(1128), s(96));
        chipLp.leftMargin = s(30);
        chipLp.topMargin = s(195);
        historyPanel.addView(chipScroll, chipLp);

        historyEmpty = new ImageView(this);
        historyEmpty.setImageResource(R.drawable.official_search_empty);
        FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(s(220), s(220));
        // 官方空态图在内容区（左 30 / 右 96 内边距）居中：[666+484,480] = [1150,480]
        emptyLp.leftMargin = s(484);
        emptyLp.topMargin = s(480);
        historyPanel.addView(historyEmpty, emptyLp);
        fillChips();
        return historyPanel;
    }

    private GradientDrawable historyClearBg(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? 0xFFFFFFFF : COLOR_CARD);
        bg.setCornerRadius(sf(32));
        return bg;
    }

    private View buildResultsPanel() {
        resultsPanel = new FrameLayout(this);

        countLabel = new TextView(this);
        countLabel.setTextColor(COLOR_TEXT_SECONDARY);
        countLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(40));
        countLabel.setGravity(Gravity.BOTTOM | Gravity.LEFT);
        countLabel.setPadding(0, 0, 0, s(20));
        FrameLayout.LayoutParams countLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, s(169));
        countLp.leftMargin = s(33);
        resultsPanel.addView(countLabel, countLp);

        resultsGrid = new GridView(this);
        resultsGrid.setId(ID_RESULTS);
        resultsGrid.setNumColumns(4);
        resultsGrid.setColumnWidth(s(252));
        resultsGrid.setHorizontalSpacing(s(40));
        resultsGrid.setVerticalSpacing(s(48));
        resultsGrid.setStretchMode(GridView.NO_STRETCH);
        resultsGrid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        resultsGrid.setPadding(s(30), s(40), s(96), s(40));
        resultsGrid.setClipChildren(false);
        resultsGrid.setClipToPadding(false);
        resultsGrid.setFocusable(true);
        resultsGrid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        resultAdapter = new ResultAdapter();
        resultsGrid.setAdapter(resultAdapter);
        resultsGrid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                openResult(position);
            }
        });
        FrameLayout.LayoutParams gridLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        gridLp.topMargin = s(169);
        resultsPanel.addView(resultsGrid, gridLp);

        resultsEmpty = new ImageView(this);
        resultsEmpty.setImageResource(R.drawable.official_search_empty);
        FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(s(220), s(220));
        // 官方结果空态图在整个右面板居中：[666+517,480] = [1183,480]
        emptyLp.leftMargin = s(517);
        emptyLp.topMargin = s(480);
        resultsEmpty.setVisibility(View.GONE);
        resultsPanel.addView(resultsEmpty, emptyLp);
        resultsPanel.setVisibility(View.GONE);
        return resultsPanel;
    }

    private TextView glyph(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTypeface(icons);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(size));
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        return view;
    }

    // ---------------- 键盘 ----------------

    private final class KeyAdapter extends BaseAdapter {
        public int getCount() {
            return KEYS.length();
        }

        public Object getItem(int position) {
            return String.valueOf(KEYS.charAt(position));
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(final int position, View convertView, ViewGroup parent) {
            final TextView cell;
            if (convertView instanceof TextView) {
                cell = (TextView) convertView;
            } else {
                cell = new TextView(OfficialSearchActivity.this);
                cell.setGravity(Gravity.CENTER);
                cell.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(40));
                cell.setFocusable(true);
                cell.setClickable(true);
                cell.setLayoutParams(new GridView.LayoutParams(s(76), s(76)));
            }
            cell.setText(String.valueOf(KEYS.charAt(position)));
            cell.setTag(Integer.valueOf(position));
            cell.setTextColor(COLOR_TEXT);
            cell.setBackground(keyBg(false));
            cell.setFocusable(true);
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    appendKey(KEYS.charAt(position));
                }
            });
            cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        lastKeyPos = position;
                    }
                    cell.setBackground(keyBg(hasFocus));
                    cell.setTextColor(hasFocus ? 0xFF15171A : COLOR_TEXT);
                    cell.startAnimation(hasFocus ? scaleAnim(1.10f) : scaleAnim(1f / 1.10f));
                }
            });
            return cell;
        }
    }

    private GradientDrawable keyBg(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? 0xFFFFFFFF : Color.TRANSPARENT);
        bg.setCornerRadius(sf(8));
        return bg;
    }

    private ScaleAnimation scaleAnim(float factor) {
        ScaleAnimation anim = new ScaleAnimation(1f, factor, 1f, factor,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        anim.setDuration(120L);
        anim.setFillAfter(true);
        return anim;
    }

    private void focusDefaultKey() {
        focusDefaultKey(0);
    }

    /** GridView 的子项要等布局完成才存在，这里重试几次直到焦点落在第 15 个键（O）。 */
    private void focusDefaultKey(final int attempt) {
        if (destroyed || attempt > 8) {
            return;
        }
        View focused = getCurrentFocus();
        Object tag = focused == null ? null : focused.getTag();
        if (tag instanceof Integer && ((Integer) tag).intValue() == 14) {
            return;
        }
        focusKey(14);
        handler.postDelayed(new Runnable() {
            public void run() {
                focusDefaultKey(attempt + 1);
            }
        }, 80L);
    }

    /** 聚焦键盘上指定位置的键（GridView 子项可能还没创建）。 */
    private void focusKey(final int position) {
        focusKey(position, 0);
    }

    private void focusKey(final int position, final int attempt) {
        if (destroyed || attempt > 3) {
            return;
        }
        View focused = getCurrentFocus();
        Object tag = focused == null ? null : focused.getTag();
        if (tag instanceof Integer && ((Integer) tag).intValue() == position) {
            return;
        }
        for (int i = 0; i < keyboard.getChildCount(); i++) {
            View child = keyboard.getChildAt(i);
            Object t = child.getTag();
            if (t instanceof Integer && ((Integer) t).intValue() == position) {
                keyboard.setSelection(position);
                child.requestFocus();
                break;
            }
        }
        handler.postDelayed(new Runnable() {
            public void run() {
                focusKey(position, attempt + 1);
            }
        }, 60L);
    }

    private void appendKey(char c) {
        if (query.length() >= 40) {
            return;
        }
        query.append(c);
        refreshInput();
        requestSearch();
    }

    private void refreshInput() {
        if (query.length() == 0) {
            input.setText("首字母 或 关键字");
            input.setTextColor(COLOR_HINT);
        } else {
            input.setText(query.toString());
            input.setTextColor(COLOR_TEXT);
        }
    }

    private void showSystemKeyboard() {
        if (imeInput == null) {
            imeInput = new EditText(this);
            imeInput.setBackground(null);
            imeInput.setVisibility(View.GONE);
            imeInput.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                public void afterTextChanged(android.text.Editable s) {
                    if (suppressIme) {
                        return;
                    }
                    query.setLength(0);
                    query.append(s.toString());
                    refreshInput();
                    if (query.length() == 0) {
                        showHistory(true);
                    } else {
                        requestSearch();
                    }
                }
            });
            FrameLayout root = (FrameLayout) findViewById(android.R.id.content);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1);
            root.addView(imeInput, lp);
        }
        suppressIme = true;
        imeInput.setText(query.toString());
        imeInput.setSelection(imeInput.getText().length());
        suppressIme = false;
        imeInput.setVisibility(View.VISIBLE);
        imeInput.setFocusable(true);
        imeInput.setFocusableInTouchMode(true);
        imeInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(imeInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    // ---------------- 搜索 ----------------

    private void requestSearch() {
        if (pendingSearch != null) {
            handler.removeCallbacks(pendingSearch);
        }
        pendingSearch = new Runnable() {
            public void run() {
                final String q = query.toString();
                if (q.length() == 0) {
                    return;
                }
                final int seq = ++searchSeq;
                new Thread(new Runnable() {
                    public void run() {
                        List<MediaItem> list = null;
                        try {
                            list = api.search(cfg, q);
                        } catch (RuntimeException ex) {
                            list = null;
                        } catch (com.juying.medianest.core.model.FnException ex) {
                            list = null;
                        }
                        final List<MediaItem> out = list;
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (!destroyed && seq == searchSeq) {
                                    if (out == null) {
                                        showErrorDialog();
                                    } else {
                                        applyResults(out);
                                    }
                                }
                            }
                        });
                    }
                }, "fntv-search").start();
            }
        };
        handler.postDelayed(pendingSearch, 260L);
    }

    private void applyResults(List<MediaItem> list) {
        hideErrorDialog();
        results.clear();
        results.addAll(list);
        resultAdapter.notifyDataSetChanged();
        showHistory(false);
        if (results.isEmpty()) {
            countLabel.setVisibility(View.GONE);
            resultsGrid.setVisibility(View.GONE);
            resultsEmpty.setVisibility(View.VISIBLE);
        } else {
            countLabel.setVisibility(View.VISIBLE);
            resultsGrid.setVisibility(View.VISIBLE);
            resultsEmpty.setVisibility(View.GONE);
            countLabel.setText("共 " + results.size() + " 个结果");
        }
    }

    private void showHistory(boolean show) {
        if (show) {
            hideErrorDialog();
        }
        historyPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        resultsPanel.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    // ---------------- 网络异常弹窗 ----------------

    /** 官方搜索失败时弹出：650×570 卡片 + Wi-Fi 感叹号图标 + 「网络异常」两行文案 + 白色「重试」。 */
    private void showErrorDialog() {
        if (errorOverlay == null) {
            errorOverlay = new FrameLayout(this);
            errorOverlay.setBackgroundColor(0x4D000000);
            errorOverlay.setFocusable(true);
            errorOverlay.setClickable(true);

            FrameLayout card = new FrameLayout(this);
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(0xFF2B2C31);
            cardBg.setCornerRadius(sf(16));
            card.setBackground(cardBg);

            ImageView net = new ImageView(this);
            net.setImageResource(R.drawable.official_net_error);
            FrameLayout.LayoutParams netLp = new FrameLayout.LayoutParams(s(180), s(180));
            netLp.leftMargin = s(235);
            netLp.topMargin = s(48);
            card.addView(net, netLp);

            TextView msg = new TextView(this);
            msg.setText("网络异常\n请检查网络设置后重试");
            msg.setTextColor(COLOR_TEXT);
            msg.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(44));
            msg.setGravity(Gravity.CENTER);
            msg.setLineSpacing(sf(12), 1f);
            FrameLayout.LayoutParams msgLp = new FrameLayout.LayoutParams(s(574), s(124));
            msgLp.leftMargin = s(38);
            msgLp.topMargin = s(258);
            card.addView(msg, msgLp);

            errorRetry = new TextView(this);
            errorRetry.setText("重试");
            errorRetry.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(32));
            errorRetry.setTextColor(0xFF15171A);
            errorRetry.setGravity(Gravity.CENTER);
            errorRetry.setFocusable(true);
            errorRetry.setClickable(true);
            GradientDrawable btn = new GradientDrawable();
            btn.setColor(0xFFFFFFFF);
            btn.setCornerRadius(sf(35));
            errorRetry.setBackground(btn);
            errorRetry.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    hideErrorDialog();
                    requestSearch();
                    focusDefaultKey();
                }
            });
            FrameLayout.LayoutParams retryLp = new FrameLayout.LayoutParams(s(596), s(70));
            retryLp.leftMargin = s(27);
            retryLp.topMargin = s(433);
            card.addView(errorRetry, retryLp);

            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(s(650), s(570));
            cardLp.gravity = Gravity.CENTER;
            errorOverlay.addView(card, cardLp);

            root.addView(errorOverlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        errorOverlay.setVisibility(View.VISIBLE);
        errorOverlay.requestFocus();
        errorRetry.requestFocus();
    }

    private void hideErrorDialog() {
        if (errorOverlay != null && errorOverlay.getVisibility() == View.VISIBLE) {
            errorOverlay.setVisibility(View.GONE);
        }
    }

    public void onBackPressed() {
        if (errorOverlay != null && errorOverlay.getVisibility() == View.VISIBLE) {
            hideErrorDialog();
            focusDefaultKey();
            return;
        }
        super.onBackPressed();
    }

    // ---------------- 搜索结果卡片 ----------------

    private final class ResultAdapter extends BaseAdapter {
        public int getCount() {
            return results.size();
        }

        public Object getItem(int position) {
            return results.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            MediaItem item = results.get(position);
            FrameLayout card = new FrameLayout(OfficialSearchActivity.this);
            card.setLayoutParams(new GridView.LayoutParams(s(252), s(481)));

            final FrameLayout frame = new FrameLayout(OfficialSearchActivity.this);
            GradientDrawable frameBg = new GradientDrawable();
            frameBg.setColor(COLOR_CARD);
            frameBg.setCornerRadius(sf(8));
            frame.setBackground(frameBg);
            ImageView image = new ImageView(OfficialSearchActivity.this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            frame.addView(image, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            if (item.posterPath != null && item.posterPath.length() > 0) {
                imageLoader.load(api.imageUrl(cfg, item.posterPath, 400), image,
                        api.authorizationHeader(cfg), R.drawable.official_video_placeholder);
            } else {
                image.setImageResource(R.drawable.official_video_placeholder);
            }
            View shadow = new View(OfficialSearchActivity.this);
            GradientDrawable shadowBg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0x50000000});
            shadowBg.setCornerRadii(new float[]{0, 0, 0, 0,
                    sf(8), sf(8), sf(8), sf(8)});
            shadow.setBackground(shadowBg);
            FrameLayout.LayoutParams shadowLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, s(93));
            shadowLp.gravity = Gravity.BOTTOM;
            frame.addView(shadow, shadowLp);
            addBadges(frame, item);
            final View border = new View(OfficialSearchActivity.this);
            updateSearchBorder(border, false);
            frame.addView(border, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            card.addView(frame, new FrameLayout.LayoutParams(s(252), s(378)));

            TextView title = new TextView(OfficialSearchActivity.this);
            title.setText(item.title == null ? "" : item.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(36));
            title.setTextColor(COLOR_TEXT);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setHorizontallyScrolling(true);
            title.setMarqueeRepeatLimit(-1);
            title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            title.setPadding(s(7), 0, 0, 0);
            FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, s(49));
            titleLp.topMargin = s(390);
            card.addView(title, titleLp);

            final int pos = position;
            final View fcard = card;
            final TextView ftitle = title;
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openResult(pos);
                }
            });
            card.setOnKeyListener(new View.OnKeyListener() {
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) {
                        return false;
                    }
                    // 官方：结果网格按左键回到键盘上最后聚焦的键；第一行按上键不动
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        focusKey(lastKeyPos);
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP && pos < 4) {
                        return true;
                    }
                    return false;
                }
            });
            card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean hasFocus) {
                    updateSearchBorder(border, hasFocus);
                    // 官方聚焦后标题从省略号切换为无限跑马灯，失焦恢复省略号
                    ftitle.setEllipsize(hasFocus
                            ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
                    ftitle.setSelected(hasFocus);
                    fcard.startAnimation(hasFocus ? scaleAnim(1.14f) : scaleAnim(1f / 1.14f));
                }
            });
            card.setFocusable(true);
            card.setClickable(true);
            return card;
        }
    }

    private void updateSearchBorder(View border, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(sf(8));
        if (focused) {
            bg.setStroke(Math.max(1, s(3)), COLOR_TEXT);
        }
        border.setBackground(bg);
    }

    private void addBadges(FrameLayout frame, MediaItem item) {
        List<String> labels = QualityBadges.from(item.resolutions);
        if (labels.isEmpty()) {
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        for (int i = 0; i < labels.size(); i++) {
            TextView badge = new TextView(this);
            badge.setText(labels.get(i));
            badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(24));
            badge.setTextColor(COLOR_MUTED);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(s(4), 0, s(4), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.TRANSPARENT);
            bg.setCornerRadius(sf(6));
            bg.setStroke(Math.max(1, s(2)), 0xFF9A9B9D);
            badge.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, s(30));
            if (i > 0) {
                lp.leftMargin = s(6);
            }
            box.addView(badge, lp);
        }
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, s(44));
        boxLp.gravity = Gravity.BOTTOM;
        boxLp.rightMargin = s(16);
        boxLp.bottomMargin = s(5);
        frame.addView(box, boxLp);
    }

    private void openResult(int position) {
        if (position < 0 || position >= results.size()) {
            return;
        }
        MediaItem item = results.get(position);
        if (query.length() > 0) {
            recordHistory(query.toString());
        }
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("item", com.juying.medianest.core.util.Json.stringify(item.toJson()));
        startActivity(i);
    }

    // ---------------- 搜索历史 ----------------

    private void loadHistory() {
        history.clear();
        String raw = prefs.getString("history", "");
        if (raw == null || raw.length() == 0) {
            return;
        }
        String[] parts = raw.split("\n");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > 0) {
                history.add(parts[i]);
            }
        }
    }

    private void recordHistory(String value) {
        if (value == null || value.length() == 0) {
            return;
        }
        history.remove(value);
        history.add(0, value);
        while (history.size() > 12) {
            history.remove(history.size() - 1);
        }
        saveHistory();
        fillChips();
    }

    private void saveHistory() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(history.get(i));
        }
        prefs.edit().putString("history", out.toString()).commit();
    }

    private void fillChips() {
        chipRow.removeAllViews();
        for (int i = 0; i < history.size(); i++) {
            final String value = history.get(i);
            final TextView chip = new TextView(this);
            chip.setText(value);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(36));
            chip.setTextColor(COLOR_TEXT);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(s(34), 0, s(34), 0);
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setBackground(chipBg(false));
            chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean hasFocus) {
                    chip.setBackground(chipBg(hasFocus));
                    chip.setTextColor(hasFocus ? 0xFF15171A : COLOR_TEXT);
                }
            });
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    query.setLength(0);
                    query.append(value);
                    refreshInput();
                    requestSearch();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, s(64));
            if (i > 0) {
                lp.leftMargin = s(20);
            }
            chipRow.addView(chip, lp);
        }
        historyEmpty.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
        chipScroll.setVisibility(history.isEmpty() ? View.GONE : View.VISIBLE);
        if (historyTitle != null) {
            historyTitle.setVisibility(history.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (historyClear != null) {
            historyClear.setVisibility(history.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private GradientDrawable chipBg(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? 0xFFFFFFFF : COLOR_CARD);
        bg.setCornerRadius(sf(12));
        return bg;
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
        }
        return super.onKeyDown(keyCode, event);
    }
}
