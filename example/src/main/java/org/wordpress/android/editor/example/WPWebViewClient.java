package org.wordpress.android.editor.example;

import android.graphics.Bitmap;
import android.net.http.SslError;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.UrlUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

/**
 * WebViewClient that is capable of handling HTTP authentication requests using the HTTP
 * username and password of the blog configured for this activity.
 */
public class WPWebViewClient extends URLFilteredWebViewClient {
    /**
     * Timeout in milliseconds for read / connect timeouts
     */
    private static final int TIMEOUT_MS = 30000;

    public WPWebViewClient() {
        this(null);
    }

    public WPWebViewClient(List<String> urls) {
        super(urls);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        //这个是一定要加上那个的,配合scrollView和WebView的height=wrap_content属性使用
        int w = View.MeasureSpec.makeMeasureSpec(0,
                View.MeasureSpec.UNSPECIFIED);
        int h = View.MeasureSpec.makeMeasureSpec(0,
                View.MeasureSpec.UNSPECIFIED);
        //重新测量
        view.measure(w, h);
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
    }


    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        super.onReceivedSslError(view, handler, error);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, String stringUrl) {
        URL imageUrl = null;
        if (UrlUtils.isImageUrl(stringUrl)) {
            try {
                imageUrl = new URL(UrlUtils.makeHttps(stringUrl));
            } catch (MalformedURLException e) {
                AppLog.e(AppLog.T.READER, e);
            }
        }

        // Intercept requests for private images and add the WP.com authorization header
        if (imageUrl != null &&
                WPUrlUtils.safeToAddWordPressComAuthToken(imageUrl)) {
            try {
                // Force use of HTTPS for the resource, otherwise the request will fail for private sites
                HttpURLConnection urlConnection = (HttpURLConnection) imageUrl.openConnection();
                urlConnection.setReadTimeout(TIMEOUT_MS);
                urlConnection.setConnectTimeout(TIMEOUT_MS);
                WebResourceResponse response = new WebResourceResponse(urlConnection.getContentType(),
                        urlConnection.getContentEncoding(),
                        urlConnection.getInputStream());
                return response;
            } catch (ClassCastException e) {
                AppLog.e(AppLog.T.POSTS, "Invalid connection type - URL: " + stringUrl);
            } catch (MalformedURLException e) {
                AppLog.e(AppLog.T.POSTS, "Malformed URL: " + stringUrl);
            } catch (IOException e) {
                AppLog.e(AppLog.T.POSTS, "Invalid post detail request: " + e.getMessage());
            }
        }
        return super.shouldInterceptRequest(view, stringUrl);
    }

    @Override
    public boolean shouldOverrideKeyEvent(WebView view, KeyEvent event) {
        return true;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return super.shouldOverrideUrlLoading(view, url);
    }
}
