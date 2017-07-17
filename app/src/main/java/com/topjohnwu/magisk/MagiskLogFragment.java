package com.topjohnwu.magisk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.topjohnwu.magisk.asyncs.ParallelTask;
import com.topjohnwu.magisk.components.Fragment;
import com.topjohnwu.magisk.components.SnackbarMaker;
import com.topjohnwu.magisk.utils.Utils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Calendar;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;

public class MagiskLogFragment extends Fragment {

    private static final String MAGISK_LOG = "/cache/magisk.log";

    private Unbinder unbinder;
    @BindView(R.id.txtLog) TextView txtLog;
    @BindView(R.id.svLog) ScrollView svLog;
    @BindView(R.id.hsvLog) HorizontalScrollView hsvLog;

    @BindView(R.id.progressBar) ProgressBar progressBar;

    private MenuItem mClickedMenuItem;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_magisk_log, container, false);
        unbinder = ButterKnife.bind(this, view);

        txtLog.setTextIsSelectable(true);

        new LogManager(getActivity()).read();

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        getActivity().setTitle(R.string.log);
    }

    @Override
    public void onResume() {
        super.onResume();
        new LogManager(getActivity()).read();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbinder.unbind();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_log, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        mClickedMenuItem = item;
        switch (item.getItemId()) {
            case R.id.menu_refresh:
                new LogManager(getActivity()).read();
                return true;
            case R.id.menu_save:
                new LogManager(getActivity()).save();
                return true;
            case R.id.menu_clear:
                new LogManager(getActivity()).clear();
                return true;
            default:
                return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (mClickedMenuItem != null) {
                    new Handler().postDelayed(() -> onOptionsItemSelected(mClickedMenuItem), 500);
                }
            } else {
                SnackbarMaker.make(txtLog, R.string.permissionNotGranted, Snackbar.LENGTH_LONG).show();
            }
        }
    }

    private class LogManager extends ParallelTask<Object, Void, Object> {

        int mode;
        File targetFile;

        LogManager(Activity activity) {
            super(activity);
        }

        @SuppressLint("DefaultLocale")
        @Override
        protected Object doInBackground(Object... params) {
            mode = (int) params[0];
            switch (mode) {
                case 0:
                    List<String> logList = Utils.readFile(magiskManager.shell, MAGISK_LOG);

                    if (Utils.isValidShellResponse(logList)) {
                        StringBuilder llog = new StringBuilder(15 * 10 * 1024);
                        for (String s : logList) {
                            llog.append(s).append("\n");
                        }
                        return llog.toString();
                    }
                    return "";

                case 1:
                    magiskManager.shell.su_raw("echo > " + MAGISK_LOG);
                    SnackbarMaker.make(txtLog, R.string.logs_cleared, Snackbar.LENGTH_SHORT).show();
                    return "";

                case 2:
                    if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
                        }
                        return false;
                    }

                    if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                        return false;
                    }

                    Calendar now = Calendar.getInstance();
                    String filename = String.format(
                            "magisk_%s_%04d%02d%02d_%02d%02d%02d.log", "error",
                            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                            now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.HOUR_OF_DAY),
                            now.get(Calendar.MINUTE), now.get(Calendar.SECOND));

                    targetFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/MagiskManager/" + filename);

                    if ((!targetFile.getParentFile().exists() && !targetFile.getParentFile().mkdirs())
                            || (targetFile.exists() && !targetFile.delete())) {
                        return false;
                    }

                    List<String> in = Utils.readFile(magiskManager.shell, MAGISK_LOG);

                    if (Utils.isValidShellResponse(in)) {
                        try (FileWriter out = new FileWriter(targetFile)) {
                            for (String line : in)
                                out.write(line + "\n");
                            return true;
                        } catch (IOException e) {
                            e.printStackTrace();
                            return false;
                        }
                    }
                    return false;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Object o) {
            if (o == null) return;
            boolean bool;
            String llog;
            switch (mode) {
                case 0:
                case 1:
                    llog = (String) o;
                    progressBar.setVisibility(View.GONE);
                    if (TextUtils.isEmpty(llog))
                        txtLog.setText(R.string.log_is_empty);
                    else
                        txtLog.setText(llog);
                    svLog.post(() -> svLog.scrollTo(0, txtLog.getHeight()));
                    hsvLog.post(() -> hsvLog.scrollTo(0, 0));
                    break;
                case 2:
                    bool = (boolean) o;
                    if (bool) {
                        Toast.makeText(getActivity(), targetFile.toString(), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(getActivity(), getString(R.string.logs_save_failed), Toast.LENGTH_LONG).show();
                    }
                    break;
            }
        }

        public void read() {
            exec(0);
        }

        public void clear() {
            exec(1);
        }

        public void save() {
            exec(2);
        }
    }

}