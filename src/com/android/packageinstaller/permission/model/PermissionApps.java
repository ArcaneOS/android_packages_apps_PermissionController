/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.packageinstaller.permission.model;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionInfo;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.packageinstaller.permission.utils.IconDrawableFactory;
import com.android.packageinstaller.permission.utils.Utils;
import com.android.permissioncontroller.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PermissionApps {
    private static final String LOG_TAG = "PermissionApps";

    private final Context mContext;
    private final String mGroupName;
    private final String mPackageName;
    private final PackageManager mPm;
    private final Callback mCallback;

    private final @Nullable PmCache mPmCache;
    private final @Nullable AppDataCache mAppDataCache;

    private CharSequence mLabel;
    private Drawable mIcon;
    private List<PermissionApp> mPermApps;
    // Map (pkg|uid) -> AppPermission
    private ArrayMap<String, PermissionApp> mAppLookup;

    private boolean mSkipUi;
    private boolean mRefreshing;

    public PermissionApps(Context context, String groupName, String packageName) {
        this(context, groupName, packageName, null, null, null);
    }

    public PermissionApps(Context context, String groupName, Callback callback) {
        this(context, groupName, null, callback, null, null);
    }

    public PermissionApps(Context context, String groupName, String packageName,
            Callback callback, @Nullable PmCache pmCache, @Nullable AppDataCache appDataCache) {
        mPmCache = pmCache;
        mAppDataCache = appDataCache;
        mContext = context;
        mPm = mContext.getPackageManager();
        mGroupName = groupName;
        mCallback = callback;
        mPackageName = packageName;
        loadGroupInfo();
    }

    public String getGroupName() {
        return mGroupName;
    }

    public void loadNowWithoutUi() {
        mSkipUi = true;
        createMap(loadPermissionApps());
    }

    /**
     * Start an async refresh and call back the registered call back once done.
     *
     * @param getUiInfo If the UI info should be updated
     */
    public void refresh(boolean getUiInfo) {
        if (!mRefreshing) {
            mRefreshing = true;
            mSkipUi = !getUiInfo;
            new PermissionAppsLoader().execute();
        }
    }

    /**
     * Refresh the state and do not return until it finishes. Should not be called while an {@link
     * #refresh async referesh} is in progress.
     */
    public void refreshSync(boolean getUiInfo) {
        mSkipUi = !getUiInfo;
        createMap(loadPermissionApps());
    }

    public int getGrantedCount(ArraySet<String> launcherPkgs) {
        int count = 0;
        for (PermissionApp app : mPermApps) {
            if (!Utils.shouldShowPermission(mContext, app.getPermissionGroup())) {
                continue;
            }
            if (Utils.isSystem(app, launcherPkgs)) {
                // We default to not showing system apps, so hide them from count.
                continue;
            }
            if (app.areRuntimePermissionsGranted()) {
                count++;
            }
        }
        return count;
    }

    public int getTotalCount(ArraySet<String> launcherPkgs) {
        int count = 0;
        for (PermissionApp app : mPermApps) {
            if (!Utils.shouldShowPermission(mContext, app.getPermissionGroup())) {
                continue;
            }
            if (Utils.isSystem(app, launcherPkgs)) {
                // We default to not showing system apps, so hide them from count.
                continue;
            }
            count++;
        }
        return count;
    }

    public List<PermissionApp> getApps() {
        return mPermApps;
    }

    public PermissionApp getApp(String key) {
        return mAppLookup.get(key);
    }

    public CharSequence getLabel() {
        return mLabel;
    }

    public Drawable getIcon() {
        return mIcon;
    }

    private @NonNull List<PackageInfo> getPackageInfos(@NonNull UserHandle user) {
        List<PackageInfo> apps = (mPmCache != null) ? mPmCache.getPackages(
                user.getIdentifier()) : null;
        if (apps != null) {
            if (mPackageName != null) {
                final int appCount = apps.size();
                for (int i = 0; i < appCount; i++) {
                    final PackageInfo app = apps.get(i);
                    if (mPackageName.equals(app.packageName)) {
                        apps = new ArrayList<>(1);
                        apps.add(app);
                        return apps;
                    }
                }
            }
            return apps;
        }
        if (mPackageName == null) {
            return mPm.getInstalledPackagesAsUser(PackageManager.GET_PERMISSIONS,
                    user.getIdentifier());
        } else {
            try {
                final PackageInfo packageInfo = mPm.getPackageInfo(mPackageName,
                        PackageManager.GET_PERMISSIONS);
                apps = new ArrayList<>(1);
                apps.add(packageInfo);
                return apps;
            } catch (NameNotFoundException e) {
                return Collections.emptyList();
            }
        }
    }

    private List<PermissionApp> loadPermissionApps() {
        PackageItemInfo groupInfo = Utils.getGroupInfo(mGroupName, mContext);
        if (groupInfo == null) {
            return Collections.emptyList();
        }

        List<PermissionInfo> groupPermInfos = Utils.getGroupPermissionInfos(mGroupName, mContext);
        if (groupPermInfos == null) {
            return Collections.emptyList();
        }

        ArrayList<PermissionApp> permApps = new ArrayList<>();

        UserManager userManager = mContext.getSystemService(UserManager.class);
        for (UserHandle user : userManager.getUserProfiles()) {
            List<PackageInfo> apps = getPackageInfos(user);
            final int N = apps.size();
            for (int i = 0; i < N; i++) {
                PackageInfo app = apps.get(i);
                if (app.requestedPermissions == null) {
                    continue;
                }

                for (int j = 0; j < app.requestedPermissions.length; j++) {
                    String requestedPerm = app.requestedPermissions[j];

                    PermissionInfo requestedPermissionInfo = null;

                    for (PermissionInfo groupPermInfo : groupPermInfos) {
                        if (requestedPerm.equals(groupPermInfo.name)) {
                            requestedPermissionInfo = groupPermInfo;
                            break;
                        }
                    }

                    if (requestedPermissionInfo == null) {
                        continue;
                    }

                    if ((requestedPermissionInfo.protectionLevel
                                & PermissionInfo.PROTECTION_MASK_BASE)
                                    != PermissionInfo.PROTECTION_DANGEROUS
                            || (requestedPermissionInfo.flags
                                & PermissionInfo.FLAG_INSTALLED) == 0
                            || (requestedPermissionInfo.flags
                                & PermissionInfo.FLAG_REMOVED) != 0) {
                        continue;
                    }

                    AppPermissionGroup group = AppPermissionGroup.create(mContext,
                            app, groupInfo, groupPermInfos, false);

                    if (group == null) {
                        continue;
                    }

                    Pair<String, Drawable> appData = null;
                    if (mAppDataCache != null && !mSkipUi) {
                        appData = mAppDataCache.getAppData(user.getIdentifier(), app);
                    }

                    String label;
                    if (mSkipUi) {
                        label = app.packageName;
                    } else if (appData != null) {
                        label = appData.first;
                    } else {
                        label = app.applicationInfo.loadLabel(mPm).toString();
                    }

                    Drawable icon = null;
                    if (!mSkipUi) {
                        if (appData != null) {
                            icon = appData.second;
                        } else {
                            icon = IconDrawableFactory.getBadgedIcon(mContext, app.applicationInfo,
                                    UserHandle.getUserHandleForUid(app.applicationInfo.uid));
                        }
                    }

                    PermissionApp permApp = new PermissionApp(app.packageName, group, label, icon,
                            app.applicationInfo);

                    permApps.add(permApp);
                    break; // move to the next app.
                }
            }
        }

        Collections.sort(permApps);

        return permApps;
    }

    private void createMap(List<PermissionApp> result) {
        mAppLookup = new ArrayMap<>();
        for (PermissionApp app : result) {
            mAppLookup.put(app.getKey(), app);
        }
        mPermApps = result;
    }

    private void loadGroupInfo() {
        PackageItemInfo info;
        try {
            info = mPm.getPermissionGroupInfo(mGroupName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            try {
                PermissionInfo permInfo = mPm.getPermissionInfo(mGroupName, 0);
                if ((permInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                        != PermissionInfo.PROTECTION_DANGEROUS) {
                    Log.w(LOG_TAG, mGroupName + " is not a runtime permission");
                    return;
                }
                info = permInfo;
            } catch (NameNotFoundException reallyNotFound) {
                Log.w(LOG_TAG, "Can't find permission: " + mGroupName, reallyNotFound);
                return;
            }
        }
        mLabel = info.loadLabel(mPm);
        if (info.icon != 0) {
            mIcon = info.loadUnbadgedIcon(mPm);
        } else {
            mIcon = mContext.getDrawable(R.drawable.ic_perm_device_info);
        }
        mIcon = Utils.applyTint(mContext, mIcon, android.R.attr.colorControlNormal);
    }

    public static class PermissionApp implements Comparable<PermissionApp> {
        private final String mPackageName;
        private final AppPermissionGroup mAppPermissionGroup;
        private final String mLabel;
        private final Drawable mIcon;
        private final ApplicationInfo mInfo;

        public PermissionApp(String packageName, AppPermissionGroup appPermissionGroup,
                String label, Drawable icon, ApplicationInfo info) {
            mPackageName = packageName;
            mAppPermissionGroup = appPermissionGroup;
            mLabel = label;
            mIcon = icon;
            mInfo = info;
        }

        public ApplicationInfo getAppInfo() {
            return mInfo;
        }

        public String getKey() {
            return mPackageName + getUid();
        }

        public String getLabel() {
            return mLabel;
        }

        public Drawable getIcon() {
            return mIcon;
        }

        public boolean areRuntimePermissionsGranted() {
            return mAppPermissionGroup.areRuntimePermissionsGranted();
        }

        public boolean isReviewRequired() {
            return mAppPermissionGroup.isReviewRequired();
        }

        public void grantRuntimePermissions() {
            mAppPermissionGroup.grantRuntimePermissions(false);
        }

        public void revokeRuntimePermissions() {
            mAppPermissionGroup.revokeRuntimePermissions(false);
        }

        public boolean isPolicyFixed() {
            return mAppPermissionGroup.isPolicyFixed();
        }

        public boolean isSystemFixed() {
            return mAppPermissionGroup.isSystemFixed();
        }

        public boolean hasGrantedByDefaultPermissions() {
            return mAppPermissionGroup.hasGrantedByDefaultPermission();
        }

        public boolean doesSupportRuntimePermissions() {
            return mAppPermissionGroup.doesSupportRuntimePermissions();
        }

        public String getPackageName() {
            return mPackageName;
        }

        public AppPermissionGroup getPermissionGroup() {
            return mAppPermissionGroup;
        }

        @Override
        public int compareTo(PermissionApp another) {
            final int result = mLabel.compareTo(another.mLabel);
            if (result == 0) {
                // Unbadged before badged.
                return getKey().compareTo(another.getKey());
            }
            return result;
        }

        public int getUid() {
            return mAppPermissionGroup.getApp().applicationInfo.uid;
        }
    }

    private class PermissionAppsLoader extends AsyncTask<Void, Void, List<PermissionApp>> {

        @Override
        protected List<PermissionApp> doInBackground(Void... args) {
            return loadPermissionApps();
        }

        @Override
        protected void onPostExecute(List<PermissionApp> result) {
            mRefreshing = false;
            createMap(result);
            if (mCallback != null) {
                mCallback.onPermissionsLoaded(PermissionApps.this);
            }
        }
    }

    /**
     * Class used to reduce the number of calls to the package manager.
     * This caches app information so it should only be used across parallel PermissionApps
     * instances, and should not be retained across UI refresh.
     */
    public static class PmCache {
        private final SparseArray<List<PackageInfo>> mPackageInfoCache = new SparseArray<>();
        private final PackageManager mPm;

        public PmCache(PackageManager pm) {
            mPm = pm;
        }

        public synchronized List<PackageInfo> getPackages(int userId) {
            List<PackageInfo> ret = mPackageInfoCache.get(userId);
            if (ret == null) {
                ret = mPm.getInstalledPackagesAsUser(PackageManager.GET_PERMISSIONS, userId);
                mPackageInfoCache.put(userId, ret);
            }
            return ret;
        }
    }

    /**
     * Class used to reduce the number of calls to loading labels and icons.
     * This caches app information so it should only be used across parallel PermissionApps
     * instances, and should not be retained across UI refresh.
     */
    public static class AppDataCache {
        private final @NonNull SparseArray<ArrayMap<String, Pair<String, Drawable>>> mCache =
                new SparseArray<>();
        private final @NonNull PackageManager mPm;
        private final @NonNull Context mContext;

        public AppDataCache(@NonNull PackageManager pm, @NonNull Context context) {
            mPm = pm;
            mContext = context;
        }

        /**
         * Get the label and icon for the given app.
         *
         * @param userId the user id.
         * @param app The app
         *
         * @return a pair of the label and icon.
         */
        public @NonNull Pair<String, Drawable> getAppData(int userId,
                @NonNull PackageInfo app) {
            ArrayMap<String, Pair<String, Drawable>> dataForUser = mCache.get(userId);
            if (dataForUser == null) {
                dataForUser = new ArrayMap<>();
                mCache.put(userId, dataForUser);
            }
            Pair<String, Drawable> data = dataForUser.get(app.applicationInfo.packageName);
            if (data == null) {
                data = Pair.create(app.applicationInfo.loadLabel(mPm).toString(),
                        IconDrawableFactory.getBadgedIcon(mContext, app.applicationInfo,
                                UserHandle.getUserHandleForUid(app.applicationInfo.uid)));
                dataForUser.put(app.applicationInfo.packageName, data);
            }
            return data;
        }
    }

    public interface Callback {
        void onPermissionsLoaded(PermissionApps permissionApps);
    }
}
