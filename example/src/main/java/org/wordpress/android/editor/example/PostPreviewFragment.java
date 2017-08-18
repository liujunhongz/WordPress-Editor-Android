package org.wordpress.android.editor.example;

import android.content.Context;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.ToastUtils;

public class PostPreviewFragment extends Fragment {
    private String mContent;
    private boolean isLocalDraft;
    private WebView mWebView;

    public static PostPreviewFragment newInstance(String content, boolean isLocalDraft) {
        Bundle args = new Bundle();
        args.putString("content", content);
        args.putBoolean("isLocalDraft", isLocalDraft);
        PostPreviewFragment fragment = new PostPreviewFragment();
        fragment.setArguments(args);
        return fragment;
    }

    public static PostPreviewFragment newInstance(String content) {
        return newInstance(content, true);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContent = getArguments().getString("content");
        isLocalDraft = getArguments().getBoolean("isLocalDraft");

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.post_preview_fragment, container, false);

        mWebView = (WebView) view.findViewById(R.id.webView);
        WPWebViewClient client = new WPWebViewClient();
        mWebView.setWebViewClient(client);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        refreshPreview();
    }

    void refreshPreview() {
        if (!isAdded()) return;

        new Thread() {
            @Override
            public void run() {
                final String htmlContent = formatPostContentForWebView(getActivity(), mContent);

                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded()) return;

                        if (htmlContent != null) {
                            mWebView.loadDataWithBaseURL(
                                    "file:///android_asset/",
                                    htmlContent,
                                    "text/html",
                                    "utf-8",
                                    null);
                        } else {
                            ToastUtils.showToast(getActivity(), R.string.post_not_found);
                        }
                    }
                });
            }
        }.start();
    }

    private String formatPostContentForWebView(Context context, String content) {
        if (context == null || content == null) {
            return null;
        }

//        String title = (TextUtils.isEmpty(title)
//                ? "(" + getResources().getText(R.string.untitled) + ")"
//                : StringUtils.unescapeHTML(post.getTitle()));

        String postContent = PostUtils.collapseShortcodes(content);

        // if this is a local draft, remove src="null" from image tags then replace the "android-uri"
        // tag added for local image with a valid "src" tag so local images can be viewed
        if (isLocalDraft) {
            postContent = postContent.replace("src=\"null\"", "").replace("android-uri=", "src=");
        }

        return "<!DOCTYPE html><html><head><meta charset='UTF-8' />"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<link rel='stylesheet' href='editor.css'>"
                + "<link rel='stylesheet' href='editor-android.css'>"
                + "</head><body>"
//                + "<h1>" + title + "</h1>"
                + StringUtils.addPTags(postContent)
                + "</body></html>";
    }
}
