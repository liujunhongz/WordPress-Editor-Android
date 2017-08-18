package org.wordpress.android.editor.example;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

public class PreviewActivity extends AppCompatActivity {

    private PostPreviewFragment mPreviewFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);
        mPreviewFragment = PostPreviewFragment.newInstance(getIntent().getStringExtra("data"));
        getSupportFragmentManager().beginTransaction().replace(R.id.preview, mPreviewFragment).commitAllowingStateLoss();
    }

}
