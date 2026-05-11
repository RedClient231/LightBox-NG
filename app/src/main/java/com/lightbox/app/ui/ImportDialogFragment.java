package com.lightbox.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.lightbox.app.R;

public class ImportDialogFragment extends DialogFragment {

    public interface ImportCallback {
        void onPickFromStorage();
        void onPickInstalledApp();
    }

    private ImportCallback callback;

    public static ImportDialogFragment newInstance() {
        return new ImportDialogFragment();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof ImportCallback) {
            callback = (ImportCallback) context;
        } else {
            throw new RuntimeException(context + " must implement ImportCallback");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_import, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.btn_pick_storage).setOnClickListener(v -> {
            dismiss();
            if (callback != null) callback.onPickFromStorage();
        });

        view.findViewById(R.id.btn_pick_installed).setOnClickListener(v -> {
            dismiss();
            if (callback != null) callback.onPickInstalledApp();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
