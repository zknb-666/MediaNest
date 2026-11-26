package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.core.model.MediaItem;

import java.util.List;

/** 大屏列表适配器：大字号、焦点高亮由 row_* 布局的 selector drawable 提供。 */
public final class Adapters {

    public static final class MediaAdapter extends ArrayAdapter<MediaItem> {
        private final LayoutInflater inflater;

        public MediaAdapter(Context ctx, List<MediaItem> items) {
            super(ctx, R.layout.row_media, items);
            inflater = LayoutInflater.from(ctx);
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = inflater.inflate(R.layout.row_media, parent, false);
            }
            MediaItem item = getItem(position);
            TextView title = (TextView) v.findViewById(R.id.row_title);
            TextView meta = (TextView) v.findViewById(R.id.row_meta);
            TextView badge = (TextView) v.findViewById(R.id.row_badge);
            title.setText(item.displayTitle());
            meta.setText(item.technicalLine());
            badge.setText(item.canPlay ? "可播放" : item.playError);
            return v;
        }
    }

    public static final class ServerAdapter extends ArrayAdapter<ConfigCodec.ServerInfo> {
        private final LayoutInflater inflater;

        public ServerAdapter(Context ctx, List<ConfigCodec.ServerInfo> items) {
            super(ctx, R.layout.row_server, items);
            inflater = LayoutInflater.from(ctx);
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = inflater.inflate(R.layout.row_server, parent, false);
            }
            ConfigCodec.ServerInfo s = getItem(position);
            TextView title = (TextView) v.findViewById(R.id.row_title);
            TextView meta = (TextView) v.findViewById(R.id.row_meta);
            TextView badge = (TextView) v.findViewById(R.id.row_badge);
            title.setText(s.baseUrl);
            meta.setText("端口 " + s.port + " · 指纹 " + s.signature + " · " + s.latencyMs + "ms");
            badge.setText(s.verified ? "已验证" : "待验证");
            return v;
        }
    }

    private Adapters() {
    }
}
