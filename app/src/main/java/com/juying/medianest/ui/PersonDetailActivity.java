package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import com.juying.medianest.app.App;
import com.juying.medianest.core.util.L;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.juying.medianest.core.util.Json;
import com.juying.medianest.ui.ImageLoader;
import com.juying.medianest.domain.repository.ConfigRepository;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.model.Person;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.domain.usecase.MediaUseCases;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 人物详情：按官方 activity_person_detail 复刻。
 *
 * <p>结构：316×474 圆角头像（左）+ 姓名 40 / 原名 24 / 简介 26（右），
 * 下方按职务分节展示作品（官方 as_actor「作为演员」/ producer「作为导演」/
 * screenplay「作为编剧」），作品为 252×378 海报横向列表。
 *
 * <p>人物接口：GET /person/{guid} 补简介与原名，POST /person/item/list 取作品；
 * NAS 的 person/item/list 不返回逐条职务，因此分节标题取入口带过来的 job。
 */
public final class PersonDetailActivity extends Activity {

    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_SUB = 0xCCFFFFFF;
    private static final int C_MUTED = 0xA6FFFFFF;
    private static final int C_DARK_TEXT = 0xFF17181B;

    private ServerConfig cfg;
    private MediaUseCases api;
    private ImageLoader imageLoader;
    private Person person;

    private TextView nameText;
    private TextView originalText;
    private TextView bioText;
    private ImageView poster;
    private LinearLayout root;

    public static void start(Context context, Person p) {
        Intent i = new Intent(context, PersonDetailActivity.class);
        i.putExtra("person", Json.stringify(p.toMap()));
        context.startActivity(i);
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ConfigRepository store = App.from(this).config();
        cfg = store.load().server;
        api = App.from(this).useCases(cfg);
        imageLoader = new ImageLoader(this);
        person = Person.fromJson(Json.parseObject(getIntent().getStringExtra("person")));

        buildUi();
        applyPerson();
        loadData();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getResources().getColor(R.color.bg));
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(80), px(80), px(80), px(80));
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        root = content;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RoundedClipLayout clip = new RoundedClipLayout(this, px(16));
        poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setImageResource(R.drawable.people_placeholder);
        clip.addView(poster, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        header.addView(clip, new LinearLayout.LayoutParams(px(316), px(474)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = px(60);
        infoLp.rightMargin = px(104);
        header.addView(info, infoLp);

        nameText = text("", 40, C_TEXT, true);
        info.addView(nameText, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));
        originalText = text("", 24, C_TEXT, false);
        info.addView(originalText, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(4)));
        bioText = text("", 26, C_SUB, false);
        bioText.setLineSpacing(px(8), 1f);
        info.addView(bioText, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(16)));
    }

    private void applyPerson() {
        nameText.setText(person.name == null ? "" : person.name);
        // 原名与姓名相同时官方不重复显示
        String original = person.originalName == null ? "" : person.originalName;
        if (original.length() > 0 && !original.equals(person.name)) {
            originalText.setText(original);
            originalText.setVisibility(View.VISIBLE);
        } else {
            originalText.setVisibility(View.GONE);
        }
        String bio = person.biography == null ? "" : person.biography.trim();
        bioText.setText(bio);
        bioText.setVisibility(bio.length() > 0 ? View.VISIBLE : View.GONE);
        if (person.profilePath != null && person.profilePath.length() > 0) {
            imageLoader.load(api.imageUrl(cfg, person.profilePath, 600), poster, api.authorizationHeader(cfg));
        }
    }

    private void loadData() {
        final String guid = person.personGuid;
        if (guid == null || guid.length() == 0) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                Person detail = null;
                try {
                    detail = api.loadPerson(cfg, guid);
                } catch (Throwable t) {
                    L.w("PersonDetail", "人物详情读取失败：" + t.getMessage());
                }
                final Person detailFinal = detail;
                List<MediaItem> works = new ArrayList<MediaItem>();
                try {
                    works = api.listPersonItems(cfg, guid);
                } catch (Throwable t) {
                    L.w("PersonDetail", "人物作品读取失败：" + t.getMessage());
                }
                final List<MediaItem> worksFinal = works;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (detailFinal != null) {
                            person.name = detailFinal.name.length() > 0
                                    ? detailFinal.name : person.name;
                            person.originalName = detailFinal.originalName;
                            person.biography = detailFinal.biography;
                            person.profilePath = detailFinal.profilePath;
                            applyPerson();
                        }
                        if (!worksFinal.isEmpty()) {
                            appendWorks(worksFinal);
                        }
                    }
                });
            }
        }, "fntv-person-detail").start();
    }

    private void appendWorks(List<MediaItem> works) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        root.addView(section, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(56)));

        section.addView(text(person.sectionTitle(), 32, C_SUB, false), lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        android.widget.HorizontalScrollView scroll =
                new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        section.addView(scroll, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(32)));

        for (int i = 0; i < works.size(); i++) {
            View card = buildWorkCard(works.get(i));
            row.addView(card, lp(px(252), ViewGroup.LayoutParams.WRAP_CONTENT,
                    i == 0 ? 0 : px(28), 0));
            if (i == 0) {
                card.requestFocus();
            }
        }
    }

    private View buildWorkCard(final MediaItem work) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipChildren(false);
        card.setClipToPadding(false);

        RoundedClipLayout clip = new RoundedClipLayout(this, px(8));
        clip.setClipChildren(false);
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        clip.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (work.posterPath != null && work.posterPath.length() > 0) {
            imageLoader.load(api.imageUrl(cfg, work.posterPath, 500), cover, api.authorizationHeader(cfg));
        }

        final View border = new View(this);
        border.setBackground(stroked(0x00000000, px(3), px(10)));
        border.setVisibility(View.GONE);
        clip.addView(border, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(clip, new LinearLayout.LayoutParams(px(252), px(378)));

        String title = work.title == null ? "" : work.title;
        if (work.subtitle != null && work.subtitle.length() > 0) {
            title = work.subtitle + " · " + title;
        }
        TextView titleView = text(title, 28, C_TEXT, false);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(titleView, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(13)));
        TextView metaView = text(workMeta(work), 24, C_MUTED, false);
        metaView.setSingleLine(true);
        metaView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(metaView, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(6)));

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                card.animate().scaleX(hasFocus ? 1.05f : 1f).scaleY(hasFocus ? 1.05f : 1f)
                        .setDuration(150).start();
                border.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(PersonDetailActivity.this, DetailActivity.class);
                i.putExtra("item", Json.stringify(work.toJson()));
                startActivity(i);
            }
        });
        return card;
    }

    private String workMeta(MediaItem work) {
        StringBuilder sb = new StringBuilder();
        if (work.year != null && work.year.length() >= 4) {
            sb.append(work.year.substring(0, 4));
        }
        if (work.voteAverage > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(String.format(Locale.US, "%.1f", work.voteAverage));
        }
        return sb.toString();
    }

    // ---------------- 基础工具 ----------------

    private int px(int design) {
        return Math.round(design * getResources().getDisplayMetrics().widthPixels / 1920f);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        if (bold) {
            v.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return v;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int left, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = left;
        p.topMargin = top;
        return p;
    }

    private static GradientDrawable stroked(int color, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        d.setStroke(stroke, 0xFFFFFFFF);
        return d;
    }
}
