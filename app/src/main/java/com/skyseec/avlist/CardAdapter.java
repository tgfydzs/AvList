package com.skyseec.avlist;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CardAdapter extends RecyclerView.Adapter<CardAdapter.CardViewHolder> {

    private final Context context;
    private final List<CardItem> list;
    private final List<CardItem> selectedItems;
    private final SimpleDateFormat sdf;

    private boolean isMultiSelectMode = false;
    private OnItemSelectListener listener;

    public interface OnItemSelectListener {
        void onItemSelected(int position, boolean isChecked);
        void onItemLongClick(int position);
        void onItemClick(int position);
        void onScrapeToggle(int position, boolean enabled);
    }

    public CardAdapter(Context context, List<CardItem> list, List<CardItem> selectedItems) {
        this.context = context;
        this.list = list;
        this.selectedItems = selectedItems;
        this.sdf = new SimpleDateFormat("yyyy MM dd HH mm", Locale.getDefault());
    }

    public void setOnItemSelectListener(OnItemSelectListener listener) {
        this.listener = listener;
    }

    public void setMultiSelectMode(boolean mode) {
        this.isMultiSelectMode = mode;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_card, parent, false);
        return new CardViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CardViewHolder holder, int position) {

        CardItem item = list.get(position);

        // =========================
        // 文本全部防空
        // =========================
        // =========================
        // 番号 + 标题显示逻辑
        // =========================
        String code = item.getCode();
        if (code != null && !code.isEmpty()) {
            holder.tvCode.setText(code);
            holder.tvCode.setVisibility(View.VISIBLE);
        } else {
            holder.tvCode.setVisibility(View.GONE);
        }

        // tvTitle: 有内容显示，无内容 GONE
        String title = item.getTitle();
        if (title != null && !title.trim().isEmpty()) {
            holder.tvTitle.setText(title);
            holder.tvTitle.setVisibility(View.VISIBLE);
        } else {
            holder.tvTitle.setVisibility(View.GONE);
        }

        holder.tvCreateTime.setText(
                sdf.format(new Date(item.getCreateTime()))
        );

        // =========================
        // 图片：优先本地，其次网络，无图时隐藏 ImageView
        // =========================
        String localPath = item.resolveLocalImagePath(context);
        File localFile = new File(localPath);
        boolean hasImage = false;

        if (localFile.exists()) {
            Glide.with(context)
                    .load(localFile)
                    .dontTransform()
                    .into(holder.ivImage);
            hasImage = true;
        } else {
            String imgUrl = item.getImageUrl();
            if (imgUrl != null && !imgUrl.trim().isEmpty()) {
                if (imgUrl.startsWith("/")) {
                    imgUrl = "https://www.javbus.com" + imgUrl;
                }
                GlideUrl glideUrl = new GlideUrl(
                        imgUrl,
                        new LazyHeaders.Builder()
                                .addHeader("User-Agent",
                                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                .addHeader("Referer", "https://www.javbus.com/")
                                .build()
                );
                Glide.with(context)
                        .load(glideUrl)
                        .dontTransform()
                        .into(holder.ivImage);
                hasImage = true;
            }
        }

        holder.ivImage.setVisibility(hasImage ? View.VISIBLE : View.GONE);

        // =========================
        // 多选（保持你原逻辑）
        // =========================
        holder.cbSelect.setVisibility(isMultiSelectMode ? View.VISIBLE : View.GONE);

        holder.cbSelect.setOnCheckedChangeListener(null);

        boolean checked = selectedItems.contains(item);
        holder.cbSelect.setChecked(checked);

        holder.cbSelect.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (listener != null) {
                listener.onItemSelected(holder.getAdapterPosition(), isChecked);
            }
        });

        // =========================
        // 跳转按钮：有番号淡色可点，无番号纯文字灰 "无跳转"
        // =========================
        final String jumpCode = item.getCode();
        final boolean hasCode = jumpCode != null && !jumpCode.trim().isEmpty();

        if (hasCode) {
            holder.btnJump.setText("跳转");
            holder.btnJump.setTextColor(0xFF888888);
            holder.btnJump.setAlpha(1.0f);
            holder.btnJump.setBackgroundResource(R.drawable.btn_jump_bg);
            holder.btnJump.setOnClickListener(v -> {
                String url = "https://missav.ws/search/" + CardItem.normalizeCode(jumpCode);
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                context.startActivity(intent);
            });
        } else {
            holder.btnJump.setText("无跳转");
            holder.btnJump.setTextColor(0xFFBBBBBB);
            holder.btnJump.setAlpha(1.0f);
            holder.btnJump.setBackground(null);
            holder.btnJump.setOnClickListener(null);
            holder.btnJump.setClickable(false);
        }

        // =========================
        // 刮削开关
        // =========================
        holder.swScrapeCard.setOnCheckedChangeListener(null);
        holder.swScrapeCard.setChecked(item.isScrapeEnabled());

        holder.swScrapeCard.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (listener != null) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    item.setScrapeEnabled(isChecked);
                    listener.onScrapeToggle(pos, isChecked);
                }
            }
        });

        // =========================
        // 点击
        // =========================
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;

            if (isMultiSelectMode) {
                boolean newState = !holder.cbSelect.isChecked();
                holder.cbSelect.setChecked(newState);

                if (listener != null) {
                    listener.onItemSelected(pos, newState);
                }
            } else {
                if (listener != null) {
                    listener.onItemClick(pos);
                }
            }
        });

        // =========================
        // 长按
        // =========================
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;

            if (!isMultiSelectMode && listener != null) {
                listener.onItemLongClick(pos);
            }
            return true;
        });
    }
    @Override
    public int getItemCount() {
        return list.size();
    }

    public static class CardViewHolder extends RecyclerView.ViewHolder {

        ImageView ivImage;
        TextView tvTitle, tvCode, tvCreateTime;
        CheckBox cbSelect;
        SwitchCompat swScrapeCard;
        AppCompatButton btnJump;

        public CardViewHolder(@NonNull View itemView) {
            super(itemView);

            ivImage = itemView.findViewById(R.id.ivImage);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvCode = itemView.findViewById(R.id.tvCode);
            tvCreateTime = itemView.findViewById(R.id.tvCreateTime);
            cbSelect = itemView.findViewById(R.id.cbSelect);
            swScrapeCard = itemView.findViewById(R.id.swScrapeCard);
            btnJump = itemView.findViewById(R.id.btnJump);
        }
    }
}