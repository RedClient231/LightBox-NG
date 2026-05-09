package com.lightbox.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lightbox.app.R;
import com.lightbox.app.model.ClonedApp;

import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    public interface OnAppClickListener {
        void onClick(ClonedApp app);
    }

    public interface OnAppLongClickListener {
        void onLongClick(ClonedApp app);
    }

    private final List<ClonedApp> apps;
    private final OnAppClickListener clickListener;
    private final OnAppLongClickListener longClickListener;

    public AppListAdapter(List<ClonedApp> apps, OnAppClickListener clickListener,
                          OnAppLongClickListener longClickListener) {
        this.apps = apps;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_cloned_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ClonedApp app = apps.get(position);
        holder.appName.setText(app.getAppName());
        holder.packageName.setText(app.getPackageName());

        if (app.getIcon() != null) {
            holder.icon.setImageDrawable(app.getIcon());
        } else {
            holder.icon.setImageResource(R.drawable.ic_default_app);
        }

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onClick(app);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) longClickListener.onLongClick(app);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView appName;
        TextView packageName;

        ViewHolder(View view) {
            super(view);
            icon = view.findViewById(R.id.app_icon);
            appName = view.findViewById(R.id.app_name);
            packageName = view.findViewById(R.id.app_package);
        }
    }
}
