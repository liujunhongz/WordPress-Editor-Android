package org.wordpress.android.editor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.ClipDescription;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.Spanned;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.RelativeLayout.LayoutParams;
import android.widget.ToggleButton;

import com.android.volley.toolbox.ImageLoader;

import org.json.JSONException;
import org.json.JSONObject;
import org.wordpress.android.editor.EditorWebViewAbstract.ErrorListener;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.JSONUtils;
import org.wordpress.android.util.ProfilingUtils;
import org.wordpress.android.util.ShortcodeUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.UrlUtils;
import org.wordpress.android.util.helpers.MediaFile;
import org.wordpress.android.util.helpers.MediaGallery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TextFragment extends EditorFragmentAbstract implements View.OnClickListener, View.OnTouchListener,
        OnJsEditorStateChangedListener, OnImeBackListener, EditorWebViewAbstract.AuthHeaderRequestListener,
        EditorMediaUploadListener {

    public class IllegalEditorStateException extends Exception {

    }

    private static final String ARG_PARAM_TITLE = "param_title";
    private static final String ARG_PARAM_CONTENT = "param_content";

    private static final String JS_CALLBACK_HANDLER = "nativeCallbackHandler";

    private static final String KEY_TITLE = "title";
    private static final String KEY_CONTENT = "content";

    private static final String TAG_FORMAT_BAR_BUTTON_MEDIA = "media";
    private static final String TAG_FORMAT_BAR_BUTTON_LINK = "link";

    private static final float TOOLBAR_ALPHA_ENABLED = 1;
    private static final float TOOLBAR_ALPHA_DISABLED = 0.5f;

    private static final List<String> DRAGNDROP_SUPPORTED_MIMETYPES_TEXT = Arrays.asList(ClipDescription.MIMETYPE_TEXT_PLAIN,
            ClipDescription.MIMETYPE_TEXT_HTML);
    private static final List<String> DRAGNDROP_SUPPORTED_MIMETYPES_IMAGE = Arrays.asList("image/jpeg", "image/png");

    public static final int MAX_ACTION_TIME_MS = 2000;

    private String mTitle = "";
    private String mContentHtml = "";

    private EditorWebViewAbstract mWebView;

    private String mFocusedFieldId;

    private String mTitlePlaceholder = "";
    private String mContentPlaceholder = "";

    private boolean mDomHasLoaded = false;
    private boolean mIsKeyboardOpen = false;
    private boolean mEditorWasPaused = false;
    private boolean mHideActionBarOnSoftKeyboardUp = false;
    private boolean mIsFormatBarDisabled = false;

    private ConcurrentHashMap<String, MediaFile> mWaitingMediaFiles;
    private Set<MediaGallery> mWaitingGalleries;
    private Map<String, MediaType> mUploadingMedia;
    private Set<String> mFailedMediaIds;
    private MediaGallery mUploadingMediaGallery;

    private String mJavaScriptResult = "";


    private final Map<String, ToggleButton> mTagToggleButtonMap = new HashMap<>();

    private long mActionStartedAt = -1;

    private final View.OnDragListener mOnDragListener = new View.OnDragListener() {
        private long lastSetCoordsTimestamp;

        private boolean isSupported(ClipDescription clipDescription, List<String> mimeTypesToCheck) {
            if (clipDescription == null) {
                return false;
            }

            for (String supportedMimeType : mimeTypesToCheck) {
                if (clipDescription.hasMimeType(supportedMimeType)) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public boolean onDrag(View view, DragEvent dragEvent) {
            return true;
        }

        private void insertTextToEditor(String text) {
            if (text != null) {
                mWebView.execJavaScriptFromString("ZSSEditor.insertText('" + Utils.escapeHtml(text) + "', true);");
            } else {
                ToastUtils.showToast(getActivity(), R.string.editor_dropped_text_error, ToastUtils.Duration.SHORT);
                AppLog.d(T.EDITOR, "Dropped text was null!");
            }
        }
    };

    public static TextFragment newInstance(String title, String content) {
        TextFragment fragment = new TextFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM_TITLE, title);
        args.putString(ARG_PARAM_CONTENT, content);
        fragment.setArguments(args);
        return fragment;
    }

    public TextFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ProfilingUtils.start("Visual Editor Startup");
        ProfilingUtils.split("EditorFragment.onCreate");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_text, container, false);

        // Setup hiding the action bar when the soft keyboard is displayed for narrow viewports
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                && !getResources().getBoolean(R.bool.is_large_tablet_landscape)) {
            mHideActionBarOnSoftKeyboardUp = true;
        }

        mWaitingMediaFiles = new ConcurrentHashMap<>();
        mWaitingGalleries = Collections.newSetFromMap(new ConcurrentHashMap<MediaGallery, Boolean>());
        mUploadingMedia = new HashMap<>();
        mFailedMediaIds = new HashSet<>();

        // -- WebView configuration

        mWebView = (EditorWebViewAbstract) view.findViewById(R.id.webview);

        // Revert to compatibility WebView for custom ROMs using a 4.3 WebView in Android 4.4
        if (mWebView.shouldSwitchToCompatibilityMode()) {
            ViewGroup parent = (ViewGroup) mWebView.getParent();
            int index = parent.indexOfChild(mWebView);
            parent.removeView(mWebView);
            mWebView = new EditorWebViewCompatibility(getActivity(), null);
            mWebView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            parent.addView(mWebView, index);
        }

        mWebView.setOnTouchListener(this);
        mWebView.setOnImeBackListener(this);
        mWebView.setAuthHeaderRequestListener(this);

        mWebView.setOnDragListener(mOnDragListener);

        if (mCustomHttpHeaders != null && mCustomHttpHeaders.size() > 0) {
            for (Map.Entry<String, String> entry : mCustomHttpHeaders.entrySet()) {
                mWebView.setCustomHeader(entry.getKey(), entry.getValue());
            }
        }

        // Ensure that the content field is always filling the remaining screen space
        mWebView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                mWebView.post(new Runnable() {
                    @Override
                    public void run() {
                        mWebView.execJavaScriptFromString("try {ZSSEditor.refreshVisibleViewportSize();} catch (e) " +
                                "{console.log(e)}");
                    }
                });
            }
        });

        mEditorFragmentListener.onEditorFragmentInitialized();

        initJsEditor();

        if (savedInstanceState != null) {
            setTitle(savedInstanceState.getCharSequence(KEY_TITLE));
            setContent(savedInstanceState.getCharSequence(KEY_CONTENT));
        }

        return view;
    }

    @Override
    public void onPause() {
        super.onPause();
        mEditorWasPaused = true;
        mIsKeyboardOpen = false;
    }

    @Override
    public void onResume() {
        super.onResume();
        // If the editor was previously paused and the current orientation is landscape,
        // hide the actionbar because the keyboard is going to appear (even if it was hidden
        // prior to being paused).
        if (mEditorWasPaused
                && (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE)
                && !getResources().getBoolean(R.bool.is_large_tablet_landscape)) {
            mIsKeyboardOpen = true;
            mHideActionBarOnSoftKeyboardUp = true;
            hideActionBarIfNeeded();
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        try {
            mEditorDragAndDropListener = (EditorDragAndDropListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement EditorDragAndDropListener");
        }
    }

    @Override
    public void onDetach() {
        // Soft cancel (delete flag off) all media uploads currently in progress
        for (String mediaId : mUploadingMedia.keySet()) {
            mEditorFragmentListener.onMediaUploadCancelClicked(mediaId, false);
        }
        super.onDetach();
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        if (mDomHasLoaded) {
            mWebView.notifyVisibilityChanged(isVisibleToUser);
        }
        super.setUserVisibleHint(isVisibleToUser);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putCharSequence(KEY_TITLE, getTitle());
        outState.putCharSequence(KEY_CONTENT, getContent());
    }

    private ActionBar getActionBar() {
        if (!isAdded()) {
            return null;
        }

        if (getActivity() instanceof AppCompatActivity) {
            return ((AppCompatActivity) getActivity()).getSupportActionBar();
        } else {
            return null;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Toggle action bar auto-hiding for the new orientation
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
                && !getResources().getBoolean(R.bool.is_large_tablet_landscape)) {
            mHideActionBarOnSoftKeyboardUp = true;
            hideActionBarIfNeeded();
        } else {
            mHideActionBarOnSoftKeyboardUp = false;
            showActionBarIfNeeded();
        }
    }

    protected void initJsEditor() {
        if (!isAdded()) {
            return;
        }

        ProfilingUtils.split("EditorFragment.initJsEditor");

        String htmlEditor = Utils.getHtmlFromFile(getActivity(), "android-editor.html");
        if (htmlEditor != null) {
            htmlEditor = htmlEditor.replace("%%TITLE%%", getString(R.string.visual_editor));
            htmlEditor = htmlEditor.replace("%%ANDROID_API_LEVEL%%", String.valueOf(Build.VERSION.SDK_INT));
            htmlEditor = htmlEditor.replace("%%LOCALIZED_STRING_INIT%%",
                    "nativeState.localizedStringEdit = '" + getString(R.string.edit) + "';\n" +
                            "nativeState.localizedStringUploading = '" + getString(R.string.uploading) + "';\n" +
                            "nativeState.localizedStringUploadingGallery = '" + getString(R.string.uploading_gallery_placeholder) + "';\n");
        }

        // To avoid reflection security issues with JavascriptInterface on API<17, we use an iframe to make URL requests
        // for callbacks from JS instead. These are received by WebViewClient.shouldOverrideUrlLoading() and then
        // passed on to the JsCallbackReceiver
        if (Build.VERSION.SDK_INT < 17) {
            mWebView.setJsCallbackReceiver(new JsCallbackReceiver(this));
        } else {
            mWebView.addJavascriptInterface(new JsCallbackReceiver(this), JS_CALLBACK_HANDLER);
        }

        mWebView.loadDataWithBaseURL("file:///android_asset/", htmlEditor, "text/html", "utf-8", "");

        if (mDebugModeEnabled) {
            enableWebDebugging(true);
        }
    }

    public boolean isActionInProgress() {
        return System.currentTimeMillis() - mActionStartedAt < MAX_ACTION_TIME_MS;
    }

    @Override
    public void onClick(View v) {
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            // If the WebView or EditText has received a touch event, the keyboard will be displayed and the action bar
            // should hide
            mIsKeyboardOpen = true;
            hideActionBarIfNeeded();
        }
        return false;
    }

    /**
     * Intercept back button press while soft keyboard is visible.
     */
    @Override
    public void onImeBack() {
        mIsKeyboardOpen = false;
        showActionBarIfNeeded();
    }

    @Override
    public String onAuthHeaderRequested(String url) {
        return mEditorFragmentListener.onAuthHeaderRequested(url);
    }

    @SuppressLint("NewApi")
    private void enableWebDebugging(boolean enable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            AppLog.i(T.EDITOR, "Enabling web debugging");
            WebView.setWebContentsDebuggingEnabled(enable);
        }
        mWebView.setDebugModeEnabled(mDebugModeEnabled);
    }

    @Override
    public void setTitle(CharSequence text) {
        mTitle = text == null ? "" : text.toString();
    }

    @Override
    public void setContent(CharSequence text) {
        mContentHtml = text.toString();
    }

    /**
     * Returns the contents of the title field from the JavaScript editor. Should be called from a background thread
     * where possible.
     */
    @Override
    public CharSequence getTitle() {
        return "";
    }

    /**
     * Returns the contents of the content field from the JavaScript editor. Should be called from a background thread
     * where possible.
     */
    @Override
    public CharSequence getContent() {
        return "";
    }

    @Override
    public void appendMediaFile(final MediaFile mediaFile, final String mediaUrl, ImageLoader imageLoader) {
        if (!mDomHasLoaded) {
            // If the DOM hasn't loaded yet, we won't be able to add media to the ZSSEditor
            // Place them in a queue to be handled when the DOM loaded callback is received
            mWaitingMediaFiles.put(mediaUrl, mediaFile);
            return;
        }

        final String safeMediaUrl = Utils.escapeQuotes(mediaUrl);

        mWebView.post(new Runnable() {
            @Override
            public void run() {
                if (URLUtil.isNetworkUrl(mediaUrl)) {
                    String mediaId = mediaFile.getMediaId();
                    if (mediaFile.isVideo()) {
                        String posterUrl = Utils.escapeQuotes(StringUtils.notNullStr(mediaFile.getThumbnailURL()));
                        String videoPressId = ShortcodeUtils.getVideoPressIdFromShortCode(
                                mediaFile.getVideoPressShortCode());

                        mWebView.execJavaScriptFromString("ZSSEditor.insertVideo('" + safeMediaUrl + "', '" +
                                posterUrl + "', '" + videoPressId + "');");
                    } else {
                        mWebView.execJavaScriptFromString("ZSSEditor.insertImage('" + safeMediaUrl + "', '" + mediaId +
                                "');");
                    }
                    mActionStartedAt = System.currentTimeMillis();
                } else {
                    String id = mediaFile.getMediaId();
                    if (mediaFile.isVideo()) {
                        String posterUrl = Utils.escapeQuotes(StringUtils.notNullStr(mediaFile.getThumbnailURL()));
                        mWebView.execJavaScriptFromString("ZSSEditor.insertLocalVideo(" + id + ", '" + posterUrl +
                                "');");
                        mUploadingMedia.put(id, MediaType.VIDEO);
                    } else {
                        mWebView.execJavaScriptFromString("ZSSEditor.insertLocalImage(" + id + ", '" + safeMediaUrl +
                                "');");
                        mUploadingMedia.put(id, MediaType.IMAGE);
                    }
                }
            }
        });
    }

    @Override
    public void appendGallery(MediaGallery mediaGallery) {
        if (!mDomHasLoaded) {
            // If the DOM hasn't loaded yet, we won't be able to add a gallery to the ZSSEditor
            // Place it in a queue to be handled when the DOM loaded callback is received
            mWaitingGalleries.add(mediaGallery);
            return;
        }

        if (mediaGallery.getIds().isEmpty()) {
            mUploadingMediaGallery = mediaGallery;
            mWebView.execJavaScriptFromString("ZSSEditor.insertLocalGallery('" + mediaGallery.getUniqueId() + "');");
        } else {
            // Ensure that the content field is in focus (it may not be if we're adding a gallery to a new post by a
            // share action and not via the format bar button)
            mWebView.execJavaScriptFromString("ZSSEditor.getField('zss_field_content').focus();");

            mWebView.execJavaScriptFromString("ZSSEditor.insertGallery('" + mediaGallery.getIdsStr() + "', '" +
                    mediaGallery.getType() + "', " + mediaGallery.getNumColumns() + ");");
        }
    }

    @Override
    public void setUrlForVideoPressId(final String videoId, final String videoUrl, final String posterUrl) {
        mWebView.post(new Runnable() {
            @Override
            public void run() {
                mWebView.execJavaScriptFromString("ZSSEditor.setVideoPressLinks('" + videoId + "', '" +
                        Utils.escapeQuotes(videoUrl) + "', '" + Utils.escapeQuotes(posterUrl) + "');");
            }
        });
    }

    @Override
    public boolean isUploadingMedia() {
        return (mUploadingMedia.size() > 0);
    }

    @Override
    public boolean hasFailedMediaUploads() {
        return (mFailedMediaIds.size() > 0);
    }

    @Override
    public void removeAllFailedMediaUploads() {
        mWebView.execJavaScriptFromString("ZSSEditor.removeAllFailedMediaUploads();");
    }

    @Override
    public Spanned getSpannedContent() {
        return null;
    }

    @Override
    public void setTitlePlaceholder(CharSequence placeholderText) {
        mTitlePlaceholder = placeholderText == null ? "" : placeholderText.toString();
    }

    @Override
    public void setContentPlaceholder(CharSequence placeholderText) {
        mContentPlaceholder = placeholderText == null ? "" : placeholderText.toString();
    }

    @Override
    public void onMediaUploadSucceeded(final String localMediaId, final MediaFile mediaFile) {
        final MediaType mediaType = mUploadingMedia.get(localMediaId);
        if (mediaType != null) {
            mWebView.post(new Runnable() {
                @Override
                public void run() {
                    String remoteUrl = Utils.escapeQuotes(mediaFile.getFileURL());
                    if (mediaType.equals(MediaType.IMAGE)) {
                        String remoteMediaId = mediaFile.getMediaId();
                        mWebView.execJavaScriptFromString("ZSSEditor.replaceLocalImageWithRemoteImage(" + localMediaId +
                                ", '" + remoteMediaId + "', '" + remoteUrl + "');");
                    } else if (mediaType.equals(MediaType.VIDEO)) {
                        String posterUrl = Utils.escapeQuotes(StringUtils.notNullStr(mediaFile.getThumbnailURL()));
                        String videoPressId = ShortcodeUtils.getVideoPressIdFromShortCode(
                                mediaFile.getVideoPressShortCode());
                        mWebView.execJavaScriptFromString("ZSSEditor.replaceLocalVideoWithRemoteVideo(" + localMediaId +
                                ", '" + remoteUrl + "', '" + posterUrl + "', '" + videoPressId + "');");
                    }
                }
            });
        }
    }

    @Override
    public void onMediaUploadProgress(final String mediaId, final float progress) {
        final MediaType mediaType = mUploadingMedia.get(mediaId);
        if (mediaType != null) {
            mWebView.post(new Runnable() {
                @Override
                public void run() {
                    String progressString = String.format(Locale.US, "%.1f", progress);
                    mWebView.execJavaScriptFromString("ZSSEditor.setProgressOnMedia(" + mediaId + ", " +
                            progressString + ");");
                }
            });
        }
    }

    @Override
    public void onMediaUploadFailed(final String mediaId, final String errorMessage) {
        mWebView.post(new Runnable() {
            @Override
            public void run() {
                MediaType mediaType = mUploadingMedia.get(mediaId);
                if (mediaType != null) {
                    switch (mediaType) {
                        case IMAGE:
                            mWebView.execJavaScriptFromString("ZSSEditor.markImageUploadFailed(" + mediaId + ", '"
                                    + Utils.escapeQuotes(errorMessage) + "');");
                            break;
                        case VIDEO:
                            mWebView.execJavaScriptFromString("ZSSEditor.markVideoUploadFailed(" + mediaId + ", '"
                                    + Utils.escapeQuotes(errorMessage) + "');");
                    }
                    mFailedMediaIds.add(mediaId);
                    mUploadingMedia.remove(mediaId);
                }
            }
        });
    }

    @Override
    public void onGalleryMediaUploadSucceeded(final long galleryId, String remoteMediaId, int remaining) {
        if (galleryId == mUploadingMediaGallery.getUniqueId()) {
            ArrayList<String> mediaIds = mUploadingMediaGallery.getIds();
            mediaIds.add(remoteMediaId);
            mUploadingMediaGallery.setIds(mediaIds);

            if (remaining == 0) {
                mWebView.post(new Runnable() {
                    @Override
                    public void run() {
                        mWebView.execJavaScriptFromString("ZSSEditor.replacePlaceholderGallery('" + galleryId + "', '" +
                                mUploadingMediaGallery.getIdsStr() + "', '" +
                                mUploadingMediaGallery.getType() + "', " +
                                mUploadingMediaGallery.getNumColumns() + ");");
                    }
                });
            }
        }
    }

    public void onDomLoaded() {
        ProfilingUtils.split("EditorFragment.onDomLoaded");

        mWebView.post(new Runnable() {
            public void run() {
                if (!isAdded()) {
                    return;
                }

                mDomHasLoaded = true;

                mWebView.execJavaScriptFromString("ZSSEditor.getField('zss_field_content').setMultiline('true');");

                // Set title and content placeholder text
                mWebView.execJavaScriptFromString("ZSSEditor.getField('zss_field_title').setPlaceholderText('" +
                        Utils.escapeQuotes(mTitlePlaceholder) + "');");
                mWebView.execJavaScriptFromString("ZSSEditor.getField('zss_field_content').setPlaceholderText('" +
                        Utils.escapeQuotes(mContentPlaceholder) + "');");

                // Load title and content into ZSSEditor
                updateVisualEditorFields();

                // If there are images that are still in progress (because the editor exited before they completed),
                // set them to failed, so the user can restart them (otherwise they will stay stuck in 'uploading' mode)
                mWebView.execJavaScriptFromString("ZSSEditor.markAllUploadingMediaAsFailed('"
                        + Utils.escapeQuotes(getString(R.string.tap_to_try_again)) + "');");

                // Update the list of failed media uploads
                mWebView.execJavaScriptFromString("ZSSEditor.getFailedMedia();");

                hideActionBarIfNeeded();

                // Reset all format bar buttons (in case they remained active through activity re-creation)
                for (ToggleButton button : mTagToggleButtonMap.values()) {
                    button.setChecked(false);
                }

                boolean editorHasFocus = false;

                // Add any media files that were placed in a queue due to the DOM not having loaded yet
                if (mWaitingMediaFiles.size() > 0) {
                    // Image insertion will only work if the content field is in focus
                    // (for a new post, no field is in focus until user action)
                    mWebView.execJavaScriptFromString("ZSSEditor.getField('zss_field_content').focus();");
                    editorHasFocus = true;

                    for (Map.Entry<String, MediaFile> entry : mWaitingMediaFiles.entrySet()) {
                        appendMediaFile(entry.getValue(), entry.getKey(), null);
                    }
                    mWaitingMediaFiles.clear();
                }

                // Add any galleries that were placed in a queue due to the DOM not having loaded yet
                if (mWaitingGalleries.size() > 0) {
                    // Gallery insertion will only work if the content field is in focus
                    // (for a new post, no field is in focus until user action)
                    mWebView.execJavaScriptFromString("ZSSEditor.getField('zss_field_content').focus();");
                    editorHasFocus = true;

                    for (MediaGallery mediaGallery : mWaitingGalleries) {
                        appendGallery(mediaGallery);
                    }

                    mWaitingGalleries.clear();
                }

                if (!editorHasFocus) {
                    mWebView.execJavaScriptFromString("ZSSEditor.focusFirstEditableField();");
                }

                // Show the keyboard
                ((InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE))
                        .showSoftInput(mWebView, InputMethodManager.SHOW_IMPLICIT);

                ProfilingUtils.split("EditorFragment.onDomLoaded completed");
                ProfilingUtils.dump();
                ProfilingUtils.stop();
            }
        });
    }

    public void onSelectionStyleChanged(final Map<String, Boolean> changeMap) {
        mWebView.post(new Runnable() {
            public void run() {
                for (Map.Entry<String, Boolean> entry : changeMap.entrySet()) {
                    // Handle toggling format bar style buttons
                    ToggleButton button = mTagToggleButtonMap.get(entry.getKey());
                    if (button != null) {
                        button.setChecked(entry.getValue());
                    }
                }
            }
        });
    }

    public void onSelectionChanged(final Map<String, String> selectionArgs) {
        mFocusedFieldId = selectionArgs.get("id"); // The field now in focus
        mWebView.post(new Runnable() {
            @Override
            public void run() {
                if (!mFocusedFieldId.isEmpty()) {
                    switch (mFocusedFieldId) {
                        case "zss_field_title":
                            updateFormatBarEnabledState(false);
                            break;
                        case "zss_field_content":
                            updateFormatBarEnabledState(true);
                            break;
                    }
                }
            }
        });
    }

    public void onMediaTapped(final String mediaId, final MediaType mediaType, final JSONObject meta, String uploadStatus) {
        if (mediaType == null || !isAdded()) {
            return;
        }

        switch (uploadStatus) {
            case "uploading":
                // Display 'cancel upload' dialog
                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                builder.setTitle(getString(R.string.stop_upload_dialog_title));
                builder.setPositiveButton(R.string.stop_upload_button, new OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        mEditorFragmentListener.onMediaUploadCancelClicked(mediaId, true);

                        mWebView.post(new Runnable() {
                            @Override
                            public void run() {
                                switch (mediaType) {
                                    case IMAGE:
                                        mWebView.execJavaScriptFromString("ZSSEditor.removeImage(" + mediaId + ");");
                                        break;
                                    case VIDEO:
                                        mWebView.execJavaScriptFromString("ZSSEditor.removeVideo(" + mediaId + ");");
                                }
                                mUploadingMedia.remove(mediaId);
                            }
                        });
                        dialog.dismiss();
                    }
                });

                builder.setNegativeButton(getString(R.string.cancel), new OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                });

                AlertDialog dialog = builder.create();
                dialog.show();
                break;
            case "failed":
                // Retry media upload
                mEditorFragmentListener.onMediaRetryClicked(mediaId);

                mWebView.post(new Runnable() {
                    @Override
                    public void run() {
                        switch (mediaType) {
                            case IMAGE:
                                mWebView.execJavaScriptFromString("ZSSEditor.unmarkImageUploadFailed(" + mediaId
                                        + ");");
                                break;
                            case VIDEO:
                                mWebView.execJavaScriptFromString("ZSSEditor.unmarkVideoUploadFailed(" + mediaId
                                        + ");");
                        }
                        mFailedMediaIds.remove(mediaId);
                        mUploadingMedia.put(mediaId, mediaType);
                    }
                });
                break;
            default:
                if (!mediaType.equals(MediaType.IMAGE)) {
                    return;
                }

                // Only show image options fragment for image taps
                FragmentManager fragmentManager = getFragmentManager();

                if (fragmentManager.findFragmentByTag(ImageSettingsDialogFragment.IMAGE_SETTINGS_DIALOG_TAG) != null) {
                    return;
                }
                mEditorFragmentListener.onTrackableEvent(TrackableEvent.IMAGE_EDITED);
                ImageSettingsDialogFragment imageSettingsDialogFragment = new ImageSettingsDialogFragment();
                imageSettingsDialogFragment.setTargetFragment(this,
                        ImageSettingsDialogFragment.IMAGE_SETTINGS_DIALOG_REQUEST_CODE);

                Bundle dialogBundle = new Bundle();

                dialogBundle.putString("maxWidth", mBlogSettingMaxImageWidth);
                dialogBundle.putBoolean("featuredImageSupported", mFeaturedImageSupported);

                // Request and add an authorization header for HTTPS images
                // Use https:// when requesting the auth header, in case the image is incorrectly using http://.
                // If an auth header is returned, force https:// for the actual HTTP request.
                HashMap<String, String> headerMap = new HashMap<>();
                if (mCustomHttpHeaders != null) {
                    headerMap.putAll(mCustomHttpHeaders);
                }

                try {
                    final String imageSrc = meta.getString("src");
                    String authHeader = mEditorFragmentListener.onAuthHeaderRequested(UrlUtils.makeHttps(imageSrc));
                    if (authHeader.length() > 0) {
                        meta.put("src", UrlUtils.makeHttps(imageSrc));
                        headerMap.put("Authorization", authHeader);
                    }
                } catch (JSONException e) {
                    AppLog.e(T.EDITOR, "Could not retrieve image url from JSON metadata");
                }
                dialogBundle.putSerializable("headerMap", headerMap);

                dialogBundle.putString("imageMeta", meta.toString());

                String imageId = JSONUtils.getString(meta, "attachment_id");
                if (!imageId.isEmpty()) {
                    dialogBundle.putBoolean("isFeatured", mFeaturedImageId == Integer.parseInt(imageId));
                }

                imageSettingsDialogFragment.setArguments(dialogBundle);

                FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
                fragmentTransaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN);

                fragmentTransaction.add(android.R.id.content, imageSettingsDialogFragment,
                        ImageSettingsDialogFragment.IMAGE_SETTINGS_DIALOG_TAG)
                        .addToBackStack(null)
                        .commit();

                mWebView.notifyVisibilityChanged(false);
                break;
        }
    }

    public void onLinkTapped(String url, String title) {
        LinkDialogFragment linkDialogFragment = new LinkDialogFragment();
        linkDialogFragment.setTargetFragment(this, LinkDialogFragment.LINK_DIALOG_REQUEST_CODE_UPDATE);

        Bundle dialogBundle = new Bundle();

        dialogBundle.putString(LinkDialogFragment.LINK_DIALOG_ARG_URL, url);
        dialogBundle.putString(LinkDialogFragment.LINK_DIALOG_ARG_TEXT, title);

        linkDialogFragment.setArguments(dialogBundle);
        linkDialogFragment.show(getFragmentManager(), "LinkDialogFragment");
    }

    @Override
    public void onMediaRemoved(String mediaId) {
        mUploadingMedia.remove(mediaId);
        mFailedMediaIds.remove(mediaId);
        mEditorFragmentListener.onMediaUploadCancelClicked(mediaId, true);
    }

    @Override
    public void onMediaReplaced(String mediaId) {
        mUploadingMedia.remove(mediaId);
    }

    @Override
    public void onVideoPressInfoRequested(final String videoId) {
        mEditorFragmentListener.onVideoPressInfoRequested(videoId);
    }

    public void onGetHtmlResponse(Map<String, String> inputArgs) {

    }

    public void setWebViewErrorListener(ErrorListener errorListener) {
        mWebView.setErrorListener(errorListener);
    }

    private void updateVisualEditorFields() {
        mWebView.execJavaScriptFromString("ZSSEditor.getField('zss_field_title').setPlainText('" +
                Utils.escapeHtml(mTitle) + "');");
        mWebView.execJavaScriptFromString("ZSSEditor.getField('zss_field_content').setHTML('" +
                Utils.escapeHtml(mContentHtml) + "');");
    }

    /**
     * Hide the action bar if needed.
     */
    private void hideActionBarIfNeeded() {

        ActionBar actionBar = getActionBar();
        if (actionBar != null
                && !isHardwareKeyboardPresent()
                && mHideActionBarOnSoftKeyboardUp
                && mIsKeyboardOpen
                && actionBar.isShowing()) {
            getActionBar().hide();
        }
    }

    /**
     * Show the action bar if needed.
     */
    private void showActionBarIfNeeded() {

        ActionBar actionBar = getActionBar();
        if (actionBar != null && !actionBar.isShowing()) {
            actionBar.show();
        }
    }

    /**
     * Returns true if a hardware keyboard is detected, otherwise false.
     */
    private boolean isHardwareKeyboardPresent() {
        Configuration config = getResources().getConfiguration();
        boolean returnValue = false;
        if (config.keyboard != Configuration.KEYBOARD_NOKEYS) {
            returnValue = true;
        }
        return returnValue;
    }

    void updateFormatBarEnabledState(boolean enabled) {
        float alpha = (enabled ? TOOLBAR_ALPHA_ENABLED : TOOLBAR_ALPHA_DISABLED);
        for (ToggleButton button : mTagToggleButtonMap.values()) {
            button.setEnabled(enabled);
            button.setAlpha(alpha);
        }

        mIsFormatBarDisabled = !enabled;
    }

    private void clearFormatBarButtons() {
        for (ToggleButton button : mTagToggleButtonMap.values()) {
            if (button != null) {
                button.setChecked(false);
            }
        }
    }

    private void buttonTappedListener(ToggleButton toggleButton) {
        int id = toggleButton.getId();
        if (id == R.id.format_bar_button_bold) {
            mEditorFragmentListener.onTrackableEvent(TrackableEvent.BOLD_BUTTON_TAPPED);
        } else if (id == R.id.format_bar_button_italic) {
            mEditorFragmentListener.onTrackableEvent(TrackableEvent.ITALIC_BUTTON_TAPPED);
        } else if (id == R.id.format_bar_button_ol) {
            mEditorFragmentListener.onTrackableEvent(TrackableEvent.OL_BUTTON_TAPPED);
        } else if (id == R.id.format_bar_button_ul) {
            mEditorFragmentListener.onTrackableEvent(TrackableEvent.UL_BUTTON_TAPPED);
        } else if (id == R.id.format_bar_button_quote) {
            mEditorFragmentListener.onTrackableEvent(TrackableEvent.BLOCKQUOTE_BUTTON_TAPPED);
        } else if (id == R.id.format_bar_button_strikethrough) {
            mEditorFragmentListener.onTrackableEvent(TrackableEvent.STRIKETHROUGH_BUTTON_TAPPED);
        }
    }

    @Override
    public void onActionFinished() {
        mActionStartedAt = -1;
    }
}
