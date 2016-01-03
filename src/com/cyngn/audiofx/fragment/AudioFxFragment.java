package com.cyngn.audiofx.fragment;

import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.media.AudioDeviceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import com.cyngn.audiofx.R;
import com.cyngn.audiofx.activity.ActivityMusic;
import com.cyngn.audiofx.activity.EqualizerManager;
import com.cyngn.audiofx.activity.MasterConfigControl;
import com.cyngn.audiofx.activity.StateCallbacks;
import com.cyngn.audiofx.stats.UserSession;
import com.cyngn.audiofx.widget.InterceptableLinearLayout;

import java.util.List;
import java.util.Map;

public class AudioFxFragment extends Fragment implements ActivityMusic.ActivityStateListener,
        StateCallbacks.DeviceChangedCallback {

    private static final String TAG = AudioFxFragment.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final String TAG_EQUALIZER = "equalizer";
    public static final String TAG_CONTROLS = "controls";

    Handler mHandler;
    int mCurrentBackgroundColor;

    // whether we are in the middle of animating while switching devices
    boolean mDeviceChanging;

    private MenuItem mMenuDevices;

    // current selected index
    public int mSelectedPosition = 0;

    EqualizerFragment mEqFragment;
    ControlsFragment mControlFragment;

    InterceptableLinearLayout mInterceptLayout;
    private ValueAnimator mColorChangeAnimator;

    private int mDisabledColor;

    private MasterConfigControl mConfig;
    private EqualizerManager mEqManager;

    private int mCurrentMode;

    private AudioDeviceInfo mSystemDevice;
    private AudioDeviceInfo mUserSelection;

    private final Map<MenuItem, AudioDeviceInfo> mMenuItems = new ArrayMap<MenuItem, AudioDeviceInfo>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mConfig = MasterConfigControl.getInstance(getActivity());
        mEqManager = mConfig.getEqualizerManager();

        if (savedInstanceState != null) {
            int user = savedInstanceState.getInt("user_device");
            mUserSelection = mConfig.getDeviceById(user);
            int system = savedInstanceState.getInt("system_device");
            mSystemDevice = mConfig.getDeviceById(system);
        }

        mHandler = new Handler();
        mDisabledColor = getResources().getColor(R.color.disabled_eq);

       // mConfig.bindService(); // bind early

        setHasOptionsMenu(true);
        ((ActivityMusic) getActivity()).addToggleListener(this);

        mCurrentMode = ((ActivityMusic) getActivity()).getCurrentMode();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("user_device", mUserSelection == null ? -1 : mUserSelection.getId());
        outState.putInt("system_device", mSystemDevice == null ? -1 : mSystemDevice.getId());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ((ActivityMusic) getActivity()).removeToggleListener(this);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mCurrentMode = ((ActivityMusic) getActivity()).getCurrentMode();
    }

    private boolean showFragments() {
        boolean createNewFrags = true;
        final FragmentTransaction fragmentTransaction = getChildFragmentManager()
                .beginTransaction();
        if (mEqFragment == null) {
            mEqFragment = (EqualizerFragment) getChildFragmentManager()
                    .findFragmentByTag(TAG_EQUALIZER);

            if (mEqFragment != null) {
                fragmentTransaction.show(mEqFragment);
            }
        }
        if (mControlFragment == null) {
            mControlFragment = (ControlsFragment) getChildFragmentManager()
                    .findFragmentByTag(TAG_CONTROLS);
            if (mControlFragment != null) {
                fragmentTransaction.show(mControlFragment);
            }
        }

        if (mEqFragment != null && mControlFragment != null) {
            createNewFrags = false;
        }

        fragmentTransaction.commit();

        return createNewFrags;
    }

    @Override
    public void onResume() {

        mConfig.bindService();

        mConfig.getCallbacks().addDeviceChangedCallback(this);

        updateEnabledState();

        super.onResume();

        mCurrentBackgroundColor = !mConfig.isCurrentDeviceEnabled()
                ? mDisabledColor
                : mEqManager.getAssociatedPresetColorHex(
                        mEqManager.getCurrentPresetIndex());
        updateBackgroundColors(mCurrentBackgroundColor, false);
    }

    @Override
    public void onPause() {
        mConfig.getCallbacks().removeDeviceChangedCallback(this);
        mConfig.unbindService();

        super.onPause();
    }

    public void updateBackgroundColors(Integer color, boolean cancelAnimated) {
        if (cancelAnimated && mColorChangeAnimator != null) {
            mColorChangeAnimator.cancel();
        }
        mCurrentBackgroundColor = color;
        if (mEqFragment != null) {
            mEqFragment.updateFragmentBackgroundColors(color);
        }
        if (mControlFragment != null) {
            mControlFragment.updateFragmentBackgroundColors(color);
        }
    }

    public void updateEnabledState() {
        boolean currentDeviceEnabled = mConfig.isCurrentDeviceEnabled();
        if (mEqFragment != null) {
            mEqFragment.updateEnabledState();
        }
        if (mControlFragment != null) {
            mControlFragment.updateEnabledState();
        }

        ((ActivityMusic) getActivity()).setGlobalToggleChecked(currentDeviceEnabled);

        if (mInterceptLayout != null) {
            mInterceptLayout.setInterception(!currentDeviceEnabled);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.devices, menu);
        mMenuDevices = menu.findItem(R.id.devices);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        mMenuDevices.getSubMenu().clear();
        mMenuItems.clear();

        final AudioDeviceInfo currentDevice = mConfig.getCurrentDevice();

        MenuItem selectedItem = null;

        List<AudioDeviceInfo> speakerDevices = mConfig.getConnectedDevices(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        if (speakerDevices.size() > 0) {
            AudioDeviceInfo ai = speakerDevices.get(0);
            int viewId = View.generateViewId();
            MenuItem item = mMenuDevices.getSubMenu().add(R.id.devices, viewId,
                    Menu.NONE, R.string.device_speaker);
            item.setIcon(R.drawable.ic_action_dsp_icons_speaker);
            mMenuItems.put(item, ai);
            selectedItem = item;
        }

        List<AudioDeviceInfo> headsetDevices = mConfig.getConnectedDevices(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET);
        if (headsetDevices.size() > 0) {
            AudioDeviceInfo ai = headsetDevices.get(0);
            int viewId = View.generateViewId();
            MenuItem item = mMenuDevices.getSubMenu().add(R.id.devices, viewId,
                    Menu.NONE, R.string.device_headset);
            item.setIcon(R.drawable.ic_action_dsp_icons_headphones);
            mMenuItems.put(item, ai);
            if (currentDevice.getId() == ai.getId()) {
                selectedItem = item;
            }
        }

        List<AudioDeviceInfo> bluetoothDevices = mConfig.getConnectedDevices(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        for (AudioDeviceInfo ai : bluetoothDevices) {
            int viewId = View.generateViewId();
            MenuItem item = mMenuDevices.getSubMenu().add(R.id.devices, viewId,
                    Menu.NONE, ai.getProductName());
            item.setIcon(R.drawable.ic_action_dsp_icons_bluetoof);
            mMenuItems.put(item, ai);
            if (currentDevice.getId() == ai.getId()) {
                selectedItem = item;
            }
        }

        List<AudioDeviceInfo> usbDevices = mConfig.getConnectedDevices(
                AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_DEVICE);
        for (AudioDeviceInfo ai : usbDevices) {
            int viewId = View.generateViewId();
            MenuItem item = mMenuDevices.getSubMenu().add(R.id.devices, viewId,
                    Menu.NONE, ai.getProductName());
            item.setIcon(R.drawable.ic_action_device_usb);
            mMenuItems.put(item,  ai);
            if (currentDevice.getId() == ai.getId()) {
                selectedItem = item;
            }
        }

        mMenuDevices.getSubMenu().setGroupCheckable(R.id.devices, true, true);

        selectedItem.setChecked(true);
        mMenuDevices.setIcon(selectedItem.getIcon());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        AudioDeviceInfo device = mMenuItems.get(item);

        if (device != null) {
            UserSession.getInstance().deviceChanged();
            mDeviceChanging = true;
            if (item.isCheckable()) {
                item.setChecked(!item.isChecked());
            }
            mSystemDevice = mConfig.getSystemDevice();
            mUserSelection = device;
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mConfig.setCurrentDevice(mUserSelection, true);
                }
            });
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             Bundle savedInstanceState) {
        if (container == null) {
            Log.w(TAG, "container is null.");
            // no longer displaying this fragment
            return null;
        }

        View root = inflater.inflate(mConfig.hasMaxxAudio()
                ? R.layout.fragment_audiofx_maxxaudio
                : R.layout.fragment_audiofx, container, false);

        final FragmentTransaction fragmentTransaction = getChildFragmentManager()
                .beginTransaction();

        boolean createNewFrags = true;

        if (savedInstanceState != null) {
            createNewFrags = showFragments();
        }

        if (createNewFrags) {
            fragmentTransaction.add(R.id.equalizer, mEqFragment = new EqualizerFragment(),
                    TAG_EQUALIZER);
            fragmentTransaction.add(R.id.controls, mControlFragment = new ControlsFragment(),
                    TAG_CONTROLS);
        }

        fragmentTransaction.commit();


        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // view was destroyed
        final FragmentTransaction fragmentTransaction = getChildFragmentManager()
                .beginTransaction();

        if (mEqFragment != null) {
            fragmentTransaction.remove(mEqFragment);
            mEqFragment = null;
        }
        if (mControlFragment != null) {
            fragmentTransaction.remove(mControlFragment);
            mControlFragment = null;
        }

        fragmentTransaction.commitAllowingStateLoss();

    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mInterceptLayout = (InterceptableLinearLayout) view.findViewById(R.id.interceptable_layout);
    }

    public void animateBackgroundColorTo(int colorTo, Animator.AnimatorListener listener,
                                         ColorUpdateListener updateListener) {
        if (mColorChangeAnimator != null) {
            mColorChangeAnimator.cancel();
            mColorChangeAnimator = null;
        }
        mColorChangeAnimator = ValueAnimator.ofObject(new ArgbEvaluator(),
                mCurrentBackgroundColor, colorTo);
        mColorChangeAnimator.setDuration(500);
        mColorChangeAnimator.addUpdateListener(updateListener != null ? updateListener
                : mColorUpdateListener);
        if (listener != null) {
            mColorChangeAnimator.addListener(listener);
        }
        mColorChangeAnimator.start();
    }

    @Override
    public void onDeviceChanged(AudioDeviceInfo device, boolean userChange) {
        updateEnabledState();
        getActivity().invalidateOptionsMenu();
    }

    private ValueAnimator.AnimatorUpdateListener mColorUpdateListener
            = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            updateBackgroundColors((Integer) animation.getAnimatedValue(), false);
        }
    };

    @Override
    public void onGlobalToggleChanged(final CompoundButton buttonView, final boolean checked) {
        if (mCurrentMode != ActivityMusic.CURRENT_MODE_AUDIOFX) {
            return;
        }
        final Animator.AnimatorListener animatorListener = new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                buttonView.setEnabled(false);
                mConfig.setCurrentDeviceEnabled(checked);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                updateEnabledState();
                buttonView.setEnabled(true);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                buttonView.setEnabled(true);
            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        };
        final Integer colorTo = checked
                ? mEqManager.getAssociatedPresetColorHex(mEqManager.getCurrentPresetIndex())
                : mDisabledColor;
        animateBackgroundColorTo(colorTo,
                animatorListener, null);
    }

    @Override
    public void onModeChanged(int mode) {
        mCurrentMode = mode;
    }

    public static class ColorUpdateListener implements ValueAnimator.AnimatorUpdateListener {

        final AudioFxBaseFragment mFrag;

        public ColorUpdateListener(AudioFxBaseFragment frag) {
            this.mFrag = frag;
        }

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mFrag.setBackgroundColor((Integer) animation.getAnimatedValue(), false);
        }
    }

    public int getDisabledColor() {
        return mDisabledColor;
    }
}
