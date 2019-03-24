package com.health.openscale.sync.gui.view;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.health.openscale.sync.R;

import java.util.ArrayList;

import androidx.annotation.Nullable;
import timber.log.Timber;

public class StatusView extends LinearLayout {
    public static final int COLOR_RED = Color.parseColor("#FF4444");

    private LinearLayout horizontalLayout;
    private LinearLayout horizontalIconLayout;
    private TextView txtName;
    private TextView txtError;
    private ImageView imgIcon;
    private String name;
    private ArrayList<Button> btnList;
    private ArrayList<Spinner> spinnerList;

    public StatusView(Context context, String name) {
        super(context);
        this.name = name;
        btnList = new ArrayList<>();
        spinnerList = new ArrayList<>();
        init();
    }

    public StatusView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public StatusView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        LinearLayout.LayoutParams lpMatchParent = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams lpWrapContent = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);

        horizontalLayout = new LinearLayout(getContext());
        horizontalLayout.setOrientation(HORIZONTAL);
        horizontalLayout.setLayoutParams(lpMatchParent);

        txtName = new TextView(getContext());
        txtName.setText(name);
        txtName.setLayoutParams(lpWrapContent);

        txtError = new TextView(getContext());
        txtError.setText("Error");
        txtError.setTextColor(COLOR_RED);
        txtError.setTextSize(12);
        txtError.setLayoutParams(lpMatchParent);
        txtError.setGravity(Gravity.RIGHT);
        txtError.setVisibility(GONE);

        imgIcon = new ImageView(getContext());
        imgIcon.setImageResource(R.drawable.ic_status_error);
        imgIcon.setLayoutParams(lpWrapContent);

        horizontalLayout.addView(txtName);

        horizontalIconLayout = new LinearLayout(getContext());
        horizontalIconLayout.setOrientation(HORIZONTAL);
        horizontalIconLayout.setLayoutParams(lpMatchParent);
        horizontalIconLayout.setGravity(Gravity.RIGHT);
        horizontalIconLayout.addView(imgIcon);

        horizontalLayout.addView(horizontalIconLayout);

        addView(horizontalLayout);
        addView(txtError);
    }

    public void setEnable(boolean on) {
        txtName.setEnabled(on);
        txtError.setEnabled(on);
        imgIcon.setEnabled(on);

        for (Button btn : btnList) {
            btn.setEnabled(on);
        }

        for (Spinner spin : spinnerList) {
            spin.setEnabled(on);
        }
    }

    public void setCheck(boolean isSuccess, String infoText) {
        if (isSuccess) {
            imgIcon.setImageResource(R.drawable.ic_status_check);

            txtError.setVisibility(GONE);

            if (infoText != null) {
                Timber.d(infoText);
            }

            for (Button btn : btnList) {
                btn.setVisibility(GONE);
            }
        } else {
            imgIcon.setImageResource(R.drawable.ic_status_error);

            if (infoText != null) {
                Timber.e(infoText);
                txtError.setVisibility(VISIBLE);
                txtError.setText(infoText);
            }

            for (Button btn : btnList) {
                btn.setVisibility(VISIBLE);
            }
        }
    }

    public Button addButton(String name) {
        Button btnStatus = new Button(getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.setMargins(15, 30, 15, 30);
        btnStatus.setLayoutParams(lp);
        btnStatus.setAllCaps(false);

        btnStatus.setText(name);
        btnStatus.setBackgroundResource(R.drawable.flat_selector);
        addView(btnStatus);

        btnList.add(btnStatus);

        return btnStatus;
    }

    public Spinner addSpinner() {
        Spinner spinnerStatus = new Spinner(getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        spinnerStatus.setLayoutParams(lp);

        horizontalIconLayout.addView(spinnerStatus, 0);

        spinnerList.add(spinnerStatus);

        return spinnerStatus;
    }
}
