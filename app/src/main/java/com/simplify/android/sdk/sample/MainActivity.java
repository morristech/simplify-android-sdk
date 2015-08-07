package com.simplify.android.sdk.sample;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.InputMethodManager;

import com.simplify.android.sdk.Card;
import com.simplify.android.sdk.CardToken;
import com.simplify.android.sdk.CardEditor;
import com.simplify.android.sdk.Simplify;

public class MainActivity extends Activity {

    private static final String TAG = MainActivity.class.getSimpleName();

    CardEditor mCardEditor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initUI();
    }

    void initUI() {
        mCardEditor = (CardEditor) findViewById(R.id.card_editor);

        mCardEditor.setOnChargeClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                // hide keyboard
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);

                Card card = mCardEditor.getCard();

                Simplify.createCardToken(card, new CardToken.Callback() {
                    @Override
                    public void onSuccess(CardToken cardToken) {
                        Log.i(TAG, "Created Token: " + cardToken.getId());
                        mCardEditor.showSuccessOverlay("Created card token " + cardToken.getId());
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        Log.e(TAG, "Error Creating Token: " + throwable.getMessage());
                        mCardEditor.showErrorOverlay("Unable to retrieve card token. " + throwable.getMessage());
                    }
                });
            }
        });

        // init reset button
        findViewById(R.id.btnReset).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                mCardEditor.reset();
            }
        });
    }
}
